package com.gijiroku.benchmark

/** Executes one provider and records metadata without persisting payloads or exception messages. */
class ProviderExecutor(private val historyStore: ExecutionHistoryStore) {

    suspend fun <I, O> execute(
        provider: PipelineProvider<I, O>,
        input: I,
        context: ExecutionContext
    ): ProviderResult<O> {
        val startedNanos = System.nanoTime()
        return try {
            AudioEgressPolicy.requireAllowed(provider.descriptor)
            val result = provider.execute(input, context)
            record(
                provider = provider,
                context = context,
                result = result,
                status = ExecutionStatus.SUCCEEDED,
                durationMs = result.durationMs,
                errorType = null
            )
            result
        } catch (error: Throwable) {
            record(
                provider = provider,
                context = context,
                result = null,
                status = ExecutionStatus.FAILED,
                durationMs = (System.nanoTime() - startedNanos) / 1_000_000,
                errorType = error.javaClass.simpleName
            )
            throw error
        }
    }

    private fun record(
        provider: PipelineProvider<*, *>,
        context: ExecutionContext,
        result: ProviderResult<*>?,
        status: ExecutionStatus,
        durationMs: Long,
        errorType: String?
    ) {
        // Audit logging is best-effort and must not turn a successful inference into a failure.
        runCatching {
            historyStore.append(
                ExecutionHistoryEntry(
                    runId = context.runId,
                    stage = provider.descriptor.stage,
                    providerId = provider.descriptor.id,
                    status = status,
                    startedAtEpochMs = context.startedAtEpochMs,
                    durationMs = durationMs,
                    inputHash = context.inputHash,
                    estimatedCostUsd = result?.estimatedCostUsd,
                    destination = result?.destination ?: provider.descriptor.destination,
                    errorType = errorType,
                    presetId = context.presetId,
                    modelId = result?.modelId
                )
            )
        }
    }
}
