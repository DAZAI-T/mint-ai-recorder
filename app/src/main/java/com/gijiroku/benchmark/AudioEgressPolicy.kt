package com.gijiroku.benchmark

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.util.UUID

enum class AudioEgressState { BLOCK, ALLOW_FOR_SESSION }

class AudioEgressBlockedException : IllegalStateException(
    AppLanguage.text("音声外部送信保護がONです。送信するには利用者の明示操作が必要です", "Audio upload protection is ON. Explicit permission is required to send audio.")
)

/** Process-local policy. ALLOW is intentionally never persisted. */
object AudioEgressPolicy {
    private val mutableState = MutableStateFlow(AudioEgressState.BLOCK)
    private val activeTransmissions = mutableMapOf<String, () -> Unit>()
    private val lock = Any()

    val state: StateFlow<AudioEgressState> = mutableState.asStateFlow()

    fun allowForCurrentSession() {
        mutableState.value = AudioEgressState.ALLOW_FOR_SESSION
    }

    fun block() {
        mutableState.value = AudioEgressState.BLOCK
        val cancellations = synchronized(lock) { activeTransmissions.values.toList() }
        cancellations.forEach { runCatching(it) }
    }

    fun requireAllowed(descriptor: ProviderDescriptor) {
        val sendsAudio = descriptor.executionMode == ExecutionMode.CLOUD &&
            DataKind.AUDIO in descriptor.dataRequired
        if (sendsAudio && mutableState.value != AudioEgressState.ALLOW_FOR_SESSION) {
            throw AudioEgressBlockedException()
        }
    }

    fun <T> withActiveTransmission(cancel: () -> Unit, block: () -> T): T {
        if (mutableState.value != AudioEgressState.ALLOW_FOR_SESSION) {
            throw AudioEgressBlockedException()
        }
        val token = UUID.randomUUID().toString()
        synchronized(lock) { activeTransmissions[token] = cancel }
        return try {
            if (mutableState.value != AudioEgressState.ALLOW_FOR_SESSION) {
                throw AudioEgressBlockedException()
            }
            block()
        } finally {
            synchronized(lock) { activeTransmissions.remove(token) }
        }
    }
}

/**
 * Per-process acknowledgement for a fixed HTTPS origin. It is never persisted, so a restart
 * restores the audio-upload protection and requires an explicit acknowledgement again.
 */
class AudioEgressConsentStore(@Suppress("UNUSED_PARAMETER") context: Context) {
    fun hasAcknowledged(origin: String): Boolean = synchronized(acknowledgedOrigins) {
        key(origin) in acknowledgedOrigins
    }

    fun acknowledge(origin: String) {
        synchronized(acknowledgedOrigins) { acknowledgedOrigins += key(origin) }
    }

    private fun key(origin: String): String {
        val normalized = normalizeHttpsOrigin(origin)
        return "ack.$WARNING_VERSION.${ArtifactHasher.sha256(normalized)}"
    }

    companion object {
        const val WARNING_VERSION = 1
        private val acknowledgedOrigins = mutableSetOf<String>()

        fun normalizeHttpsOrigin(value: String): String {
            val uri = URI(value)
            require(uri.scheme.equals("https", ignoreCase = true)) { "HTTPS origin required" }
            require(!uri.host.isNullOrBlank()) { "Valid origin required" }
            val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
            return "https://${uri.host.lowercase()}$port"
        }
    }
}
