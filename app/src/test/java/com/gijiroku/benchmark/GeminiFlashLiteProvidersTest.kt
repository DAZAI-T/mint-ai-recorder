package com.gijiroku.benchmark

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiFlashLiteProvidersTest {
    private val provenance = ArtifactProvenance("test", 1, "hash")

    @Test
    fun englishFormattingUsesEnglishPromptAndKeepsParticipantNamesLocal() = runBlocking {
        val service = CapturingService("""{"formatted_text":"SPEAKER_A: Hello"}""")
        val provider = GeminiFlashLiteTextFormattingProvider(service)
        provider.execute(
            TextFormattingRequest(
                TranscriptArtifact(listOf(TranscriptSegment(0, 1_000, "Hello")), provenance),
                DiarizationArtifact(listOf(SpeakerSegment(0, 1_000, "speaker1")), 1, false, provenance),
                mapOf("speaker1" to "PrivateName"), language = "en"
            ), ExecutionContext("en", "hash")
        )
        assertTrue(service.lastPrompt.contains("in English"))
        assertFalse(service.lastPrompt.contains("PrivateName"))
        assertFalse(Regex("[ぁ-んァ-ヶ一-龠]").containsMatchIn(service.lastPrompt))
    }

    @Test
    fun generatedContentParsesTextAndUsageWithoutExposingResponseEnvelope() {
        val parsed = GeminiRestClient.parseGeneratedContent(
            """{"candidates":[{"content":{"parts":[{"text":"{\"ok\":true}"}]}}],"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":20},"modelVersion":"gemini-3.5-flash"}"""
        )
        assertEquals("{\"ok\":true}", parsed.text)
        assertEquals(100L, parsed.promptTokens)
        assertEquals(20L, parsed.outputTokens)
        assertEquals("gemini-3.5-flash", parsed.modelVersion)
    }

    @Test
    fun generationConfigUsesGenerateContentJsonSchemaInsteadOfOpenApiSchema() {
        val schema = JSONObject().put("type", "object")
        val config = GeminiRestClient.buildGenerationConfig(schema)
        assertFalse(config.has("responseSchema"))
        assertFalse(config.has("responseFormat"))
        assertEquals("application/json", config.getString("responseMimeType"))
        assertEquals(schema.toString(), config.getJSONObject("responseJsonSchema").toString())
    }

    @Test
    fun retriesOnlyTransientStatusesWithBoundedExponentialBackoff() {
        assertTrue(GeminiRestClient.isRetryableStatus(408))
        assertTrue(GeminiRestClient.isRetryableStatus(429))
        assertTrue(GeminiRestClient.isRetryableStatus(503))
        assertFalse(GeminiRestClient.isRetryableStatus(400))
        assertFalse(GeminiRestClient.isRetryableStatus(403))
        assertEquals(1_100L, GeminiRestClient.retryDelayMs(0, null, 100))
        assertEquals(2_100L, GeminiRestClient.retryDelayMs(1, null, 100))
        assertEquals(4_100L, GeminiRestClient.retryDelayMs(2, null, 100))
        assertEquals(8_250L, GeminiRestClient.retryDelayMs(2, 60_000, 999))
    }

    @Test
    fun transcriptionNormalizesAndClampsSegmentsToAudioDuration() {
        val segments = GeminiFlashLiteTranscriptionProvider.parseTranscript(
            """{"segments":[{"start_ms":-10,"end_ms":900,"text":" こんにちは "},{"start_ms":900,"end_ms":9999,"text":"次です"}]}""",
            1_500
        )
        assertEquals(TranscriptSegment(0, 900, "こんにちは"), segments[0])
        assertEquals(TranscriptSegment(900, 1_500, "次です"), segments[1])
    }

    @Test
    fun transcriptionRepairsPointTimestampsForSpeakerSeparation() {
        val segments = GeminiFlashLiteTranscriptionProvider.parseTranscript(
            """{"segments":[{"start_ms":791009,"end_ms":791009,"text":"first"},{"start_ms":794549,"end_ms":794549,"text":"second"}]}""",
            800_000
        )

        assertEquals(TranscriptSegment(791_009, 792_509, "first"), segments[0])
        assertEquals(TranscriptSegment(794_549, 796_049, "second"), segments[1])
    }

    @Test
    fun pricingUsesReportedConcreteModelAndRejectsUnknownLatestTarget() {
        val known = GeminiGeneratedContent("{}", 100, 20, "gemini-3.5-flash-lite")
        val unknown = GeminiGeneratedContent("{}", 100, 20, "gemini-future-flash")
        assertEquals(0.00008, known.estimatedCostUsd(audioInput = true)!!, 0.0000001)
        assertEquals(null, unknown.estimatedCostUsd(audioInput = true))
    }

    @Test
    fun fastProviderUsesCurrentStableFlashLiteModel() {
        assertEquals("gemini-3.5-flash-lite", CloudProviderSettings.GEMINI_FLASH_MODEL)
    }

    @Test
    fun formattingNeverSendsManualParticipantNameAndReappliesItLocally() = runBlocking {
        val service = CapturingService("""{"formatted_text":"[00:00] SPEAKER_A: こんにちは"}""")
        val provider = GeminiFlashLiteTextFormattingProvider(service)
        val transcript = TranscriptArtifact(listOf(TranscriptSegment(0, 1_000, "こんにちは")), provenance)
        val diarization = DiarizationArtifact(
            listOf(SpeakerSegment(0, 1_000, "話者A")), 1, false, provenance
        )

        val result = provider.execute(
            TextFormattingRequest(transcript, diarization, mapOf("話者A" to "田中")),
            ExecutionContext("run", "hash")
        )

        assertFalse(service.lastPrompt.contains("田中"))
        assertTrue(service.lastPrompt.contains("SPEAKER_A"))
        assertTrue(result.output.text.contains("田中: こんにちは"))
        assertEquals(CloudProviderSettings.GEMINI_FLASH_MODEL, result.modelId)
    }

    @Test
    fun summarySendsOnlyAliasesAndKeepsOnlyRealEvidence() = runBlocking {
        val response = """{
          "purpose":"SPEAKER_Aの確認",
          "decisions":["決定"],
          "action_items":[{"owner":"SPEAKER_A","due_date":null,"description":"対応"}],
          "open_questions":[],
          "summary":"SPEAKER_Aが確認した",
          "evidence":[
            {"field":"summary","item_index":null,"source_turn_ids":["t1"]},
            {"field":"decisions","item_index":0,"source_turn_ids":["missing"]}
          ]
        }""".trimIndent()
        val service = CapturingService(response)
        val provider = GeminiFlashLiteSummaryProvider(service)
        val turn = SummarySourceTurn("t1", 0, 1_000, "話者A", "田中が確認します")

        val result = provider.execute(
            SummaryRequest(
                TextDocumentArtifact("田中: 確認します", provenance),
                sourceTurns = listOf(turn),
                speakerDisplayNames = mapOf("話者A" to "田中")
            ),
            ExecutionContext("run", "hash")
        )

        assertFalse(service.lastPrompt.contains("田中"))
        assertTrue(service.lastPrompt.contains("SPEAKER_A"))
        assertEquals("田中", result.output.actionItems.single().owner)
        assertEquals("田中が確認した", result.output.summary)
        assertEquals(listOf("t1"), result.output.evidence.single().sourceTurnIds)
    }

    @Test
    fun cloudStagesDeclareOnlyTheirRequiredData() {
        val service = CapturingService("{}")
        assertEquals(setOf(DataKind.AUDIO), GeminiFlashLiteTranscriptionProvider(service).descriptor.dataRequired)
        assertFalse(GeminiFlashLiteTextFormattingProvider(service).descriptor.dataRequired.contains(DataKind.AUDIO))
        assertFalse(GeminiFlashLiteSummaryProvider(service).descriptor.dataRequired.contains(DataKind.AUDIO))
    }

    @Test
    fun audioProtectionBlocksGeminiAudioButNotGeminiTextStages() {
        val service = CapturingService("{}")
        AudioEgressPolicy.block()
        var blocked = false
        try {
            AudioEgressPolicy.requireAllowed(GeminiFlashLiteTranscriptionProvider(service).descriptor)
        } catch (_: AudioEgressBlockedException) {
            blocked = true
        }
        assertTrue(blocked)
        AudioEgressPolicy.requireAllowed(GeminiFlashLiteTextFormattingProvider(service).descriptor)
        AudioEgressPolicy.requireAllowed(GeminiFlashLiteSummaryProvider(service).descriptor)
    }

    @Test
    fun uploadedFileIsDeletedAfterSuccessAndFailure() {
        val deleted = mutableListOf<String>()
        val value = GeminiUploadedFileLifecycle.use(
            upload = { "files/verified-1" to "https://generativelanguage.googleapis.com/v1beta/files/verified-1" },
            delete = deleted::add
        ) { _, _ -> "ok" }
        assertEquals("ok", value)
        assertEquals(listOf("files/verified-1"), deleted)

        runCatching {
            GeminiUploadedFileLifecycle.use(
                upload = { "files/verified-2" to "https://generativelanguage.googleapis.com/v1beta/files/verified-2" },
                delete = deleted::add
            ) { _, _ -> error("generation failed") }
        }
        assertEquals(listOf("files/verified-1", "files/verified-2"), deleted)
    }

    private class CapturingService(private val response: String) : GeminiContentService {
        var lastPrompt: String = ""

        override fun generateText(prompt: String, schema: JSONObject): GeminiGeneratedContent {
            lastPrompt = prompt
            return GeminiGeneratedContent(response, 10, 5)
        }

        override fun generateAudio(
            audio: AudioArtifact,
            prompt: String,
            schema: JSONObject
        ): GeminiGeneratedContent {
            lastPrompt = prompt
            return GeminiGeneratedContent(response, 10, 5)
        }
    }
}
