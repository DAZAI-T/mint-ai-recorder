package com.gijiroku.benchmark

import android.content.Context
import java.io.File
import java.io.OutputStream

/** Writes a user-requested WAV directly to SAF without a plaintext temporary file. */
class EncryptedRecordingWavExporter(context: Context) {
    private val keyManager = MeetingKeyManager(context.applicationContext ?: context)

    fun write(reference: RecordingReference, output: OutputStream) {
        val encryptedFile = File(reference.encryptedFilePath)
        require(reference.finalized && encryptedFile.isFile) { AppLanguage.text("保存できる録音音声がありません", "No recorded audio to save") }
        keyManager.useKey(reference.meetingId, MeetingKeyPurpose.AUDIO) { key ->
            val inspection = EncryptedAudioReader.read(
                file = encryptedFile,
                key = key,
                onPcmChunk = {}
            )
            require(inspection.finalized) { AppLanguage.text("録音が完了していません", "Recording is not complete") }
            Pcm16WavStream.writeHeader(
                output = output,
                dataBytes = inspection.plaintextBytes,
                sampleRate = inspection.metadata.sampleRate,
                channels = inspection.metadata.channels,
                bitsPerSample = inspection.metadata.bitsPerSample
            )
            val written = EncryptedAudioReader.read(
                file = encryptedFile,
                key = key,
                onMetadata = { metadata -> require(metadata == inspection.metadata) { AppLanguage.text("録音形式が変化しました", "Recording format changed") } },
                onPcmChunk = output::write
            )
            require(written.finalized && written.plaintextBytes == inspection.plaintextBytes) {
                AppLanguage.text("録音データが書き出し中に変化しました", "Recording data changed during export")
            }
            output.flush()
        }
    }
}
