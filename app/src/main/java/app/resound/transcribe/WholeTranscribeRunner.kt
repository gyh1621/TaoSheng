package app.resound.transcribe

import app.resound.data.TranscribeDao
import app.resound.data.WholeTranscriptDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/** 只提交原文件一次。不拼录音，不按说话人切块，不伪造时间戳。由转写 gate 串行调用。 */
class WholeTranscribeRunner(
    private val dao: WholeTranscriptDao,
    private val segments: TranscribeDao,
    private val provider: AsrProvider,
    private val credential: () -> String,
    private val allowed: () -> Boolean,
    private val identity: () -> String,
    private val warning: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val gate = kotlinx.coroutines.sync.Mutex()
    suspend fun runPending(): Boolean {
        if (!gate.tryLock()) return true
        try { return runLocked() } finally { gate.unlock() }
    }
    private suspend fun runLocked(): Boolean {
        dao.recover()
        if (!allowed()) return dao.pending().isNotEmpty()
        val config = identity()
        for (row in dao.pending()) {
            if (config != identity() || !allowed()) return true
            val segment = segments.findSegment(row.segmentId) ?: continue
            if (dao.claim(row.segmentId, row.generation) == 0) continue
            try {
                val file = File(segment.path)
                if (!file.isFile) throw AsrException(AsrError.Audio, detail = "原录音已不存在，无法整体识别")
                val result = provider.recognize(AsrAudio(file, file.extension,
                    (segment.endAt - segment.startAt).coerceAtLeast(0), separateSpeakers = false), credential()) {
                    check(dao.get(row.segmentId)?.let { it.generation == row.generation && it.status == "Running" } == true)
                }
                if (config != identity()) throw AsrException(AsrError.Interrupted, detail = "配置已更改，请重新转录")
                val text = result.sentences.joinToString("\n") { it.text }
                val notice = if (text.isNotBlank() && segments.linesForRedo(row.segmentId).none { it.text.isNotBlank() })
                    "切分版为空，整体版有内容，尚未自动整理；请重转录核对" else null
                if (dao.complete(row.segmentId, row.generation, text, now(), notice) > 0 && notice != null) warning(notice)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { dao.fail(row.segmentId, row.generation, "任务中断，等待重试", now()) }
                throw e
            } catch (e: Exception) {
                // 不存服务器正文或凭据；A 的成功结果完全不受影响。
                dao.fail(row.segmentId, row.generation,
                    if (e is AsrException) e.detail ?: e.type.human else "整体识别失败，请重试", now())
            }
        }
        return dao.pending().isNotEmpty()
    }
}
