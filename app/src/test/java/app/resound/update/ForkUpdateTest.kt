package app.resound.update

import java.io.File
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ForkUpdateTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun customBuildDoesNotRequestOrOfferOfficialUpdates() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val platform = FakePlatform()
            val store = MemoryUpdateStore().apply { save("manifest" to fixtureManifest().json()) }
            val engine = UpdateEngine(83, "0.4.0-custom-asr.1", fixture("old.bin"),
                File(temporary.root, "update"), scope, store, remote(server), platform, {})
            try {
                engine.checkNow(startup = true)
                assertEquals(UpdateState.Idle, engine.state.value)
                assertNull(store.string("manifest"))
                engine.checkNow(manual = true)
                assertTrue((engine.state.value as UpdateState.Failed).message.contains("自编版本"))
                assertEquals(0, server.requestCount)
                assertEquals(0, platform.notifications)
                engine.download(); engine.install()
                // Join the scheduled jobs without a timing-dependent sleep.
                scope.coroutineContext[Job]!!.children.toList().joinAll()
                assertEquals(0, server.requestCount)
                assertEquals(0, platform.installs)
            } finally { scope.cancel() }
        }
    }
}
