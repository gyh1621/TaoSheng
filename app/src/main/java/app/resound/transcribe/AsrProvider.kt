package app.resound.transcribe

import app.resound.data.SegmentEntity
import java.io.File

/** 后续服务商/自建服务实现此接口；地址与资源统一从配置传入。 */
interface AsrProvider {
    suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult
}
data class AsrAudio(val file: File, val format: String, val durationMillis: Long, val separateSpeakers: Boolean = true, val pcmFile: File? = null, val speakerCount: Int? = null,
    val segmentId: Long? = null, val startAt: Long? = null, val endAt: Long? = null, val sourceId: String? = null,
    val playbackMillis: Long = 0L, val speakerIntervalsJson: String? = null, val redo: Boolean = false)

internal fun SegmentEntity.asrAudio(distinguish: Boolean, pcmFile: File? = null, redo: Boolean = redoPending): AsrAudio {
    val file = File(path)
    return AsrAudio(file, file.extension, (endAt - startAt).coerceAtLeast(0), distinguish, pcmFile, speakerCount,
        id, startAt, endAt, sourceId, playbackMillis, speakerIntervals, redo)
}
