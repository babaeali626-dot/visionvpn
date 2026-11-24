package com.visionvpn.app.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Thrown when encrypted storage fails authentication or Keystore decryption.
 * Callers must surface this error rather than silently resetting user data to empty.
 */
class SecureStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface SecretCipher {
    fun encrypt(plaintext: String, associatedData: String? = null): String
    fun decrypt(storedValue: String, associatedData: String? = null): String
    fun isEncrypted(storedValue: String): Boolean
}

class AesGcmSecretCipher(
    private val keyProvider: () -> SecretKey
) : SecretCipher {

    companion object {
        const val PREFIX_V2 = "enc:v2:"
        const val PREFIX_V1 = "enc:v1:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12
    }

    override fun isEncrypted(storedValue: String): Boolean =
        storedValue.startsWith(PREFIX_V2) || storedValue.startsWith(PREFIX_V1)

    override fun encrypt(plaintext: String, associatedData: String?): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val key = keyProvider()
        cipher.init(Cipher.ENCRYPT_MODE, key)
        if (!associatedData.isNullOrEmpty()) {
            cipher.updateAAD(associatedData.toByteArray(StandardCharsets.UTF_8))
        }
        val iv = cipher.iv ?: ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val ivB64 = Base64.getEncoder().encodeToString(iv)
        val ctB64 = Base64.getEncoder().encodeToString(ciphertext)
        return "$PREFIX_V2$ivB64:$ctB64"
    }

    override fun decrypt(storedValue: String, associatedData: String?): String {
        if (!isEncrypted(storedValue)) {
            return storedValue
        }
        val isV2 = storedValue.startsWith(PREFIX_V2)
        val body = if (isV2) storedValue.removePrefix(PREFIX_V2) else storedValue.removePrefix(PREFIX_V1)
        val sep = body.indexOf(':')
        if (sep <= 0) {
            throw SecureStorageException("Malformed encrypted record header.")
        }

        return try {
            val iv = Base64.getDecoder().decode(body.substring(0, sep))
            val ciphertext = Base64.getDecoder().decode(body.substring(sep + 1))
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(GCM_TAG_BITS, iv))
            if (isV2 && !associatedData.isNullOrEmpty()) {
                cipher.updateAAD(associatedData.toByteArray(StandardCharsets.UTF_8))
            }
            val plainBytes = cipher.doFinal(ciphertext)
            String(plainBytes, StandardCharsets.UTF_8)
        } catch (t: Throwable) {
            throw SecureStorageException("Encrypted data failed authentication or Keystore key was invalidated.", t)
        }
    }
}

object AndroidKeystoreCrypto {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "vision_vpn_master_aes_v1"

    fun createCipher(): SecretCipher = AesGcmSecretCipher { getOrCreateSecretKey() }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()

        generator.init(spec)
        return generator.generateKey()
    }
}
