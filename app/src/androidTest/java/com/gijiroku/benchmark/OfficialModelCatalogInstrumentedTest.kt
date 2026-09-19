package com.gijiroku.benchmark

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfficialModelCatalogInstrumentedTest {
    @Test
    fun llamaRuntimeLibraryLoadsOnDevice() {
        System.loadLibrary("gijiroku_llama")
    }

    @Test
    fun bundledCatalogSignatureAndNoticesAreValid() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val catalog = OfficialModelCatalog.load(context)

        assertEquals(4, catalog.models.size)
        assertEquals(
            "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5",
            catalog.requireModel(OfficialModelCatalog.QWEN_SUMMARY_ID).sha256
        )
        assertEquals(190085487L, catalog.requireModel(OfficialModelCatalog.LIVE_WHISPER_ID).byteSize)
        assertEquals(574041195L, catalog.requireModel(OfficialModelCatalog.FINAL_WHISPER_ID).byteSize)
        catalog.models.forEach { entry ->
            assertTrue(context.assets.open(entry.noticePath).use { it.read() >= 0 })
        }
        val capability = LocalModelDeviceCapabilityEvaluator.evaluate(context)
        assertTrue(capability.reportedRamBytes > 0L)
        assertTrue(capability.usableStorageBytes > 0L)
    }
}
