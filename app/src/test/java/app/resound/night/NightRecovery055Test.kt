package app.resound.night

import android.app.Application
import android.os.BatteryManager
import app.resound.AppGraph
import app.resound.data.*
import app.resound.envsound.*
import app.resound.platform.*
import app.resound.privacy.RoomPrivacyActions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE,application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NightRecovery055Test {
    @Test fun persistedInterruptionRetainsSameWindowDeadlineAndGapAcrossDatabaseReopen()=runBlocking(Dispatchers.IO) {
        val c=RuntimeEnvironment.getApplication();val name="night055-restart.db";c.deleteDatabase(name)
        var db=CaptureDatabase.open(c,name)
        val session=NightSession(1000,1000,1000,3000,deadline=900_000).interruptedUntil(603_000)
        val json=kotlinx.serialization.json.Json
        val id=db.night().insert(NightRow(date="2026-11-21",kind="night",windowId="original",start=1000,end=603_000,state="Interrupted",payload="{}",signals=json.encodeToString(session),createdAt=1,updatedAt=2))
        db.close();db=CaptureDatabase.open(c,name)
        try {
            val row=db.night().get(id)!!;val restored=json.decodeFromString<NightSession>(row.signals)
            assertTrue(NightRules.canResume(NightState.valueOf(row.state),row.windowId,"original",restored.deadline,604_000))
            assertFalse(NightRules.canResume(NightState.valueOf(row.state),row.windowId,"changed",restored.deadline,604_000))
            assertFalse(NightRules.canResume(NightState.valueOf(row.state),row.windowId,"original",restored.deadline,900_000))
            assertTrue(restored.signals.any { it.kind=="gap" && it.start==3000L && it.end==603_000L })
            assertFalse(NightRules.readyToProcess(NightState.Interrupted,restored.deadline,604_000))
            assertTrue(NightRules.readyToProcess(NightState.Interrupted,restored.deadline,900_000))
        } finally { db.close();c.deleteDatabase(name) }
    }
    @Test fun failedModelRetriesWithoutEnvSwitchAndLocalCandidatesSurviveUntilSuccessfulProcessing()=runBlocking(Dispatchers.IO) {
        val c=RuntimeEnvironment.getApplication();c.deleteDatabase("night055.db")
        val db=CaptureDatabase.open(c,"night055.db");val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val state=MutableStateFlow<EnvModelState>(EnvModelState.NotDownloaded);var downloads=0;var reads=0;var inferences=0
        val files=NightFiles(c);val now=System.currentTimeMillis();val start=now-10_000
        AppGraph.privacy=RoomPrivacyActions(db)
        AppGraph.push=java.lang.reflect.Proxy.newProxyInstance(app.resound.push.PushChannels::class.java.classLoader,arrayOf(app.resound.push.PushChannels::class.java)) { _,_,_->error("不调用推送") } as app.resound.push.PushChannels
        val store=NightStore(db,scope,NightSettings(enabled=true),{}, {},{now},{ZoneHistory(emptyList(),"UTC")},files::exists,{FileRef(files.cleanupPath(it))},{error("没有曲线")},{AppGraph.privacy.let { (it as RoomPrivacyActions).retryFileCleanup() };Unit},{},MutableStateFlow(emptyList()))
        val prefs=c.getSharedPreferences("env055",0).also { it.edit().clear().commit() }
        val model=LocalEnvSoundRepository(db,prefs,scope,state,{downloads++;state.value=if(downloads==1)EnvModelState.Failed("合成失败") else EnvModelState.Ready},{error("不用普通环境音")},{},now={now},zoneProvider=ZoneProvider { "UTC" },io=Dispatchers.IO)
        model.nightScores={inferences++;mapOf("Snoring" to 0f,"Cough" to 0f)}
        val window=SleepWindows.fallback
        val session=NightSession(start,start,start,start+2000,deadline=now+10_000)
        val id=store.create(session,LocalDate(2026,11,21),window)
        val path="$id/$start-candidate.ogg";val candidate=files.resolve(path).apply { parentFile!!.mkdirs();writeText("synthetic") }
        val features=CandidateFeatures(listOf(CandidateWindow(start,NightFeatures(0.0,0.0,0))))
        val clip=db.night().insertClip(NightClipRow(nightId=id,start=start,end=start+2000,path=path,scores=byteArrayOf(),features=store.json.encodeToString(features),confirmed=false,expiresAt=now+86_400_000,candidate=true))
        var failRead=true
        val night=AndroidNight(c,store,files,model) { reads++;if(failRead)error("模拟本地解码失败");ShortArray(32000) }
        try {
            store.persistSession(id,session,start+2000,NightState.Interrupted)
            night.process();assertEquals(0,downloads);assertTrue(db.night().clip(clip)!!.candidate)
            store.persistSession(id,session,start+2000,NightState.Processing)
            night.process();withTimeout(5000) { while(state.value !is EnvModelState.Failed)delay(5) }
            assertFalse(model.settings.value.enabled);assertEquals(0,reads);assertTrue(candidate.exists())
            night.process();withTimeout(5000) { state.first { it==EnvModelState.Ready } }
            shadowOf(c.getSystemService(BatteryManager::class.java)).setIsCharging(true)
            assertTrue(runCatching { night.process() }.isFailure);assertTrue(db.night().clip(clip)!!.candidate);assertTrue(candidate.exists())
            failRead=false;night.process()
            assertEquals(1,inferences);assertFalse(db.night().clip(clip)!!.candidate);assertNull(db.night().clip(clip)!!.path);assertFalse(candidate.exists())
            assertFalse(model.settings.value.enabled);assertEquals(2,downloads)
        } finally { scope.coroutineContext.job.cancelAndJoin();db.close();c.deleteDatabase("night055.db");candidate.delete() }
    }
}
