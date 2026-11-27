package ru.epbloger.trachu.security

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.epbloger.trachu.core.Ledger
import ru.epbloger.trachu.data.Backup
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import androidx.test.platform.app.InstrumentationRegistry

@RunWith(AndroidJUnit4::class)
class DeviceSecurityTest {
    @Test fun keystorePinAndTampering() {
        val first=PinVault.create("1942")
        val second=PinVault.create("1942")
        assertNotEquals(first,second)
        assertTrue(PinVault.verify("1942",first))
        assertFalse(PinVault.verify("1943",first))
        val bytes=Base64.decode(first,Base64.NO_WRAP)
        bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
        assertTrue(runCatching{PinVault.verify("1942",Base64.encodeToString(bytes,Base64.NO_WRAP))}.isFailure)
    }
    @Test fun encryptedBackupOnAndroidProvider() {
        val ledger=Ledger.initial()
        val out=ByteArrayOutputStream()
        Backup.write(ledger,"test-password-2026".toCharArray(),out)
        assertEquals(ledger,Backup.read("test-password-2026".toCharArray(),out.toByteArray().inputStream()))
        assertTrue(runCatching{Backup.read("wrong-password".toCharArray(),out.toByteArray().inputStream())}.isFailure)
    }
    @Test fun persistedPinRateLimitConsentAndBudgetThreshold()=runBlocking {
        // Instrumented runs use a fresh app sandbox; restore security defaults afterwards.
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=Preferences(context)
        val previous=context.settingsStore.data.first()
        prefs.setCredential("test-credential")
        try {
            repeat(5){prefs.pinResult(false)}
            assertEquals(5,prefs.current().failedAttempts)
            assertTrue(prefs.current().lockedUntil>System.currentTimeMillis())
            repeat(2){prefs.pinResult(false)}
            assertEquals(5,prefs.current().failedAttempts)
            prefs.pinResult(true)
            assertEquals(0,prefs.current().failedAttempts)
            assertEquals(0L,prefs.current().lockedUntil)
            prefs.adsConsent(true);assertEquals(true,prefs.current().adsConsent)
            prefs.adsConsent(false);assertEquals(false,prefs.current().adsConsent)
            val id=java.util.UUID.randomUUID().toString()
            prefs.notificationsEnabled(false)
            assertFalse(prefs.markNotice(id,"today"))
            assertFalse(prefs.markBudgetNotice(id,"2026-11",1))
            prefs.notificationsEnabled(true)
            assertTrue(prefs.markNotice(id,"today"))
            assertTrue(prefs.markBudgetNotice(id,"2026-11",1))
            assertFalse(prefs.markBudgetNotice(id,"2026-11",0))
            assertFalse(prefs.markBudgetNotice(id,"2026-11",1))
            assertTrue(prefs.markBudgetNotice(id,"2026-11",2))
            assertFalse(prefs.markBudgetNotice(id,"2026-11",1))
            assertTrue(prefs.markBudgetNotice(id,"2026-12",1))
            prefs.lastAccount("old-account")
            prefs.widget(true)
            prefs.clearFinancialHistorySettings()
            assertEquals("",prefs.current().lastAccount)
            assertFalse(prefs.current().showWidgetBalance)
            assertEquals("test-credential",prefs.current().credential)
            assertEquals(false,prefs.current().adsConsent)
            assertTrue(prefs.markNotice(id,"today"))
            assertTrue(prefs.markBudgetNotice(id,"2026-12",1))
        } finally { context.settingsStore.updateData{previous} }
    }
}
