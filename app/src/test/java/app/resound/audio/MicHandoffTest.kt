package app.resound.audio

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart

class MicHandoffTest {
    @Test fun phoneReleasesForCompetitorAndCanReopenAfterItEnds() {
        assertTrue(MicHandoff.shouldRelease(InputKind.Phone, true))
        assertFalse(MicHandoff.shouldRelease(InputKind.Phone, false))
    }

    @Test fun bluetoothStillYields() {
        assertTrue(MicHandoff.shouldRelease(InputKind.BluetoothCall, true))
        assertTrue(MicHandoff.shouldRelease(InputKind.BluetoothLe, true))
    }

    @Test fun usbKeepsExistingBehavior() {
        assertFalse(MicHandoff.shouldRelease(InputKind.Usb, true))
    }

    @Test fun waitsUntilCompetitorEndsThenObservesQuietWindow() = runBlocking {
        var active = true
        val delays = mutableListOf<Long>()
        MicHandoff.awaitRelease({ active }) { ms -> delays += ms; active = false }
        assertEquals(List(9) { 250L }, delays)
    }

    @Test fun competitorRestartDuringQuietWindowPreventsResume() = runBlocking {
        var checks = 0
        val delays = mutableListOf<Long>()
        MicHandoff.awaitRelease({ ++checks == 2 }) { delays += it }
        assertEquals(List(9) { 250L }, delays)
        assertEquals(18, checks)
    }

    @Test fun briefCompetitorWakesWaiterAndRemainsLatched() = runBlocking {
        val signal = MicHandoff.Signal()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { signal.awaitOrTimeout() }
        signal.observe(true)
        signal.observe(false)
        waiter.await()
        assertTrue(signal.requested)
        assertFalse(MicHandoff.Signal().requested)
    }

    @Test fun cancellationWhileYieldingNeverReportsReadyToResume() = runBlocking {
        var resumed = false
        try {
            MicHandoff.awaitRelease({ true }) { throw CancellationException("手动暂停") }
            resumed = true
        } catch (_: CancellationException) { }
        assertFalse(resumed)
    }
}
