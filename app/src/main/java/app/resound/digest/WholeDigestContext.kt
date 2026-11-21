package app.resound.digest

import app.resound.data.TranscriptLineEntity
import app.resound.data.WholeTranscript

internal fun wholeDigestText(text: String, lines: List<TranscriptLineEntity>, rows: List<WholeTranscript>): String {
    val edited = lines.filter { it.textEditedAt != null }.map { it.segmentId }.toSet()
    val missing = rows.count { it.status != "Done" && it.status != "Deleted" }
    val source = if (missing > 0) text + "\n注意：本批有 $missing 段整体识别未成功，只能使用其切分版，不得假定已参考两份结果。" else text
    return WholeTranscriptContext.append(if (edited.isEmpty()) source else source + "\n人工修正的切分版为准；这些录音的旧整体机器文本不参与本次整理。", rows.filter { it.segmentId !in edited && it.status == "Done" && it.text.isNotBlank() }.map { row ->
        WholeContext(row.segmentId, lines.mapIndexedNotNull { index, line -> (index + 1).takeIf { line.segmentId == row.segmentId } }, row.text)
    }.filter { it.lineNumbers.isNotEmpty() })

}
