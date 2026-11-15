package app.resound.scheduling

import kotlin.test.*

class PipelinePendingAudioTest {
    @Test fun pendingWholeNeverBlocksOfflineLocalSplit() {
        val both = PipelinePendingAudio(split = 1000, whole = 2000)
        assertTrue(both.localWork(sourceIsLocal = true))
        assertTrue(both.ready(selectedReady = true, customReady = false))
        assertEquals(2000L, both.millis)
        val wholeOnly = PipelinePendingAudio(whole = 2000)
        assertFalse(wholeOnly.localWork(sourceIsLocal = true))
        assertTrue(wholeOnly.ready(selectedReady = false, customReady = true))
        assertFalse(wholeOnly.ready(selectedReady = false, customReady = false))
    }
}
