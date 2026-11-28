package app.resound.transcribe

import android.app.Application
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.resound.data.*
import app.resound.envsound.*
import app.resound.platform.ZoneProvider
import app.resound.privacy.RoomPrivacyActions
import app.resound.transcribe.local.LocalAsrLanguage
import app.resound.voiceprint.LocalVoiceprintRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StudioRoute2Test {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: CaptureDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var directory: File
    private lateinit var settings: AndroidTranscribeSettings
    private lateinit var server: MockWebServer
    private val config = EnvSoundSettings(true, SkipMode.On, MediaMode.SkipTranscribe)
    private val secret = "stub-test-bearer"
    private val original = "OggS-synthetic-raw-audio"

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).setDriver(AndroidSQLiteDriver()).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        directory = File(context.cacheDir, "studio-test").apply { mkdirs() }
        context.getSharedPreferences("transcribe", 0).edit().clear().commit()
        context.getSharedPreferences("local-asr", 0).edit().clear().commit()
        settings = AndroidTranscribeSettings(context).apply { setSource(TranscribeSource.Custom) }
        server = MockWebServer().apply { start() }
    }

    @After fun close() {
        runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        server.shutdown()
        db.close()
        directory.deleteRecursively()
    }

    @Test fun endpointDerivationAndOldCustomFallback() = runBlocking {
        assertNull(StudioClient.baseUrl(""))
        assertNull(StudioClient.baseUrl("http://user@example.test/v1/audio/process"))
        assertNull(StudioClient.baseUrl("http://example.test/#fragment"))
        assertEquals("https://example.test/workspace", StudioClient.baseUrl(" https://example.test/workspace/v1/audio/alignments/ "))
        assertEquals("http://example.test:8011", StudioClient.baseUrl("http://example.test:8011/v1/audio/process"))
        settings.saveCustom(server.url("/v1/audio/transcriptions").toString(), "stub", CustomEndpointMode.Full)
        val pcm = File(directory, "audio.pcm").apply { writeBytes(ByteArray(64000) { if (it % 2 == 0) 0x10 else 0x27 }) }
        val audio = segment(1).asrAudio(false, pcm)
        val provider = ConfiguredAsrProvider(context, settings)
        var reserves = 0
        server.enqueue(ok("""{"text":"legacy"}"""))
        assertFalse(provider.recognize(audio, secret) { reserves++ }.studioHosted)
        val legacy = take()
        assertEquals("/v1/audio/transcriptions", legacy.path)
        assertTrue(legacy.body.readUtf8().contains("RIFF"))
        useStudio()
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(ok("""{"text":"legacy"}"""))
        server.enqueue(ok("""{"items":[{"text":"legacy","start_ms":3,"end_ms":20}]}"""))
        val fallback = provider.recognize(audio, secret) { reserves++ }
        assertFalse(fallback.studioHosted)
        assertEquals(3L, fallback.sentences.single().startMillis)
        assertEquals(listOf("/v1/audio/process", "/v1/audio/transcriptions", "/v1/audio/alignments"), List(3) { take().path })
        assertEquals(2, reserves)
    }

    @Test fun temporaryAudioUsesCustomAndReturnsCapabilityWithoutStudioUpload() = runBlocking {
        useStudio()
        settings.saveCustom(server.url("/v1/audio/transcriptions").toString(), "stub", CustomEndpointMode.Full)
        server.enqueue(ok("""{"text":"temporary"}"""))
        var capability: CustomCapability? = null
        val source = segment(1)
        val test = runner(env()).test(AsrAudio(File(source.path), "ogg", 2000), secret) { capability = it.customCapability }
        assertTrue(test.isSuccess)
        assertNotNull(capability)
        assertEquals("/v1/audio/transcriptions", take().path)
        assertEquals(1, server.requestCount)
        assertNull(db.transcribe().findSegment(source.id))
    }

    @Test fun anonymousStudioScoreIsNotStoredAsIdentityConfidence() = runBlocking {
        useStudio()
        db.capture().segment(segment(1))
        val anonymous = JSONObject(lines).getJSONArray("lines").getJSONObject(0).apply {
            put("speaker_tag", "S1")
            put("speaker_score", .99)
            remove("speaker_name")
            remove("person_id")
        }
        server.enqueue(ok("""{"lines":[$anonymous]}"""))
        assertFalse(runner(env()).run())
        val saved = db.voiceprint().lines(1).single()
        assertEquals("S1", saved.speakerTag)
        assertNull(saved.speakerScore)
        assertNull(saved.speakerPersonId)
        assertNull(saved.speakerName)
    }

    @Test fun uploadsOriginalOnceAndSavesStudioIdentityWithoutWholeVersion() = runBlocking {
        useStudio()
        assertTrue(settings.ready(TranscribeSource.Custom))
        settings.setLanguage(LocalAsrLanguage.English)
        val segment = segment(1).copy(playbackMillis = 350, speakerCount = 2,
            speakerIntervals = """[{"from":10,"until":110,"remembered":true},{"from":2,"until":1}]""")
        db.capture().segment(segment)
        db.wholeTranscripts().enqueue(segment.id, 1)
        server.enqueue(ok(lines))
        assertFalse(runner(env()).run())
        val request = take()
        assertEquals("/v1/audio/process", request.path)
        assertEquals("Bearer " + secret, request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("filename=\"1.ogg\""))
        assertTrue(body.contains(original))
        val metadata = metadata(body)
        assertEquals("en", metadata.getString("language"))
        assertEquals(1L, metadata.getLong("segment_id"))
        assertEquals(segment.startAt, metadata.getLong("start_ms"))
        assertEquals(segment.endAt, metadata.getLong("end_ms"))
        assertEquals("mic", metadata.getString("source_id"))
        assertEquals(350L, metadata.getLong("playback_millis"))
        assertEquals(2, metadata.getInt("speaker_count"))
        assertTrue(metadata.getBoolean("separate_speakers"))
        val intervals = metadata.getJSONArray("speaker_intervals")
        assertEquals(1, intervals.length())
        assertEquals(setOf("from", "until"), intervals.getJSONObject(0).keys().asSequence().toSet())
        assertEquals(110L, intervals.getJSONObject(0).getLong("until"))
        assertEquals("fixture", metadata.getJSONArray("hotwords").getString(0))
        assertEquals("SkipTranscribe", metadata.getJSONObject("env_sound_settings").getString("media"))
        val line = db.voiceprint().lines(segment.id).single()
        assertEquals(segment.startAt, line.startAt)
        assertEquals(segment.startAt + 80, line.endAt)
        assertEquals("Fixture person", line.speakerName)
        assertEquals(.83f, line.speakerScore!!, .001f)
        assertEquals(42L, line.speakerPersonId)
        assertNotNull(db.transcribe().segment(segment.id).voiceprintProcessedAt)
        assertEquals("Deleted", db.wholeTranscripts().get(segment.id)?.status)
        assertEquals(1, server.requestCount)
    }

    @Test fun skipWritesDecisionAndManualRestoresOverrideServerModes() = runBlocking {
        useStudio()
        settings.setLanguage(LocalAsrLanguage.Japanese)
        val repo = env()
        val runner = runner(repo)
        val segment = segment(1)
        db.capture().segment(segment)
        server.enqueue(ok("""{"lines":[],"env_sound":{"audio_class":"music","is_media":false,"verdict":"Skipped","skip":true,"reason":"fixture music","mark_media":false,"tags":["Music"],"sounds":["Knock"],"scores":{"Music":0.9}}}"""))
        assertFalse(runner.run())
        val metadata = metadata(take().body.readUtf8())
        assertEquals("zh", metadata.getString("language"))
        assertEquals("On", metadata.getJSONObject("env_sound_settings").getString("skip"))
        assertEquals("SkippedEnv", db.transcribe().segment(1).status)
        val sound = db.envSound().get(1)!!
        assertEquals("Music", sound.tags)
        assertEquals("Knock", sound.sounds)
        assertEquals(.9, JSONObject(sound.scores).getDouble("Music"), .001)
        val second = segment(2)
        db.capture().segment(second)
        db.envSound().insert(sound.copy(segmentId = 2, startAt = second.startAt, endAt = second.endAt, path = second.path, reportedWrong = true))
        db.envSound().restored(1)
        db.envSound().requeue(1)
        repeat(2) { server.enqueue(ok(lines)) }
        assertFalse(runner.run())
        repeat(2) {
            val env = metadata(take().body.readUtf8()).getJSONObject("env_sound_settings")
            assertEquals("Off", env.getString("skip"))
            assertEquals("Off", env.getString("media"))
        }
        assertEquals("Done", db.transcribe().segment(1).status)
        assertEquals("Done", db.transcribe().segment(2).status)
        assertTrue(db.envSound().get(1)!!.restored)
        assertTrue(db.envSound().get(2)!!.reportedWrong)
    }

    @Test fun retries429AndKeepsDisconnectedAudioPending() = runBlocking {
        useStudio()
        val runner = runner(env())
        db.capture().segment(segment(1))
        repeat(2) { server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0")) }
        server.enqueue(ok(lines))
        assertFalse(runner.run())
        assertEquals(3, server.requestCount)
        assertEquals("Done", db.transcribe().segment(1).status)
        db.capture().segment(segment(2))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(runner.run())
        val pending = db.transcribe().segment(2)
        assertEquals("Pending", pending.status)
        assertEquals(0, pending.attempts)
        assertTrue(File(pending.path).isFile)
        server.enqueue(ok(lines))
        assertFalse(runner.run())
        assertEquals("Done", db.transcribe().segment(2).status)
    }

    @Test fun syncReopensOfflineEntriesAndSendsGroupAndLineCorrections() = runBlocking {
        useStudio()
        val segment = segment(1)
        val line = line(segment, 2, 50, 500)
        val first = line.copy(id = 1, speakerTag = "other")
        val group = line.copy(id = 3, startAt = segment.startAt + 800, speakerTag = line.speakerTag)
        val ordered = listOf(first, line, group)
        val offline = queue()
        offline.correct(segment, line, listOf(line), "assign", 0, "我", voiced)
        offline.correct(segment, line, ordered, "not_me")
        val untagged = line.copy(speakerTag = null)
        offline.correct(segment, untagged, listOf(untagged), "clear")
        offline.person("rename", 42, "Fixture person")
        offline.person("delete", 43)
        offline.person("reset_me", 0)
        offline.enroll(42, "Fixture person", voiced)
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(offline.flush())
        take()
        assertFalse(queueFile().readText().contains(secret))
        assertFalse(queueFile().readText().contains(line.text))
        assertEquals(2, clips().size)
        server.enqueue(ok("""{"ok":true,"webhook_synced":false}"""))
        assertTrue(queue().flush())
        assertEquals("/v1/voiceprints/correct", take().path)
        assertEquals(2, clips().size)
        repeat(6) { server.enqueue(ok(receipt)) }
        server.enqueue(ok("{}"))
        assertFalse(queue().flush())
        val assign = take().body.readUtf8()
        assertFalse(assign.contains("line_seq"))
        assertEquals("50", field(assign, "line_start_ms"))
        assertEquals("500", field(assign, "line_end_ms"))
        assertEquals("true", field(assign, "is_me"))
        assertTrue(assign.contains("RIFF"))
        val notMe = take().json()
        assertEquals("not_me", notMe.getString("action"))
        assertEquals("speaker", notMe.getString("speaker_tag"))
        assertFalse(notMe.has("line_seq"))
        assertFalse(notMe.has("line_start_ms"))
        assertFalse(notMe.has("line_end_ms"))
        assertEquals("clear", take().json().getString("action"))
        assertEquals("Fixture person", take().json().getString("name"))
        assertEquals("delete", take().json().getString("action"))
        assertEquals("reset_me", take().json().getString("action"))
        val enroll = take()
        assertEquals("/v1/voiceprints/enroll", enroll.path)
        assertTrue(enroll.body.readUtf8().contains("RIFF"))
        assertTrue(clips().isEmpty())
        assertEquals("[]", queueFile().readText())
    }

    @Test fun privacyPrunesMatchingClipsAndRequiresBothAcknowledgements() = runBlocking {
        useStudio()
        val a = segment(1)
        val b = segment(2)
        val c = segment(3)
        val queue = queue()
        for (s in listOf(a, b, c)) {
            val line = line(s, s.id, 100, 400)
            queue.correct(s, line, listOf(line), "assign", 0, "我", voiced)
            val payload = JSONObject(unsynced(s)).apply {
                getJSONArray("lines").getJSONObject(0)
                    .put("abs_start_ms", line.startAt).put("abs_end_ms", line.endAt)
            }
            queue.segmentSync(s, payload.toString())
        }
        settings.setAlignmentEndpoint("")
        queue.privacyDelete(listOf(a.id), listOf(b.startAt + 100 to b.startAt + 400), false)
        assertEquals(1, clips().size)
        useStudio()
        for (response in listOf(MockResponse().setResponseCode(503), MockResponse().setResponseCode(422), ok("""{"ok":true,"webhook_synced":false}"""),
            ok("""{"ok":"true","webhook_synced":true}"""))) {
            server.enqueue(response)
            assertTrue(queue.flush())
            val request = take()
            assertEquals("/v1/studio/privacy-delete", request.path)
            assertEquals(a.id, request.json().getJSONArray("segment_ids").getLong(0))
        }
        repeat(3) { server.enqueue(ok(receipt)) }
        assertFalse(queue().flush())
        assertEquals("/v1/studio/privacy-delete", take().path)
        val correction = take()
        assertEquals("/v1/voiceprints/correct", correction.path)
        assertEquals(c.id.toString(), field(correction.body.readUtf8(), "segment_id"))
        val sync = take()
        assertEquals("/v1/studio/sync-segment", sync.path)
        assertEquals(c.id, sync.json().getLong("segment_id"))
        assertTrue(clips().isEmpty())
        assertEquals("[]", queueFile().readText())
    }

    @Test fun failedStudioDeliverySavesLocallyAndRetriesWithoutReuploadingAudio() = runBlocking {
        useStudio()
        val segment = segment(1).copy(playbackMillis = 350,
            speakerIntervals = """[{"from":10,"until":110,"remembered":true},{"from":2,"until":1}]""")
        db.capture().segment(segment)
        server.enqueue(ok(unsynced(segment)))
        val queue = queue()
        assertFalse(runner(env(), queue::segmentSync).run())
        assertEquals("/v1/audio/process", take().path)
        assertEquals("Done", db.transcribe().segment(segment.id).status)
        assertEquals("fixture speech", db.voiceprint().lines(segment.id).single().text)
        assertTrue(queueFile().readText().contains("segment_sync"))
        server.enqueue(ok("""{"ok":true,"webhook_synced":false}"""))
        assertTrue(queue().flush())
        val attempt = take()
        assertEquals("/v1/studio/sync-segment", attempt.path)
        val payload = attempt.json()
        assertFalse(payload.has("webhook_synced"))
        assertEquals(350L, payload.getLong("playback_millis"))
        val intervals = payload.getJSONArray("speaker_intervals")
        assertEquals(1, intervals.length())
        assertEquals(10L, intervals.getJSONObject(0).getLong("startMs"))
        assertEquals(110L, intervals.getJSONObject(0).getLong("endMs"))
        server.enqueue(ok(receipt))
        assertFalse(queue().flush())
        assertEquals(payload.toString(), take().json().toString())
        assertEquals("[]", queueFile().readText())
        assertEquals(3, server.requestCount)
    }

    @Test fun syncedQueuedRedoClearsOldDeliveryWithoutReplayingIt() = runBlocking {
        val segment = segment(1).copy(redoPending = true)
        db.capture().segment(segment)
        var queued = 0
        val queue = StudioSyncQueue(directory, { StudioClient.baseUrl(settings.alignmentEndpoint.value) }, { secret }) { queued++ }
        queue.segmentSync(segment, null)
        assertFalse(queueFile().exists())
        assertEquals(0, queued)
        useStudio()
        queue.segmentSync(segment, unsynced(segment))
        server.enqueue(ok(lines))
        assertFalse(runner(env(), queue::segmentSync).run())
        assertEquals("/v1/audio/process", take().path)
        assertFalse(db.transcribe().segment(segment.id).redoPending)
        assertEquals("[]", queueFile().readText())
        assertEquals(1, queued)
        assertFalse(queue.flush())
        assertEquals(1, server.requestCount)
    }

    @Test fun failedQueuedRedoReplacesOlderDelivery() = runBlocking {
        useStudio()
        val segment = segment(1).copy(redoPending = true)
        db.capture().segment(segment)
        val queue = queue()
        queue.segmentSync(segment, unsynced(segment))
        server.enqueue(ok(unsynced(segment).replace("fixture speech", "updated speech")))
        assertFalse(runner(env(), queue::segmentSync).run())
        assertEquals("/v1/audio/process", take().path)
        assertFalse(db.transcribe().segment(segment.id).redoPending)
        val pending = JSONArray(queueFile().readText())
        assertEquals(1, pending.length())
        assertEquals("updated speech", pending.getJSONObject(0).getJSONObject("body").getJSONArray("lines")
            .getJSONObject(0).getString("text"))
        server.enqueue(ok(receipt))
        assertFalse(queue.flush())
        assertEquals("/v1/studio/sync-segment", take().path)
        assertEquals(2, server.requestCount)
    }

    @Test fun failedStudioDeliveryWithoutQueueLeavesSegmentRetryable() = runBlocking {
        useStudio()
        val segment = segment(1)
        db.capture().segment(segment)
        server.enqueue(ok(unsynced(segment)))
        assertTrue(runner(env()).run())
        assertEquals("/v1/audio/process", take().path)
        val failed = db.transcribe().segment(segment.id)
        assertEquals("Failed", failed.status)
        assertEquals(AsrError.Server.human, failed.error)
        assertNotNull(failed.nextRetryAt)
        assertTrue(db.voiceprint().lines(segment.id).isEmpty())
        assertFalse(queueFile().exists())
    }

    @Test fun queuedStudioRedoPreservesManualIdentity() = runBlocking {
        useStudio()
        val previous = segment(1).copy(redoPending = true)
        db.capture().segment(previous)
        db.transcribe().insertLines(listOf(line(previous, 5, 0, 100).copy(
            speakerPersonId = 3, speakerName = "Manual", speakerScore = .92f, speakerManual = true)))
        server.enqueue(ok(lines))
        assertFalse(runner(env()).run())
        val saved = db.voiceprint().lines(1).single()
        assertEquals(5L, saved.id)
        assertEquals("fixture speech", saved.text)
        assertEquals(3L, saved.speakerPersonId)
        assertEquals("Manual", saved.speakerName)
        assertEquals(.92f, saved.speakerScore!!, .001f)
        assertTrue(saved.speakerManual)
    }

    @Test fun studioRedoDisablesSkippingAndReplacesIdentity() = runBlocking {
        useStudio()
        val previous = segment(1).copy(redoPending = true, voiceprintProcessedAt = 1)
        db.capture().segment(previous)
        db.transcribe().insertLines(listOf(line(previous, 2, 0, 100).copy(speakerPersonId = 3, speakerName = "Old")))
        server.enqueue(ok(lines))
        assertFalse(runner(env()).run())
        val request = metadata(take().body.readUtf8())
        assertTrue(request.getBoolean("redo"))
        assertEquals("Off", request.getJSONObject("env_sound_settings").getString("skip"))
        assertEquals(42L, db.voiceprint().lines(1).single().speakerPersonId)
        assertEquals(100_000L, db.transcribe().segment(1).voiceprintProcessedAt)
        assertFalse(db.transcribe().segment(1).redoPending)
    }

    @Test fun forgettingEventScrubsOnlyItsQueuedTranscriptLines() = runBlocking {
        useStudio()
        val segment = segment(1)
        db.capture().segment(segment)
        val event = db.memory().insertEvent(MemoryEventEntity(startAt = segment.startAt, endAt = segment.endAt,
            scene = "fixture", title = "fixture", summary = "fixture", pointsJson = "[]", peopleJson = "[]", place = null,
            emotion = null, intensity = null, important = false, createdAt = 1, model = "stub"))
        val selected = line(segment, 1, 100, 200).copy(eventId = event, text = "selected")
        val retained = line(segment, 2, 200, 400).copy(text = "retained")
        db.transcribe().insertLines(listOf(selected, retained))
        val payload = JSONObject(unsynced(segment)).apply {
            put("full_text", "selected retained")
            put("has_me", true)
            put("lines", JSONArray().put(JSONObject().put("text", selected.text).put("is_me", true)
                .put("abs_start_ms", selected.startAt).put("abs_end_ms", selected.endAt))
                .put(JSONObject().put("text", retained.text).put("start_ms", 200).put("end_ms", 400)))
        }.toString()
        val queue = queue()
        queue.segmentSync(segment, payload)
        val originalId = JSONArray(queueFile().readText()).getJSONObject(0).getString("id")
        settings.setAlignmentEndpoint("")
        RoomPrivacyActions(db, onStudioPrivacyDelete = queue::privacyDelete).forgetEvent(event)
        assertNull(db.voiceprint().line(selected.id))
        assertEquals(retained.text, db.voiceprint().line(retained.id)?.text)
        val saved = queueFile().readText()
        assertFalse(saved.contains("selected"))
        assertFalse(saved.contains("has_me"))
        assertTrue(saved.contains("retained"))
        val entries = JSONArray(saved)
        val clipped = entries.getJSONObject(0)
        assertEquals(originalId, clipped.getString("id"))
        val range = clipped.getJSONArray("ranges").getJSONObject(0)
        assertEquals(retained.startAt, range.getLong("from_ms"))
        assertEquals(retained.endAt, range.getLong("until_ms"))
        assertEquals("privacy_delete", entries.getJSONObject(1).getString("kind"))

        useStudio()
        repeat(2) { server.enqueue(ok(receipt)) }
        assertFalse(queue().flush())
        assertEquals("/v1/studio/privacy-delete", take().path)
        val replay = take()
        assertEquals("/v1/studio/sync-segment", replay.path)
        val body = replay.json()
        assertFalse(body.has("has_me"))
        assertFalse(body.has("full_text"))
        assertEquals(1, body.getJSONArray("lines").length())
        assertEquals("retained", body.getJSONArray("lines").getJSONObject(0).getString("text"))

        queue.segmentSync(segment, body.toString())
        queue.privacyDelete(emptyList(), listOf(retained.startAt to retained.endAt), false)
        assertFalse(queueFile().readText().contains("segment_sync"))
        assertFalse(queueFile().readText().contains("retained"))
    }

    @Test fun roomPrivacyCallbackReportsCommittedPartialAndWholeDeletion() = runBlocking {
        val segment = segment(1)
        db.capture().segment(segment)
        val event = db.memory().insertEvent(MemoryEventEntity(startAt = segment.startAt, endAt = segment.endAt,
            scene = "fixture", title = "fixture", summary = "fixture", pointsJson = "[]", peopleJson = "[]", place = null,
            emotion = null, intensity = null, important = false, createdAt = 1, model = "stub"))
        val selected = line(segment, 1, 100, 200).copy(eventId = event)
        db.transcribe().insertLines(listOf(selected, line(segment, 2, 300, 400)))
        val calls = mutableListOf<Triple<List<Long>, List<Pair<Long, Long>>, Boolean>>()
        val actions = RoomPrivacyActions(db, onStudioPrivacyDelete = { ids, ranges, all ->
            assertNull(db.voiceprint().line(selected.id))
            calls += Triple(ids.toList(), ranges.toList(), all)
        })
        actions.forgetEvent(event)
        actions.forgetSegment(segment.id)
        actions.deleteRange(-100, 100)
        actions.deleteAll()
        assertEquals(listOf(Triple(emptyList<Long>(), listOf(selected.startAt to selected.endAt), false),
            Triple(listOf(segment.id), emptyList(), false), Triple(emptyList(), listOf(0L to 100L), false),
            Triple(emptyList(), emptyList(), true)), calls)
    }

    @Test fun manualVoiceprintStillCommitsWhenCampPlusIsMissing() = runBlocking {
        useStudio()
        val segment = segment(1).copy(status = "Done", voiceprintProcessedAt = 500, logId = STUDIO_HOSTED_LOG_ID)
        db.capture().segment(segment)
        db.transcribe().insertLines(listOf(line(segment, 1, 0, 1800)))
        val repo = LocalVoiceprintRepository(db, scope, { voiced }, { error("synthetic missing model") }, {})
        repo.studioSync = queue()
        assertTrue(repo.nameAndAssign(1, "Fixture person").isSuccess)
        val person = db.voiceprint().people().single()
        assertEquals(person.id, db.voiceprint().line(1)!!.speakerPersonId)
        assertTrue(repo.enrollPerson(person.id, voiced).isSuccess)
        assertTrue(db.voiceprint().allSamples().isEmpty())
        assertEquals(2, clips().size)
        assertEquals(500L, db.transcribe().segment(1).voiceprintProcessedAt)
    }

    private fun useStudio() = settings.setAlignmentEndpoint(server.url("/v1/audio/alignments").toString())
    private fun take(): RecordedRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
    private fun RecordedRequest.json() = JSONObject(body.clone().readUtf8())
    private fun ok(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")
    private fun metadata(body: String) = JSONObject(field(body, "metadata"))
    private fun field(body: String, name: String) = body.substringAfter("name=\"$name\"").substringAfter("\r\n\r\n").substringBefore("\r\n--")
    private fun queue() = StudioSyncQueue(directory, { StudioClient.baseUrl(settings.alignmentEndpoint.value) }, { secret })
    private fun queueFile() = File(directory, "studio-sync-queue.json")
    private fun clips() = File(directory, "studio-sync-clips").listFiles().orEmpty().filter { it.extension == "wav" }
    private fun segment(id: Long): SegmentEntity {
        val file = File(directory, "$id.ogg").apply { writeText(original) }
        return SegmentEntity(id, id * 10_000, id * 10_000 + 2_000, "mic", file.path, file.length(), separateSpeakers = true)
    }
    private fun line(s: SegmentEntity, id: Long, from: Long, until: Long) =
        TranscriptLineEntity(id, s.id, s.startAt + from, s.startAt + until, "private transcript fixture", "speaker")
    private fun env() = LocalEnvSoundRepository(db, context.getSharedPreferences("studio-env-test", 0), scope,
        MutableStateFlow(EnvModelState.NotDownloaded), {}, { error("local analysis ran") }, {}, now = { 20_000 },
        zoneProvider = ZoneProvider { "UTC" }, io = Dispatchers.IO)
    private fun runner(env: LocalEnvSoundRepository, enqueue: (suspend (SegmentEntity, String?) -> Unit)? = null) = TranscribeRunner(db.transcribe(),
        ConfiguredAsrProvider(context, settings, { config }, env::isRestoredOrWrong) { listOf("fixture") }, { secret }, { _, _ -> },
        defaultSource = { TranscribeSource.Custom }, beforeTranscribe = { error("local analysis ran") },
        wholePrepare = { _, _ -> error("whole transcription enqueued") }, invalidateWhole = { db.wholeTranscripts().invalidate(it) },
        recordStudioDecision = env::recordStudioDecision, isRestoredOrWrong = env::isRestoredOrWrong, now = { 100_000 },
        onStudioUnsyncedSegment = enqueue)

    private fun unsynced(segment: SegmentEntity) = JSONObject(lines).apply {
        put("segment_id", segment.id)
        put("start_ms", segment.startAt)
        put("end_ms", segment.endAt)
        put("env_sound", JSONObject().put("verdict", "Keep"))
        put("webhook_synced", false)
    }.toString()

    private val receipt = """{"ok":true,"webhook_synced":true}"""
    private val voiced = ShortArray(32_000) { 3200 }
    private val lines = """{"lines":[{"line_seq":0,"text":"fixture speech","rel_start_ms":-4,"rel_end_ms":80,"speaker_tag":"speaker","speaker_name":"Fixture person","person_id":42,"speaker_score":0.83,"is_me":false}]}"""
}
