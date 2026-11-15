package app.resound.data

import android.app.Application
import androidx.room.Room
import androidx.sqlite.*
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.resound.backup.BackupArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
class WholeTranscriptStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun memory() = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
        .setDriver(AndroidSQLiteDriver()).build()
    private suspend fun add(db: CaptureDatabase, id: Long = 1) {
        db.capture().segment(SegmentEntity(id, 1000, 2000, "mic", "synthetic-$id.ogg", 8, "Done"))
    }
    @Test fun migration34PreservesOriginalRowsAndCreatesUsableWholeStore() = runBlocking(Dispatchers.IO) {
        val folder = File(context.cacheDir, "whole-migration").apply { deleteRecursively() }
        BackupArchive.verify(File("src/test/resources/driver175/synthetic-backup.zip"), folder, CaptureDatabase.VERSION)
        val file = File(folder, BackupArchive.DATABASE)
        var count = 0L
        AndroidSQLiteDriver().open(file.path).use { c ->
            assertEquals(33, c.userVersion)
            CaptureDatabase.MIGRATION_33_34.migrate(c)
            c.userVersion = 34
            c.prepare("SELECT COUNT(*) FROM segments").use { it.step(); count = it.getLong(0) }
        }
        val db = CaptureDatabase.open(context, file.path)
        try {
            assertEquals(count, db.readSql("SELECT COUNT(*) FROM segments") { it.step(); it.getLong(0) })
            assertEquals(CaptureDatabase.VERSION.toLong(), db.readSql("PRAGMA user_version") { it.step(); it.getLong(0) })
            assertTrue(db.wholeTranscripts().pending().isEmpty())
            val id = db.transcribe().allAudio().first().id
            db.wholeTranscripts().enqueue(id, 1)
            assertEquals("Pending", db.wholeTranscripts().get(id)!!.status)
        } finally { db.close(); folder.deleteRecursively() }
    }
    @Test fun forgetAndDeleteRejectLateNetworkCompletion() = runBlocking(Dispatchers.IO) {
        val db = memory()
        try {
            add(db)
            val dao = db.wholeTranscripts()
            dao.enqueue(1, 1); val old = dao.get(1)!!
            assertEquals(1, dao.claim(1, old.generation))
            dao.invalidate(1)
            assertEquals(0, dao.complete(1, old.generation, "不可复活", 2))
            assertEquals("", dao.get(1)!!.text)
            dao.enqueue(1, 3, replace = true); val next = dao.get(1)!!
            dao.claim(1, next.generation)
            db.transcribe().deleteSegmentRow(1)
            assertNull(dao.get(1))
            assertEquals(0, dao.complete(1, next.generation, "也不可复活", 4))
        } finally { db.close() }
    }
    @Test fun retryKeepsPreviousSuccessAndGenerationRejectsStaleResult() = runBlocking(Dispatchers.IO) {
        val db = memory()
        try {
            add(db)
            val dao = db.wholeTranscripts()
            dao.enqueue(1, 1); val first = dao.get(1)!!
            dao.claim(1, first.generation); dao.complete(1, first.generation, "旧成功", 2)
            dao.enqueue(1, 3, replace = true); val newer = dao.get(1)!!
            assertTrue(newer.generation > first.generation)
            assertEquals("旧成功", newer.text)
            dao.claim(1, newer.generation); dao.fail(1, newer.generation, "网络失败", 4)
            assertEquals("旧成功", dao.get(1)!!.text)
            assertEquals(listOf(1L), dao.pendingIds())
            assertEquals(0, dao.complete(1, first.generation, "迟到旧请求", 5))
            assertEquals(1, dao.useSegmentedOnly(1))
            assertEquals("Skipped", dao.get(1)!!.status)
            assertEquals("旧成功", dao.get(1)!!.text)
            assertTrue(dao.pendingIds().isEmpty())
            dao.enqueue(1, 6, replace = true)
            assertEquals("Pending", dao.get(1)!!.status)
        } finally { db.close() }
    }
}
