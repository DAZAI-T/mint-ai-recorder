package com.gijiroku.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudModelNameValidatorTest {

    @Test
    fun acceptsProviderModelNamesWithoutChangingApiOrigin() {
        assertEquals(
            "whisper-1",
            CloudModelNameValidator.normalize(CloudModelProvider.OPENAI, " whisper-1 ")
        )
        assertEquals(
            "gemini-flash-latest",
            CloudModelNameValidator.normalize(
                CloudModelProvider.GEMINI,
                "models/gemini-flash-latest"
            )
        )
    }

    @Test
    fun rejectsUrlsQueriesAndPathTraversal() {
        listOf(
            "https://example.com/model",
            "gemini-flash?key=secret",
            "../model",
            "model#fragment"
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                CloudModelNameValidator.normalize(CloudModelProvider.GEMINI, value)
            }
        }
    }
}
