package com.gijiroku.benchmark

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class MeetingKeyPurpose(val storageName: String) {
    CONTENT("content"),
    AUDIO("audio")
}

/**
 * Creates independently erasable content/audio keys for each meeting.
 *
 * Each exportable data key is wrapped by its own non-exportable Android Keystore key. Deleting
 * the Keystore alias first makes stale copies of the wrapped data key unusable, which allows
 * audio-only cryptographic deletion without destroying text artifacts.
 */
class MeetingKeyManager(context: Context) {

    private val appContext = context.applicationContext ?: context
    private val preferences = appContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()
    private val lock = Any()

    fun createMeetingKeys(meetingId: String) = synchronized(lock) {
        validateMeetingId(meetingId)
        check(!hasKey(meetingId, MeetingKeyPurpose.CONTENT)) { "Meeting keys already exist" }
        check(!hasKey(meetingId, MeetingKeyPurpose.AUDIO)) { "Meeting keys already exist" }
        try {
            createKey(meetingId, MeetingKeyPurpose.CONTENT)
            createKey(meetingId, MeetingKeyPurpose.AUDIO)
        } catch (error: Throwable) {
            runCatching { deleteMeetingKeys(meetingId) }
            throw error
        }
    }

    fun hasKey(meetingId: String, purpose: MeetingKeyPurpose): Boolean = synchronized(lock) {
        validateMeetingId(meetingId)
        val keyStore = loadKeyStore()
        keyStore.containsAlias(alias(meetingId, purpose)) &&
            preferences.contains(ivPreference(meetingId, purpose)) &&
            preferences.contains(wrappedPreference(meetingId, purpose))
    }

    fun <T> useKey(
        meetingId: String,
        purpose: MeetingKeyPurpose,
        block: (SecretKey) -> T
    ): T = synchronized(lock) {
        validateMeetingId(meetingId)
        val wrappingKey = loadKeyStore().getKey(alias(meetingId, purpose), null) as? SecretKey
            ?: error("Meeting key is unavailable")
        val iv = preferences.getString(ivPreference(meetingId, purpose), null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: error("Wrapped meeting key is unavailable")
        val encrypted = preferences.getString(wrappedPreference(meetingId, purpose), null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: error("Wrapped meeting key is unavailable")
        val raw = Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(aad(meetingId, purpose))
            doFinal(encrypted)
        }
        val dataKey = SecretKeySpec(raw, "AES")
        try {
            block(dataKey)
        } finally {
            raw.fill(0)
            iv.fill(0)
            encrypted.fill(0)
        }
    }

    fun deleteAudioKey(meetingId: String) = synchronized(lock) {
        deleteKey(meetingId, MeetingKeyPurpose.AUDIO)
    }

    fun deleteMeetingKeys(meetingId: String) = synchronized(lock) {
        var firstFailure: Throwable? = null
        MeetingKeyPurpose.entries.forEach { purpose ->
            try {
                deleteKey(meetingId, purpose)
            } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = error
            }
        }
        firstFailure?.let { throw it }
    }

    private fun createKey(meetingId: String, purpose: MeetingKeyPurpose) {
        val wrappingAlias = alias(meetingId, purpose)
        val keyStore = loadKeyStore()
        check(!keyStore.containsAlias(wrappingAlias)) { "Meeting wrapping key already exists" }
        val wrappingKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    wrappingAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    .build()
            )
            generateKey()
        }
        val raw = ByteArray(KEY_BITS / Byte.SIZE_BITS).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, wrappingKey)
            updateAAD(aad(meetingId, purpose))
        }
        val encrypted = cipher.doFinal(raw)
        try {
            val stored = preferences.edit()
                .putString(ivPreference(meetingId, purpose), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(
                    wrappedPreference(meetingId, purpose),
                    Base64.encodeToString(encrypted, Base64.NO_WRAP)
                )
                .commit()
            if (!stored) error("Could not persist wrapped meeting key")
        } catch (error: Throwable) {
            keyStore.deleteEntry(wrappingAlias)
            throw error
        } finally {
            raw.fill(0)
            encrypted.fill(0)
        }
    }

    private fun deleteKey(meetingId: String, purpose: MeetingKeyPurpose) {
        validateMeetingId(meetingId)
        val keyStore = loadKeyStore()
        val wrappingAlias = alias(meetingId, purpose)
        if (keyStore.containsAlias(wrappingAlias)) keyStore.deleteEntry(wrappingAlias)
        val removed = preferences.edit()
            .remove(ivPreference(meetingId, purpose))
            .remove(wrappedPreference(meetingId, purpose))
            .commit()
        check(removed) { "Could not remove wrapped meeting key" }
    }

    private fun alias(meetingId: String, purpose: MeetingKeyPurpose): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(meetingId.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$KEY_ALIAS_PREFIX${purpose.storageName}_${digest.take(ALIAS_DIGEST_CHARS)}"
    }

    private fun aad(meetingId: String, purpose: MeetingKeyPurpose): ByteArray =
        "$FORMAT_VERSION|$meetingId|${purpose.storageName}"
            .toByteArray(StandardCharsets.UTF_8)

    private fun ivPreference(meetingId: String, purpose: MeetingKeyPurpose) =
        "${alias(meetingId, purpose)}.iv"

    private fun wrappedPreference(meetingId: String, purpose: MeetingKeyPurpose) =
        "${alias(meetingId, purpose)}.wrapped"

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun validateMeetingId(meetingId: String) {
        require(meetingId.length in 1..MAX_MEETING_ID_CHARS) { "Invalid meeting ID" }
        require(meetingId.none(Char::isISOControl)) { "Invalid meeting ID" }
    }

    companion object {
        private const val PREFERENCES_NAME = "gijiroku_wrapped_meeting_keys_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS_PREFIX = "gijiroku_meeting_v1_"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION = 1
        private const val GCM_TAG_BITS = 128
        private const val KEY_BITS = 256
        private const val ALIAS_DIGEST_CHARS = 32
        private const val MAX_MEETING_ID_CHARS = 256
    }
}
