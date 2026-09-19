package com.gijiroku.benchmark

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.CharBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores user-provided API keys encrypted with a non-exportable Android Keystore key.
 * Decrypted characters exist only for the duration of [useSecret] and are wiped afterwards.
 */
class SecretStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(secretId: String, value: CharArray) {
        require(value.isNotEmpty()) { AppLanguage.text("APIキーが空です", "API key is empty") }
        val plaintext = encodeUtf8(value)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
            val encrypted = cipher.doFinal(plaintext)
            val suffix = value.takeLast(minOf(4, value.size)).joinToString("")
            prefs.edit()
                .putString(ivKey(secretId), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(valueKey(secretId), Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(suffixKey(secretId), suffix)
                .apply()
            encrypted.fill(0)
        } finally {
            plaintext.fill(0)
            value.fill('\u0000')
        }
    }

    fun <T> useSecret(secretId: String, block: (CharArray) -> T): T {
        val encodedIv = requireNotNull(prefs.getString(ivKey(secretId), null)) {
            AppLanguage.text("APIキーが設定されていません", "API key is not configured")
        }
        val encodedValue = requireNotNull(prefs.getString(valueKey(secretId), null)) {
            AppLanguage.text("APIキーが設定されていません", "API key is not configured")
        }
        val iv = Base64.decode(encodedIv, Base64.NO_WRAP)
        val encrypted = Base64.decode(encodedValue, Base64.NO_WRAP)
        val plaintext = Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(encrypted)
        }
        val characters = Charsets.UTF_8.decode(java.nio.ByteBuffer.wrap(plaintext)).let { buffer ->
            CharArray(buffer.remaining()).also { buffer.get(it) }
        }
        return try {
            block(characters)
        } finally {
            characters.fill('\u0000')
            plaintext.fill(0)
            encrypted.fill(0)
            iv.fill(0)
        }
    }

    fun hasSecret(secretId: String): Boolean {
        return prefs.contains(ivKey(secretId)) && prefs.contains(valueKey(secretId))
    }

    fun maskedLabel(secretId: String): String? {
        if (!hasSecret(secretId)) return null
        val suffix = prefs.getString(suffixKey(secretId), "") ?: ""
        return "••••$suffix"
    }

    fun delete(secretId: String) {
        prefs.edit()
            .remove(ivKey(secretId))
            .remove(valueKey(secretId))
            .remove(suffixKey(secretId))
            .apply()
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(MASTER_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    MASTER_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    private fun encodeUtf8(value: CharArray): ByteArray {
        val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(value))
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun ivKey(secretId: String) = "$secretId.iv"
    private fun valueKey(secretId: String) = "$secretId.value"
    private fun suffixKey(secretId: String) = "$secretId.suffix"

    companion object {
        const val OPENAI_API_KEY = "openai.api_key"
        const val GEMINI_API_KEY = "google.gemini.api_key"
        private const val PREFERENCES_NAME = "gijiroku_encrypted_secrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "gijiroku_provider_secrets_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
