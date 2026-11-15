package app.resound.digest

/** 双版是同一录音的两次识别，第二版只校正上下文，不创造第二件事或说话人。 */
data class WholeContext(val segmentId: Long, val lineNumbers: List<Int>, val text: String)
object WholeTranscriptContext {
    fun blocksDigest(status: String): Boolean = status != "Done" && status != "Deleted" && status != "Skipped"
    const val WAITING = "整体版尚未成功，已保留切分版并暂停这段录音的整理；请在历史中重新转录，或在整体版里明确选择“本次仅切分版整理”"
    fun append(original: String, rows: List<WholeContext>): String {
        if (rows.isEmpty()) return original
        return original + "\n\n以下是相同原始录音的整体识别版，不是新增录音。上面的切分版提供说话人、时间和引用编号；下面只补充上下文、纠正识别文字，禁止重复统计、虚构说话人或给整体版编造引用编号。可能包含当前选中句子之外的同段上下文，只整理上面选中句子的内容，不将额外上下文另建事件。\n" +
            rows.joinToString("\n\n") { "【同一录音，切分版句号 ${it.lineNumbers.joinToString(",")} 的整段上下文】\n${it.text}" }
    }
}
