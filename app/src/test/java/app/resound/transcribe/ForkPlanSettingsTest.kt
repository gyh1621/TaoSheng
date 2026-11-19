package app.resound.transcribe
import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class ForkPlanSettingsTest {
    @Test fun planNeverReadsMeteredKeyAndChangesHistoricalRetryIdentity() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("transcribe", 0)
        prefs.edit().clear().putString("source", "Local").commit()
        val settings = AndroidTranscribeSettings(context)
        val before = settings.identity()
        settings.setDoubaoPlan(true)
        assertNotEquals(before, settings.identity())
        prefs.edit().putString("encryptedKey", "not-a-key").commit()
        assertEquals("", settings.readKey(TranscribeSource.Doubao))
        assertEquals("", settings.readKey(TranscribeSource.Custom))
        assertTrue(app.resound.backup.PreferenceBackupPolicy.secret("transcribe", "planEncryptedKey"))
    }
}
