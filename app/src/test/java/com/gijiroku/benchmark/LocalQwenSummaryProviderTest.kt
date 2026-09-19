package com.gijiroku.benchmark

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalQwenSummaryProviderTest {
    @Test
    fun englishRequestsUseEnglishSystemAndUserPrompts() = runBlocking {
        val generator = FakeGenerator(tokenLimitByCharacters = 100_000)
        val provider = LocalQwenSummaryProvider(generatorFactory = { generator })
        provider.execute(
            SummaryRequest(
                document = TextDocumentArtifact("Discuss the launch", ArtifactProvenance("test", 1, "hash")),
                language = "en",
                sourceTurns = listOf(SummarySourceTurn("t000001", 10, 20, "SPEAKER_A", "Discuss the launch"))
            ), ExecutionContext("english", "english-input")
        )
        assertFalse(Regex("[ぁ-んァ-ヶ一-龠]").containsMatchIn(generator.lastSystem))
        assertFalse(Regex("[ぁ-んァ-ヶ一-龠]").containsMatchIn(generator.lastUser))
        assertTrue(generator.lastSystem.contains("Output language: English"))
        assertTrue(generator.lastUser.contains("t000001"))
    }

    @Test
    fun parsesEvidenceAndDropsInventedTurnIds() {
        val turn = SummarySourceTurn("t000001", 1_000, 2_000, "話者A", "公開する")
        val parsed = LocalQwenSummaryProvider.parseGeneratedSummary(
            """
            <think>この内容は利用者へ返さない</think>
            ```json
            {"purpose":"計画確認","decisions":["金曜に公開"],"action_items":[{"owner":"田中","due_date":null,"description":"確認する"}],"open_questions":[],"summary":"公開計画を確認した。","evidence":[{"field":"decisions","item_index":0,"source_turn_ids":["t000001","t999999"]}]}
            ```
            """.trimIndent(),
            mapOf(turn.turnId to turn)
        )

        assertEquals("計画確認", parsed.purpose)
        assertEquals(listOf("金曜に公開"), parsed.decisions)
        assertEquals("田中", parsed.actionItems.single().owner)
        assertNull(parsed.actionItems.single().dueDate)
        assertEquals(listOf("t000001"), parsed.evidence.single().sourceTurnIds)
        assertEquals(listOf(TimeRange(1_000, 2_000)), parsed.evidence.single().sourceRanges)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsResponseWithoutJsonObject() {
        LocalQwenSummaryProvider.parseGeneratedSummary("要約できませんでした")
    }

    @Test
    fun executesShortMeetingLocallyWithCommonArtifactSchema() = runBlocking {
        val generator = FakeGenerator(tokenLimitByCharacters = 100_000)
        val provider = LocalQwenSummaryProvider(generatorFactory = { generator })
        val result = provider.execute(
            SummaryRequest(
                document = TextDocumentArtifact("話者1: テスト", ArtifactProvenance("test", 1, "hash")),
                sourceTurns = listOf(SummarySourceTurn("t000001", 10, 20, "話者A", "テスト"))
            ),
            ExecutionContext("run", "input-hash")
        )

        assertEquals(ProviderIds.LOCAL_QWEN3_SUMMARY, result.providerId)
        assertEquals(ExecutionMode.LOCAL, provider.descriptor.executionMode)
        assertNull(provider.descriptor.destination)
        assertEquals("概要", result.output.summary)
        assertEquals("input-hash", result.output.provenance.inputHash)
        assertTrue(generator.lastSystem.contains("推測してはいけません"))
        assertTrue(generator.lastUser.startsWith("/no_think"))
        assertTrue(generator.lastUser.contains("t000001"))
        assertFalse(generator.lastUser.contains("api.openai.com"))
    }

    @Test
    fun longMeetingUsesHierarchyAndReusesEveryCheckpoint() = runBlocking {
        val checkpoints = MemoryCheckpoints()
        val turns = (1..3).map { index ->
            SummarySourceTurn(
                turnId = "t${index.toString().padStart(6, '0')}",
                startMs = index * 1_000L,
                endMs = index * 1_000L + 900,
                speakerLabel = "話者$index",
                text = "あ".repeat(3_400)
            )
        }
        val firstGenerator = FakeGenerator(tokenLimitByCharacters = 1)
        val provider = LocalQwenSummaryProvider(generatorFactory = { firstGenerator })
        val request = SummaryRequest(
            TextDocumentArtifact("長時間会議", ArtifactProvenance("test", 1, "hash")),
            sourceTurns = turns
        )

        val first = provider.executeWithCheckpoints(
            request,
            ExecutionContext("first", "long-input"),
            checkpoints
        )
        assertTrue(firstGenerator.generateCount >= 3)
        assertTrue(checkpoints.values.isNotEmpty())
        assertTrue(first.output.evidence.isNotEmpty())

        val secondGenerator = FakeGenerator(tokenLimitByCharacters = 1)
        val resumedProvider = LocalQwenSummaryProvider(generatorFactory = { secondGenerator })
        val resumed = resumedProvider.executeWithCheckpoints(
            request,
            ExecutionContext("second", "long-input"),
            checkpoints
        )

        assertEquals(0, secondGenerator.generateCount)
        assertEquals(first.output.summary, resumed.output.summary)
        assertEquals(first.output.evidence, resumed.output.evidence)
    }

    private class FakeGenerator(private val tokenLimitByCharacters: Int) : LocalTextGenerator {
        var generateCount = 0
        var lastSystem = ""
        var lastUser = ""

        override fun countTokens(systemPrompt: String, userPrompt: String): Int =
            if (tokenLimitByCharacters == 1) userPrompt.length else userPrompt.length / 4

        override fun generate(systemPrompt: String, userPrompt: String, maxOutputTokens: Int): String {
            generateCount += 1
            lastSystem = systemPrompt
            lastUser = userPrompt
            val ids = Regex("t\\d{6}(?:p\\d{3})?").findAll(userPrompt).map { it.value }.distinct().toList()
            return """{"purpose":"目的","decisions":[],"action_items":[],"open_questions":[],"summary":"概要","evidence":[{"field":"summary","item_index":null,"source_turn_ids":${JSONArray(ids)}}]}"""
        }
    }

    private class MemoryCheckpoints : SummaryCheckpointStore {
        val values = linkedMapOf<String, MeetingSummaryArtifact>()
        override fun load(checkpointKey: String): MeetingSummaryArtifact? = values[checkpointKey]
        override fun save(
            checkpointKey: String,
            artifact: MeetingSummaryArtifact,
            sourceRanges: List<TimeRange>
        ) {
            values[checkpointKey] = artifact
        }
    }
}
