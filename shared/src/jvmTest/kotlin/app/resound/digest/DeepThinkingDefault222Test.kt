package app.resound.digest

import org.junit.Test
import org.junit.Assert.assertEquals

class DeepThinkingDefault222Test {
    private fun store(saved: MutableMap<String, Boolean>, newInstall: Boolean) = DigestPreferenceStore(
        { k, d -> saved[k] ?: d }, { _, d -> d }, { 30 }, { k, v -> saved[k] = v; true }, { _, _ -> true }, { newInstall })

    @Test fun newInstallKeepsEffectiveOffDefault() {
        val s = store(mutableMapOf(), newInstall = true)
        assertEquals(false, s.deepThinking.value)
        assertEquals(false, s.deepThinkingNotice.value)
    }

    @Test fun upgradingUserWhoHadItOffStaysOff() {
        val saved = mutableMapOf("deepThinking" to false)
        val s = store(saved, newInstall = false)
        assertEquals(false, s.deepThinking.value)
        assertEquals(false, s.deepThinkingNotice.value)
        s.dismissDeepThinkingNotice()
        assertEquals(false, store(saved, newInstall = false).deepThinkingNotice.value)
    }

    @Test fun upgradingUserWhoHadItOnSeesNoNotice() {
        val s = store(mutableMapOf("deepThinking" to true), newInstall = false)
        assertEquals(true, s.deepThinking.value)
        assertEquals(false, s.deepThinkingNotice.value)
    }

    @Test fun turningItOffAfterwardsSticks() {
        val saved = mutableMapOf<String, Boolean>()
        store(saved, newInstall = false).setDeepThinking(false)
        assertEquals(false, store(saved, newInstall = false).deepThinking.value)
    }
}
