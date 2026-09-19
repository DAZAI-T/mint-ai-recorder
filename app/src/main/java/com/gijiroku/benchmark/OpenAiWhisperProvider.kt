package com.gijiroku.benchmark

import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/** OpenAI-hosted Whisper adapter. The endpoint is fixed so a key cannot be sent elsewhere. */
class OpenAiWhisperProvider(
    private val secretStore: SecretStore,
    private val model: String = CloudProviderSettings.DEFAULT_OPENAI_TRANSCRIPTION_MODEL
) : PipelineProvider<TranscriptionRequest, TranscriptArtifact> {

    private val validatedModel = CloudModelNameValidator.normalize(CloudModelProvider.OPENAI, model)

    override val descriptor = ProviderDescriptor(
        id = ProviderIds.OPENAI_WHISPER,
        displayName = "OpenAI Whisper API",
        stage = PipelineStage.TRANSCRIPTION,
        executionMode = ExecutionMode.CLOUD,
        dataRequired = setOf(DataKind.AUDIO),
        destination = TRANSCRIPTIONS_ENDPOINT
    )

    override suspend fun execute(
        input: TranscriptionRequest,
        context: ExecutionContext
    ): ProviderResult<TranscriptArtifact> {
        val startedAt = System.currentTimeMillis()
        val response = upload(input.audio, input.language)
        val segments = parseVerboseTranscript(response, input.audio.durationMs)
        val duration = System.currentTimeMillis() - startedAt
        return ProviderResult(
            output = TranscriptArtifact(
                segments = segments,
                provenance = ArtifactProvenance(
                    providerId = descriptor.id,
                    createdAtEpochMs = System.currentTimeMillis(),
                    inputHash = context.inputHash
                )
            ),
            providerId = descriptor.id,
            durationMs = duration,
            estimatedCostUsd = estimateCostUsd(input.audio.durationMs),
            destination = descriptor.destination,
            modelId = validatedModel
        )
    }

    private fun upload(audio: AudioArtifact, language: String): String {
        val url = URL(TRANSCRIPTIONS_ENDPOINT)
        check(url.protocol == "https" && url.host == OPENAI_HOST) { AppLanguage.text("許可されていない送信先です", "Destination is not allowed") }
        val boundary = "----Gijiroku${UUID.randomUUID()}"
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setChunkedStreamingMode(STREAM_CHUNK_BYTES)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        return try {
            AudioEgressPolicy.withActiveTransmission(cancel = connection::disconnect) {
                secretStore.useSecret(SecretStore.OPENAI_API_KEY) { secret ->
                    connection.setRequestProperty("Authorization", "Bearer ${String(secret)}")
                    BufferedOutputStream(connection.outputStream).use { output ->
                        output.writeField(boundary, "model", validatedModel)
                        output.writeField(boundary, "language", language)
                        output.writeField(boundary, "response_format", "verbose_json")
                        output.writeField(boundary, "timestamp_granularities[]", "segment")
                        output.writeAudio(boundary, "file", audio)
                        output.writeUtf8("--$boundary--\r\n")
                    }
                    val status = connection.responseCode
                    if (status !in 200..299) {
                        connection.errorStream?.close()
                        throw CloudProviderException(AppLanguage.text("OpenAI APIでエラーが発生しました（HTTP $status）", "OpenAI API error (HTTP $status)"))
                    }
                    connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                }
            }
        } catch (e: CloudProviderException) {
            throw e
        } catch (e: Exception) {
            throw CloudProviderException(AppLanguage.text("OpenAI APIへの接続に失敗しました", "Could not connect to OpenAI API"), e)
        } finally {
            connection.disconnect()
        }
    }

    private fun OutputStream.writeField(boundary: String, name: String, value: String) {
        writeUtf8("--$boundary\r\n")
        writeUtf8("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        writeUtf8(value)
        writeUtf8("\r\n")
    }

    private fun OutputStream.writeAudio(boundary: String, name: String, audio: AudioArtifact) {
        writeUtf8("--$boundary\r\n")
        writeUtf8("Content-Disposition: form-data; name=\"$name\"; filename=\"recording.wav\"\r\n")
        writeUtf8("Content-Type: audio/wav\r\n\r\n")
        Pcm16WavStream.write(this, audio.samples, audio.sampleRate, STREAM_CHUNK_BYTES)
        writeUtf8("\r\n")
    }

    private fun OutputStream.writeUtf8(value: String) {
        write(value.toByteArray(StandardCharsets.UTF_8))
    }

    companion object {
        const val TRANSCRIPTIONS_ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"
        private const val OPENAI_HOST = "api.openai.com"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 10 * 60_000
        private const val STREAM_CHUNK_BYTES = 64 * 1024
        private const val WHISPER_COST_USD_PER_MINUTE = 0.006

        internal fun estimateCostUsd(durationMs: Long): Double =
            durationMs.coerceAtLeast(0).toDouble() / 60_000.0 * WHISPER_COST_USD_PER_MINUTE

        internal fun parseVerboseTranscript(json: String, fallbackDurationMs: Long): List<TranscriptSegment> {
            val root = JSONObject(json)
            val responseSegments = root.optJSONArray("segments")
            if (responseSegments != null && responseSegments.length() > 0) {
                return buildList {
                    for (index in 0 until responseSegments.length()) {
                        val segment = responseSegments.getJSONObject(index)
                        val startMs = (segment.optDouble("start", 0.0) * 1000).toLong().coerceAtLeast(0)
                        val endMs = (segment.optDouble("end", startMs / 1000.0) * 1000)
                            .toLong()
                            .coerceAtLeast(startMs)
                        val text = segment.optString("text").trim()
                        if (text.isNotEmpty()) add(TranscriptSegment(startMs, endMs, text))
                    }
                }
            }

            val text = root.optString("text").trim()
            return if (text.isEmpty()) emptyList() else listOf(
                TranscriptSegment(0, fallbackDurationMs.coerceAtLeast(0), text)
            )
        }
    }
}
