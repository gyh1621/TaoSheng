package app.resound.transcribe

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.resound.AppGraph
import app.resound.data.*
import app.resound.ui.theme.*
import app.resound.ui.transcript.TranscriptVersions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class DualView054Test {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun switchingPreservesManualAAndIndependentWholeB() {
        val db = CaptureDatabase.open(compose.activity, "dual-view054.db")
        try {
            runBlocking(Dispatchers.IO) {
                db.capture().segment(SegmentEntity(1,1000,2000,"mic","/synthetic.ogg",8,"Done"))
                db.wholeTranscripts().enqueue(1,1)
                val row=db.wholeTranscripts().get(1)!!
                db.wholeTranscripts().claim(1,row.generation)
                db.wholeTranscripts().complete(1,row.generation,"B 独立原始整体文本",2)
            }
            AppGraph.wholeTranscripts=db.wholeTranscripts()
            val a=mutableStateOf("A 机器文字")
            compose.setContent { ResoundTheme(ThemeMode.Light) { TranscriptVersions(listOf(1)) { Text(a.value) } } }
            compose.onNodeWithText("A 机器文字").assertExists()
            compose.runOnIdle { a.value="A 人工修正" }
            compose.onNodeWithText("整体版").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("B 独立原始整体文本").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("A 人工修正").assertDoesNotExist()
            compose.onNodeWithText("切分版").performClick()
            compose.onNodeWithText("A 人工修正").assertExists()
            compose.onNodeWithText("B 独立原始整体文本").assertDoesNotExist()
        } finally { db.close(); compose.activity.deleteDatabase("dual-view054.db") }
    }
}
