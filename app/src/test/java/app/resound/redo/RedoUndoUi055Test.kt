package app.resound.redo

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.resound.AppGraph
import app.resound.ui.redo.RedoUndoCard
import app.resound.ui.theme.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(application=Application::class,sdk=[34])
class RedoUndoUi055Test {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun transcriptEntrySurvivesDismissHideAndRestart()=verify(true)
    @Test fun todayEntrySurvivesDismissHideAndRestart()=verify(false)
    private fun verify(transcribe:Boolean) {
        val f=Redo055Fixture(compose.activity)
        runBlocking(Dispatchers.IO) { f.setup() }
        try {
            val originalGeneration=runBlocking(Dispatchers.IO) { f.db.wholeTranscripts().get(1)!!.generation }
            var service=f.service();AppGraph.redo=service
            val request=if(transcribe)RedoRequest(segmentIds=listOf(1),scope=RedoScope.TranscribeOnly) else RedoRequest(eventIds=listOf(1),scope=RedoScope.DigestOnly)
            val id=service.start(request);runBlocking(Dispatchers.IO) { service.drain{} };service.dismiss(id)
            var revision by mutableIntStateOf(0)
            compose.setContent { ResoundTheme(ThemeMode.Light) { key(revision) { RedoUndoCard(transcribe) } } }
            compose.waitUntil(5000) { compose.onAllNodesWithText("撤销").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("收起").performClick()
            compose.onNodeWithText("撤销").assertDoesNotExist()
            runBlocking(Dispatchers.IO) { f.restart() }
            service=f.service();AppGraph.redo=service;compose.runOnIdle { revision++ }
            compose.waitUntil(5000) { compose.onAllNodesWithText("撤销").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("撤销").performClick()
            compose.onNodeWithText("确定撤销").performClick()
            compose.waitUntil(5000) { service.undoable.value==null }
            runBlocking(Dispatchers.IO) { f.assertOriginal(originalGeneration) }
            assertTrue(service.jobs.value.isEmpty())
        } finally { runBlocking(Dispatchers.IO) { f.close() } }
    }
}
