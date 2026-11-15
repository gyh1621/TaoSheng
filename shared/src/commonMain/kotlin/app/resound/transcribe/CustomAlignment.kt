package app.resound.transcribe

import kotlinx.serialization.json.*

/** Alignment times are relative to the uploaded slice; text must remain unchanged. */
object CustomAlignment {
    fun parse(body: String, text: String, durationMillis: Long): List<AsrSentence> {
        val root = Json.parseToJsonElement(body).jsonObject
        root["text"]?.let { require(it.jsonPrimitive.content == text) { "对齐服务改写了原文" } }
        val items = root.getValue("items").jsonArray
        require(items.isNotEmpty() || text.isBlank()) { "对齐结果为空" }
        var previous = 0L
        val lines = items.map { item ->
            val obj = item.jsonObject
            val word = obj.getValue("text").jsonPrimitive.content
            val start = obj.getValue("start_ms").jsonPrimitive.long
            val end = obj.getValue("end_ms").jsonPrimitive.long
            require(start >= previous && start >= 0 && end > start && end <= durationMillis) { "对齐时间越界或顺序错误" }
            previous = start
            AsrSentence(start, end, word, null)
        }
        fun normalized(value: String) = value.filterNot(Char::isWhitespace)
        require(normalized(lines.joinToString("") { it.text }) == normalized(text)) { "对齐结果与原文不一致" }
        // Match the local model's sentence display, without inventing word timestamps.
        val sentences = mutableListOf<AsrSentence>()
        var current: AsrSentence? = null
        for (line in lines) {
            val old = current
            if (old != null && line.startMillis - old.endMillis >= 800) {
                sentences += old; current = null
            }
            current = current?.let { it.copy(endMillis = maxOf(it.endMillis, line.endMillis), text = it.text + line.text) } ?: line
            if (line.text.trimEnd().lastOrNull() in listOf('。', '！', '？', '!', '?', '；', ';', '\n')) {
                sentences += current!!; current = null
            }
        }
        current?.let { sentences += it }
        return sentences
    }
}

/** Do not silently accept a server's explicit incomplete-output marker. */
fun requireCompleteCustomTranscript(body: String) {
    val root = Json.parseToJsonElement(body).jsonObject
    fun incomplete(node: JsonObject): Boolean {
        val reason = node["finish_reason"]?.jsonPrimitive?.contentOrNull ?: node["stop_reason"]?.jsonPrimitive?.contentOrNull
        return reason in setOf("length", "max_tokens", "max_length", "truncated") ||
            node["truncated"]?.jsonPrimitive?.booleanOrNull == true ||
            node["incomplete"]?.jsonPrimitive?.booleanOrNull == true
    }
    if (incomplete(root) || root["choices"]?.jsonArray?.any { incomplete(it.jsonObject) } == true)
        throw AsrException(AsrError.Protocol, detail = "模型返回不完整（达到长度限制），请调整服务上限后重试")
}
