package app.resound.scheduling

/** 两版只是同一录音的任务计数，不改变录音或会话边界；耗时不用于重复计费。 */
data class PipelinePendingAudio(val split: Long = 0, val whole: Long = 0) {
    val millis: Long get() = maxOf(split, whole)
    fun ready(selectedReady: Boolean, customReady: Boolean) = selectedReady || whole > 0 && customReady
    // 本地 A 仍可离线执行；只有 B 待处理时才要求 Custom 的网络条件。
    fun localWork(sourceIsLocal: Boolean) = sourceIsLocal && (split > 0 || whole <= 0)
}
