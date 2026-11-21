package app.resound.transcribe

import android.content.Context
import app.resound.transcribe.local.LocalModels
import app.resound.transcribe.local.PauseChunks
import app.resound.voiceprint.LocalSpeakerDiarizer
import app.resound.voiceprint.SpeakerTurns
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/** Only the existing recording is processed. Capture boundaries and digest sessions are untouched. */
class CustomSpeakerAsrProvider(
    private val context: Context,
    private val settings: AndroidTranscribeSettings,
    private val remote: AsrProvider,
) : AsrProvider {
    override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult = withContext(Dispatchers.IO) {
        fun status(value: String) { settings.setLocalStatus(value); app.resound.PipelineProgress.detail(value) }
        status("自定义转写：准备音频")
        val pcm = audio.pcmFile?.takeIf { it.isFile }?.let(::readPcm16) ?: AsrAudioReader(context.cacheDir).readPcm(audio.file)
        currentCoroutineContext().ensureActive()
        val speakers = audio.separateSpeakers && (audio.speakerCount == 2 || LocalModels.speakersEnabled(context))
        val turns = if (!audio.separateSpeakers) listOf(SpeakerTurns.Turn(0, pcm.size, null)) else {
            status("自定义转写：区分说话人")
            try { LocalSpeakerDiarizer(context).use { diarizer ->
                diarizer.turns(pcm, audio.speakerCount ?: -1) { done, total -> status("区分说话人 $done/$total") }
            } } catch (e: CancellationException) { throw e }
            catch (_: Exception) { throw AsrException(AsrError.Speakers, detail = "本机说话人模型不可用，请检查模型下载") }
        }
        val chunks = PauseChunks.split(pcm, turns)
        val output = mutableListOf<AsrSentence>()
        var inputTokens: Long? = null
        var outputTokens: Long? = null
        var reserved = false
        var fine = 0
        var fallback = ""
        for ((index, chunk) in chunks.withIndex()) {
            currentCoroutineContext().ensureActive()
            status("自定义切分转写 ${index + 1}/${chunks.size}")
            val file = File.createTempFile("custom-slice-", ".wav", context.cacheDir)
            try {
                app.resound.digest.writeTodoWav(file, pcm.copyOfRange(chunk.start, chunk.end))
                val slice = AsrAudio(file, "wav", (chunk.end - chunk.start) / 16L, separateSpeakers = false)
                val result = remote.recognize(slice, key) {
                    // The caller accounts for this original recording, not its number of slices.
                    if (!reserved) { beforeAudio(); reserved = true }
                    check(audio.file.isFile) { "录音已删除" }
                }
                result.inputTokens?.let { inputTokens = (inputTokens ?: 0) + it }
                result.outputTokens?.let { outputTokens = (outputTokens ?: 0) + it }
                val text = result.sentences.joinToString("") { it.text }
                if (text.isBlank()) continue
                val aligned = if (settings.alignmentEndpoint.value.isNotBlank()) {
                    status("对齐时间戳 ${index + 1}/${chunks.size}")
                    try { align(slice, text, key).also { fine++ } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { fallback = "对齐失败，保留片段级时间戳"; null }
                } else { fallback = "未设置对齐服务，使用片段级时间戳"; null }
                val tag = chunk.speaker?.toString() ?: if (speakers) "uncertain:${chunk.groupStart}" else null
                val base = chunk.start / 16L
                val lines = (aligned ?: listOf(AsrSentence(0, slice.durationMillis, text, null))).map {
                    it.copy(startMillis = base + it.startMillis, endMillis = base + it.endMillis, speakerTag = tag)
                }
                // Local PauseChunks may overlap at its hard limit. Deduplicate only exact matching text.
                val old = output.lastOrNull()
                val first = lines.firstOrNull()
                if (chunk.overlapSamples > 0 && old != null && first != null && old.speakerTag == tag) {
                    val limit = minOf(old.text.length, first.text.length, 80)
                    val same = (limit downTo 2).firstOrNull { old.text.endsWith(first.text.take(it)) } ?: 0
                    if (same > 0) {
                        val trimmed = first.copy(text = first.text.drop(same))
                        output += listOfNotNull(trimmed.takeIf { it.text.isNotBlank() }) + lines.drop(1)
                    } else output += lines
                } else output += lines
            } finally { file.delete() }
        }
        status("切分转写完成：${fine}/${chunks.size} 段已精细对齐" + if (fallback.isNotBlank()) "；$fallback" else "")
        AsrResult(output, pcm.size / 16L, null, inputTokens, outputTokens)
    }

    private suspend fun align(audio: AsrAudio, text: String, key: String): List<AsrSentence> {
        val url = fullEndpoint(settings.alignmentEndpoint.value)
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(8, TimeUnit.MINUTES)
            .callTimeout(8, TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false).build()
        try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", audio.file.name, audio.file.asRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("text", text).build()
            val request = Request.Builder().url(url).apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }.post(body).build()
            client.newCall(request).awaitResponse().use { response ->
                check(response.isSuccessful) { "对齐服务请求失败" }
                val bytes = response.body!!.byteStream().use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (out.size() <= 1_000_000) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
                check(bytes.size <= 1_000_000) { "对齐响应过大" }
                return CustomAlignment.parse(bytes.toString(Charsets.UTF_8), text, audio.durationMillis)
            }
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
}
