package com.gijiroku.benchmark

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Streams an authenticated excerpt to AudioTrack without creating plaintext audio files. */
class EncryptedRecordingPlayer(context: Context) {
    private val keyManager = MeetingKeyManager(context.applicationContext ?: context)
    private val generation = AtomicLong(0)

    @Volatile
    private var activeTrack: AudioTrack? = null

    @Volatile
    private var playbackStartMs = 0L

    @Volatile
    private var playbackEndMs = 0L

    @Volatile
    private var playbackSampleRate = 0

    fun play(
        reference: RecordingReference,
        startMs: Long,
        endMs: Long,
        onFinished: (Throwable?) -> Unit
    ) {
        stop()
        val request = generation.incrementAndGet()
        Thread {
            var track: AudioTrack? = null
            var failure: Throwable? = null
            var shouldDrain = false
            var framesWritten = 0L
            var sampleRate = 0
            try {
                keyManager.useKey(reference.meetingId, MeetingKeyPurpose.AUDIO) { key ->
                    var cursor: PcmRangeCursor? = null
                    EncryptedAudioReader.read(
                        file = File(reference.encryptedFilePath),
                        key = key,
                        onMetadata = { metadata ->
                            checkCurrent(request)
                            require(metadata.channels == 1) { AppLanguage.text("モノラル音声のみ再生できます", "Only mono audio can be played") }
                            require(metadata.bitsPerSample == 16) { AppLanguage.text("PCM16音声のみ再生できます", "Only PCM16 audio can be played") }
                            cursor = PcmRangeCursor.forTimeRange(
                                startMs = startMs,
                                endMs = endMs,
                                sampleRate = metadata.sampleRate,
                                channels = metadata.channels,
                                bitsPerSample = metadata.bitsPerSample
                            )
                            sampleRate = metadata.sampleRate
                            playbackStartMs = startMs.coerceAtLeast(0)
                            playbackEndMs = endMs.coerceAtLeast(playbackStartMs)
                            playbackSampleRate = metadata.sampleRate
                            track = createTrack(metadata.sampleRate).also {
                                activeTrack = it
                                it.play()
                            }
                        }
                    ) { pcm ->
                        checkCurrent(request)
                        val selection = cursor?.take(pcm.size)
                        if (selection != null) {
                            writeFully(track ?: error(AppLanguage.text("再生を開始できません", "Cannot start playback")), pcm, selection)
                            framesWritten += selection.length / PCM16_MONO_FRAME_BYTES
                        }
                        if (cursor?.reachedEnd == true) throw PlaybackRangeComplete
                    }
                }
                shouldDrain = true
            } catch (_: PlaybackRangeCompleteException) {
                shouldDrain = true
            } catch (_: PlaybackCancelledException) {
                return@Thread
            } catch (error: Throwable) {
                failure = error
            } finally {
                if (shouldDrain && failure == null && generation.get() == request) {
                    runCatching { awaitPlayback(track, framesWritten, sampleRate, request) }
                }
                if (activeTrack === track) activeTrack = null
                runCatching { track?.stop() }
                runCatching { track?.flush() }
                track?.release()
            }
            if (generation.get() == request) onFinished(failure)
        }.start()
    }

    fun stop() {
        generation.incrementAndGet()
        activeTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
        }
    }

    fun pause(): Boolean {
        val track = activeTrack ?: return false
        return runCatching {
            track.pause()
            true
        }.getOrDefault(false)
    }

    fun resume(): Boolean {
        val track = activeTrack ?: return false
        return runCatching {
            track.play()
            true
        }.getOrDefault(false)
    }

    /** Current media position without exposing decrypted audio outside AudioTrack. */
    fun currentPositionMs(): Long? {
        val track = activeTrack ?: return null
        val sampleRate = playbackSampleRate.takeIf { it > 0 } ?: return null
        val playedFrames = track.playbackHeadPosition.toLong() and UNSIGNED_INT_MASK
        return (playbackStartMs + playedFrames * 1_000L / sampleRate)
            .coerceAtMost(playbackEndMs)
    }

    private fun checkCurrent(request: Long) {
        if (generation.get() != request) throw PlaybackCancelled
    }

    private fun createTrack(sampleRate: Int): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sampleRate / 2)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minimum)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { check(it.state == AudioTrack.STATE_INITIALIZED) { AppLanguage.text("音声再生を初期化できません", "Cannot initialize audio playback") } }
    }

    private fun writeFully(track: AudioTrack, pcm: ByteArray, selection: PcmChunkSlice) {
        var offset = selection.offset
        var remaining = selection.length
        while (remaining > 0) {
            val written = track.write(pcm, offset, remaining, AudioTrack.WRITE_BLOCKING)
            check(written > 0) { AppLanguage.text("音声を再生できませんでした: $written", "Could not play audio: $written") }
            offset += written
            remaining -= written
        }
    }

    private fun awaitPlayback(track: AudioTrack?, framesWritten: Long, sampleRate: Int, request: Long) {
        if (track == null || framesWritten <= 0 || sampleRate <= 0) return
        val expectedMs = framesWritten * 1_000L / sampleRate
        val deadline = System.currentTimeMillis() + (expectedMs + 2_000L).coerceAtMost(MAX_DRAIN_WAIT_MS)
        while (generation.get() == request && System.currentTimeMillis() < deadline) {
            val played = track.playbackHeadPosition.toLong() and UNSIGNED_INT_MASK
            if (played >= framesWritten) return
            Thread.sleep(DRAIN_POLL_MS)
        }
    }

    companion object {
        private const val PCM16_MONO_FRAME_BYTES = 2
        private const val DRAIN_POLL_MS = 20L
        private const val MAX_DRAIN_WAIT_MS = 120_000L
        private const val UNSIGNED_INT_MASK = 0xffff_ffffL
    }
}

private class PlaybackCancelledException : RuntimeException()
private class PlaybackRangeCompleteException : RuntimeException()
private val PlaybackCancelled = PlaybackCancelledException()
private val PlaybackRangeComplete = PlaybackRangeCompleteException()
