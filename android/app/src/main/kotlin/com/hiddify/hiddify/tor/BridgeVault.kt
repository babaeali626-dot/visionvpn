package com.hiddify.hiddify.tor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM bridge storage; plaintext is never written to SharedPreferences or logs. */
internal class BridgeVault(context: Context) {
    private val prefs = context.getSharedPreferences("visionvpn_tor_vault", Context.MODE_PRIVATE)
    private val alias = "visionvpn.tor.bridge.aes.v1"

    @Synchronized fun save(lines: List<String>) {
        val plaintext = lines.joinToString("\n").toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plaintext)
        val packed = cipher.iv + encrypted
        check(prefs.edit().putString("bridges", Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
            "Could not persist encrypted bridge settings"
        }
    }

    @Synchronized fun load(): List<String> {
        val packedText = prefs.getString("bridges", null) ?: return emptyList()
        val packed = Base64.decode(packedText, Base64.NO_WRAP)
        require(packed.size > 12) { "Encrypted bridge data is invalid" }
        val iv = packed.copyOfRange(0, 12)
        val ciphertext = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8).lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() }.toList()
    }

    @Synchronized fun clear() { prefs.edit().remove("bridges").apply() }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
}
