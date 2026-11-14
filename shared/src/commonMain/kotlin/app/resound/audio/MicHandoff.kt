package app.resound.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** 已启动录音的竞争者优先；仅手机和蓝牙通话路由主动释放，USB/有线保持原行为。 */
object MicHandoff {
    fun shouldRelease(kind: InputKind, othersRecording: Boolean): Boolean =
        othersRecording && (kind == InputKind.Phone || kind == InputKind.BluetoothCall || kind == InputKind.BluetoothLe)
    /** 本轮一旦看到竞争者就锁存，避免短暂 start→失败→stop 被下一次读取漏掉。 */
    class Signal {
        private val observed = MutableStateFlow(false)
        val requested: Boolean get() = observed.value
        fun observe(active: Boolean) { if (active) observed.value = true }
        suspend fun awaitOrTimeout() { withTimeoutOrNull(250) { observed.first { it } } }
    }

    const val QUIET_WINDOW_MS = 2_000L
    const val POLL_MS = 250L

    /** 留出重试时间；连续空闲两秒才恢复。暂停/停止取消所属协程时不会自行重开。 */
    suspend fun awaitRelease(isRecording: () -> Boolean, pause: suspend (Long) -> Unit) {
        var quiet = 0L
        while (quiet < QUIET_WINDOW_MS) {
            val busyBefore = isRecording()
            pause(POLL_MS)
            quiet = if (busyBefore || isRecording()) 0 else quiet + POLL_MS
        }
    }
}
