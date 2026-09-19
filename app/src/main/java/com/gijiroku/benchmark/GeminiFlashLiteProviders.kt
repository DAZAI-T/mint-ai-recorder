package com.gijiroku.benchmark

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.random.Random

private class RetryableGeminiHttpException(
    val status: Int,
    val apiCode: String?,
    val retryAfterMs: Long?
) : Exception()

internal data class GeminiGeneratedContent(
    val text: String,
    val promptTokens: Long?,
    val outputTokens: Long?,
    val modelVersion: String? = null
) {
    fun estimatedCostUsd(audioInput: Boolean): Double? {
        if (promptTokens == null && outputTokens == null) return null
        val rates = GeminiPricing.ratesFor(modelVersion) ?: return null
        val inputRate = if (audioInput) rates.audioInput else rates.textInput
        return (promptTokens ?: 0L) * inputRate / 1_000_000.0 +
            (outputTokens ?: 0L) * rates.output / 1_000_000.0
    }
}

internal object GeminiPricing {
    const val VERIFIED_AT = "2026-09-09"
    data class Rates(val textInput: Double, val audioInput: Double, val output: Double)

    /** Unknown future targets deliberately return null instead of showing a stale estimate. */
    fun ratesFor(modelVersion: String?): Rates? = when (modelVersion?.removePrefix("models/")) {
        "gemini-3.5-flash" -> Rates(textInput = 1.50, audioInput = 1.50, output = 9.00)
        "gemini-3.5-flash-lite" -> Rates(textInput = 0.30, audioInput = 0.30, output = 2.50)
        "gemini-2.5-flash-lite" -> Rates(textInput = 0.10, audioInput = 0.30, output = 0.40)
        else -> null
    }
}

internal interface GeminiContentService {
    fun generateText(prompt: String, schema: JSONObject): GeminiGeneratedContent
    fun generateAudio(audio: AudioArtifact, prompt: String, schema: JSONObject): GeminiGeneratedContent
}

internal object GeminiUploadedFileLifecycle {
    fun <T> use(
        upload: () -> Pair<String, String>,
        delete: (String) -> Unit,
        block: (name: String, uri: String) -> T
    ): T {
        var uploadedName: String? = null
        return try {
            val uploaded = upload()
            uploadedName = uploaded.first
            block(uploaded.first, uploaded.second)
        } finally {
            uploadedName?.let { runCatching { delete(it) } }
        }
    }
}

/** Fixed-origin Gemini REST client. Request/response bodies and credentials are never logged. */
internal class GeminiRestClient(
    private val secretStore: SecretStore,
    model: String = CloudProviderSettings.LEGACY_GEMINI_MODEL
) : GeminiContentService {
    private val model = CloudModelNameValidator.normalize(CloudModelProvider.GEMINI, model)

    override fun generateText(prompt: String, schema: JSONObject): GeminiGeneratedContent =
        sanitizedCall {
            secretStore.useSecret(SecretStore.GEMINI_API_KEY) { key ->
                generate(key, textPart(prompt), schema, requiresAudioPermission = false)
            }
        }

    override fun generateAudio(
        audio: AudioArtifact,
        prompt: String,
        schema: JSONObject
    ): GeminiGeneratedContent {
        require(audio.durationMs <= MAX_AUDIO_DURATION_MS) { AppLanguage.text("Geminiへ送信できる音声は9.5時間までです", "Gemini supports up to 9.5 hours of audio") }
        val wavBytes = Pcm16WavStream.HEADER_BYTES.toLong() + audio.samples.size.toLong() * 2L
        return sanitizedCall { secretStore.useSecret(SecretStore.GEMINI_API_KEY) { key ->
            AudioEgressPolicy.requireAllowed(
                ProviderDescriptor(
                    id = ProviderIds.GEMINI_FLASH_LITE_TRANSCRIPTION,
                    displayName = AppLanguage.text("Google Gemini 3.5 Flash-Lite 音声認識", "Google Gemini 3.5 Flash-Lite transcription"),
                    stage = PipelineStage.TRANSCRIPTION,
                    executionMode = ExecutionMode.CLOUD,
                    dataRequired = setOf(DataKind.AUDIO)
                )
            )
            if (wavBytes <= MAX_INLINE_WAV_BYTES) {
                val output = ByteArrayOutputStream(wavBytes.toInt())
                Pcm16WavStream.write(output, audio.samples, audio.sampleRate)
                val wav = output.toByteArray()
                try {
                    val encoded = Base64.getEncoder().encodeToString(wav)
                    generate(key, JSONArray().put(textPart(prompt)).put(
                        JSONObject().put("inlineData", JSONObject()
                            .put("mimeType", WAV_MIME_TYPE)
                            .put("data", encoded))
                    ), schema, requiresAudioPermission = true)
                } finally {
                    wav.fill(0)
                }
            } else {
                generateFromUploadedFile(key, audio, wavBytes, prompt, schema)
            }
        } }
    }

    private fun generateFromUploadedFile(
        key: CharArray,
        audio: AudioArtifact,
        wavBytes: Long,
        prompt: String,
        schema: JSONObject
    ): GeminiGeneratedContent {
        return GeminiUploadedFileLifecycle.use(
            upload = {
                val uploadUrl = startUpload(key, wavBytes)
                uploadAudio(key, uploadUrl, audio, wavBytes)
            },
            delete = { deleteFile(key, it) }
        ) { _, uploadedUri ->
            val parts = JSONArray()
                .put(textPart(prompt))
                .put(JSONObject().put("fileData", JSONObject()
                    .put("mimeType", WAV_MIME_TYPE)
                    .put("fileUri", uploadedUri)))
            generate(key, parts, schema, requiresAudioPermission = true)
        }
    }

    private fun startUpload(key: CharArray, wavBytes: Long): URL {
        val connection = fixedConnection(URL(UPLOAD_ENDPOINT)).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("x-goog-api-key", String(key))
            setRequestProperty("X-Goog-Upload-Protocol", "resumable")
            setRequestProperty("X-Goog-Upload-Command", "start")
            setRequestProperty("X-Goog-Upload-Header-Content-Length", wavBytes.toString())
            setRequestProperty("X-Goog-Upload-Header-Content-Type", WAV_MIME_TYPE)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        val metadata = JSONObject().put("file", JSONObject().put("display_name", "gijiroku-audio"))
            .toString().toByteArray(StandardCharsets.UTF_8)
        return try {
            connection.setFixedLengthStreamingMode(metadata.size)
            AudioEgressPolicy.withActiveTransmission(connection::disconnect) {
                connection.outputStream.use { it.write(metadata) }
                requireSuccess(connection, AppLanguage.text("Gemini Files APIの開始", "Starting Gemini Files API"))
                val upload = connection.getHeaderField("X-Goog-Upload-URL")
                    ?: throw CloudProviderException(AppLanguage.text("Gemini Files APIがアップロード先を返しませんでした", "Gemini Files API did not return an upload destination"))
                requireFixedOrigin(URL(upload))
            }
        } finally {
            metadata.fill(0)
            connection.disconnect()
        }
    }

    private fun uploadAudio(
        key: CharArray,
        uploadUrl: URL,
        audio: AudioArtifact,
        wavBytes: Long
    ): Pair<String, String> {
        val connection = fixedConnection(uploadUrl).apply {
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(wavBytes)
            setRequestProperty("x-goog-api-key", String(key))
            setRequestProperty("Content-Length", wavBytes.toString())
            setRequestProperty("X-Goog-Upload-Offset", "0")
            setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
        }
        return try {
            AudioEgressPolicy.withActiveTransmission(connection::disconnect) {
                connection.outputStream.buffered().use {
                    Pcm16WavStream.write(it, audio.samples, audio.sampleRate)
                }
                requireSuccess(connection, AppLanguage.text("Gemini Files APIへの音声送信", "Uploading audio to Gemini Files API"))
                val file = JSONObject(readLimited(connection.inputStream)).getJSONObject("file")
                val name = file.getString("name")
                val uri = file.getString("uri")
                require(FILE_NAME.matches(name)) { AppLanguage.text("Gemini Files APIが不正なファイルIDを返しました", "Gemini Files API returned an invalid file ID") }
                requireFixedOrigin(URL(uri))
                name to uri
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun deleteFile(key: CharArray, name: String) {
        require(FILE_NAME.matches(name))
        val connection = fixedConnection(URL("$API_BASE/$name")).apply {
            requestMethod = "DELETE"
            setRequestProperty("x-goog-api-key", String(key))
        }
        try {
            requireSuccess(connection, AppLanguage.text("Gemini Files APIの後始末", "Gemini Files API cleanup"))
            connection.inputStream?.close()
        } finally {
            connection.disconnect()
        }
    }

    private fun generate(
        key: CharArray,
        parts: Any,
        schema: JSONObject,
        requiresAudioPermission: Boolean
    ): GeminiGeneratedContent {
        val contentParts = if (parts is JSONArray) parts else JSONArray().put(parts)
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", contentParts)))
            .put("generationConfig", buildGenerationConfig(schema))
            .toString().toByteArray(StandardCharsets.UTF_8)
        try {
            var retryIndex = 0
            while (true) {
                try {
                    return generateOnce(key, body, requiresAudioPermission)
                } catch (error: RetryableGeminiHttpException) {
                    if (retryIndex >= MAX_GENERATE_RETRIES) {
                        val code = error.apiCode?.let { " / $it" }.orEmpty()
                        throw CloudProviderException(
                            AppLanguage.text("Gemini APIが一時的に利用できません。最大4回試行しました。", "Gemini API is temporarily unavailable after 4 attempts.") +
                                AppLanguage.text("時間を置いて再実行してください（HTTP ${error.status}$code）", "Try again later (HTTP ${error.status}$code)")
                        )
                    }
                    val delayMs = retryDelayMs(
                        retryIndex = retryIndex,
                        serverDelayMs = error.retryAfterMs,
                        jitterMs = Random.nextLong(MAX_RETRY_JITTER_MS + 1)
                    )
                    try {
                        Thread.sleep(delayMs)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw CloudProviderException(AppLanguage.text("Gemini APIの再試行が中断されました", "Gemini API retry interrupted"), interrupted)
                    }
                    retryIndex++
                }
            }
        } finally {
            body.fill(0)
        }
    }

    private fun generateOnce(
        key: CharArray,
        body: ByteArray,
        requiresAudioPermission: Boolean
    ): GeminiGeneratedContent {
        val endpoint = URL("$API_BASE/models/$model:generateContent")
        val connection = fixedConnection(endpoint).apply {
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(body.size)
            setRequestProperty("x-goog-api-key", String(key))
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            val request = {
                connection.outputStream.use { it.write(body) }
                requireSuccess(connection, "Gemini API", signalRetryable = true)
                parseGeneratedContent(readLimited(connection.inputStream))
            }
            if (requiresAudioPermission) {
                AudioEgressPolicy.withActiveTransmission(connection::disconnect, request)
            } else request()
        } finally {
            connection.disconnect()
        }
    }

    private fun fixedConnection(url: URL): HttpURLConnection {
        requireFixedOrigin(url)
        return (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
        }
    }

    private fun requireFixedOrigin(url: URL): URL {
        check(url.protocol == "https" && url.host == GOOGLE_HOST && url.port == -1) {
            AppLanguage.text("許可されていないGemini送信先です", "Gemini destination is not allowed")
        }
        return url
    }

    private fun requireSuccess(
        connection: HttpURLConnection,
        operation: String,
        signalRetryable: Boolean = false
    ) {
        val status = connection.responseCode
        if (status !in 200..299) {
            val retryAfterMs = parseRetryAfterMs(connection.getHeaderField("Retry-After"))
            val apiCode = connection.errorStream?.let { stream ->
                runCatching {
                    JSONObject(readLimited(stream)).optJSONObject("error")
                        ?.optString("status")
                        ?.takeIf { SAFE_API_CODE.matches(it) }
                }.getOrNull()
            }
            if (signalRetryable && isRetryableStatus(status)) {
                throw RetryableGeminiHttpException(status, apiCode, retryAfterMs)
            }
            val category = when (status) {
                400 -> AppLanguage.text("要求形式", "Request format")
                401, 403 -> AppLanguage.text("認証またはAPIキー", "Authentication or API key")
                429 -> AppLanguage.text("利用上限または混雑", "Usage limit or congestion")
                in 500..599 -> AppLanguage.text("Provider障害", "Provider failure")
                404 -> AppLanguage.text("モデル未提供（$model）", "Model unavailable ($model)")
                else -> AppLanguage.text("要求", "Request")
            }
            val code = apiCode?.let { " / $it" }.orEmpty()
            throw CloudProviderException(AppLanguage.text("$operation で${category}エラーが発生しました（HTTP $status$code）", "$operation failed: $category (HTTP $status$code)"))
        }
    }

    private fun <T> sanitizedCall(block: () -> T): T = try {
        block()
    } catch (error: AudioEgressBlockedException) {
        throw error
    } catch (error: CloudProviderException) {
        throw error
    } catch (error: IllegalArgumentException) {
        throw error
    } catch (error: Exception) {
        throw CloudProviderException(AppLanguage.text("Gemini APIへの接続に失敗しました", "Could not connect to Gemini API"), error)
    }

    private fun readLimited(stream: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        stream.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_RESPONSE_BYTES) {
                    throw CloudProviderException(AppLanguage.text("Gemini APIの応答が大きすぎます", "Gemini API response is too large"))
                }
                output.write(buffer, 0, count)
            }
        }
        buffer.fill(0)
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun textPart(text: String) = JSONObject().put("text", text)

    companion object {
        const val API_BASE = "https://generativelanguage.googleapis.com/v1beta"
        const val UPLOAD_ENDPOINT = "https://generativelanguage.googleapis.com/upload/v1beta/files"
        private const val GOOGLE_HOST = "generativelanguage.googleapis.com"
        private const val WAV_MIME_TYPE = "audio/wav"
        private const val MAX_INLINE_WAV_BYTES = 14L * 1024 * 1024
        private const val MAX_AUDIO_DURATION_MS = 9L * 60 * 60 * 1000 + 30L * 60 * 1000
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 10 * 60_000
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val MAX_GENERATE_RETRIES = 3
        private const val MAX_RETRY_DELAY_MS = 8_000L
        private const val MAX_RETRY_JITTER_MS = 250L
        private val FILE_NAME = Regex("files/[a-z0-9-]{1,40}")
        private val SAFE_API_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")

        /** JSON Schema form accepted by the v1beta GenerateContent endpoint. */
        internal fun buildGenerationConfig(schema: JSONObject): JSONObject = JSONObject()
            .put("temperature", 0.1)
            .put("responseMimeType", "application/json")
            .put("responseJsonSchema", schema)

        internal fun isRetryableStatus(status: Int): Boolean =
            status == 408 || status == 429 || status == 500 || status == 502 ||
                status == 503 || status == 504

        internal fun retryDelayMs(retryIndex: Int, serverDelayMs: Long?, jitterMs: Long): Long {
            require(retryIndex in 0 until MAX_GENERATE_RETRIES)
            require(jitterMs >= 0)
            val exponential = 1_000L shl retryIndex
            val requested = maxOf(exponential, serverDelayMs ?: 0L).coerceAtMost(MAX_RETRY_DELAY_MS)
            return requested + jitterMs.coerceAtMost(MAX_RETRY_JITTER_MS)
        }

        private fun parseRetryAfterMs(value: String?): Long? =
            value?.trim()?.toLongOrNull()
                ?.coerceIn(0L, MAX_RETRY_DELAY_MS / 1_000L)
                ?.times(1_000L)

        internal fun parseGeneratedContent(responseJson: String): GeminiGeneratedContent {
            val root = JSONObject(responseJson)
            val candidates = root.optJSONArray("candidates")
                ?: throw CloudProviderException(AppLanguage.text("Gemini APIの応答に候補がありません", "Gemini API response has no candidates"))
            val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw CloudProviderException(AppLanguage.text("Gemini APIの応答に本文がありません", "Gemini API response has no text"))
            val text = buildString {
                for (index in 0 until parts.length()) {
                    parts.optJSONObject(index)?.optString("text")?.takeIf { it.isNotBlank() }?.let(::append)
                }
            }.trim()
            if (text.isEmpty()) throw CloudProviderException(AppLanguage.text("Gemini APIの応答に本文がありません", "Gemini API response has no text"))
            val usage = root.optJSONObject("usageMetadata")
            return GeminiGeneratedContent(
                text = text,
                promptTokens = usage?.optLong("promptTokenCount", -1)?.takeIf { it >= 0 },
                outputTokens = usage?.optLong("candidatesTokenCount", -1)?.takeIf { it >= 0 },
                modelVersion = root.optString("modelVersion").takeIf { it.isNotBlank() }
            )
        }
    }
}

internal data class SpeakerAliasMap(
    val labelToAlias: Map<String, String>,
    val aliasToDisplayName: Map<String, String>
) {
    fun anonymizeText(text: String, speakerDisplayNames: Map<String, String>): String {
        var result = text
        labelToAlias.forEach { (label, alias) ->
            speakerDisplayNames[label]?.trim()?.takeIf { it.isNotEmpty() }?.let { result = result.replace(it, alias) }
            result = result.replace(label, alias)
        }
        return result
    }

    fun reapply(text: String): String {
        var result = text
        aliasToDisplayName.entries.sortedByDescending { it.key.length }.forEach { (alias, display) ->
            result = result.replace(Regex("(?<![A-Z0-9_])${Regex.escape(alias)}(?![A-Z0-9_])"), display)
        }
        return result
    }

    companion object {
        fun create(labels: Collection<String>, speakerDisplayNames: Map<String, String>): SpeakerAliasMap {
            val ordered = (labels + speakerDisplayNames.keys).filter { it.isNotBlank() }.distinct().sorted()
            val labelToAlias = ordered.mapIndexed { index, label -> label to alias(index) }.toMap()
            return SpeakerAliasMap(
                labelToAlias,
                labelToAlias.mapNotNull { (label, alias) ->
                    speakerDisplayNames[label]?.trim()?.takeIf { it.isNotEmpty() }?.let { alias to it }
                }.toMap()
            )
        }

        private fun alias(index: Int): String =
            if (index < 26) "SPEAKER_${('A'.code + index).toChar()}" else "SPEAKER_${index + 1}"
    }
}

class GeminiFlashLiteTranscriptionProvider internal constructor(
    private val service: GeminiContentService,
    private val configuredModel: String = CloudProviderSettings.LEGACY_GEMINI_MODEL
) : PipelineProvider<TranscriptionRequest, TranscriptArtifact> {
    constructor(secretStore: SecretStore, model: String) : this(GeminiRestClient(secretStore, model), model)

    override val descriptor = ProviderDescriptor(
        id = ProviderIds.GEMINI_FLASH_LITE_TRANSCRIPTION,
        displayName = AppLanguage.text("Google Gemini 音声認識", "Google Gemini transcription"),
        stage = PipelineStage.TRANSCRIPTION,
        executionMode = ExecutionMode.CLOUD,
        dataRequired = setOf(DataKind.AUDIO),
        destination = GeminiRestClient.API_BASE
    )

    override suspend fun execute(input: TranscriptionRequest, context: ExecutionContext): ProviderResult<TranscriptArtifact> {
        val startedAt = System.currentTimeMillis()
        val response = service.generateAudio(input.audio, transcriptionPrompt(input.language), transcriptionSchema())
        val segments = parseTranscript(response.text, input.audio.durationMs)
        return ProviderResult(
            output = TranscriptArtifact(
                segments,
                ArtifactProvenance(descriptor.id, System.currentTimeMillis(), context.inputHash)
            ),
            providerId = descriptor.id,
            durationMs = System.currentTimeMillis() - startedAt,
            estimatedCostUsd = response.estimatedCostUsd(audioInput = true),
            destination = descriptor.destination,
            modelId = response.modelVersion ?: configuredModel
        )
    }

    companion object {
        internal fun parseTranscript(json: String, durationMs: Long): List<TranscriptSegment> {
            val values = JSONObject(json).optJSONArray("segments")
                ?: throw CloudProviderException(AppLanguage.text("Gemini音声認識の応答に発話区間がありません", "Gemini transcription response has no segments"))
            val segments = buildList {
                for (index in 0 until values.length()) {
                    val value = values.optJSONObject(index) ?: continue
                    val start = value.optLong("start_ms", -1).coerceAtLeast(0)
                    val end = value.optLong("end_ms", -1).coerceIn(start, durationMs.coerceAtLeast(start))
                    val text = value.optString("text").trim()
                    if (text.isNotEmpty()) add(TranscriptSegment(start, end, text))
                }
            }
            return TranscriptSegmentSanitizer.sanitize(segments, durationMs)
        }

        private fun transcriptionPrompt(language: String) =
            if (language == "en") "Transcribe the audio in English. Split speech into timestamped segments using milliseconds from the start. Do not invent content. Do not add speaker names or numbers."
            else "音声を${language}で文字起こししてください。発話を時刻付き区間へ分け、時刻は音声先頭からのミリ秒で返してください。推測で内容を補わないでください。話者名や話者番号は付けないでください。"

        private fun transcriptionSchema() = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("segments", JSONObject()
                .put("type", "array")
                .put("items", JSONObject().put("type", "object")
                    .put("properties", JSONObject()
                        .put("start_ms", JSONObject().put("type", "integer"))
                        .put("end_ms", JSONObject().put("type", "integer"))
                        .put("text", JSONObject().put("type", "string")))
                    .put("required", JSONArray().put("start_ms").put("end_ms").put("text")))))
            .put("required", JSONArray().put("segments"))
    }
}

class GeminiFlashLiteTextFormattingProvider internal constructor(
    private val service: GeminiContentService,
    private val configuredModel: String = CloudProviderSettings.LEGACY_GEMINI_MODEL
) : PipelineProvider<TextFormattingRequest, TextDocumentArtifact> {
    constructor(secretStore: SecretStore, model: String) : this(GeminiRestClient(secretStore, model), model)

    override val descriptor = ProviderDescriptor(
        id = ProviderIds.GEMINI_FLASH_LITE_FORMATTING,
        displayName = AppLanguage.text("Google Gemini テキスト整形", "Google Gemini text formatting"),
        stage = PipelineStage.TEXT_FORMATTING,
        executionMode = ExecutionMode.CLOUD,
        dataRequired = setOf(DataKind.TRANSCRIPT, DataKind.DIARIZATION),
        destination = GeminiRestClient.API_BASE
    )

    override suspend fun execute(input: TextFormattingRequest, context: ExecutionContext): ProviderResult<TextDocumentArtifact> {
        val startedAt = System.currentTimeMillis()
        val merged = TranscriptSpeakerMerger.merge(input.transcript.segments, input.diarization?.segments.orEmpty())
        val aliases = SpeakerAliasMap.create(merged.mapNotNull { it.speakerLabel }, input.speakerDisplayNames)
        val turns = JSONArray(merged.map { turn -> JSONObject()
            .put("start_ms", turn.startMs)
            .put("end_ms", turn.endMs)
            .put("speaker", turn.speakerLabel?.let(aliases.labelToAlias::get) ?: "SPEAKER_UNKNOWN")
            .put("text", aliases.anonymizeText(turn.text, input.speakerDisplayNames))
        })
        val prompt = if (input.language == "en")
            "Format these meeting turns for readability in English. Start every turn with [MM:SS - MM:SS], converting start_ms and end_ms from milliseconds. Preserve meaning, timestamps, and anonymous speaker IDs. Do not add or remove information. Manually assigned participant names are not included.\n$turns"
        else "次の会議発話を読みやすく整形してください。各発話の先頭にstart_msとend_msをミリ秒から変換した[MM:SS - MM:SS]を付けてください。意味、時刻、匿名話者IDを変えず、情報を追加・削除しないでください。手動参加者名は含まれていません。\n$turns"
        val response = service.generateText(prompt, JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("formatted_text", JSONObject().put("type", "string")))
            .put("required", JSONArray().put("formatted_text")))
        val formatted = TranscriptTimestampParser.restoreTrustedRanges(
            JSONObject(response.text).optString("formatted_text").trim(),
            merged.map { TimeRange(it.startMs, it.endMs) }
        )
        if (formatted.isEmpty()) throw CloudProviderException(AppLanguage.text("Geminiテキスト整形の応答が空です", "Gemini text formatting response is empty"))
        val countLine = input.diarization?.let {
            AppLanguage.text("${if (it.isSpeakerCountSpecified) "指定" else "推定"}話者数: ${it.speakerCount}人", "${if (it.isSpeakerCountSpecified) "Specified" else "Estimated"} speakers: ${it.speakerCount}")
        }
        val output = listOfNotNull(countLine, aliases.reapply(formatted)).joinToString("\n\n")
        return ProviderResult(
            TextDocumentArtifact(output, ArtifactProvenance(descriptor.id, System.currentTimeMillis(), context.inputHash)),
            descriptor.id,
            System.currentTimeMillis() - startedAt,
            response.estimatedCostUsd(audioInput = false),
            descriptor.destination,
            response.modelVersion ?: configuredModel
        )
    }
}

class GeminiFlashLiteSummaryProvider internal constructor(
    private val service: GeminiContentService,
    private val configuredModel: String = CloudProviderSettings.LEGACY_GEMINI_MODEL
) : PipelineProvider<SummaryRequest, MeetingSummaryArtifact> {
    constructor(secretStore: SecretStore, model: String) : this(GeminiRestClient(secretStore, model), model)

    override val descriptor = ProviderDescriptor(
        id = ProviderIds.GEMINI_FLASH_LITE_SUMMARY,
        displayName = AppLanguage.text("Google Gemini 構造化要約", "Google Gemini structured summary"),
        stage = PipelineStage.SUMMARIZATION,
        executionMode = ExecutionMode.CLOUD,
        dataRequired = setOf(DataKind.STRUCTURED_TRANSCRIPT),
        destination = GeminiRestClient.API_BASE
    )

    override suspend fun execute(input: SummaryRequest, context: ExecutionContext): ProviderResult<MeetingSummaryArtifact> {
        val startedAt = System.currentTimeMillis()
        val aliases = SpeakerAliasMap.create(input.sourceTurns.mapNotNull { it.speakerLabel }, input.speakerDisplayNames)
        val sourceMap = input.sourceTurns.associateBy { it.turnId }
        val source = if (input.sourceTurns.isNotEmpty()) {
            JSONArray(input.sourceTurns.map { turn -> JSONObject()
                .put("turn_id", turn.turnId)
                .put("start_ms", turn.startMs)
                .put("end_ms", turn.endMs)
                .put("speaker", turn.speakerLabel?.let(aliases.labelToAlias::get) ?: "SPEAKER_UNKNOWN")
                .put("text", aliases.anonymizeText(turn.text, input.speakerDisplayNames))
            }).toString()
        } else {
            aliases.anonymizeText(input.document.text, input.speakerDisplayNames)
        }
        val prompt = MeetingMinutesTemplates.systemPrompt(
            input.language,
            input.minutesTemplateId,
            input.customTemplateInstructions
        ) + (if (input.language == "en") "\nManually assigned participant names are not included.\nSOURCE_TURNS:\n$source"
             else "\n手動参加者名は含まれていません。\nSOURCE_TURNS:\n$source")
        val response = service.generateText(prompt, summarySchema())
        val parsed = MeetingSummaryJson.parse(response.text, sourceMap)
        val restored = parsed.copy(
            title = aliases.reapply(parsed.title),
            purpose = aliases.reapply(parsed.purpose),
            topics = parsed.topics.map { it.copy(
                topic = aliases.reapply(it.topic),
                discussion = aliases.reapply(it.discussion),
                speakers = it.speakers.map(aliases::reapply)
            ) },
            decisions = parsed.decisions.map(aliases::reapply),
            actionItems = parsed.actionItems.map { it.copy(
                owner = it.owner?.let(aliases::reapply),
                description = aliases.reapply(it.description)
            ) },
            openQuestions = parsed.openQuestions.map(aliases::reapply),
            importantInformation = parsed.importantInformation.map(aliases::reapply),
            summary = aliases.reapply(parsed.summary),
            templateId = MeetingMinutesTemplates.find(input.minutesTemplateId).id,
            provenance = ArtifactProvenance(descriptor.id, System.currentTimeMillis(), context.inputHash)
        )
        return ProviderResult(
            restored,
            descriptor.id,
            System.currentTimeMillis() - startedAt,
            response.estimatedCostUsd(audioInput = false),
            descriptor.destination,
            response.modelVersion ?: configuredModel
        )
    }

    companion object {
        const val PROMPT_VERSION = "gemini-flash-lite-summary-v2"

        private fun summarySchema(): JSONObject {
            val nullableString = JSONArray().put("string").put("null")
            val action = JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("owner", JSONObject().put("type", nullableString))
                    .put("due_date", JSONObject().put("type", nullableString))
                    .put("description", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("owner").put("due_date").put("description"))
            val evidence = JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("field", JSONObject().put("type", "string"))
                    .put("item_index", JSONObject().put("type", JSONArray().put("integer").put("null")))
                    .put("source_turn_ids", stringArray()))
                .put("required", JSONArray().put("field").put("item_index").put("source_turn_ids"))
            val topic = JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("topic", JSONObject().put("type", "string"))
                    .put("discussion", JSONObject().put("type", "string"))
                    .put("speakers", stringArray()))
                .put("required", JSONArray().put("topic").put("discussion").put("speakers"))
            return JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("title", JSONObject().put("type", "string"))
                    .put("purpose", JSONObject().put("type", "string"))
                    .put("topics", JSONObject().put("type", "array").put("items", topic))
                    .put("decisions", stringArray())
                    .put("action_items", JSONObject().put("type", "array").put("items", action))
                    .put("open_questions", stringArray())
                    .put("important_information", stringArray())
                    .put("summary", JSONObject().put("type", "string"))
                    .put("evidence", JSONObject().put("type", "array").put("items", evidence)))
                .put("required", JSONArray().put("title").put("purpose").put("topics")
                    .put("decisions").put("action_items").put("open_questions")
                    .put("important_information").put("summary").put("evidence"))
        }

        private fun stringArray() = JSONObject().put("type", "array")
            .put("items", JSONObject().put("type", "string"))
    }
}
