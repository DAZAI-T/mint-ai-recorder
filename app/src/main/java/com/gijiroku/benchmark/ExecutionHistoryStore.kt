package com.gijiroku.benchmark

import android.content.Context
import java.io.File

enum class ExecutionStatus { SUCCEEDED, FAILED }

data class ExecutionHistoryEntry(
    val runId: String,
    val stage: PipelineStage,
    val providerId: String,
    val status: ExecutionStatus,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val inputHash: String,
    val estimatedCostUsd: Double?,
    val destination: String?,
    /** Exception type only: messages may contain URLs, paths, or provider response bodies. */
    val errorType: String?,
    val presetId: String? = null,
    val modelId: String? = null
)

/** Append-only audit history encrypted with the meeting content key. */
class ExecutionHistoryStore(
    context: Context,
    private val meetingId: String,
    private val database: EncryptedMeetingDatabase = EncryptedMeetingDatabase(context)
) {

    private val appContext = context.applicationContext ?: context
    init {
        // Pre-Phase-5C rows cannot be attributed safely to a meeting, so do not migrate them.
        File(appContext.filesDir, LEGACY_PLAINTEXT_HISTORY).delete()
    }

    fun append(entry: ExecutionHistoryEntry) {
        database.appendProviderRun(meetingId, entry)
    }

    companion object {
        private const val LEGACY_PLAINTEXT_HISTORY = "pipeline_execution_history.jsonl"
    }
}
