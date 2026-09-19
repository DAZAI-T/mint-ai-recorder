package com.gijiroku.benchmark

import org.junit.Assert.assertThrows
import org.junit.Test

class LocalModelFileValidatorTest {

    @Test
    fun acceptsWhisperGgmlAndSummaryGgufHeaders() {
        LocalModelFileValidator.requireCompatibleName(LocalModelLibraryKind.TRANSCRIPTION, "whisper.bin")
        LocalModelFileValidator.requireCompatibleHeader(
            LocalModelLibraryKind.TRANSCRIPTION,
            byteArrayOf(0x6c, 0x6d, 0x67, 0x67),
            4
        )
        LocalModelFileValidator.requireCompatibleName(LocalModelLibraryKind.SUMMARIZATION, "qwen.gguf")
        LocalModelFileValidator.requireCompatibleHeader(
            LocalModelLibraryKind.SUMMARIZATION,
            "GGUF".toByteArray(Charsets.US_ASCII),
            4
        )
    }

    @Test
    fun rejectsWrongExtensionAndExecutableContent() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalModelFileValidator.requireCompatibleName(LocalModelLibraryKind.SUMMARIZATION, "model.apk")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalModelFileValidator.requireCompatibleHeader(
                LocalModelLibraryKind.SUMMARIZATION,
                byteArrayOf(0x50, 0x4b, 0x03, 0x04),
                4
            )
        }
    }
}
