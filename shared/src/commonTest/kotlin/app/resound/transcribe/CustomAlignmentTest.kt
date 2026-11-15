package app.resound.transcribe

import kotlin.test.*

class CustomAlignmentTest {
    @Test fun validatesOriginalTextAndRelativeTimes() {
        val lines = CustomAlignment.parse("""{"text":"你好。","items":[{"text":"你好。","start_ms":100,"end_ms":800}]}""", "你好。", 1000)
        assertEquals(100L, lines.single().startMillis)
        assertEquals("你好。", lines.single().text)
    }
    @Test fun rejectsAlteredTextAndOutOfRangeTimes() {
        assertFails { CustomAlignment.parse("""{"items":[{"text":"错了","start_ms":0,"end_ms":800}]}""", "你好", 1000) }
        assertFails { CustomAlignment.parse("""{"items":[{"text":"你好","start_ms":0,"end_ms":1100}]}""", "你好", 1000) }
    }
    @Test fun refusesExplicitTruncation() {
        assertFailsWith<AsrException> { requireCompleteCustomTranscript("""{"text":"半句","finish_reason":"length"}""") }
        requireCompleteCustomTranscript("""{"text":"完整句子"}""")
    }
}
