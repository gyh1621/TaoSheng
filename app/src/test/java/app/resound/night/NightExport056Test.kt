package app.resound.night
import android.app.Application
import app.resound.data.*
import app.resound.envsound.*
import app.resound.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE,application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NightExport056Test {
    @Test fun explicitExportUsesActualExpiryRechecksAfterEncodingAndCleansOnlyCache()=runBlocking(Dispatchers.IO) {
        val c=RuntimeEnvironment.getApplication();c.deleteDatabase("export056.db")
        val db=CaptureDatabase.open(c,"export056.db");val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val files=NightFiles(c);var now=1000L;var encodes=0
        val store=NightStore(db,scope,NightSettings(),{},{},{now},{ZoneHistory(emptyList(),"UTC")},files::exists,{FileRef(files.cleanupPath(it))},{error("unused")},{},{},MutableStateFlow(emptyList()))
        val model=LocalEnvSoundRepository(db,c.getSharedPreferences("export056",0),scope,MutableStateFlow(EnvModelState.NotDownloaded),{error("no download")},{error("no inference")},{},now={now},zoneProvider=ZoneProvider {"UTC"},io=Dispatchers.IO)
        val night=AndroidNight(c,store,files,model)
        val id=store.create(NightSession(0,0,0,2000),LocalDate(2026,11,22))
        val path="$id/100-test.ogg";val original=files.resolve(path).apply {parentFile!!.mkdirs();writeText("synthetic")}
        val row=NightClipRow(nightId=id,start=100,end=200,path=path,scores=byteArrayOf(),features="{}",confirmed=false,expiresAt=2000,candidate=false)
        val clip=db.night().insertClip(row)
        var expireDuringEncode=false
        val share=NightShare(c,night,{ShortArray(16000)},{_,target->encodes++;target.writeText("synthetic encoder fixture");if(expireDuringEncode)now=2000;target})
        try {
            assertEquals(0,encodes)
            val exported=share.exportClip(clip,"../合成")!!;assertTrue(File(exported.id).isFile)
            assertEquals(2000L,db.night().clip(clip)!!.expiresAt)
            now=2000;assertNull(share.exportClip(clip,"expired"));assertEquals(1,encodes)
            val event=store.addEvent(id,NightEvent(0,NightEventKind.Snore,100,200,clipId=clip))
            now=1000;store.feedback(event,Feedback.No)
            assertEquals(now+7L*86_400_000,db.night().clip(clip)!!.expiresAt)
            now=2000;assertNotNull(store.audio(clip))
            store.feedback(event,Feedback.Yes)
            assertTrue(db.night().clip(clip)!!.confirmed)
            assertNotNull(share.exportClip(clip,"confirmed")) // old expiresAt does not shorten confirmed retention
            db.night().updateClip(row.copy(id=clip));now=1000;expireDuringEncode=true
            assertNull(share.exportClip(clip,"race"))
            val cache=File(c.cacheDir,"night-share")
            assertFalse(cache.walk().any {it.name=="race.m4a"})
            val saved=File(c.filesDir,"saved056.m4a").apply {writeText("saved copy");setLastModified(1)}
            File(exported.id).setLastModified(1);share.cleanup()
            assertFalse(File(exported.id).exists());assertTrue(original.exists());assertTrue(saved.exists())
            now=1000;original.delete();assertNull(share.exportClip(clip,"missing"));assertNull(share.exportClip(Long.MAX_VALUE,"missing"))
            saved.delete();Unit
        } finally {scope.cancel();db.close();c.deleteDatabase("export056.db");File(c.cacheDir,"night-share").deleteRecursively();original.delete()}
    }
}
