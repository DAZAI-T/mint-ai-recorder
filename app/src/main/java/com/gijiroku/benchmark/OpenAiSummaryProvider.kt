package com.gijiroku.benchmark

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Produces the common meeting-summary schema with OpenAI's Responses API. */
class OpenAiSummaryProvider(
    private val secretStore: SecretStore,
    private val model: String
) : PipelineProvider<SummaryRequest, MeetingSummaryArtifact> {

    private val validatedModel = CloudModelNameValidator.normalize(CloudModelProvider.OPENAI, model)

    override val descriptor = ProviderDescriptor(
        id = ProviderIds.OPENAI_RESPONSES_SUMMARY,
        displayName = AppLanguage.text("OpenAI 構造化要約", "OpenAI structured summary"),
        stage = PipelineStage.SUMMARIZATION,
        executionMode = ExecutionMode.CLOUD,
        dataRequired = setOf(DataKind.STRUCTURED_TRANSCRIPT),
        destination = RESPONSES_ENDPOINT
    )

    override suspend fun execute(
        input: SummaryRequest,
        context: ExecutionContext
    ): ProviderResult<MeetingSummaryArtifact> {
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
        val response = requestSummary(source, input)
        val parsed = parseResponse(response, sourceMap)
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
            templateId = MeetingMinutesTemplates.find(input.minutesTemplateId).id
        )
        val duration = System.currentTimeMillis() - startedAt
        return ProviderResult(
            output = restored.copy(
                provenance = ArtifactProvenance(
                    providerId = descriptor.id,
                    createdAtEpochMs = System.currentTimeMillis(),
                    inputHash = context.inputHash
                )
            ),
            providerId = descriptor.id,
            durationMs = duration,
            // Token usage and model pricing vary; keep the estimate unknown rather than misleading.
            estimatedCostUsd = null,
            destination = descriptor.destination,
            modelId = validatedModel
        )
    }

    private fun requestSummary(transcript: String, input: SummaryRequest): String {
        val url = URL(RESPONSES_ENDPOINT)
        check(url.protocol == "https" && url.host == OPENAI_HOST) { AppLanguage.text("許可されていない送信先です", "Destination is not allowed") }
        val body = buildRequest(transcript, input).toString().toByteArray(StandardCharsets.UTF_8)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setFixedLengthStreamingMode(body.size)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            secretStore.useSecret(SecretStore.OPENAI_API_KEY) { secret ->
                connection.setRequestProperty("Authorization", "Bearer ${String(secret)}")
                connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
                if (status !in 200..299) {
                    connection.errorStream?.close()
                    throw CloudProviderException(AppLanguage.text("OpenAI APIでエラーが発生しました（HTTP $status）", "OpenAI API error (HTTP $status)"))
                }
                connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            }
        } catch (e: CloudProviderException) {
            throw e
        } catch (e: Exception) {
            throw CloudProviderException(AppLanguage.text("OpenAI APIへの接続に失敗しました", "Could not connect to OpenAI API"), e)
        } finally {
            body.fill(0)
            connection.disconnect()
        }
    }

    private fun buildRequest(transcript: String, input: SummaryRequest): JSONObject {
        val nullableString = JSONArray().put("string").put("null")
        val actionItemSchema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put("properties", JSONObject()
                .put("owner", JSONObject().put("type", nullableString))
                .put("due_date", JSONObject().put("type", JSONArray().put("string").put("null")))
                .put("description", JSONObject().put("type", "string")))
            .put("required", JSONArray().put("owner").put("due_date").put("description"))
        val topicSchema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put("properties", JSONObject()
                .put("topic", JSONObject().put("type", "string"))
                .put("discussion", JSONObject().put("type", "string"))
                .put("speakers", stringArraySchema()))
            .put("required", JSONArray().put("topic").put("discussion").put("speakers"))
        val evidenceSchema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put("properties", JSONObject()
                .put("field", JSONObject().put("type", "string"))
                .put("item_index", JSONObject().put("type", JSONArray().put("integer").put("null")))
                .put("source_turn_ids", stringArraySchema()))
            .put("required", JSONArray().put("field").put("item_index").put("source_turn_ids"))
        val schema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put("properties", JSONObject()
                .put("title", JSONObject().put("type", "string"))
                .put("purpose", JSONObject().put("type", "string"))
                .put("topics", JSONObject().put("type", "array").put("items", topicSchema))
                .put("decisions", stringArraySchema())
                .put("action_items", JSONObject().put("type", "array").put("items", actionItemSchema))
                .put("open_questions", stringArraySchema())
                .put("important_information", stringArraySchema())
                .put("summary", JSONObject().put("type", "string"))
                .put("evidence", JSONObject().put("type", "array").put("items", evidenceSchema)))
            .put("required", JSONArray()
                .put("title")
                .put("purpose")
                .put("topics")
                .put("decisions")
                .put("action_items")
                .put("open_questions")
                .put("important_information")
                .put("summary")
                .put("evidence"))

        return JSONObject()
            .put("model", validatedModel)
            .put("store", false)
            .put("instructions", MeetingMinutesTemplates.systemPrompt(
                input.language,
                input.minutesTemplateId,
                input.customTemplateInstructions
            ))
            .put("input", transcript)
            .put("max_output_tokens", 2_000)
            .put("text", JSONObject().put("format", JSONObject()
                .put("type", "json_schema")
                .put("name", "meeting_summary")
                .put("strict", true)
                .put("schema", schema)))
    }

    private fun stringArraySchema() = JSONObject()
        .put("type", "array")
        .put("items", JSONObject().put("type", "string"))

    companion object {
        const val PROMPT_VERSION = "openai-summary-v2"
        const val RESPONSES_ENDPOINT = "https://api.openai.com/v1/responses"
        private const val OPENAI_HOST = "api.openai.com"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 10 * 60_000

        internal fun parseResponse(
            responseJson: String,
            sourceTurns: Map<String, SummarySourceTurn> = emptyMap()
        ): MeetingSummaryArtifact {
            val response = JSONObject(responseJson)
            val output = response.optJSONArray("output")
                ?: throw CloudProviderException(AppLanguage.text("OpenAI APIの応答に要約がありません", "OpenAI API response has no summary"))
            var structuredText: String? = null
            for (outputIndex in 0 until output.length()) {
                val content = output.optJSONObject(outputIndex)?.optJSONArray("content") ?: continue
                for (contentIndex in 0 until content.length()) {
                    val item = content.optJSONObject(contentIndex) ?: continue
                    if (item.optString("type") == "output_text") {
                        structuredText = item.optString("text").takeIf { it.isNotBlank() }
                        if (structuredText != null) break
                    }
                }
                if (structuredText != null) break
            }
            val summary = structuredText
                ?: throw CloudProviderException(AppLanguage.text("OpenAI APIの応答に要約がありません", "OpenAI API response has no summary"))
            return MeetingSummaryJson.parse(summary, sourceTurns)
        }
    }
}
