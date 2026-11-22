package app.resound.net

/**
 * 卡 220：429 不一定是“太频繁”（免费模型并发限制、模型过载、欠费都可能回 429）。
 * 只提取错误类别，不回显不可信服务商正文（可能包含提示词、凭证或签名 URL）。
 * 认得的写法：{"error":{"message":"..."}}、{"error":"..."}、{"message":"..."}、{"msg":"..."}；认不出时说明为 null，沿用“请求太频繁”。
 */
internal fun rateLimitReason(raw: String): Pair<NetLlmError, String?> {
    val json = runCatching { Obj(raw) }.getOrNull() ?: return NetLlmError.RateLimit to null
    val error = json.optJSONObject("error")
    val message = listOfNotNull(error?.optString("message"), json.optString("error").takeIf { error == null },
        json.optString("message"), json.optString("msg"))
        .map { it.trim() }.firstOrNull { it.isNotEmpty() && it != "null" }?.take(120)
        ?: return NetLlmError.RateLimit to null
    val text = message.lowercase()
    val quota = listOf("欠费", "余额", "额度", "quota", "insufficient", "balance", "billing", "credit").any { it in text }
    val safe = when {
        quota -> "服务商额度不足，请检查账户额度"
        "并发" in text || "concurr" in text -> "服务商并发数过高，请稍后重试"
        "overload" in text || "过载" in text -> "服务商暂时过载，请稍后重试"
        else -> "请求受到服务商限制，请稍后重试"
    }
    return (if (quota) NetLlmError.Quota else NetLlmError.RateLimit) to safe
}
