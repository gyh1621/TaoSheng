package app.resound.push

import android.app.Application
import android.content.Context
import app.resound.data.ReviewInput
import app.resound.data.TranscriptLineEntity
import app.resound.location.FootprintReport
import app.resound.location.FootprintReports
import app.resound.location.LocationPoint
import app.resound.location.Place
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class Route2PushFootprintTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 11, 20)
    private val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun at(min: Long) = dayStart + min * 60_000
    private fun p(min: Long, lat: Double, lng: Double) = LocationPoint(at = at(min), lat = lat, lng = lng, accuracy = 15f, provider = "fused")
    private val homeLat = 30.2408; private val homeLng = 119.9199
    private val mallLat = 30.2600; private val mallLng = 119.9500
    private fun home(from: Long, to: Long) = (from..to step 5).map { p(it, homeLat, homeLng) }
    private fun mall(from: Long, to: Long) = (from..to step 5).map { p(it, mallLat, mallLng) }
    private val isHome: (Double, Double) -> Boolean = { la, _ -> la < 30.25 }
    private fun render(points: List<LocationPoint>, to: Long, home: (Double, Double) -> Boolean = isHome,
                       place: (Double, Double) -> Place? = { _, _ -> null }): FootprintReport =
        FootprintReports.render(date, zone, points, to, home, place)!!
    private fun FootprintReport.stays() = json.getJSONArray("stays").let { a -> List(a.length()) { a.getJSONObject(it) } }
    private fun sig(points: List<LocationPoint>, toMin: Long, place: (Double, Double) -> Place? = { _, _ -> null },
                    home: (Double, Double) -> Boolean = isHome) = FootprintChangeDetector.signature(render(points, at(toMin), home, place), at(toMin))

    @Before fun clean() {
        listOf("push", "push-retries", "footprint-live-test").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
    }

    // ---------- 原话行的 speakerScore / speakerManual ----------

    private fun line(id: Long, score: Float?, manual: Boolean, name: String?, person: Long?) =
        TranscriptLineEntity(id, 1, 1_000L * id, 1_000L * id + 500, "第${id}句", "speaker$id", name,
            speakerScore = score, speakerPersonId = person, speakerManual = manual)
    private val lines = listOf(
        line(1, 0.83f, false, "我", 0),          // 自动识别：分数照写，手标显式 null
        line(2, Float.NaN, true, null, 0),        // 手标“是我”；NaN 置 null
        line(3, 1.5f, true, "小王", 7),            // 手标成某人；越界置 null
        line(4, null, true, null, null),          // 手标但无身份：有排除行才是“不是我”，否则是“标成未知”
        line(5, 0f, true, "我", null),             // 名字是“我”也算 me；0 是合法分数
        line(6, -0.1f, false, null, null),        // 负数越界
    )
    private val notMe = setOf(1L to "tag:speaker4")
    private fun assertSpeaker(rows: Map<Long, JSONObject>, excluded: Boolean = true) {
        fun score(id: Long) = rows.getValue(id).let { assertTrue(it.has("speakerScore")); if (it.isNull("speakerScore")) null else it.getDouble("speakerScore") }
        fun manual(id: Long) = rows.getValue(id).let { assertTrue(it.has("speakerManual")); if (it.isNull("speakerManual")) null else it.getString("speakerManual") }
        assertEquals(0.83, score(1)!!, 0.0)
        assertNull(score(2)); assertNull(score(3)); assertNull(score(4)); assertEquals(0.0, score(5)!!, 0.0); assertNull(score(6))
        assertNull(manual(1)); assertEquals("me", manual(2)); assertEquals("小王", manual(3)); assertEquals(if (excluded) "not_me" else null, manual(4))
        assertEquals("me", manual(5)); assertNull(manual(6))
    }

    @Test fun recordDeltaLinesCarrySpeakerScoreAndManual() = runBlocking {
        val records = RecordDelta.records(PushSnapshot(lines = lines, voiceprintExclusions = notMe), PushSubscription(lines = LinesMode.All))
        assertSpeaker(records.filterKeys { it.startsWith("line:") }.mapKeys { it.key.removePrefix("line:").toLong() })
        // 没有排除行（clearIdentity 标成未知）：同样清空身份，但不能冒充 not_me
        val unknown = RecordDelta.records(PushSnapshot(lines = lines), PushSubscription(lines = LinesMode.All))
        assertSpeaker(unknown.filterKeys { it.startsWith("line:") }.mapKeys { it.key.removePrefix("line:").toLong() }, excluded = false)
        assertEquals("小王", records.getValue("line:3").getString("speaker"))
        // 撤销手标后哈希变化，增量会重发这一行，让对端清掉旧 speaker_manual。
        val undone = RecordDelta.records(PushSnapshot(lines = listOf(lines[2].copy(speakerManual = false))), PushSubscription(lines = LinesMode.All))
        assertNotEquals(sha256(canonical(records.getValue("line:3"))), sha256(canonical(undone.getValue("line:3"))))
        assertTrue(undone.getValue("line:3").isNull("speakerManual"))
    }

    @Test fun dailyMemoryLinesCarrySpeakerScoreAndManual() = runBlocking {
        suspend fun rows(excluded: Set<Pair<Long, String>>) = dailyMemoryJson(date, at(600), "# 文档", null, ReviewInput(emptyList(), emptyList()), emptyList(), lines,
            excluded = excluded).getJSONObject("data").getJSONArray("lines").let { a -> List(a.length()) { a.getJSONObject(it) }.associateBy { it.getLong("id") } }
        assertSpeaker(rows(notMe))
        assertSpeaker(rows(emptySet()), excluded = false)
    }

    @Test fun clearIdentityRemovesExclusionSoPushStopsSayingNotMe() = runBlocking {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, app.resound.data.CaptureDatabase::class.java)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).allowMainThreadQueries().build()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val done = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        try {
            val repository = app.resound.voiceprint.LocalVoiceprintRepository(db, scope, { error("不解码") },
                { app.resound.voiceprint.SpeakerEmbedding { FloatArray(192) } }, { done.trySend(Unit); Unit })
            suspend fun action(block: () -> Unit) { block(); kotlinx.coroutines.withTimeout(5000) { done.receive() } }
            db.capture().segment(app.resound.data.SegmentEntity(startAt = 1000, endAt = 6000, sourceId = "mic", path = "/nope.ogg",
                sizeBytes = 1, status = "Done", completedAt = 1))
            val segment = db.transcribe().observeSegments().first().single()
            db.transcribe().insertLines(listOf(TranscriptLineEntity(segmentId = segment.id, startAt = 1000, endAt = 3000, text = "第一句", speakerTag = "0"),
                TranscriptLineEntity(segmentId = segment.id, startAt = 3000, endAt = 6000, text = "第二句", speakerTag = "0")))
            val first = db.voiceprint().lines(segment.id).first()
            suspend fun manual(): List<Any?> {
                val snapshot = db.push().snapshot(0, withLines = true)
                val records = RecordDelta.records(snapshot, PushSubscription(lines = LinesMode.All))
                return snapshot.lines.map { records.getValue("line:${it.id}").get("speakerManual") }
            }
            action { repository.markNotMe(first.id) }
            assertEquals(listOf(segment.id to "tag:0"), db.push().voiceprintExclusions().map { it.segmentId to it.groupKey })
            assertEquals(listOf(segment.id to "tag:0"), db.memory().voiceprintExclusions().map { it.segmentId to it.groupKey })
            assertEquals(listOf<Any?>("not_me", "not_me"), manual())
            action { repository.clearIdentity(first.id) }
            assertTrue(db.push().voiceprintExclusions().isEmpty())
            assertTrue(db.voiceprint().lines(segment.id).all { it.speakerManual && it.speakerPersonId == null && it.speakerName == null })
            assertEquals(listOf<Any?>(JSONObject.NULL, JSONObject.NULL), manual())
            assertTrue(db.push().snapshot(0, withLines = false).voiceprintExclusions.isEmpty())
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel(); db.close() }
    }

    // ---------- 足迹停留的 ongoing ----------

    @Test fun ongoingOnlyForTodaysLastStayStillObserved() {
        val atHome = render(home(0, 480), at(485))
        assertEquals(listOf(true), atHome.stays().map { it.getBoolean("ongoing") })
        assertEquals(java.time.Instant.ofEpochMilli(at(480)).toString(), atHome.stays().single().getString("end"))
        // 刚离开：最新一点在半径外，停留结束
        val left = render(home(0, 480) + mall(490, 495), at(495))
        assertEquals(listOf(false), left.stays().map { it.getBoolean("ongoing") })
        // 新停留形成：只有最后一段进行中
        val atMall = render(home(0, 480) + mall(490, 520), at(522))
        assertEquals(listOf(false, true), atMall.stays().map { it.getBoolean("ongoing") })
        // 断档超过 GAP：不再算进行中
        assertFalse(render(home(0, 480), at(480 + 21)).stays().single().getBoolean("ongoing"))
        // 往日（统计截止到次日零点）恒为 false，哪怕待到 23:55
        assertFalse(FootprintReports.render(date, zone, home(0, 1435), dayEnd, isHome) { _, _ -> null }!!.stays().single().getBoolean("ongoing"))
    }

    // ---------- 变化检测 ----------

    @Test fun signatureIgnoresStillTimeButCatchesEveryBusinessChange() {
        val still = sig(home(0, 480), 482)
        assertEquals(still, sig(home(0, 600), 602))                                 // 原地多点：不变
        val moving = sig(home(0, 600) + mall(610, 610), 611)
        assertNotEquals(still, moving); assertTrue(moving.endsWith("moving:false"))  // 离家
        assertTrue(sig(home(0, 600) + listOf(p(610, homeLat + 0.0005, homeLng)) + mall(700, 700), 701).endsWith("moving:false"))
        val stayed = sig(home(0, 600) + mall(610, 630), 631)
        assertNotEquals(moving, stayed)                                              // 新停留形成
        assertEquals(stayed, sig(home(0, 600) + mall(610, 700), 701))                // 新地方原地不动：不变
        val named = sig(home(0, 600) + mall(610, 700), 701, { la, _ -> if (la > 30.25) Place("西溪印象城", "", "高德") else null })
        assertNotEquals(stayed, named)                                               // 地名补全
        val addressed = sig(home(0, 600) + mall(610, 700), 701, { la, _ -> if (la > 30.25) Place("西溪印象城", "五常大道 1 号", "高德") else null })
        assertNotEquals(named, addressed)                                            // 地址补全
        // 手动设/取消家：白天的停留没有夜里兜底，只看手动的家
        val daytime = sig(mall(600, 700), 701)
        val markedHome = sig(mall(600, 700), 701, home = { _, _ -> true })
        assertNotEquals(daytime, markedHome); assertTrue(markedHome, markedHome.endsWith(":true"))
        assertNotEquals(still, sig(home(0, 480), 480 + 25))                          // 断档
        val back = sig(home(0, 600) + mall(610, 700) + home(720, 720), 721)
        assertTrue(back, back.endsWith("moving:true"))                               // 回到家附近、还没成停留
    }

    @Test fun placeRevisionBumpsOnEveryPlaceWrite() = runBlocking {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, app.resound.data.CaptureDatabase::class.java)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).allowMainThreadQueries().build()
        val amap = """{"status":"1","regeocode":{"formatted_address":"五常大道 1 号","addressComponent":{},"aois":[{"name":"西溪印象城"}],"pois":[]}}"""
        val server = MockWebServer().apply { enqueue(MockResponse().setBody(amap)); start() }
        try {
            val places = app.resound.location.PlaceResolver(context, db.places(), app.resound.location.LocationSettings(context),
                amapBase = server.url("/v3/geocode/regeo").toString(), amapKey = { "k" })
            val r = places.revision
            assertEquals(0L, r.value)
            assertEquals("西溪印象城", places.resolve(mallLat, mallLng)?.name); assertEquals(1L, r.value)   // 地名补全写缓存
            places.resolve(mallLat, mallLng); assertEquals(1L, r.value)                                    // 命中缓存不算变化
            places.setManual(homeLat, homeLng, "家", "", home = true); assertEquals(2L, r.value)          // 设家
            val row = db.places().manualNow().single()
            places.setManual(homeLat, homeLng, "老家", "", home = false, replacing = row); assertEquals(3L, r.value)   // 改名、取消家
            places.removeManual(db.places().manualNow().single()); assertEquals(4L, r.value)
            places.clearCache(); assertEquals(5L, r.value)
        } finally { server.shutdown(); db.close() }
    }

    // ---------- 防抖、冷却、去重 ----------

    private val prefs get() = context.getSharedPreferences("footprint-live-test", 0)

    @Test fun stillPointsDoNotSpamAndRestartDedupesAndNewDayResets() = runBlocking {
        val sent = mutableListOf<String>()
        var points = home(0, 480); var toMin = 482L
        fun live() = FootprintLiveSync(prefs, { sig(points, toMin) }, { sent += sig(points, toMin); 1 }, { 0L })
        val sync = live()
        assertTrue(sync.sync(date))
        for (m in 485L..600L step 5) { points = home(0, m); toMin = m + 1; assertFalse(sync.sync(date)) }
        assertEquals(1, sent.size)
        assertFalse(live().sync(date))                                                // 重启：今天没变不重推
        points = home(0, 600) + mall(610, 630); toMin = 631
        assertTrue(live().sync(date)); assertEquals(2, sent.size)                     // 新停留
        assertTrue(live().sync(date.plusDays(1)))                                     // 跨日：同签名也按新的一天推
        assertEquals(date.plusDays(1).toString(), prefs.getString(FootprintLiveSync.DATE, null))
    }

    @Test fun nothingToSendIsNotRemembered() = runBlocking {
        var sig: String? = null; var delivered = 0
        val sync = FootprintLiveSync(prefs, { sig }, { delivered }, { 0L })
        assertFalse(sync.sync(date))                                                  // 今天没足迹
        sig = "a"; assertFalse(sync.sync(date))                                       // 没有可推的 Webhook
        assertNull(prefs.getString(FootprintLiveSync.SIG, null))
        delivered = 1; assertTrue(sync.sync(date))
    }

    @Test fun burstIsDebouncedIntoOneSync() = runBlocking {
        val delays = mutableListOf<Long>(); var clock = 0L; var sends = 0
        FootprintLiveSync(prefs, { "s" }, { sends++; 1 }, { clock }, { delays += it; clock += it })
            .run(flowOf(date, date, date, date))
        assertEquals(1, sends)
        assertEquals(listOf(20_000L), delays)
    }

    @Test fun changesDuringCooldownAreSentAsLatestAfterIt() = runBlocking {
        var clock = 0L; var state = "s0"
        val delays = mutableListOf<Long>(); val sent = mutableListOf<String>()
        val firstSent = CompletableDeferred<Unit>()
        FootprintLiveSync(prefs, { state }, { sent += state; firstSent.complete(Unit); 1 }, { clock },
            { ms -> delays += ms; clock += ms; if (ms > 20_000) state = "s2" })
            .run(flow { emit(date); firstSent.await(); clock += 60_000; state = "s1"; emit(date) })
        assertEquals(listOf("s0", "s2"), sent)                                        // 冷却中又变了：不丢，补发最新的
        assertEquals(listOf(20_000L, 20_000L, 300_000L - 60_000 - 20_000), delays)
    }

    // ---------- 只推 Webhook，不碰晚间全量 ----------

    private class Server(code: () -> Int) : AutoCloseable {
        val bodies = mutableListOf<String>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    synchronized(bodies) { bodies += request.body.readUtf8() }
                    return MockResponse().setResponseCode(code()).setBody("{\"ok\":true,\"code\":0}")
                }
            }
            start()
        }
        val transport = PushTransport(OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url("/hook")).build())
        }.build())
        override fun close() = server.shutdown()
    }
    private fun repo(transport: PushTransport, jobs: MutableList<String> = mutableListOf()) = AndroidPushChannels(
        context.getSharedPreferences("push", 0), context.getSharedPreferences("push-retries", 0), object : PushSecrets {
            val map = HashMap<String, String>()
            override fun read(ref: String) = map.getValue(ref)
            override fun write(ref: String, value: String) { map[ref] = value }
            override fun remove(ref: String) { map.remove(ref) }
        }, transport, { jobs += it }).apply {
        footprintMessage = { d -> PushMessage("今日足迹", "在外", PushTopic.Footprint, null, at(600),
            footprint = JSONObject().put("date", d.toString()).put("stays", org.json.JSONArray().put(JSONObject().put("ongoing", true)))) }
    }
    private val subscription = PushSubscription(summaries = false, footprint = true, footprintAt = 22 * 60)
    private suspend fun AndroidPushChannels.webhook() = saveWithSubscription(null, PushKind.Webhook, "agent",
        mapOf("url" to "https://example.com/hook"), subscription, PushFormat.Json, false).getOrThrow()
    private suspend fun AndroidPushChannels.telegram() = saveWithSubscription(null, PushKind.Telegram, "人看",
        mapOf("token" to "123:abc", "chatId" to "123"), subscription, PushFormat.Text, false).getOrThrow()

    @Test fun liveGoesToWebhookOnlyAndLeavesEveningFullPush() = runBlocking {
        Server { 200 }.use { s ->
            val repo = repo(s.transport)
            val hook = repo.webhook(); val tg = repo.telegram()
            assertEquals(1, repo.dispatchFootprintLive(date))
            assertEquals(1, s.bodies.size)
            val body = JSONObject(s.bodies.single())
            assertEquals("footprint", body.getString("type"))
            assertTrue(body.getJSONObject("footprint").getJSONArray("stays").getJSONObject(0).getBoolean("ongoing"))
            val prefs = context.getSharedPreferences("push", 0)
            listOf(hook, tg).forEach { assertNull(prefs.getString("footprint-sent-$it", null)) }
            // 晚上到点：两个通道都照常收到全量
            repo.dispatchFootprint(at(22 * 60 + 1), zone)
            assertEquals(3, s.bodies.size)
            listOf(hook, tg).forEach { assertEquals(date.toString(), prefs.getString("footprint-sent-$it", null)) }
            // 没内容、或 Webhook 关掉时不推
            repo.footprintMessage = { null }
            assertEquals(0, repo.dispatchFootprintLive(date))
            assertEquals(3, s.bodies.size)
        }
    }

    @Test fun noWebhookMeansNoLivePush() = runBlocking {
        Server { 200 }.use { s ->
            val repo = repo(s.transport)
            repo.telegram()
            assertEquals(0, repo.dispatchFootprintLive(date))
            assertEquals(0, s.bodies.size)
        }
    }

    @Test fun offlineLivePushGoesToExistingRetryQueue() = runBlocking {
        var code = 500
        Server { code }.use { s ->
            val jobs = mutableListOf<String>()
            val repo = repo(s.transport, jobs)
            repo.webhook()
            assertEquals(1, repo.dispatchFootprintLive(date))
            val outbox = context.getSharedPreferences("push-retries", 0)
            assertEquals(1, jobs.size); assertEquals(1, outbox.all.size)
            code = 200
            assertFalse(repo.retry(jobs.single(), 0))                                 // PushRetryWork 补传成功，不再重试
            assertEquals(0, outbox.all.size)
            assertEquals(2, s.bodies.size)
            assertEquals(date.toString(), JSONObject(s.bodies.last()).getJSONObject("footprint").getString("date"))
        }
    }

    // ---------- F07：旧重试不覆盖新状态 ----------

    private fun JSONObject.ongoing() = getJSONObject("footprint").getJSONArray("stays").getJSONObject(0).getBoolean("ongoing")

    @Test fun staleFootprintRetryRebuildsLatestState() = runBlocking {
        var code = 500; var ongoing = true
        Server { code }.use { s ->
            val jobs = mutableListOf<String>()
            val repo = repo(s.transport, jobs)
            repo.footprintMessage = { d -> PushMessage("今日足迹", "在外", PushTopic.Footprint, null, at(600),
                footprint = JSONObject().put("date", d.toString()).put("stays", org.json.JSONArray().put(JSONObject().put("ongoing", ongoing)))) }
            repo.webhook()
            assertEquals(1, repo.dispatchFootprintLive(date))                        // ongoing=true 失败，入队
            assertEquals(1, jobs.size)
            code = 200; ongoing = false
            assertEquals(1, repo.dispatchFootprintLive(date))                        // ongoing=false 已送达
            assertFalse(JSONObject(s.bodies.last()).ongoing())
            assertFalse(repo.retry(jobs.first(), 0))                                  // 旧任务补传：按日期重建，发的是最新状态
            assertEquals(3, s.bodies.size)
            assertFalse(JSONObject(s.bodies.last()).ongoing())
            assertEquals(date.toString(), JSONObject(s.bodies.last()).getJSONObject("footprint").getString("date"))
            assertEquals(0, context.getSharedPreferences("push-retries", 0).all.size)
            // 重建不出内容：丢掉任务，不发旧报文
            code = 500; ongoing = true
            assertEquals(1, repo.dispatchFootprintLive(date)); assertEquals(2, jobs.size)
            repo.footprintMessage = { null }
            assertFalse(repo.retry(jobs.last(), 0))
            assertEquals(4, s.bodies.size)
            assertEquals(0, context.getSharedPreferences("push-retries", 0).all.size)
        }
    }

    // ---------- F08：停留自然结束也触发同步 ----------

    @Test fun endedStayTriggersSyncAfterGapWithoutNewPoints() = kotlinx.coroutines.test.runTest {
        val points = home(0, 480)
        val base = at(482)
        val now = { base + testScheduler.currentTime }
        val sent = mutableListOf<Boolean>(); val emittedAt = mutableListOf<Long>()
        val changes = footprintChanges({ flowOf(points) }, emptyFlow(), now, { zone })
        FootprintLiveSync(prefs, { FootprintChangeDetector.signature(render(points, now()), now()) }, {
            sent += render(points, now()).stays().last().getBoolean("ongoing"); 1 }, now, { kotlinx.coroutines.delay(it) })
            .run(flow { changes.take(2).collect { emittedAt += now(); emit(it) } })
        assertEquals(listOf(base, at(480) + app.resound.location.Timeline.GAP_MS + 1_000), emittedAt)
        assertEquals(listOf(true, false), sent)                                      // 没有新点，超过 GAP 后推出 ongoing=false
    }

    // ---------- F08：跨零点昨日停留必收尾 ----------

    /** 足迹变化流 + 实时同步跑 30 分钟；只有昨日（date）有点，返回每次发送的（日期, 末段是否进行中）。 */
    private fun TestScope.syncAcrossMidnight(now: () -> Long, points: (LocalDate) -> Flow<List<LocationPoint>>,
                                             latest: () -> List<LocationPoint>): List<Pair<LocalDate, Boolean>> {
        val sent = mutableListOf<Pair<LocalDate, Boolean>>()
        fun to() = minOf(now(), dayEnd)
        backgroundScope.launch {
            FootprintLiveSync(prefs, { d -> if (d == date) FootprintChangeDetector.signature(render(latest(), to()), to()) else null },
                { d -> sent += d to render(latest(), to()).stays().last().getBoolean("ongoing"); 1 }, now, { kotlinx.coroutines.delay(it) })
                .run(footprintChanges(points, emptyFlow(), now, { zone }))
        }
        advanceTimeBy(30 * 60_000L); runCurrent()
        return sent
    }

    @Test fun crossMidnightClosesYesterdaysStayInsteadOfLosingItToToday() = runTest {
        val points = home(0, 1435)                                                    // 23:55 最后一点，仍在停留
        val base = at(1437)                                                           // 23:57 启动
        val sent = syncAcrossMidnight({ base + testScheduler.currentTime }, { d -> flowOf(if (d == date) points else emptyList()) }, { points })
        assertEquals(listOf(date to true, date to false), sent)                       // 零点的昨日收尾不被 00:00:01 的次日冲掉
    }

    @Test fun crossMidnightDoesNotResendYesterdayThatAlreadyEnded() = runTest {
        val points = home(0, 1380)                                                    // 23:00 后无点，23:30 时已断档
        val base = at(1410)
        val sent = syncAcrossMidnight({ base + testScheduler.currentTime }, { d -> flowOf(if (d == date) points else emptyList()) }, { points })
        assertEquals(listOf(date to false), sent)                                     // 换日补发的昨日签名不变，不再推
    }

    @Test fun dayEndTimerCancelledByDaySwitchStillClosesYesterday() = runTest {
        val base = at(1430)                                                           // 23:50 启动
        var points = home(0, 1425)
        // 23:59 来一个新点时墙钟被校正回拨 1s：昨日零点定时与换日定时同刻到期，先排队的换日先跑，flatMapLatest 取消了昨日定时
        val now = { base + testScheduler.currentTime - if (testScheduler.currentTime >= 540_000) 1_000 else 0 }
        val sent = syncAcrossMidnight(now, { d ->
            if (d != date) flowOf(emptyList()) else flow { emit(points); kotlinx.coroutines.delay(540_000); points = points + p(1439, homeLat, homeLng); emit(points) }
        }, { points })
        assertEquals(listOf(date to true, date to false), sent)                       // 换日时补发昨日，停留照样收尾
    }

    // ---------- F09：去重状态不存明文坐标 ----------

    @Test fun storedDedupStateIsDigestOnly() = runBlocking {
        val points = mall(600, 700)
        val raw = sig(points, 701, home = { _, _ -> false })
        assertTrue(raw, raw.contains("30.26000"))                                   // 无地名时签名里确有坐标
        val hex = Regex("[0-9a-f]{64}")
        fun assertClean() = prefs.all.forEach { (k, v) ->
            val text = v.toString()
            assertFalse(text, text.contains("30.26") || text.contains("119.95"))
            if (k == FootprintLiveSync.DATE || k.startsWith("${FootprintLiveSync.DATE}-")) assertEquals(date.toString(), text) else assertTrue(text, hex.matches(text))
        }
        assertTrue(FootprintLiveSync(prefs, { raw }, { 1 }, { 0L }).sync(date))
        assertEquals(2, prefs.all.size); assertClean()
        assertFalse(FootprintLiveSync(prefs, { raw }, { 1 }, { 0L }).sync(date))  // 重启后按摘要去重
        assertTrue(FootprintLiveSync(prefs, { raw }, { 1 }, { 0L }, targetIds = { setOf(7L) }, sendTo = { _, ids -> ids.size }).sync(date))
        assertEquals(setOf("${FootprintLiveSync.DATE}-7", "${FootprintLiveSync.SIG}-7"), prefs.all.keys); assertClean()
    }

    // ---------- F10：新通道补发当前足迹 ----------

    @Test fun newWebhookGetsCurrentFootprintAndOthersAreNotRepeated() = runBlocking {
        Server { 200 }.use { s ->
            val repo = repo(s.transport)
            val calls = mutableListOf<Set<Long>>()
            fun live() = FootprintLiveSync(prefs, { "s" }, { repo.dispatchFootprintLive(it) }, { 0L },
                targetIds = { repo.liveFootprintWebhookIds() }, sendTo = { d, ids -> calls += ids; repo.dispatchFootprintLive(d, ids) })
            val a = repo.webhook()
            assertTrue(live().sync(date)); assertEquals(1, s.bodies.size)
            val b = repo.webhook()
            assertTrue(live().sync(date))
            assertEquals(listOf(setOf(a), setOf(b)), calls)                         // 只给 B 补发
            assertEquals(2, s.bodies.size)
            assertFalse(live().sync(date)); assertEquals(2, s.bodies.size)           // 都同步过：不再发
            repo.setEnabled(a, false); repo.setEnabled(a, true)                     // 停用后重新启用算新通道
            assertFalse(live().sync(date)); assertEquals(2, s.bodies.size)           // 仅有启停、没经过同步的空窗口：仍去重
        }
    }
}
