package app.resound.ui.transcript

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import app.resound.AppGraph
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType

/** 只切换展示，绝不把整体文字转换成带说话人或虚假时间的原话行。 */
@Composable
fun TranscriptVersions(segmentIds: List<Long>, segmented: @Composable () -> Unit) {
    val ids = segmentIds.filter { it > 0 }.distinct()
    if (ids.isEmpty() || !AppGraph.isWholeTranscriptsReady) { segmented(); return }
    var whole by remember(ids) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        app.resound.ui.skills.Segments(listOf("切分版", "整体版"), if (whole) 1 else 0) { whole = it == 1 }
        if (!whole) segmented() else {
            Text("同一原始录音的整段识别，仅供上下文参考；不代表当前引用句或某一位说话人的发言，无逐句时间。", style = RsType.caption, color = Rs.colors.textTertiary)
            ids.forEachIndexed { index, id ->
                key(id) {
                    if (ids.size > 1) Text("录音 ${index + 1}", style = RsType.caption, color = Rs.colors.textSecondary)
                    WholeTranscriptText(id)
                }
            }
        }
    }
}

@Composable
private fun WholeTranscriptText(segmentId: Long) {
    val row by remember(segmentId) { AppGraph.wholeTranscripts.observe(segmentId) }.collectAsStateWithLifecycle(initialValue = null)
    val value = row
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var choosing by remember(segmentId) { mutableStateOf(false) }
    val status = when (value?.status) {
        "Pending" -> "整体版等待识别"
        "Running" -> "整体版识别中…"
        "Failed" -> "整体版失败：${value?.error ?: "请重新转写此录音"}"
        "Deleted" -> "整体版已删除"
        "Skipped" -> "已选择本次仅使用切分版整理，旧整体版不参与本次整理"
        "Done" -> if (value?.text.isNullOrBlank()) "整体版未识别出说话内容" else null
        else -> "此录音尚无整体版；可用自定义模型重新转写生成"
    }
    if (status != null) Text(status, style = RsType.caption, color = if (value?.status == "Failed") c.warning else c.textSecondary)
    if (value?.status == "Done" && !value.error.isNullOrBlank()) Text(value.error.orEmpty(), style = RsType.caption, color = c.warning)
    if (value?.status == "Failed") {
        app.resound.ui.verify.Pill(if (choosing) "处理中…" else "本次仅切分版整理", filled = false) {
            if (!choosing) {
                choosing = true
                scope.launch {
                    try {
                        if (AppGraph.wholeTranscripts.useSegmentedOnly(segmentId) > 0 && AppGraph.isMemoryReady) AppGraph.memory.runNow()
                    } finally { choosing = false }
                }
            }
        }
        Text("或回到录音历史重新转写，重试两版识别。", style = RsType.caption, color = c.textSecondary)
    }
    if (value != null && value.text.isNotBlank() && value.status != "Deleted") {
        if (value.status != "Done") Text(if (value.status == "Skipped") "以下是旧整体版，仅供查看，不是本次结果" else "以下为上次成功结果，新结果尚未完成", style = RsType.caption, color = c.warning)
        SelectionContainer { Text(value.text, style = RsType.body, color = c.text) }
    }
}

/** 引用和声纹只持有原话 ID；解析其所属录音，不按时间猜关联。 */
@Composable
fun LineTranscriptVersions(lineId: Long?, segmented: @Composable () -> Unit) {
    val segmentId by produceState<Long?>(null, lineId) {
        value = if (lineId != null && AppGraph.isWholeTranscriptsReady)
            AppGraph.wholeTranscripts.segmentIdForLine(lineId) else null
    }
    val id = segmentId
    if (id == null) segmented() else TranscriptVersions(listOf(id), segmented)
}

/** A 没文字也不隐藏 B，且不假造 A 原话供整理引用。 */
@Composable
internal fun EmptySegmentVersions(segmentId: Long, segmented: @Composable () -> Unit) {
    if (AppGraph.isWholeTranscriptsReady) {
        val row by remember(segmentId) { AppGraph.wholeTranscripts.observe(segmentId) }.collectAsStateWithLifecycle(initialValue = null)
        if (row?.status == "Done" && !row?.text.isNullOrBlank()) {
            Text("切分版为空，整体版有内容，尚未自动整理；请重转录核对", style = RsType.caption, color = Rs.colors.warning)
            Spacer(Modifier.height(8.dp))
        }
    }
    TranscriptVersions(listOf(segmentId), segmented)
}
