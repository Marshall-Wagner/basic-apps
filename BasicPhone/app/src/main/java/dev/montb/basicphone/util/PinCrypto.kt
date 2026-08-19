package dev.montb.basicphone.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts a small secret (the voicemail PIN) with a non-exportable AES-256-GCM key held in
 * the Android Keystore, hardware-backed on devices that support it. Only ciphertext is stored
 * in SharedPreferences, so a root/forensic read of the prefs file yields ciphertext, and the
 * key itself can't be extracted from secure hardware.
 */
object PinCrypto {
    private const val ALIAS = "basicphone_pin_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12      // AES-GCM nonce
    private const val TAG_BITS = 128

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    /** Encrypt to base64(iv || ciphertext). */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Decrypt base64(iv || ciphertext); null if it isn't our ciphertext (e.g. legacy plaintext). */
    fun decrypt(stored: String): String? = runCatching {
        val data = Base64.decode(stored, Base64.NO_WRAP)
        if (data.size <= IV_LEN) return null
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data.copyOfRange(0, IV_LEN)))
        }
        String(cipher.doFinal(data.copyOfRange(IV_LEN, data.size)), Charsets.UTF_8)
    }.getOrNull()
}
