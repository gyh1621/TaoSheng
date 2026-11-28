package app.resound.transcribe

import android.content.Context
import androidx.work.*
import app.resound.ResoundApp
import app.resound.data.SegmentEntity
import app.resound.data.TranscriptLineEntity
import app.resound.digest.writeTodoWav
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class StudioSyncQueue(
    directory: File,
    private val baseUrl: () -> String?,
    private val credential: () -> String,
    private val onQueued: () -> Unit = {},
) {
    private val file = File(directory, "studio-sync-queue.json")
    private val clips = File(directory, "studio-sync-clips")
    private val storage = Mutex()
    private val delivery = Mutex()
    private val jsonType = "application/json".toMediaType()
    private val wavType = "audio/wav".toMediaType()

    fun configured() = baseUrl() != null

    suspend fun correct(segment: SegmentEntity, line: TranscriptLineEntity, orderedLines: List<TranscriptLineEntity>,
        action: String, personId: Long? = null, personName: String? = null, clip: ShortArray? = null) {
        val tag = line.speakerTag
        val group = if (tag == null) listOf(line) else orderedLines.filter { it.speakerTag == tag }
        val payload = buildJsonObject {
            put("action", action)
            put("segment_id", segment.id)
            if (tag != null && group.size > 1) put("speaker_tag", tag) else {
                put("line_start_ms", (line.startAt - segment.startAt).coerceAtLeast(0))
                put("line_end_ms", (line.endAt - segment.startAt).coerceAtLeast(0))
            }
            personId?.let { put("person_id", it); put("is_me", it == 0L) }
            personName?.let { put("person_name", it) }
        }
        enqueue("correct", payload, clip, group.map { it.startAt to it.endAt })
    }

    suspend fun enroll(personId: Long, name: String, speech: ShortArray) = enqueue("enroll", buildJsonObject {
        put("person_id", personId)
        put("person_name", name)
    }, speech)

    suspend fun person(action: String, personId: Long, name: String? = null) = enqueue("person", buildJsonObject {
        put("action", action)
        put("person_id", personId)
        name?.let { put("name", it) }
    })

    suspend fun segmentSync(segment: SegmentEntity, payloadJson: String?) = withContext(Dispatchers.IO + NonCancellable) {
        storage.withLock {
            val previous = read()
            val kept = previous.filterNot { it.kind == "segment_sync" && it.body["segment_id"]?.jsonPrimitive?.longOrNull == segment.id }
            if (payloadJson == null) {
                if (kept.size != previous.size) save(kept)
                return@withLock
            }
            val base = baseUrl() ?: throw AsrException(AsrError.Server)
            val body = Json.parseToJsonElement(payloadJson).jsonObject
            save(kept + Entry(newId(), base, "segment_sync", body, false, listOf(segment.startAt to segment.endAt)))
        }
        if (payloadJson != null) onQueued()
    }

    suspend fun privacyDelete(ids: List<Long>, ranges: List<Pair<Long, Long>>, all: Boolean) {
        if (ids.isEmpty() && ranges.isEmpty() && !all) return
        val payload = buildJsonObject {
            putJsonArray("segment_ids") { ids.forEach { add(it) } }
            put("time_ranges", jsonRanges(ranges))
            put("delete_all", all)
        }
        withContext(Dispatchers.IO + NonCancellable) {
            val queued = storage.withLock {
                val previous = read()
                val targets = listOfNotNull(baseUrl()).toMutableSet()
                val removed = mutableListOf<Entry>()
                val kept = previous.mapNotNull { item ->
                    if (item.kind != "correct" && item.kind != "segment_sync") return@mapNotNull item
                    val deleteWhole = all || item.body["segment_id"]?.jsonPrimitive?.longOrNull in ids
                    if (!deleteWhole && item.ranges.none { overlaps(it, ranges) }) return@mapNotNull item
                    targets += item.base
                    val remaining = if (!deleteWhole && item.kind == "segment_sync") pruneLines(item, ranges) else null
                    if (remaining == null) removed += item
                    remaining
                }
                if (targets.isEmpty()) return@withLock false
                save(kept + targets.map { Entry(newId(), it, "privacy_delete", payload, false, emptyList()) })
                removed.filter { it.hasClip }.forEach { clipFile(it).delete() }
                true
            }
            if (queued) onQueued()
        }
    }

    private fun overlaps(range: Pair<Long, Long>, ranges: List<Pair<Long, Long>>): Boolean {
        val (start, end) = range
        return ranges.any { (from, until) -> start < until && maxOf(start + 1, end) > from }
    }

    private fun pruneLines(item: Entry, ranges: List<Pair<Long, Long>>): Entry? {
        val original = (item.body["lines"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val segmentStart = item.body.millis("start_ms") ?: item.ranges.first().first
        val kept = original.mapNotNull { line ->
            val range = lineRange(line, segmentStart) ?: return@mapNotNull null
            if (overlaps(range, ranges)) null else line to range
        }
        if (kept.isEmpty()) return null
        if (kept.size == original.size) return item
        val body = JsonObject((item.body - setOf("full_text", "has_me")) + ("lines" to JsonArray(kept.map { it.first })))
        return item.copy(body = body, ranges = kept.map { it.second })
    }

    private fun lineRange(line: JsonObject, segmentStart: Long): Pair<Long, Long>? {
        val start = line.millis("start_ms")
        val end = line.millis("end_ms")
        val relStart = line.millis("rel_start_ms")
        val relEnd = line.millis("rel_end_ms")
        val relative = if (start != null) start == relStart || start < segmentStart
            else end != null && (end == relEnd || end < segmentStart)
        val offset = if (relative) segmentStart else 0L
        val absoluteStart = line.millis("abs_start_ms") ?: start?.plus(offset) ?: relStart?.plus(segmentStart) ?: return null
        val absoluteEnd = line.millis("abs_end_ms") ?: end?.plus(offset) ?: relEnd?.plus(segmentStart) ?: return null
        return absoluteStart to absoluteEnd
    }

    private fun JsonObject.millis(key: String) = (get(key) as? JsonPrimitive)?.longOrNull

    private suspend fun enqueue(kind: String, body: JsonObject, clip: ShortArray? = null,
        ranges: List<Pair<Long, Long>> = emptyList()) = withContext(Dispatchers.IO + NonCancellable) {
        val base = baseUrl() ?: return@withContext
        storage.withLock {
            val previous = read()
            val entry = Entry(newId(), base, kind, body, clip != null, ranges)
            val wav = clip?.let {
                check(clips.isDirectory || clips.mkdirs())
                clipFile(entry).also { path -> writeTodoWav(path, it) }
            }
            try { save(previous + entry) } catch (e: Exception) { wav?.delete(); throw e }
        }
        onQueued()
    }

    /** true 表示还有待传。已开始的纠偏和段补传先于隐私删除结束。 */
    suspend fun flush(): Boolean = withContext(Dispatchers.IO) {
        if (!delivery.tryLock()) return@withContext true
        try {
            storage.withLock {
                val wanted = read().filter { it.hasClip }.mapTo(mutableSetOf()) { clipFile(it).name }
                clips.listFiles()?.filter { it.extension == "wav" && it.name !in wanted }?.forEach { it.delete() }
            }
            var pending = false
            while (true) {
                val activeBase = baseUrl()
                val entry = storage.withLock {
                    val rows = read()
                    val matching = rows.filter { it.base == activeBase }
                    pending = rows.isNotEmpty()
                    matching.firstOrNull { it.kind == "privacy_delete" } ?: matching.firstOrNull()
                } ?: break
                if (!send(entry)) break
                storage.withLock {
                    // A privacy delete may prune this entry while its previous body is in flight.
                    save(read() - entry)
                    if (entry.hasClip) clipFile(entry).delete()
                }
            }
            pending
        } finally { delivery.unlock() }
    }

    private suspend fun send(entry: Entry): Boolean = try {
        val path = when (entry.kind) {
            "privacy_delete" -> "/v1/studio/privacy-delete"
            "segment_sync" -> "/v1/studio/sync-segment"
            else -> "/v1/voiceprints/" + entry.kind
        }
        val body = if (entry.hasClip || entry.kind == "enroll") {
            MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                entry.body.forEach { (name, value) -> addFormDataPart(name, value.jsonPrimitive.content) }
                if (entry.hasClip) addFormDataPart("file", "voice.wav", clipFile(entry).asRequestBody(wavType))
            }.build()
        } else entry.body.toString().toRequestBody(jsonType)
        StudioClient.syncHttp.newCall(StudioClient.request(entry.base, path, credential(), body)).awaitResponse().use { response ->
            val permanent = response.code in setOf(400, 404, 405, 410, 413, 415, 422)
            when {
                entry.kind == "enroll" -> response.isSuccessful || permanent
                response.code == 200 -> {
                    val receipt = response.body?.string()?.let { Json.parseToJsonElement(it) as? JsonObject }
                    receipt != null && receipt["ok"] == JsonPrimitive(true) && receipt["webhook_synced"] == JsonPrimitive(true)
                }
                else -> entry.kind != "privacy_delete" && permanent
            }
        }
    } catch (_: IOException) { false }
    catch (_: IllegalArgumentException) { false }
    catch (_: IllegalStateException) { false }

    private data class Entry(val id: String, val base: String, val kind: String, val body: JsonObject,
        val hasClip: Boolean, val ranges: List<Pair<Long, Long>>)

    private fun newId() = UUID.randomUUID().toString()

    private fun clipFile(entry: Entry) = File(clips, entry.id + ".wav")

    private fun read(): List<Entry> {
        if (!file.exists()) return emptyList()
        return Json.parseToJsonElement(file.readText()).jsonArray.map { item ->
            val row = item.jsonObject
            Entry(row.getValue("id").jsonPrimitive.content, row.getValue("base").jsonPrimitive.content,
                row.getValue("kind").jsonPrimitive.content, row.getValue("body").jsonObject, row.getValue("clip").jsonPrimitive.boolean,
                row.getValue("ranges").jsonArray.map { range ->
                    range.jsonObject.let { it.getValue("from_ms").jsonPrimitive.long to it.getValue("until_ms").jsonPrimitive.long }
                })
        }
    }

    private fun save(rows: List<Entry>) {
        val bytes = JsonArray(rows.map { row -> buildJsonObject {
            put("id", row.id)
            put("base", row.base)
            put("kind", row.kind)
            put("body", row.body)
            put("clip", row.hasClip)
            put("ranges", jsonRanges(row.ranges))
        } }).toString().encodeToByteArray()
        val temp = File(file.parentFile, file.name + ".new")
        try {
            temp.outputStream().use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    private fun jsonRanges(ranges: List<Pair<Long, Long>>) = JsonArray(ranges.map { (from, until) ->
        buildJsonObject { put("from_ms", from); put("until_ms", until) }
    })
}

class StudioSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if ((applicationContext as ResoundApp).studioSync.flush()) Result.retry() else Result.success()
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { Result.retry() }

    companion object {
        private fun schedule(context: Context, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<StudioSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("studio-sync", policy, request)
        }

        internal fun flushAsync(context: Context, scope: CoroutineScope, queue: StudioSyncQueue, newWork: Boolean) {
            if (newWork) schedule(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
            scope.launch(Dispatchers.IO) {
                val pending = try { queue.flush() } catch (e: CancellationException) { throw e } catch (_: Exception) { true }
                if (pending && !newWork) schedule(context, ExistingWorkPolicy.KEEP)
            }
        }
    }
}
