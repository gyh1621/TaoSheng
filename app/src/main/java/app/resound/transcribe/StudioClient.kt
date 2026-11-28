package app.resound.transcribe

import app.resound.envsound.EnvSoundSettings
import app.resound.envsound.MediaMode
import app.resound.envsound.SkipMode
import app.resound.net.defaultAndroidHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object StudioClient {
    private val languages = setOf("zh", "en", "yue", "de", "es", "fr", "it", "pt", "ru")
    private val upload = defaultAndroidHttp.newBuilder().connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.MINUTES).readTimeout(8, TimeUnit.MINUTES).callTimeout(15, TimeUnit.MINUTES)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    internal val syncHttp = upload.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()

    fun baseUrl(endpoint: String): String? {
        val url = runCatching { fullEndpoint(endpoint) }.getOrNull()?.toHttpUrlOrNull() ?: return null
        val path = url.encodedPath.trimEnd('/').removeSuffix("/v1/audio/alignments").removeSuffix("/v1/audio/process")
        return url.newBuilder().encodedPath(path.ifEmpty { "/" }).query(null).build().toString().trimEnd('/')
    }

    fun request(base: String, path: String, key: String, body: RequestBody): Request =
        Request.Builder().url(base + path).apply {
            if (key.isNotBlank()) header("Authorization", "Bearer $key")
        }.post(body).build()

    suspend fun process(base: String, audio: AsrAudio, key: String, language: String, hotwords: List<String>,
        env: EnvSoundSettings, keepAudio: Boolean, beforeAudio: suspend () -> Unit): AsrResult? = withContext(Dispatchers.IO) {
        val mediaType = when (audio.format.lowercase()) {
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "m4a", "mp4" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            else -> "application/octet-stream"
        }
        val metadata = metadata(audio, language, hotwords, env, keepAudio)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", audio.file.name, audio.file.asRequestBody(mediaType.toMediaType()))
            .addFormDataPart("metadata", metadata.toString()).build()
        try {
            if (!audio.file.isFile) throw AsrException(AsrError.Audio)
            val request = request(base, "/v1/audio/process", key, body)
            beforeAudio()
            repeat(3) { attempt ->
                val wait = upload.newCall(request).awaitResponse().use { response ->
                    when (response.code) {
                        200 -> return@withContext parse(response.body?.string(), audio.durationMillis, metadata)
                        404, 405 -> return@withContext null
                        401, 403 -> throw AsrException(AsrError.Auth)
                        413, 415 -> throw AsrException(AsrError.Audio)
                        429 -> {
                            if (attempt == 2) throw AsrException(AsrError.Server, detail = "工作室服务繁忙，等待重试")
                            (response.header("Retry-After")?.toLongOrNull() ?: 1).coerceIn(0, 5) * 1000
                        }
                        in 500..599 -> throw AsrException(AsrError.Server)
                        else -> throw AsrException(AsrError.Protocol)
                    }
                }
                delay(wait)
            }
            error("工作室请求重试结束")
        } catch (_: FileNotFoundException) { throw AsrException(AsrError.Audio) }
        catch (_: IOException) { throw AsrException(AsrError.Network) }
    }

    private fun metadata(audio: AsrAudio, language: String, hotwords: List<String>, env: EnvSoundSettings,
        keepAudio: Boolean) = buildJsonObject {
        audio.segmentId?.let { put("segment_id", it) }
        audio.startAt?.let { put("start_ms", it) }
        audio.endAt?.takeIf { it > (audio.startAt ?: 0) }?.let { put("end_ms", it) }
        audio.sourceId?.let { put("source_id", it) }
        put("playback_millis", audio.playbackMillis.coerceAtLeast(0))
        putJsonArray("speaker_intervals") {
            val intervals = audio.speakerIntervalsJson?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
            intervals.orEmpty().forEach { item ->
                val interval = item as? JsonObject ?: return@forEach
                val from = interval.long("from") ?: return@forEach
                val until = interval.long("until") ?: return@forEach
                if (until > from) add(buildJsonObject { put("from", from); put("until", until) })
            }
        }
        put("separate_speakers", audio.separateSpeakers)
        audio.speakerCount?.takeIf { it >= 1 }?.let { put("speaker_count", it) }
        put("language", language.takeIf { it in languages } ?: "zh")
        if (hotwords.isNotEmpty()) putJsonArray("hotwords") { hotwords.forEach { add(it) } }
        putJsonObject("env_sound_settings") {
            put("enabled", env.enabled)
            put("skip", when { keepAudio || audio.redo -> "Off"; env.skip == SkipMode.MarkOnly -> "DetectOnly"; else -> env.skip.name })
            put("media", if (keepAudio || audio.redo || env.media == MediaMode.Keep) "Off" else env.media.name)
            put("tags", env.tags)
            put("special_sounds", env.specialSounds)
        }
        put("redo", audio.redo)
    }

    private fun parse(body: String?, duration: Long, metadata: JsonObject): AsrResult = try {
        val response = Json.parseToJsonElement(requireNotNull(body)).jsonObject
        val lines = response.getValue("lines").jsonArray.map { it.jsonObject }
            .sortedBy { it.long("line_seq") ?: Long.MAX_VALUE }.map { line ->
            val start = (line.long("start_ms") ?: line.long("rel_start_ms") ?: 0).coerceAtLeast(0)
            val end = (line.long("end_ms") ?: line.long("rel_end_ms") ?: start).coerceAtLeast(start)
            val isMe = line.boolean("is_me") == true
            val personId = if (isMe) 0L else line.long("person_id")
            val speakerName = line.string("speaker_name") ?: if (isMe) "我" else null
            val speakerScore = if (isMe || personId != null || speakerName != null)
                (line["speaker_score"] as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() } else null
            AsrSentence(start, end, requireNotNull(line.string("text")), line.string("speaker_tag"),
                speakerName = speakerName, speakerScore = speakerScore, speakerPersonId = personId)
        }
        val env = (response["env_sound"] as? JsonObject)?.let { sound ->
            val verdict = sound.string("verdict") ?: if (sound.boolean("is_media") == true || sound.string("audio_class") == "media_playback") "Media"
                else throw AsrException(AsrError.Protocol)
            StudioEnvSound(verdict, sound.string("reason"), sound.boolean("skip") == true,
                sound.boolean("mark_media") == true, sound.strings("tags"), sound.strings("sounds"),
                (sound["scores"] as? JsonObject).orEmpty().mapNotNull { (name, score) ->
                    (score as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() }?.let { name to it }
                }.toMap())
        }
        val webhookSynced = response.boolean("webhook_synced") != false
        val syncPayload = if (webhookSynced) null else buildJsonObject {
            response.forEach { (key, value) -> if (key != "webhook_synced") put(key, value) }
            put("playback_millis", metadata.getValue("playback_millis"))
            putJsonArray("speaker_intervals") {
                metadata.getValue("speaker_intervals").jsonArray.forEach { item ->
                    add(buildJsonObject {
                        put("startMs", item.jsonObject.getValue("from"))
                        put("endMs", item.jsonObject.getValue("until"))
                    })
                }
            }
        }.toString()
        AsrResult(lines, duration, null, studioHosted = true, studioEnvSound = env,
            studioWebhookSynced = webhookSynced, studioSyncPayload = syncPayload)
    } catch (_: IllegalArgumentException) { throw AsrException(AsrError.Protocol) }
    catch (_: NoSuchElementException) { throw AsrException(AsrError.Protocol) }

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
    private fun JsonObject.boolean(key: String) = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    private fun JsonObject.strings(key: String) = (get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}

// The colon cannot occur in a provider log ID accepted by TranscribePolicy.
internal const val STUDIO_HOSTED_LOG_ID = "studio:hosted"
