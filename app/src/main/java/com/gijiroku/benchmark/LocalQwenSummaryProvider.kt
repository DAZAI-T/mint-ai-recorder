package com.gijiroku.benchmark

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal interface LocalTextGenerator : AutoCloseable {
    fun countTokens(systemPrompt: String, userPrompt: String): Int
    fun generate(systemPrompt: String, userPrompt: String, maxOutputTokens: Int): String
    override fun close() = Unit
}

internal interface SummaryCheckpointStore {
    fun load(checkpointKey: String): MeetingSummaryArtifact?
    fun save(checkpointKey: String, artifact: MeetingSummaryArtifact, sourceRanges: List<TimeRange>)
}

private object NoOpSummaryCheckpointStore : SummaryCheckpointStore {
    override fun load(checkpointKey: String): MeetingSummaryArtifact? = null
    override fun save(checkpointKey: String, artifact: MeetingSummaryArtifact, sourceRanges: List<TimeRange>) = Unit
}

/** Local Qwen3-4B provider with exact-token, resumable hierarchical summarization. */
class LocalQwenSummaryProvider internal constructor(
    private val generatorFactory: () -> LocalTextGenerator,
    private val appContext: android.content.Context? = null
) : PipelineProvider<SummaryRequest, MeetingSummaryArtifact> {

    constructor(modelPath: String, appContext: android.content.Context? = null) :
        this({ LlamaSessionTextGenerator(modelPath) }, appContext)

    override val descriptor = ProviderDescriptor(
        id = ProviderIds.LOCAL_QWEN3_SUMMARY,
        displayName = AppLanguage.text("ローカル Qwen3-4B 構造化要約", "Local Qwen3-4B structured summary"),
        stage = PipelineStage.SUMMARIZATION,
        executionMode = ExecutionMode.LOCAL,
        dataRequired = setOf(DataKind.STRUCTURED_TRANSCRIPT)
    )

    override suspend fun execute(
        input: SummaryRequest,
        context: ExecutionContext
    ): ProviderResult<MeetingSummaryArtifact> = executeWithCheckpoints(input, context, NoOpSummaryCheckpointStore)

    internal suspend fun executeWithCheckpoints(
        input: SummaryRequest,
        context: ExecutionContext,
        checkpoints: SummaryCheckpointStore
    ): ProviderResult<MeetingSummaryArtifact> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val initialTurns = input.sourceTurns.ifEmpty {
            listOf(SummarySourceTurn("t000001", 0, 0, null, input.document.text))
        }.filter { it.text.isNotBlank() }
        check(initialTurns.isNotEmpty()) { AppLanguage.text("要約できる発話がありません", "No speech to summarize") }

        val result = executeWithModelGate {
            generatorFactory().use { generator ->
                summarize(input, initialTurns, generator, checkpoints)
            }
        }
        ProviderResult(
            output = result.copy(
                provenance = ArtifactProvenance(
                    providerId = descriptor.id,
                    createdAtEpochMs = System.currentTimeMillis(),
                    inputHash = context.inputHash
                ),
                templateId = MeetingMinutesTemplates.find(input.minutesTemplateId).id
            ),
            providerId = descriptor.id,
            durationMs = System.currentTimeMillis() - startedAt
        )
    }

    private fun <T> executeWithModelGate(block: () -> T): T = appContext?.let {
        LocalModelRuntimeCoordinator.withExclusiveModel(it, LocalModelKind.QWEN_SUMMARY, block)
    } ?: block()

    private fun summarize(
        request: SummaryRequest,
        originalTurns: List<SummarySourceTurn>,
        generator: LocalTextGenerator,
        checkpoints: SummaryCheckpointStore
    ): MeetingSummaryArtifact {
        val system = systemPrompt(
            request.language,
            request.minutesTemplateId,
            request.customTemplateInstructions
        )
        val sourceTurnMap = linkedMapOf<String, SummarySourceTurn>()
        originalTurns.forEach { sourceTurnMap[it.turnId] = it }
        val fullPrompt = turnsPrompt(originalTurns, final = true, request.language)
        if (fits(generator, system, fullPrompt, FINAL_OUTPUT_TOKENS)) {
            return generateSummary(generator, system, fullPrompt, FINAL_OUTPUT_TOKENS, sourceTurnMap)
        }

        val expandedTurns = originalTurns.flatMap { turn ->
            splitTurnIfNeeded(turn, generator, system, request.language).also { parts ->
                parts.forEach { sourceTurnMap[it.turnId] = it }
            }
        }
        val leafGroups = groupTurns(expandedTurns, generator, system, request.language)
        var nodes = leafGroups.map { group ->
            checkpointedSummary(
                generator = generator,
                system = system,
                prompt = turnsPrompt(group, final = false, request.language),
                maxOutputTokens = PARTIAL_OUTPUT_TOKENS,
                ranges = group.map { TimeRange(it.startMs, it.endMs) },
                sourceTurns = sourceTurnMap,
                checkpoints = checkpoints,
                kind = "leaf"
            )
        }

        var level = 1
        while (nodes.size > 1) {
            val finalPrompt = nodesPrompt(nodes, final = true, request.language)
            if (fits(generator, system, finalPrompt, FINAL_OUTPUT_TOKENS)) {
                return checkpointedSummary(
                    generator = generator,
                    system = system,
                    prompt = finalPrompt,
                    maxOutputTokens = FINAL_OUTPUT_TOKENS,
                    ranges = nodes.flatMap(SummaryNode::sourceRanges).distinct(),
                    sourceTurns = sourceTurnMap,
                    checkpoints = checkpoints,
                    kind = "final-level-$level"
                ).artifact
            }
            val groups = groupNodes(nodes, generator, system, request.language)
            check(groups.size < nodes.size) { AppLanguage.text("部分要約をコンテキスト内で統合できませんでした", "Could not merge partial summaries within context limit") }
            nodes = groups.map { group ->
                checkpointedSummary(
                    generator = generator,
                    system = system,
                    prompt = nodesPrompt(group, final = false, request.language),
                    maxOutputTokens = PARTIAL_OUTPUT_TOKENS,
                    ranges = group.flatMap(SummaryNode::sourceRanges).distinct(),
                    sourceTurns = sourceTurnMap,
                    checkpoints = checkpoints,
                    kind = "level-$level"
                )
            }
            level += 1
        }
        return nodes.single().artifact
    }

    private fun checkpointedSummary(
        generator: LocalTextGenerator,
        system: String,
        prompt: String,
        maxOutputTokens: Int,
        ranges: List<TimeRange>,
        sourceTurns: Map<String, SummarySourceTurn>,
        checkpoints: SummaryCheckpointStore,
        kind: String
    ): SummaryNode {
        val key = ArtifactHasher.sha256(
            "$PROMPT_VERSION\u001f$kind\u001f$maxOutputTokens\u001f$system\u001f$prompt"
        )
        val artifact = checkpoints.load(key) ?: generateSummary(
            generator, system, prompt, maxOutputTokens, sourceTurns
        ).also { checkpoints.save(key, it, ranges) }
        return SummaryNode(artifact, ranges)
    }

    private fun generateSummary(
        generator: LocalTextGenerator,
        system: String,
        prompt: String,
        maxOutputTokens: Int,
        sourceTurns: Map<String, SummarySourceTurn>
    ): MeetingSummaryArtifact {
        check(fits(generator, system, prompt, maxOutputTokens)) { AppLanguage.text("ローカルQwenの入力予算を超えています", "Local Qwen input budget exceeded") }
        appContext?.let {
            LocalModelRuntimeCoordinator.requireSafeToContinue(it, LocalModelKind.QWEN_SUMMARY)
        }
        return parseGeneratedSummary(generator.generate(system, prompt, maxOutputTokens), sourceTurns)
    }

    private fun splitTurnIfNeeded(
        turn: SummarySourceTurn,
        generator: LocalTextGenerator,
        system: String,
        language: String
    ): List<SummarySourceTurn> {
        if (fits(generator, system, turnsPrompt(listOf(turn), false, language), PARTIAL_OUTPUT_TOKENS)) return listOf(turn)
        val result = mutableListOf<SummarySourceTurn>()
        var remaining = turn.text
        var part = 1
        while (remaining.isNotEmpty()) {
            var low = 1
            var high = remaining.length
            var accepted = 0
            while (low <= high) {
                val middle = (low + high) ushr 1
                val candidate = fragment(turn, remaining.substring(0, middle), part)
                if (fits(generator, system, turnsPrompt(listOf(candidate), false, language), PARTIAL_OUTPUT_TOKENS)) {
                    accepted = middle
                    low = middle + 1
                } else {
                    high = middle - 1
                }
            }
            check(accepted > 0) { AppLanguage.text("一つの発話をローカルQwenの入力予算へ分割できませんでした", "Could not split a turn within the local Qwen input budget") }
            val boundary = preferredBoundary(remaining, accepted)
            result += fragment(turn, remaining.substring(0, boundary).trim(), part)
            remaining = remaining.substring(boundary).trimStart()
            part += 1
        }
        return result
    }

    private fun groupTurns(
        turns: List<SummarySourceTurn>,
        generator: LocalTextGenerator,
        system: String,
        language: String
    ): List<List<SummarySourceTurn>> = greedyGroups(turns) { group ->
        fits(generator, system, turnsPrompt(group, false, language), PARTIAL_OUTPUT_TOKENS)
    }

    private fun groupNodes(
        nodes: List<SummaryNode>,
        generator: LocalTextGenerator,
        system: String,
        language: String
    ): List<List<SummaryNode>> = greedyGroups(nodes) { group ->
        fits(generator, system, nodesPrompt(group, false, language), PARTIAL_OUTPUT_TOKENS)
    }

    private fun <T> greedyGroups(values: List<T>, fitsGroup: (List<T>) -> Boolean): List<List<T>> {
        val groups = mutableListOf<List<T>>()
        var current = mutableListOf<T>()
        values.forEach { value ->
            val candidate = current + value
            if (current.isNotEmpty() && !fitsGroup(candidate)) {
                groups += current.toList()
                current = mutableListOf(value)
                check(fitsGroup(current)) { AppLanguage.text("要約チャンクが入力予算を超えています", "Summary chunk exceeds input budget") }
            } else {
                current += value
            }
        }
        if (current.isNotEmpty()) groups += current
        return groups
    }

    private fun fits(
        generator: LocalTextGenerator,
        system: String,
        user: String,
        maxOutputTokens: Int
    ): Boolean = generator.countTokens(system, user) + maxOutputTokens <= CONTEXT_SIZE

    private fun turnsPrompt(turns: List<SummarySourceTurn>, final: Boolean, language: String): String = buildString {
        appendLine("/no_think")
        appendLine(if (language == "en") { if (final) "Create final minutes from the complete meeting below." else "Create a faithful partial summary of the meeting segment below." } else if (final) "次の全会議から最終要約を作成してください。" else "次の会議区間から忠実な部分要約を作成してください。")
        appendLine(if (language == "en") "Include only turn_id values actually used as evidence in source_turn_ids." else "source_turn_idsには、根拠として実際に使ったturn_idだけを記載してください。")
        appendLine("SOURCE_TURNS_JSONL:")
        turns.forEach { appendLine(turnJson(it)) }
    }

    private fun nodesPrompt(nodes: List<SummaryNode>, final: Boolean, language: String): String = buildString {
        appendLine("/no_think")
        appendLine(if (language == "en") { if (final) "Merge the partial summaries below into final minutes." else "Merge the partial summaries below without duplication." } else if (final) "次の部分要約を統合して最終要約を作成してください。" else "次の部分要約を重複なく統合してください。")
        appendLine(if (language == "en") "Do not add information absent from the source. Carry forward only existing source_turn_ids." else "原文にない内容を補わず、既存のsource_turn_idsだけを引き継いでください。")
        appendLine("PARTIAL_SUMMARIES_JSONL:")
        nodes.forEach { appendLine(summaryJson(it.artifact)) }
    }

    private fun turnJson(turn: SummarySourceTurn): String = JSONObject()
        .put("turn_id", turn.turnId)
        .put("start_ms", turn.startMs)
        .put("end_ms", turn.endMs)
        .put("speaker", turn.speakerLabel ?: AppLanguage.text("話者未分離", "Speakers not separated"))
        .put("text", turn.text)
        .toString()

    private fun summaryJson(summary: MeetingSummaryArtifact): String = JSONObject()
        .put("title", summary.title)
        .put("purpose", summary.purpose)
        .put("topics", JSONArray(summary.topics.map { topic -> JSONObject()
            .put("topic", topic.topic)
            .put("discussion", topic.discussion)
            .put("speakers", JSONArray(topic.speakers))
        }))
        .put("decisions", JSONArray(summary.decisions))
        .put("action_items", JSONArray(summary.actionItems.map { item -> JSONObject()
            .put("owner", item.owner ?: JSONObject.NULL)
            .put("due_date", item.dueDate ?: JSONObject.NULL)
            .put("description", item.description)
        }))
        .put("open_questions", JSONArray(summary.openQuestions))
        .put("important_information", JSONArray(summary.importantInformation))
        .put("summary", summary.summary)
        .put("template_id", summary.templateId)
        .put("evidence", JSONArray(summary.evidence.map { evidence -> JSONObject()
            .put("field", evidence.field)
            .put("item_index", evidence.itemIndex ?: JSONObject.NULL)
            .put("source_turn_ids", JSONArray(evidence.sourceTurnIds))
        }))
        .toString()

    private fun fragment(turn: SummarySourceTurn, text: String, part: Int) = turn.copy(
        turnId = "${turn.turnId}p${part.toString().padStart(3, '0')}",
        text = text
    )

    private fun preferredBoundary(text: String, maximum: Int): Int {
        if (maximum >= text.length) return text.length
        val minimum = maximum / 2
        for (index in maximum downTo minimum) {
            if (text[index - 1] in setOf('。', '！', '？', '\n', ' ')) return index
        }
        return maximum
    }

    private data class SummaryNode(val artifact: MeetingSummaryArtifact, val sourceRanges: List<TimeRange>)

    companion object {
        internal const val PROMPT_VERSION = "qwen3-summary-v4"
        internal const val CONTEXT_SIZE = 8_192
        internal const val FINAL_OUTPUT_TOKENS = 1_200
        internal const val PARTIAL_OUTPUT_TOKENS = 900

        internal fun systemPrompt(
            language: String,
            templateId: String = MinutesTemplateIds.STANDARD_MEETING,
            customInstructions: String = ""
        ): String = """
            ${MeetingMinutesTemplates.systemPrompt(language, templateId, customInstructions)}
            ${if (language == "en") "Return only a JSON object of the following shape, without Markdown, reasoning, or explanations." else "Markdown、思考過程、説明文を付けず、次の形のJSONオブジェクトだけを返してください。"}
            {"title":"","purpose":"","topics":[{"topic":"","discussion":"","speakers":[]}],"decisions":[],"action_items":[{"owner":null,"due_date":null,"description":""}],"open_questions":[],"important_information":[],"summary":"","evidence":[{"field":"decisions","item_index":0,"source_turn_ids":["t000001"]}]}
            ${if (language == "en") "field must be purpose, topics, decisions, action_items, open_questions, important_information, or summary. Match array entries using item_index." else "fieldはpurpose、topics、decisions、action_items、open_questions、important_information、summaryのいずれかです。配列項目はitem_indexで対応させてください。"}
            ${if (language == "en") "Use empty strings, null, or empty arrays for unsupported items. Never invent turn_id values." else "根拠がない項目は空文字、null、空配列にし、存在しないturn_idを作ってはいけません。"}
        """.trimIndent()

        internal fun parseGeneratedSummary(
            raw: String,
            sourceTurns: Map<String, SummarySourceTurn> = emptyMap()
        ): MeetingSummaryArtifact {
            val withoutThinking = raw.replace(
                Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
                ""
            ).trim()
            val start = withoutThinking.indexOf('{')
            val end = withoutThinking.lastIndexOf('}')
            check(start >= 0 && end > start) { AppLanguage.text("ローカルQwenが有効な要約JSONを返しませんでした", "Local Qwen did not return valid summary JSON") }
            return runCatching {
                MeetingSummaryJson.parse(withoutThinking.substring(start, end + 1), sourceTurns)
            }.getOrElse { throw IllegalStateException(AppLanguage.text("ローカルQwenの要約JSONを読み取れませんでした", "Could not parse local Qwen summary JSON"), it) }
        }
    }
}

private class LlamaSessionTextGenerator(modelPath: String) : LocalTextGenerator {
    private val session = LlamaNativeBridge.openSession(modelPath, LocalQwenSummaryProvider.CONTEXT_SIZE, 6)

    override fun countTokens(systemPrompt: String, userPrompt: String): Int =
        session.countTokens(systemPrompt, userPrompt)

    override fun generate(systemPrompt: String, userPrompt: String, maxOutputTokens: Int): String =
        session.generate(systemPrompt, userPrompt, maxOutputTokens)

    override fun close() = session.close()
}
