package com.gijiroku.benchmark

/** Keeps model weights loaded across hierarchy chunks, while recreating and freeing each context. */
object LlamaNativeBridge {
    init {
        System.loadLibrary("gijiroku_llama")
    }

    @Synchronized
    fun openSession(modelPath: String, contextSize: Int, threads: Int): LlamaNativeSession {
        require(modelPath.isNotBlank()) { AppLanguage.text("Qwenモデルの場所が空です", "Qwen model path is empty") }
        require(contextSize > 0 && threads > 0)
        val handle = loadModelNative(modelPath, contextSize, threads)
        check(handle != 0L) { AppLanguage.text("Qwenモデルを読み込めませんでした", "Could not load Qwen model") }
        return LlamaNativeSession(handle)
    }

    @Synchronized
    internal fun countTokens(handle: Long, systemPrompt: String, userPrompt: String): Int =
        countTokensNative(handle, systemPrompt, userPrompt)

    @Synchronized
    internal fun generate(
        handle: Long,
        systemPrompt: String,
        userPrompt: String,
        maxOutputTokens: Int
    ): String = generateNative(handle, systemPrompt, userPrompt, maxOutputTokens)

    @Synchronized
    internal fun close(handle: Long) = closeModelNative(handle)

    @JvmStatic
    private external fun loadModelNative(modelPath: String, contextSize: Int, threads: Int): Long

    @JvmStatic
    private external fun countTokensNative(handle: Long, systemPrompt: String, userPrompt: String): Int

    @JvmStatic
    private external fun generateNative(
        handle: Long,
        systemPrompt: String,
        userPrompt: String,
        maxOutputTokens: Int
    ): String

    @JvmStatic
    private external fun closeModelNative(handle: Long)
}

class LlamaNativeSession internal constructor(private var handle: Long) : AutoCloseable {
    @Synchronized
    fun countTokens(systemPrompt: String, userPrompt: String): Int {
        check(handle != 0L) { AppLanguage.text("Qwenセッションは終了しています", "Qwen session has ended") }
        return LlamaNativeBridge.countTokens(handle, systemPrompt, userPrompt)
    }

    @Synchronized
    fun generate(systemPrompt: String, userPrompt: String, maxOutputTokens: Int): String {
        check(handle != 0L) { AppLanguage.text("Qwenセッションは終了しています", "Qwen session has ended") }
        require(maxOutputTokens > 0)
        return LlamaNativeBridge.generate(handle, systemPrompt, userPrompt, maxOutputTokens)
    }

    @Synchronized
    override fun close() {
        if (handle == 0L) return
        val closing = handle
        handle = 0L
        LlamaNativeBridge.close(closing)
    }
}
