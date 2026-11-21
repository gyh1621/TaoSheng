package app.resound.redo

import androidx.room.*
import app.resound.data.*
import org.json.JSONArray
import org.json.JSONObject

internal data class RedoRange(val start: Long, val end: Long) {
    fun overlaps(start: Long, end: Long) = this.start < end && this.end > start
}
/** 分段读出快照正文，每段 20 万字（中文按 UTF-8 约 600KB），不受游标 2MB 上限影响 */
internal suspend fun RedoDao.fullSnapshot(): RedoSnapshotRow? {
    val meta = snapshotMeta() ?: return null
    val length = payloadLength() ?: 0
    val text = StringBuilder(length)
    var from = 1
    while (from <= length) { text.append(payloadPart(from, SNAPSHOT_PART) ?: ""); from += SNAPSHOT_PART }
    return meta.copy(payload = text.toString())
}
private const val SNAPSHOT_PART = 200_000
internal fun mergedRanges(ranges: List<RedoRange>): List<RedoRange> {
    val out = mutableListOf<RedoRange>()
    for (r in ranges.sortedBy { it.start }) {
        val last = out.lastOrNull()
        if (last != null && r.start <= last.end) out[out.lastIndex] = last.copy(end = maxOf(last.end, r.end)) else out += r
    }
    return out
}
internal fun overlap(a: Long, b: Long, c: Long, d: Long) = (minOf(b, d) - maxOf(a, c)).coerceAtLeast(0)
internal fun List<Long>.sqlIds() = if (isEmpty()) "NULL" else distinct().joinToString(",")

/** 只快照本任务涉及的行；JSON 保留列类型和 NULL，不复制整个数据库，也不回滚计费用量。 */
internal class RedoSnapshot(private val db: CaptureDatabase) {
    suspend fun rows(table: String, where: String): JSONArray = JSONArray().apply {
        db.readSql("SELECT * FROM $table WHERE $where ORDER BY ${if (table == "whole_transcripts") "segmentId" else "id"}") { c ->
            while (c.step()) put(JSONObject().apply {
                c.columnNames.forEachIndexed { i, name -> put(name, when (c.getColumnType(i)) {
                    androidx.sqlite.SQLITE_DATA_NULL -> JSONObject.NULL
                    androidx.sqlite.SQLITE_DATA_INTEGER -> c.getLong(i)
                    androidx.sqlite.SQLITE_DATA_FLOAT -> c.getDouble(i)
                    else -> c.getText(i)
                }) }
            })
        }
    }
    suspend fun capture(selectors: Map<String, String>) = JSONObject().apply { selectors.forEach { (t, w) -> put(t, rows(t, w)) } }
    suspend fun restore(data: JSONObject) {
        // UPDATE segments 避免 REPLACE 级联删除原话；其余表由调用者先按 ID 清理。
        listOf("segments", "memory_events", "transcript_lines", "memory_items", "skill_cards", "whole_transcripts").filter { data.has(it) }.forEach { table ->
            val rows = data.getJSONArray(table)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val keys = row.keys().asSequence().filterNot { table == "segments" && it == "sentMillis" }.toList()
                val values = keys.map { if (row.isNull(it)) null else row.get(it) }.toTypedArray()
                if (table == "segments") {
                    db.writeSql("UPDATE segments SET ${keys.joinToString { "$it = ?" }} WHERE id = ${row.getLong("id")}", values)
                } else db.writeSql("INSERT INTO $table (${keys.joinToString()}) VALUES (${keys.joinToString { "?" }})", values)
            }
        }
    }
    suspend fun delete(data: JSONObject) {
        // 子表先删，避免 FK 级联影响保留项。
        listOf("memory_items", "transcript_lines", "skill_cards", "memory_events", "whole_transcripts").forEach { table ->
            val rows = data.optJSONArray(table) ?: return@forEach
            val key = if (table == "whole_transcripts") "segmentId" else "id"
            val ids = (0 until rows.length()).map { rows.getJSONObject(it).getLong(key) }
            db.writeSql("DELETE FROM $table WHERE $key IN (${ids.sqlIds()})")
        }
    }
    suspend fun unchanged(data: JSONObject, voiceprint: Boolean = false): Boolean = data.keys().asSequence().all { table ->
        val old = data.getJSONArray(table)
        val key = if (table == "whole_transcripts") "segmentId" else "id"
        val ids = (0 until old.length()).map { old.getJSONObject(it).getLong(key) }
        fun comparable(value: JSONArray): String {
            val copy = JSONArray(value.toString())
            for (i in 0 until copy.length()) {
                val row = copy.getJSONObject(i)
                if (table == "segments") row.remove("sentMillis")
                if (voiceprint && table == "segments") row.remove("voiceprintProcessedAt")
                if (voiceprint && table == "transcript_lines") listOf("speakerName", "speakerScore", "speakerPersonId").forEach(row::remove)
            }
            return copy.toString()
        }
        fun idsOf(name: String, column: String = "id"): List<Long> = data.optJSONArray(name)?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it).getLong(column) }
        }.orEmpty()
        // 既核对旧行，也发现后台新增的子行；否则空 B 或新 A/引用可能绕过 ID 快照校验。
        val where = when (table) {
            "whole_transcripts" -> "$key IN (${(ids + idsOf("segments") + idsOf("transcript_lines", "segmentId")).sqlIds()})"
            "transcript_lines" -> "id IN (${ids.sqlIds()}) OR segmentId IN (${idsOf("segments").sqlIds()}) OR eventId IN (${idsOf("memory_events").sqlIds()})"
            "memory_items" -> "id IN (${ids.sqlIds()}) OR eventId IN (${idsOf("memory_events").sqlIds()}) OR lineId IN (${idsOf("transcript_lines").sqlIds()}) OR doneHintLineId IN (${idsOf("transcript_lines").sqlIds()})"
            else -> "$key IN (${ids.sqlIds()})"
        }
        comparable(rows(table, where)) == comparable(old)
    }
}

/** 隐私删除还要清掉含该录音的旧快照，不能把已删除原话留在可导出的 JSON 中。 */
internal suspend fun clearRedoForPrivacy(db: CaptureDatabase, segmentIds: Set<Long>, eventId: Long?) {
    val row = db.redoData().fullSnapshot() ?: return
    val affected = runCatching {
        val payload = JSONObject(row.payload)
        listOf("before", "after").any { side ->
            val data = payload.getJSONObject(side)
            listOf("segments" to "id", "transcript_lines" to "segmentId", "whole_transcripts" to "segmentId", "memory_events" to "id").any { (table, column) ->
                val a = data.optJSONArray(table) ?: JSONArray()
                (0 until a.length()).any { i -> val id = a.getJSONObject(i).getLong(column)
                    if (table == "memory_events") eventId == id else id in segmentIds }
            }
        }
    }.getOrDefault(true)
    if (affected) db.redoData().clear()
}
