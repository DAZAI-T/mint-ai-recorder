package com.gijiroku.benchmark

import org.json.JSONArray
import org.json.JSONObject

internal object PipelineArtifactCodec {

    fun encodeTranscript(artifact: TranscriptArtifact): String = JSONObject()
        .put("segments", JSONArray(artifact.segments.map(::encodeTranscriptSegment)))
        .put("provenance", encodeProvenance(artifact.provenance))
        .toString()

    fun decodeTranscript(value: String): TranscriptArtifact {
        val json = JSONObject(value)
        return TranscriptArtifact(
            segments = json.getJSONArray("segments").objectList().map { segment ->
                TranscriptSegment(
                    startMs = segment.getLong("startMs"),
                    endMs = segment.getLong("endMs"),
                    text = segment.getString("text")
                )
            },
            provenance = decodeProvenance(json.getJSONObject("provenance"))
        )
    }

    fun encodeDiarization(artifact: DiarizationArtifact): String = JSONObject()
        .put(
            "segments",
            JSONArray(
                artifact.segments.map { segment ->
                    JSONObject()
                        .put("startMs", segment.startMs)
                        .put("endMs", segment.endMs)
                        .put("speakerLabel", segment.speakerLabel)
                }
            )
        )
        .put("speakerCount", artifact.speakerCount)
        .put("isSpeakerCountSpecified", artifact.isSpeakerCountSpecified)
        .put("provenance", encodeProvenance(artifact.provenance))
        .toString()

    fun decodeDiarization(value: String): DiarizationArtifact {
        val json = JSONObject(value)
        return DiarizationArtifact(
            segments = json.getJSONArray("segments").objectList().map { segment ->
                SpeakerSegment(
                    startMs = segment.getLong("startMs"),
                    endMs = segment.getLong("endMs"),
                    speakerLabel = segment.getString("speakerLabel")
                )
            },
            speakerCount = json.getInt("speakerCount"),
            isSpeakerCountSpecified = json.getBoolean("isSpeakerCountSpecified"),
            provenance = decodeProvenance(json.getJSONObject("provenance"))
        )
    }

    fun encodeDocument(artifact: TextDocumentArtifact): String = JSONObject()
        .put("text", artifact.text)
        .put("provenance", encodeProvenance(artifact.provenance))
        .toString()

    fun decodeDocument(value: String): TextDocumentArtifact {
        val json = JSONObject(value)
        return TextDocumentArtifact(
            text = json.getString("text"),
            provenance = decodeProvenance(json.getJSONObject("provenance"))
        )
    }

    fun encodeSummary(artifact: MeetingSummaryArtifact): String = JSONObject()
        .put("title", artifact.title)
        .put("purpose", artifact.purpose)
        .put(
            "topics",
            JSONArray(artifact.topics.map { topic ->
                JSONObject()
                    .put("topic", topic.topic)
                    .put("discussion", topic.discussion)
                    .put("speakers", JSONArray(topic.speakers))
            })
        )
        .put("decisions", JSONArray(artifact.decisions))
        .put(
            "actionItems",
            JSONArray(
                artifact.actionItems.map { item ->
                    JSONObject()
                        .put("owner", item.owner ?: JSONObject.NULL)
                        .put("dueDate", item.dueDate ?: JSONObject.NULL)
                        .put("description", item.description)
                }
            )
        )
        .put("openQuestions", JSONArray(artifact.openQuestions))
        .put("importantInformation", JSONArray(artifact.importantInformation))
        .put("summary", artifact.summary)
        .put("templateId", artifact.templateId)
        .put(
            "evidence",
            JSONArray(
                artifact.evidence.map { evidence ->
                    JSONObject()
                        .put("field", evidence.field)
                        .put("itemIndex", evidence.itemIndex ?: JSONObject.NULL)
                        .put("sourceTurnIds", JSONArray(evidence.sourceTurnIds))
                        .put(
                            "sourceRanges",
                            JSONArray(evidence.sourceRanges.map { range ->
                                JSONObject().put("startMs", range.startMs).put("endMs", range.endMs)
                            })
                        )
                }
            )
        )
        .put("provenance", encodeProvenance(artifact.provenance))
        .toString()

    fun decodeSummary(value: String): MeetingSummaryArtifact {
        val json = JSONObject(value)
        return MeetingSummaryArtifact(
            purpose = json.optString("purpose"),
            decisions = json.optJSONArray("decisions")?.stringList().orEmpty(),
            actionItems = json.optJSONArray("actionItems")?.objectList()?.map { item ->
                ActionItem(
                    owner = item.nullableString("owner"),
                    dueDate = item.nullableString("dueDate"),
                    description = item.optString("description")
                )
            }.orEmpty(),
            openQuestions = json.optJSONArray("openQuestions")?.stringList().orEmpty(),
            summary = json.optString("summary"),
            provenance = decodeProvenance(json.getJSONObject("provenance")),
            evidence = json.optJSONArray("evidence")?.objectList()?.map { evidence ->
                SummaryEvidence(
                    field = evidence.getString("field"),
                    itemIndex = if (evidence.isNull("itemIndex")) null else evidence.getInt("itemIndex"),
                    sourceTurnIds = evidence.optJSONArray("sourceTurnIds")?.stringList().orEmpty(),
                    sourceRanges = evidence.optJSONArray("sourceRanges")?.objectList()?.map { range ->
                        TimeRange(range.getLong("startMs"), range.getLong("endMs"))
                    }.orEmpty()
                )
            } ?: emptyList(),
            title = json.optString("title"),
            topics = json.optJSONArray("topics")?.objectList()?.map { topic ->
                MeetingTopic(
                    topic = topic.optString("topic"),
                    discussion = topic.optString("discussion"),
                    speakers = topic.optJSONArray("speakers")?.stringList().orEmpty()
                )
            }.orEmpty(),
            importantInformation = json.optJSONArray("importantInformation")?.stringList().orEmpty(),
            templateId = MeetingMinutesTemplates.find(json.optString("templateId")).id
        )
    }

    private fun encodeTranscriptSegment(segment: TranscriptSegment): JSONObject = JSONObject()
        .put("startMs", segment.startMs)
        .put("endMs", segment.endMs)
        .put("text", segment.text)

    private fun encodeProvenance(provenance: ArtifactProvenance): JSONObject = JSONObject()
        .put("providerId", provenance.providerId)
        .put("createdAtEpochMs", provenance.createdAtEpochMs)
        .put("inputHash", provenance.inputHash)

    private fun decodeProvenance(json: JSONObject): ArtifactProvenance = ArtifactProvenance(
        providerId = json.getString("providerId"),
        createdAtEpochMs = json.getLong("createdAtEpochMs"),
        inputHash = json.getString("inputHash")
    )
}

private fun JSONArray.objectList(): List<JSONObject> = buildList {
    for (index in 0 until length()) add(getJSONObject(index))
}

private fun JSONArray.stringList(): List<String> = buildList {
    for (index in 0 until length()) add(getString(index))
}

private fun JSONObject.nullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
