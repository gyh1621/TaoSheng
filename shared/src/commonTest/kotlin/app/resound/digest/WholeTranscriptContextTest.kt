package app.resound.digest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WholeTranscriptContextTest {
    @Test fun failedWholeNeverSilentlyFallsBackToSplitOnly() {
        listOf("Pending", "Running", "Failed").forEach { assertTrue(WholeTranscriptContext.blocksDigest(it)) }
        assertEquals(false, WholeTranscriptContext.blocksDigest("Done"))
        assertEquals(false, WholeTranscriptContext.blocksDigest("Deleted"))
        assertEquals(false, WholeTranscriptContext.blocksDigest("Skipped"))
    }
    @Test fun noWholeLeavesOriginalUntouched() {
        assertEquals("原句", WholeTranscriptContext.append("原句", emptyList()))
    }
    @Test fun wholeIsContextNotAnotherRecording() {
        val prompt = WholeTranscriptContext.append("1:切分", listOf(WholeContext(8, listOf(1, 2), "整体原句")))
        assertTrue(prompt.startsWith("1:切分"))
        assertTrue(prompt.contains("不是新增录音"))
        assertTrue(prompt.contains("整体原句"))
        assertTrue(prompt.contains("1,2"))
        assertTrue(prompt.contains("禁止重复统计"))
    }
}
