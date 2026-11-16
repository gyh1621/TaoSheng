package app.resound.data

import android.app.Application
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
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

/** 两种已发布的 35 库不能按相同表集合假定。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Fork35MigrationTest {
    @Test fun fork35KeepsWholeTextAndAddsUpstreamTables() = migrate(true)
    @Test fun upstream35AlsoGetsWholeStore() = migrate(false)

    private fun migrate(fork: Boolean) = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication()
        val name = "fork35-$fork.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        val schema = JSONObject(File("../shared/schemas/app.resound.data.CaptureDatabase/${if (fork) 35 else 34}.json").readText()).getJSONObject("database")
        AndroidSQLiteDriver().open(file.path).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                fun execute(sql: String) { old.prepare(sql.replace("\u0024{TABLE_NAME}", e.getString("tableName"))).use { it.step() } }
                execute(e.getString("createSql"))
                val indices = e.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) execute(indices.getJSONObject(j).getString("createSql"))
            }
            if (!fork) CaptureDatabase.MIGRATION_34_35.migrate(old)
            old.prepare("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,1000,2000,'mic','synthetic.ogg',8,'Done')").use { it.step() }
            if (fork) old.prepare("INSERT INTO whole_transcripts VALUES(1,7,'保留整体原文','Done',NULL,1,2000)").use { it.step() }
            old.userVersion = 35
        }
        val db = Room.databaseBuilder(context, CaptureDatabase::class.java, name)
            .setDriver(AndroidSQLiteDriver()).addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            assertEquals(CaptureDatabase.VERSION, db.testSql.version)
            assertEquals(1, db.transcribe().allAudio().size)
            assertEquals(0, db.transcribe().allAudio().single().playbackDigest)
            if (fork) {
                assertEquals("保留整体原文", db.wholeTranscripts().get(1)!!.text)
                assertEquals(7L, db.wholeTranscripts().get(1)!!.generation)
            } else assertTrue(db.wholeTranscripts().pending().isEmpty())
            for (table in listOf("screen_events", "screen_app_names", "screen_pull_state", "screen_coverage_gaps", "playback_rows", "marks")) {
                db.testSql.query("SELECT COUNT(*) FROM `$table`").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
