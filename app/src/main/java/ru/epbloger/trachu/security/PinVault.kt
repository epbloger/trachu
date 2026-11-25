package ru.epbloger.trachu.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

object PinVault {
    private const val alias = "trachu-pin-v1"
    private val random = SecureRandom()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(),salt,150_000,256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    fun create(pin: String): String {
        require(pin.matches(Regex("[0-9]{4,12}"))) { "PIN: от 4 до 12 цифр" }
        val salt = ByteArray(16).also(random::nextBytes)
        val clear = salt+hash(pin,salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        return try { Base64.encodeToString(cipher.iv+cipher.doFinal(clear),Base64.NO_WRAP) }
        finally { clear.fill(0) }
    }
    fun verify(pin: String, encoded: String): Boolean {
        if (!pin.matches(Regex("[0-9]{4,12}")) || encoded.isEmpty()) return false
        val bytes = Base64.decode(encoded,Base64.NO_WRAP)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        val clear = cipher.doFinal(bytes,12,bytes.size-12)
        return try {
            require(clear.size == 48)
            val candidate = hash(pin,clear.copyOfRange(0,16))
            try { MessageDigest.isEqual(clear.copyOfRange(16,48),candidate) } finally { candidate.fill(0) }
        } finally { clear.fill(0) }
    }
}
