package com.gijiroku.benchmark

import org.json.JSONArray
import org.json.JSONObject

/** Shared structured-summary decoder used by local and cloud providers. */
object MeetingSummaryJson {
    fun parse(
        json: String,
        sourceTurns: Map<String, SummarySourceTurn> = emptyMap()
    ): MeetingSummaryArtifact {
        val summary = JSONObject(json)
        return MeetingSummaryArtifact(
            title = summary.optString("title"),
            purpose = summary.optString("purpose"),
            topics = summary.optJSONArray("topics").toObjectList { item ->
                MeetingTopic(
                    topic = item.optString("topic"),
                    discussion = item.optString("discussion"),
                    speakers = item.stringList("speakers")
                )
            },
            decisions = summary.stringList("decisions"),
            actionItems = summary.optJSONArray("action_items").toObjectList { item ->
                ActionItem(
                    owner = item.nullableString("owner"),
                    dueDate = item.nullableString("due_date"),
                    description = item.optString("description")
                )
            },
            openQuestions = summary.stringList("open_questions"),
            importantInformation = summary.stringList("important_information"),
            summary = summary.optString("summary"),
            provenance = ArtifactProvenance("", 0, ""),
            evidence = parseEvidence(summary.optJSONArray("evidence"), sourceTurns),
            templateId = MeetingMinutesTemplates.find(summary.optString("template_id")).id
        )
    }

    private fun parseEvidence(
        values: JSONArray?,
        sourceTurns: Map<String, SummarySourceTurn>
    ): List<SummaryEvidence> {
        if (values == null || sourceTurns.isEmpty()) return emptyList()
        val acceptedFields = setOf(
            "purpose", "topics", "decisions", "action_items", "open_questions",
            "important_information", "summary"
        )
        return buildList {
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index) ?: continue
                val field = value.optString("field")
                if (field !in acceptedFields) continue
                val ids = value.optJSONArray("source_turn_ids")?.let { array ->
                    buildList {
                        for (sourceIndex in 0 until array.length()) {
                            array.optString(sourceIndex).takeIf(sourceTurns::containsKey)?.let(::add)
                        }
                    }.distinct()
                }.orEmpty()
                if (ids.isEmpty()) continue
                add(
                    SummaryEvidence(
                        field = field,
                        itemIndex = value.optInt("item_index", -1).takeIf { it >= 0 },
                        sourceTurnIds = ids,
                        sourceRanges = ids.mapNotNull(sourceTurns::get).map { TimeRange(it.startMs, it.endMs) }
                    )
                )
            }
        }
    }

    private fun JSONObject.stringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun <T> JSONArray?.toObjectList(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) optJSONObject(index)?.let { add(transform(it)) }
        }
    }

    private fun JSONObject.nullableString(key: String): String? {
        if (isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() }
    }
}
