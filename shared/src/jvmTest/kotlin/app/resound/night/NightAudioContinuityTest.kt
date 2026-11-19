package app.resound.night
import kotlin.test.*
class NightAudioContinuityTest {
    @Test fun keepsContinuousWindows() { assertTrue(NightAudioContinuity.contiguous(listOf(1000L,3000L,5000L))) }
    @Test fun rejectsMissingOverlappingAndReorderedWindows() {
        for (starts in listOf(emptyList(),listOf(0L,4000L),listOf(0L,1000L),listOf(2000L,0L)))
            assertFalse(NightAudioContinuity.contiguous(starts))
    }
}
