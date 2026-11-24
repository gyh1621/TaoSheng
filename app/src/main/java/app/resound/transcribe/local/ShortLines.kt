package app.resound.transcribe.local

import app.resound.transcribe.AsrSentence

/** 仅新本机结果，同一已知说话人、原有 800ms 停顿以内；不跨暂停或未知说话人。 */
internal object ShortLines {
    /** 少于这么多字算“很短” */
    const val SHORT = 4
    /** 同一个人两行隔得再久就不并了 */
    const val MAX_GAP_MS = 799L
    /** 和 TimedSentences 的停顿断行一致：短于它说明两行之间其实没停 */
    const val PAUSE_MS = 800L
    const val MAX_CHARS = 60
    private val sentenceEnd = setOf('。', '！', '？', '!', '?', '；', ';', '.')

    private fun letters(text: String) = text.count(Char::isLetterOrDigit)
    private fun finished(text: String) = text.trimEnd().lastOrNull()?.let { it in sentenceEnd } ?: false
    private fun cjk(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    fun merge(lines: List<AsrSentence>): List<AsrSentence> {
        val out = mutableListOf<AsrSentence>()
        for (line in lines) {
            val last = out.lastOrNull()
            val gap = if (last == null) 0L else line.startMillis - last.endMillis
            val join = last != null && last.speakerTag != null && last.speakerTag == line.speakerTag && !finished(last.text) &&
                gap in 0..MAX_GAP_MS && letters(last.text) + letters(line.text) <= MAX_CHARS &&
                (gap < PAUSE_MS || letters(last.text) < SHORT || letters(line.text) < SHORT)
            if (!join) { out += line; continue }
            out[out.lastIndex] = last!!.copy(endMillis = maxOf(last.endMillis, line.endMillis),
                text = last.text.trimEnd() + separator(last.text.trimEnd(), line.text.trimStart(), gap) + line.text.trimStart())
        }
        return out
    }

    /** 中间停过就用逗号（中文）或空格（外文）隔开；没停就直接接上 */
    private fun separator(left: String, right: String, gap: Long): String {
        val a = left.lastOrNull() ?: return ""; val b = right.firstOrNull() ?: return ""
        if (!a.isLetterOrDigit()) return ""
        return if (!cjk(a) && !cjk(b)) " " else if (gap < PAUSE_MS) "" else "，"
    }
}
