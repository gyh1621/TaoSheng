package app.resound.redo

import android.app.Application
import android.content.Context
import app.resound.data.*
import app.resound.transcribe.*
import app.resound.privacy.RoomPrivacyActions
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import java.io.File

/** 从已经交付的真实 fork Room44 schema 建库，不用本版新建表冒充覆盖升级。 */
internal class Redo055Fixture(val context: Context) {
    val name="redo055.db"
    var now=System.currentTimeMillis()
    var scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    lateinit var db: CaptureDatabase
    val chooser=object:AsrChooser {
        override val options=MutableStateFlow(listOf(AsrOption(TranscribeSource.Custom,"合成",true)))
        override val lastChoice=MutableStateFlow(TranscribeSource.Custom)
        override fun remember(source:TranscribeSource) {}
        override fun estimate(durationMs:Long,source:TranscribeSource)=AsrEstimate(1,0.0)
    }
    suspend fun setup() {
        context.deleteDatabase(name)
        listOf("redo-jobs","redo-ui").forEach { context.getSharedPreferences(it,0).edit().clear().commit() }
        val file=context.getDatabasePath(name);file.parentFile!!.mkdirs()
        val schema=JSONObject(File("../shared/schemas/app.resound.data.CaptureDatabase/44.json").readText()).getJSONObject("database")
        assertEquals(44,schema.getInt("version"));assertEquals(44,CaptureDatabase.VERSION)
        AndroidSQLiteDriver().open(file.path).use { c ->
            val entities=schema.getJSONArray("entities")
            repeat(entities.length()) { n ->
                val e=entities.getJSONObject(n);fun sql(s:String)=s.replace("\u0024{TABLE_NAME}",e.getString("tableName"))
                c.execSQL(sql(e.getString("createSql")))
                e.optJSONArray("indices")?.let { a -> repeat(a.length()) { c.execSQL(sql(a.getJSONObject(it).getString("createSql"))) } }
            }
            val queries=schema.getJSONArray("setupQueries");repeat(queries.length()) { c.execSQL(queries.getString(it)) };c.userVersion=44
        }
        db=CaptureDatabase.open(context,name)
        for(id in listOf(1L,2L)) {
            val at=id*100_000
            db.memory().insertEvent(MemoryEventEntity(id,at,at+2000,"工作","历史$id","摘要$id","[]","[]",null,null,null,false,createdAt=1,model="fake"))
            val audio=File(context.cacheDir,"redo055-$id.ogg").apply { writeText("synthetic") }
            db.capture().segment(SegmentEntity(id,at,at+2000,"mic",audio.path,9,"Done",completedAt=1))
            db.transcribe().insertLines(listOf(TranscriptLineEntity(id,id,at,at+2000,"人工 A$id","0",eventId=id,digestedAt=1,textEditedAt=77)))
            db.wholeTranscripts().enqueue(id,1);val row=db.wholeTranscripts().get(id)!!
            db.wholeTranscripts().claim(id,row.generation);db.wholeTranscripts().complete(id,row.generation,"整体 B$id",2)
        }
        db.memory().insertItems(listOf(MemoryItemEntity(id=7,eventId=1,category="待办",kind=null,content="引用",dueText=null,dueAt=null,who=null,lineId=1,status="Done",createdAt=1)))
    }
    fun engine()=RedoEngine(db,chooser,recognize={_,_->AsrResult(listOf(AsrSentence(0,2000,"新机器 A",null)),2000,null)},
        digest={lines,_->RedoDigest(listOf(db.memory().event(1)!!.copy(id=0,title="新事件") to lines.map { it.id })) { emptyList() }},
        afterTranscribe={segment,_->db.wholeTranscripts().enqueue(segment.id,now,replace=true);val r=db.wholeTranscripts().get(segment.id)!!;db.wholeTranscripts().claim(segment.id,r.generation);db.wholeTranscripts().complete(segment.id,r.generation,"新机器 B",now)},now={now})
    fun service()=AndroidRedoService(context,db,chooser,engine(),scope,{listOf(LlmOption("fake","合成",true))},{"fake"},enqueue={},now={now})
    suspend fun restart() { scope.coroutineContext.job.cancelAndJoin();db.close();db=CaptureDatabase.open(context,name);scope=CoroutineScope(SupervisorJob()+Dispatchers.IO) }
    suspend fun close() { scope.coroutineContext.job.cancelAndJoin();db.close();context.deleteDatabase(name);listOf(1,2).forEach { File(context.cacheDir,"redo055-$it.ogg").delete() } }
    suspend fun assertOriginal(generation:Long) {
        val line=db.transcribe().exportSegments(listOf(1)).single().lines.single()
        assertEquals("人工 A1",line.text);assertEquals(77L,line.textEditedAt);assertEquals(1L,line.eventId)
        assertEquals("整体 B1",db.wholeTranscripts().get(1)!!.text);assertEquals(generation,db.wholeTranscripts().get(1)!!.generation)
        assertEquals(1L,db.redoData().items(listOf(1)).single().lineId)
        assertEquals("人工 A2",db.transcribe().exportSegments(listOf(2)).single().lines.single().text)
        assertEquals("整体 B2",db.wholeTranscripts().get(2)!!.text)
        db.testSql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE,application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RedoRecovery055Test {
    private suspend fun fixture(block:suspend (Redo055Fixture)->Unit) {
        val f=Redo055Fixture(RuntimeEnvironment.getApplication());f.setup();try { block(f) } finally { f.close() }
    }
    @Test fun appliedDismissedRestartRestoresDualManualHistoryAndReferences()=runBlocking { fixture { f ->
        val generation=f.db.wholeTranscripts().get(1)!!.generation
        val s=f.service();val id=s.start(RedoRequest(segmentIds=listOf(1),scope=RedoScope.TranscribeOnly));s.drain{};s.dismiss(id)
        f.restart();val restored=f.service();val u=withTimeout(5000) { restored.undoable.first { it!=null } }!!
        assertEquals(id,u.jobId);assertEquals(RedoScope.TranscribeOnly,u.scope)
        assertTrue(restored.undo(id).isSuccess);f.assertOriginal(generation)
    } }
    @Test fun lateAOrBOrBackgroundReferencesBlockUndoWithoutLosingNewData()=runBlocking {
        for(change in 0..3) fixture { f ->
            if(change==3) f.db.writeSql("DELETE FROM whole_transcripts WHERE segmentId=1")
            val e=f.engine();e.run("one",if(change==3) RedoRequest(eventIds=listOf(1),scope=RedoScope.DigestOnly) else RedoRequest(segmentIds=listOf(1),scope=RedoScope.TranscribeOnly),{},{_,_->})
            when(change) {
                0->f.db.transcribe().insertLines(listOf(TranscriptLineEntity(50,1,100_000,102_000,"后置 A",null)))
                1->f.db.wholeTranscripts().enqueue(1,f.now,replace=true)
                3->{ f.db.writeSql("DELETE FROM whole_transcripts WHERE segmentId=1");f.db.wholeTranscripts().enqueue(1,f.now,replace=true) }
                else->f.db.memory().insertItems(listOf(MemoryItemEntity(id=99,eventId=1,category="待办",kind=null,content="后台新增",dueText=null,dueAt=null,who=null,lineId=f.db.transcribe().exportSegments(listOf(1)).single().lines.first().id,status="Pending",createdAt=2)))
            }
            assertTrue(e.undo("one").isFailure)
            if(change!=3) assertEquals("新机器 B",f.db.wholeTranscripts().get(1)!!.text)
        }
    }
    @Test fun onlyLatestAppliedWithinCreationDeadlineAndPrivacyDeletionCanNeverReturn()=runBlocking { fixture { f ->
        val e=f.engine();val request=RedoRequest(segmentIds=listOf(1),scope=RedoScope.TranscribeOnly)
        e.run("one",request,{},{_,_->});val created=f.db.redoData().snapshotMeta()!!.at
        e.run("two",request,{},{_,_->});assertTrue(e.undo("one").isFailure)
        val row=f.db.redoData().fullSnapshot()!!;f.db.redoData().snapshot(row.copy(applied=false));assertTrue(e.undo("two").isFailure)
        f.db.redoData().snapshot(row);f.now=created+86_400_000;assertTrue(e.undo("two").isFailure)
        f.now=created+1000
        RoomPrivacyActions(f.db,now={f.now}).deleteRange(100_000,102_000)
        assertTrue(e.undo("two").isFailure);assertNull(f.db.redoData().snapshotMeta());assertNull(f.db.wholeTranscripts().get(1));assertNull(f.db.transcribe().findSegment(1))
    } }
}
