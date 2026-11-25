package ru.epbloger.trachu.security

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.epbloger.trachu.core.Ledger
import ru.epbloger.trachu.data.Backup
import java.io.ByteArrayOutputStream

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
}
