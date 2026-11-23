@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package app.resound.net

import app.resound.digest.LlmPreset
import app.resound.diagnostics.FailureNotes
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Test

class RatePrivacy056Test {
    @Test fun allRequestModesRejectRawProviderDataIncludingMalformedBodies() = runTest {
        val secret = "SYNTHETIC_SECRET https://synthetic.invalid/?signature=secret prompt=private"
        for (raw in listOf("""{"error":{"message":"quota $secret"}}""", "{broken $secret")) {
            for (mode in 0..3) {
                val client = HttpClient(MockEngine(MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { respond(raw, HttpStatusCode.TooManyRequests) }
                }))
                val seen = mutableListOf<String>()
                val trace = object : NetworkTrace {
                    override fun http(status: Int) { assertEquals(429,status) }
                    override fun content(text: String) { seen += text }
                    override fun finishReason(reason: String?) { reason?.let(seen::add) }
                }
                val support = object : JsonStreamSupport {
                    override fun nonStreaming(preset: LlmPreset) = mode == 3
                    override fun rememberNonStreaming(preset: LlmPreset) {}
                }
                val preset=LlmPreset("synthetic","合成","https://synthetic.invalid/v1","model")
                val core=NetLlmClient(client,listOf(preset.baseUrl),200_000,support) { "网络失败" }
                try {
                    try {
                        when(mode) {
                            0 -> core.probe(preset,"",emptyList(),"synthetic","synthetic",false,trace)
                            1 -> core.stream(preset,"",emptyList(),"[]","[]",{seen+=it},{_,_->},false,trace)
                            else -> core.chat(preset,"",emptyList(),"synthetic","synthetic",false,trace)
                        }
                        fail("429 must fail")
                    } catch(e: NetLlmException) {
                        assertEquals(429,e.status)
                        assertFalse(e.toString().contains("SYNTHETIC_SECRET"))
                        assertFalse(e.toString().contains("signature="))
                    }
                    assertTrue(seen.isEmpty())
                    assertFalse(FailureNotes.dump().contains(secret))
                } finally {client.close()}
            }
        }
    }
}
