package app.resound.push

import android.content.SharedPreferences
import app.resound.diagnostics.FailureNotes
import app.resound.location.FootprintReport
import app.resound.location.Timeline
import app.resound.location.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

/**
 * 足迹的结构签名：每段停留的（开始、在家、地名、地址、进行中）加上当前状态。
 * 原地不动只让停留的结束时间和分钟数变长，签名不变；新停留、离开、进出家、地名补全或改名都会让它变。
 */
internal object FootprintChangeDetector {
    fun signature(report: FootprintReport, now: Long): String {
        val stays = report.json.getJSONArray("stays").let { a -> List(a.length()) { a.getJSONObject(it) } }
        val points = report.json.getJSONArray("points")
        val last = points.getJSONObject(points.length() - 1)
        val lat = last.getDouble("lat"); val lng = last.getDouble("lng")
        val open = stays.lastOrNull()?.takeIf { it.getBoolean("ongoing") }
        val current = when {
            open != null -> "stay:${open.getString("start")}:${open.getBoolean("home")}"
            now - Instant.parse(last.getString("at")).toEpochMilli() > Timeline.GAP_MS -> "gap"
            else -> "moving:" + stays.any { it.getBoolean("home") &&
                Track.meters(it.getDouble("lat"), it.getDouble("lng"), lat, lng) <= Track.STAY_RADIUS_M }
        }
        return (stays.map { s -> listOf("start", "home", "name", "address", "ongoing").joinToString("|") { s.opt(it).toString() } } + current)
            .joinToString("\n")
    }
}

/**
 * 足迹变化即同步：changes 每发一次表示“这天的足迹可能变了”（新定位点、地名、手动地点、跨日）。
 * 先等 debounceMs 合并连发，再守 minIntervalMs；等完才算签名，所以冷却期里的变化不会丢，补发的是最新状态。
 * 已同步的签名只存 SHA-256 摘要（签名里有地址，无地名时还有坐标），重启后今天没变就不重推；跨日按新日期重新比较。
 * targetIds 给出具体通道时按通道记：新增或重新启用的通道补发当前足迹，已同步的不重复打扰。
 */
internal class FootprintLiveSync(
    private val prefs: SharedPreferences,
    private val signature: suspend (LocalDate) -> String?,
    private val send: suspend (LocalDate) -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val debounceMs: Long = 20_000L,
    private val minIntervalMs: Long = 5 * 60_000L,
    private val targetIds: () -> Set<Long>? = { null },
    private val sendTo: (suspend (LocalDate, Set<Long>) -> Int)? = null,
) {
    private var lastSentAt: Long? = null

    /** 待同步日期按天去重、最早的先发：跨零点时昨日的收尾（ongoing=false）不会被次日的变化覆盖。 */
    suspend fun run(changes: Flow<LocalDate>) = coroutineScope {
        val pending = sortedSetOf<LocalDate>()
        val wake = Channel<Unit>(Channel.CONFLATED)
        launch { try { changes.collect { d -> synchronized(pending) { pending += d }; wake.trySend(Unit) } } finally { wake.close() } }
        for (ignored in wake) {
            while (true) {
                if (synchronized(pending) { pending.isEmpty() }) break
                delayFn(debounceMs)
                lastSentAt?.let { at -> (at + minIntervalMs - clock()).takeIf { it > 0 }?.let { delayFn(it) } }
                val date = synchronized(pending) { pending.firstOrNull()?.also { pending.remove(it) } } ?: break
                try { sync(date) } catch (e: CancellationException) { throw e } catch (caught: Exception) {
                    FailureNotes.record("push/footprint-live-sync", caught) }
            }
        }
    }

    /** 签名与上次已同步的不同才推；没有可推的通道或内容时不记，下次变化再试。 */
    suspend fun sync(date: LocalDate): Boolean {
        val ids = sendTo?.let { targetIds() }
        if (ids != null) forgetLeft(ids)
        val digest = signature(date)?.let(::sha256) ?: return false
        val day = date.toString()
        if (ids == null) {
            if (prefs.getString(DATE, null) == day && prefs.getString(SIG, null) == digest) return false
            if (send(date) <= 0) return false
            check(prefs.edit().putString(DATE, day).putString(SIG, digest).commit())
        } else {
            val pending = ids.filterTo(mutableSetOf()) { prefs.getString("$DATE-$it", null) != day || prefs.getString("$SIG-$it", null) != digest }
            if (pending.isEmpty() || sendTo!!(date, pending) <= 0) return false
            val edit = prefs.edit()
            pending.forEach { edit.putString("$DATE-$it", day).putString("$SIG-$it", digest) }
            check(edit.commit())
        }
        lastSentAt = clock()
        return true
    }

    /** 停用或删除的通道忘掉同步记录，重新启用时按新通道补发；顺带清掉旧版单桶（可能是明文签名）。 */
    private fun forgetLeft(ids: Set<Long>) {
        val left = prefs.all.keys.filter { key -> key == DATE || key == SIG || key.startsWith("$DATE-") && key.removePrefix("$DATE-").toLongOrNull()?.let { it !in ids } == true ||
            key.startsWith("$SIG-") && key.removePrefix("$SIG-").toLongOrNull()?.let { it !in ids } == true }
        if (left.isNotEmpty()) check(prefs.edit().apply { left.forEach(::remove) }.commit())
    }

    companion object {
        const val DATE = "footprint-live-sig-date"
        const val SIG = "footprint-live-sig"
    }
}
