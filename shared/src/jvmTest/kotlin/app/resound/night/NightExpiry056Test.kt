package app.resound.night
import org.junit.Assert.*
import org.junit.Test
class NightExpiry056Test {
    @Test fun expiryMatchesActualRetentionIncludingFeedback() {
        val clip=NightClipRow(1,1,100,200,"synthetic.pcm",byteArrayOf(),"{}",false,1000,true)
        assertFalse(clip.audioExpired(999));assertTrue(clip.audioExpired(1000))
        assertFalse(clip.copy(expiresAt=2000).audioExpired(1000))
        val confirmed=clip.copy(confirmed=true)
        assertFalse(confirmed.audioExpired(1000))
        assertFalse(confirmed.audioExpired(100+7_776_000_000L-1))
        assertTrue(confirmed.audioExpired(100+7_776_000_000L))
    }
}
