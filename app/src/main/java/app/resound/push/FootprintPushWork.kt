package app.resound.push

import app.resound.location.FootprintReports
import app.resound.location.LocationPoint
import app.resound.location.LocationRepository
import app.resound.location.Timeline
import app.resound.location.Track

import android.content.Context
import androidx.work.*
import app.resound.AppGraph
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/** 每日足迹：到点推送，再排下一次。 */
class FootprintPushWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val channels = AppGraph.push as? AndroidPushChannels ?: return Result.retry()
        FootprintPushScheduler.plan(applicationContext, channels.dispatchFootprint())
        return Result.success()
    }
}

internal object FootprintPushScheduler {
    private const val NAME = "push-footprint"
    fun plan(context: Context, at: Long?) {
        val wm = WorkManager.getInstance(context)
        if (at == null) { wm.cancelUniqueWork(NAME); return }
        wm.enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<FootprintPushWork>()
            .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build())
    }
}

/** 通道或订阅变化时重排足迹推送；足迹内容由足迹模块生成。 */
internal fun AndroidPushChannels.attachFootprint(context: Context, scope: CoroutineScope) {
    footprintMessage = { date ->
        if (!AppGraph.isLocationReady) null
        else FootprintReports.build(AppGraph.location, date)?.let { r ->
            PushMessage(r.title, r.body, PushTopic.Footprint, "resound://footprint?date=${r.date}", System.currentTimeMillis(), footprint = r.json)
        }
    }
    scope.launch(Dispatchers.IO) {
        channels.map { list -> list.filter { it.enabled && it.subscription.footprint }.map { it.id to it.subscription.footprintAt } }
            .distinctUntilChanged().collect { FootprintPushScheduler.plan(context, nextFootprintAt()) }
    }
    // 有订阅足迹的 Webhook 才盯着已有的定位与地名数据（不另外要定位）；全部关掉或退订时 collectLatest 取消监听。
    // 增删单个通道不重启监听，只触发一次同步：新通道立即拿到当天当前足迹，已同步的不重复推。
    scope.launch(Dispatchers.IO) {
        val webhookIds = channels.map { liveFootprintWebhookIds(it) }.distinctUntilChanged()
        webhookIds.map { it.isNotEmpty() }.distinctUntilChanged().collectLatest { on ->
                if (!on || !AppGraph.isLocationReady) return@collectLatest
                val repo = AppGraph.location
                FootprintLiveSync(context.getSharedPreferences("footprint-live", Context.MODE_PRIVATE),
                    signature = { date -> val now = System.currentTimeMillis()
                        FootprintReports.build(repo, date, now = now)?.let { FootprintChangeDetector.signature(it, now) } },
                    send = { date -> dispatchFootprintLive(date) },
                    targetIds = { liveFootprintWebhookIds() },
                    sendTo = { date, ids -> dispatchFootprintLive(date, ids) },
                ).run(merge(footprintChanges(repo), webhookIds.drop(1).map { LocalDate.now() }))
            }
    }
}

private fun footprintChanges(repo: LocationRepository): Flow<LocalDate> =
    footprintChanges(repo::day, merge<Any?>(repo.places.manual, repo.places.revision))

/**
 * 今天的定位点、手动地点、地名缓存任一变化就发出今天的日期；过了零点换成新的一天。
 * 定位点不再来时，在末段停留或最后一点超过 GAP_MS 的那一刻再发一次，让“进行中”及时变成断档；新点到来就取消旧定时。
 * 换日时再补发一次前一天：昨日零点定时被换日取消（Doze、时钟校正）时昨日停留仍能收尾，签名没变则不发请求。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun footprintChanges(
    points: (LocalDate) -> Flow<List<LocationPoint>>, others: Flow<Any?>,
    now: () -> Long = System::currentTimeMillis, zone: () -> ZoneId = ZoneId::systemDefault,
): Flow<LocalDate> = flow {
    var previous: LocalDate? = null
    while (true) {
        val z = zone()
        val today = Instant.ofEpochMilli(now()).atZone(z).toLocalDate()
        emit(previous to today)
        previous = today
        delay(today.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli() - now() + 1_000)
    }
}.flatMapLatest { (previous, day) ->
    merge(flow { if (previous != null && previous != day) emit(previous) }, points(day).transformLatest { pts ->
        emit(day)
        val track = Track.day(pts)
        val last = track.points.lastOrNull() ?: return@transformLatest
        val stay = track.stays.lastOrNull()?.takeIf { Track.meters(it.lat, it.lng, last.lat, last.lng) <= Track.STAY_RADIUS_M }
        val expireAt = minOf(stay?.end ?: last.at, last.at) + Timeline.GAP_MS + 1_000L
        val dayEnd = day.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()
        val waitMs = minOf(expireAt, dayEnd) - now()
        if (waitMs > 0) { delay(waitMs); emit(day) }
    }, others.map { day })
}
