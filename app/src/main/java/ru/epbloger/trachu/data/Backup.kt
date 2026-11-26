package ru.epbloger.trachu.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import ru.epbloger.trachu.core.Ledger
import ru.epbloger.trachu.core.validated
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable private data class BackupEnvelope(val version: Int = 1, val ledger: Ledger)

object Backup {
    private val magic = "TRACHU01".toByteArray(Charsets.US_ASCII)
    private const val maxBytes = 100 * 1024 * 1024
    private val random = SecureRandom()
    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, 310_000, 256)
        try {
            val encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return try { SecretKeySpec(encoded, "AES") } finally { encoded.fill(0) }
        } finally { spec.clearPassword() }
    }
    fun write(ledger: Ledger, password: CharArray, output: OutputStream) {
        try {
            require(password.size >= 8) { "Пароль резервной копии: минимум 8 символов" }
            val salt = ByteArray(16).also(random::nextBytes)
            val iv = ByteArray(12).also(random::nextBytes)
            val header = magic + salt + iv
            val clear = LedgerRecords.json.encodeToString(BackupEnvelope(ledger = ledger.validated())).toByteArray(Charsets.UTF_8)
            try {
                require(clear.size <= maxBytes - header.size - 16) { "Резервная копия слишком большая" }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
                cipher.updateAAD(header)
                val encrypted = cipher.doFinal(clear)
                output.write(header)
                output.write(encrypted)
                output.flush()
            } finally { clear.fill(0) }
        } finally { password.fill('\u0000') }
    }
    fun read(password: CharArray, input: InputStream): Ledger {
        try {
            val encrypted = input.readBounded(maxBytes)
            val headerSize = magic.size + 16 + 12
            require(encrypted.size >= headerSize + 16 && encrypted.copyOfRange(0, magic.size).contentEquals(magic)) { "Неверный формат копии" }
            val salt = encrypted.copyOfRange(magic.size, magic.size + 16)
            val iv = encrypted.copyOfRange(magic.size + 16, headerSize)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
            cipher.updateAAD(encrypted, 0, headerSize)
            val clear = try { cipher.doFinal(encrypted, headerSize, encrypted.size - headerSize) }
                catch (_: javax.crypto.AEADBadTagException) { error("Неверный пароль или повреждённая копия") }
            try {
                val data = LedgerRecords.json.decodeFromString<BackupEnvelope>(clear.toString(Charsets.UTF_8))
                require(data.version == 1) { "Версия резервной копии не поддерживается" }
                return data.ledger.validated()
            } finally { clear.fill(0) }
        } finally { password.fill('\u0000') }
    }
}

fun InputStream.readBounded(maxBytes: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        require(out.size() <= maxBytes - n) { "Файл слишком большой" }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
