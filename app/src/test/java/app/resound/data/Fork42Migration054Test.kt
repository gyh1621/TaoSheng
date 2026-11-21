package app.resound.data

import android.app.Application
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Fork42Migration054Test {
    @Test fun realFork42PreservesDualTextGenerationAndReferencesThroughRoom44() = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "synthetic-fork42.db").apply { delete() }
        val schema = JSONObject(File("../shared/schemas/app.resound.data.CaptureDatabase/42.json").readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val tables = (0 until entities.length()).map { entities.getJSONObject(it) }.associateBy { it.getString("tableName") }
        assertTrue(tables.containsKey("whole_transcripts"))
        AndroidSQLiteDriver().open(file.path).use { old ->
            for ((name, entity) in tables) {
                old.execSQL(entity.getString("createSql").replace("\u0024{TABLE_NAME}", name))
                entity.optJSONArray("indices")?.let { indices -> repeat(indices.length()) {
                    old.execSQL(indices.getJSONObject(it).getString("createSql").replace("\u0024{TABLE_NAME}", name))
                } }
            }
            fun insert(table: String, values: Map<String, Any?>) {
                val fields = tables.getValue(table).getJSONArray("fields")
                val columns = (0 until fields.length()).map { fields.getJSONObject(it) }
                val sql = "INSERT INTO `$table` (${columns.joinToString { "`${it.getString("columnName")}`" }}) VALUES (${columns.joinToString { "?" }})"
                old.prepare(sql).use { stmt ->
                    columns.forEachIndexed { i, field ->
                        val name = field.getString("columnName")
                        val value = if (values.containsKey(name)) values[name] else if (!field.optBoolean("notNull", false)) null
                            else if (field.getString("affinity") == "TEXT") "" else 0L
                        when (value) { null -> stmt.bindNull(i+1); is String -> stmt.bindText(i+1,value); is Number -> stmt.bindLong(i+1,value.toLong()); else -> error("unsupported") }
                    }
                    stmt.step()
                }
            }
            insert("segments", mapOf("id" to 1L,"startAt" to 1000L,"endAt" to 3000L,"sourceId" to "mic","status" to "Done","path" to "synthetic.ogg"))
            insert("memory_events", mapOf("id" to 2L,"startAt" to 1000L,"endAt" to 3000L,"title" to "历史事件","summary" to "历史摘要","pointsJson" to "[]","peopleJson" to "[]"))
            insert("transcript_lines", mapOf("id" to 3L,"segmentId" to 1L,"startAt" to 1000L,"endAt" to 3000L,"text" to "分人原文甲","speakerTag" to "speaker:1","speakerName" to "合成说话人","eventId" to 2L))
            insert("memory_items", mapOf("id" to 4L,"eventId" to 2L,"lineId" to 3L,"category" to "待办","content" to "引用原话的历史事项","status" to "Pending"))
            insert("whole_transcripts", mapOf("segmentId" to 1L,"generation" to 19L,"text" to "独立整体原文乙","status" to "Done","updatedAt" to 4000L))
            insert("asr_usage", mapOf("day" to "2026-11-20"))
            schema.getJSONArray("setupQueries").let { queries -> repeat(queries.length()) { old.execSQL(queries.getString(it)) } }
            old.userVersion = 42
        }
        var db = CaptureDatabase.open(context, file.path)
        try {
            assertEquals(CaptureDatabase.VERSION, db.testSql.version)
            assertEquals(19L,db.wholeTranscripts().get(1)!!.generation)
            assertEquals("独立整体原文乙",db.wholeTranscripts().get(1)!!.text)
            val line = db.transcribe().exportSegments(listOf(1)).single().lines.single()
            assertEquals("分人原文甲",line.text); assertEquals("合成说话人",line.speakerName); assertEquals(2L,line.eventId); assertNull(line.textEditedAt)
            db.testSql.query("SELECT eventId,lineId FROM memory_items WHERE id=4").use { assertTrue(it.moveToFirst()); assertEquals(2L,it.getLong(0)); assertEquals(3L,it.getLong(1)) }
            db.testSql.query("SELECT inputTokens,outputTokens FROM asr_usage").use { assertTrue(it.moveToFirst()); assertEquals(0L,it.getLong(0)); assertEquals(0L,it.getLong(1)) }
            db.transcribe().editLineText(3,"迁移后手改",5000)
            db.close(); db = CaptureDatabase.open(context,file.path)
            assertEquals("迁移后手改",db.transcribe().exportSegments(listOf(1)).single().lines.single().text)
            assertEquals("独立整体原文乙",db.wholeTranscripts().get(1)!!.text)
            db.testSql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { db.close(); file.delete() }
    }
}
