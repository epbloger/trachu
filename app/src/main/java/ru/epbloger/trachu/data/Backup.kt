package ru.epbloger.trachu.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import ru.epbloger.trachu.core.Ledger
import ru.epbloger.trachu.core.validated
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable private data class BackupEnvelope(val version: Int = 1, val ledger: Ledger)

/** The provider may buffer GCM ciphertext until authentication, and JSON decoding
 * retains the resulting ledger. Leave headroom for both, including non-compact
 * JVM strings and the currently displayed ledger. Never force a GC here. */
internal object MemoryBudget {
    fun available(): Long = Runtime.getRuntime().let { it.maxMemory() - it.totalMemory() + it.freeMemory() }
    fun storageLimit(): Long = Runtime.getRuntime().maxMemory() / 2
    fun fileLimit(maxBytes: Int, factor: Int = 8): Int =
        minOf(maxBytes.toLong(), available() / factor).coerceAtLeast(0).toInt()
    fun requireFileSize(size: Long, absolute: Int, admitted: Int) {
        require(size <= absolute) { "Файл слишком большой" }
        require(size <= admitted) { "Недостаточно памяти для обработки файла такого размера" }
    }
}

/** The byte limit is checked before handing each block to a parser or cipher. */
internal class LimitedInput(
    private val source: InputStream,
    private val absolute: Int,
    private val admitted: Int
) : InputStream() {
    private var count = 0L
    override fun read(): Int {
        val value = source.read()
        if (value >= 0) MemoryBudget.requireFileSize(++count, absolute, admitted)
        return value
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val allowed = minOf(len.toLong(), minOf(absolute, admitted).toLong() - count + 1).coerceAtLeast(1).toInt()
        val read = source.read(b, off, allowed)
        val n = if (read == 0) {
            val next = source.read()
            if (next < 0) -1 else { b[off] = next.toByte(); 1 }
        } else read
        if (n > 0) {
            count += n
            MemoryBudget.requireFileSize(count, absolute, admitted)
        }
        return n
    }
}

@OptIn(ExperimentalSerializationApi::class)
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
            val envelope = BackupEnvelope(ledger = ledger.validated())
            require(LedgerRecords.stats(ledger).estimatedMemory() <= MemoryBudget.storageLimit()) {
                "Недостаточно памяти устройства для восстановления такой копии"
            }
            // Use the same admission rule on export and restore, before writing
            // even the header. GCM adds exactly one 16-byte authentication tag.
            val admitted = MemoryBudget.fileLimit(maxBytes)
            var size = header.size.toLong() + 16
            MemoryBudget.requireFileSize(size, maxBytes, admitted)
            LedgerRecords.json.encodeToStream(BackupEnvelope.serializer(), envelope, object : OutputStream() {
                override fun write(b: Int) { MemoryBudget.requireFileSize(++size, maxBytes, admitted) }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    size += len
                    MemoryBudget.requireFileSize(size, maxBytes, admitted)
                }
            })
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            output.write(header)
            val encrypted = object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (len > 0) cipher.update(b, off, len)?.let(output::write)
                }
            }
            LedgerRecords.json.encodeToStream(BackupEnvelope.serializer(), envelope, encrypted)
            output.write(cipher.doFinal())
            output.flush()
        } finally { password.fill('\u0000') }
    }
    fun read(password: CharArray, input: InputStream): Ledger {
        try {
            val bounded = LimitedInput(input, maxBytes, MemoryBudget.fileLimit(maxBytes))
            val headerSize = magic.size + 16 + 12
            val header = ByteArray(headerSize)
            java.io.DataInputStream(bounded).readFully(header)
            require(header.copyOfRange(0, magic.size).contentEquals(magic)) { "Неверный формат копии" }
            val salt = header.copyOfRange(magic.size, magic.size + 16)
            val iv = header.copyOfRange(magic.size + 16, headerSize)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            val clear = AuthenticatedInput(bounded, cipher)
            try {
                val data = LedgerRecords.json.decodeFromStream(BackupEnvelope.serializer(), clear)
                // Do not rely on the JSON parser reading EOF. GCM authentication
                // must succeed even on providers which release bytes on update.
                require(clear.read() == -1 && clear.authenticated) { "Неверный формат копии" }
                require(data.version == 1) { "Версия резервной копии не поддерживается" }
                return data.ledger.validated()
            } finally { clear.wipe() }
        } finally { password.fill('\u0000') }
    }

    // CipherInputStream is unsuitable here: provider authentication errors must
    // reach the caller. Never close the caller's stream or write plaintext to disk.
    private class AuthenticatedInput(private val input: InputStream, private val cipher: Cipher) : InputStream() {
        private val encrypted = ByteArray(8192)
        private var clear = ByteArray(0)
        private var offset = 0
        var authenticated = false
            private set
        private fun availableBlock(): Boolean {
            while (offset == clear.size) {
                clear.fill(0)
                if (authenticated) return false
                offset = 0
                val n = input.read(encrypted)
                clear = if (n < 0) {
                    val final = try { cipher.doFinal() }
                    catch (_: AEADBadTagException) { error("Неверный пароль или повреждённая копия") }
                    authenticated = true
                    final
                } else if (n == 0) {
                    val value = input.read()
                    if (value < 0) {
                        val final = try { cipher.doFinal() }
                        catch (_: AEADBadTagException) { error("Неверный пароль или повреждённая копия") }
                        authenticated = true
                        final
                    } else cipher.update(byteArrayOf(value.toByte())) ?: ByteArray(0)
                } else cipher.update(encrypted, 0, n) ?: ByteArray(0)
            }
            return true
        }
        override fun read(): Int = if (availableBlock()) clear[offset++].toInt() and 255 else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!availableBlock()) return -1
            val n = minOf(len, clear.size - offset)
            clear.copyInto(b, off, offset, offset + n)
            offset += n
            return n
        }
        fun wipe() { clear.fill(0); encrypted.fill(0) }
    }
}

fun InputStream.readBounded(maxBytes: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        if (n == 0) {
            val b = read()
            if (b < 0) break
            require(out.size() < maxBytes) { "Файл слишком большой" }
            out.write(b)
            continue
        }
        require(out.size() <= maxBytes - n) { "Файл слишком большой" }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
