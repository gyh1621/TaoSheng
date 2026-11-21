package app.resound.transcribe
import android.app.Application
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.resound.data.*
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
@Config(sdk=[29],manifest=Config.NONE,application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CustomDualToken054Test {
    @Test fun independentRequestsPreserveTokensWithoutCombiningOriginals() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("transcribe",0).edit().clear().commit()
        val original=File(context.cacheDir,"synthetic054.ogg").apply { writeBytes(byteArrayOf(1)) }
        val pcm=File(context.cacheDir,"synthetic054.pcm").apply { writeBytes(ByteArray(64000){if(it%2==0)0x10 else 0x27}) }
        val seen=mutableListOf<File>()
        val remote=object:AsrProvider { override suspend fun recognize(audio:AsrAudio,key:String,beforeAudio:suspend()->Unit):AsrResult {
            beforeAudio();seen+=audio.file;return AsrResult(listOf(AsrSentence(0,audio.durationMillis,"合成原话",null)),audio.durationMillis,null,7,3)
        } }
        val settings=AndroidTranscribeSettings(context)
        val a=CustomSpeakerAsrProvider(context,settings,remote).recognize(AsrAudio(original,"ogg",2000,separateSpeakers=false,pcmFile=pcm),""){}
        assertTrue(seen.isNotEmpty());assertTrue(seen.all { it!=original });assertEquals(seen.size*7L,a.inputTokens);assertEquals(seen.size*3L,a.outputTokens)
        seen.clear()
        val db=Room.inMemoryDatabaseBuilder(context,CaptureDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
        try {
            db.capture().segment(SegmentEntity(1,1000,3000,"mic",original.path,1,"Done"))
            db.wholeTranscripts().enqueue(1,1)
            WholeTranscribeRunner(db.wholeTranscripts(),db.transcribe(),remote,{""},{true},{"synthetic"},now={4000}).runPending()
            assertEquals(listOf(original),seen);assertEquals("合成原话",db.wholeTranscripts().get(1)!!.text)
            db.testSql.query("SELECT inputTokens,outputTokens FROM asr_usage").use { assertTrue(it.moveToFirst());assertEquals(7L,it.getLong(0));assertEquals(3L,it.getLong(1)) }
        } finally { db.close();original.delete();pcm.delete() }
    }
}
