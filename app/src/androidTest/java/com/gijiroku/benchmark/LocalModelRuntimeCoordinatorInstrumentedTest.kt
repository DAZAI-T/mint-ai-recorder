package com.gijiroku.benchmark

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalModelRuntimeCoordinatorInstrumentedTest {
    @Test
    fun modelGateIsExclusiveAndAlwaysReleased() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        LocalModelRuntimeCoordinator.withExclusiveModel(context, LocalModelKind.LIVE_WHISPER) {
            assertEquals(LocalModelKind.LIVE_WHISPER, LocalModelRuntimeCoordinator.activeModelForTest())
            try {
                LocalModelRuntimeCoordinator.withExclusiveModel(context, LocalModelKind.SPEAKER_EMBEDDING) { Unit }
                fail("nested model execution must be rejected")
            } catch (_: IllegalStateException) {
                // expected
            }
            assertEquals(LocalModelKind.LIVE_WHISPER, LocalModelRuntimeCoordinator.activeModelForTest())
        }
        assertNull(LocalModelRuntimeCoordinator.activeModelForTest())

        try {
            LocalModelRuntimeCoordinator.withExclusiveModel(context, LocalModelKind.LIVE_WHISPER) {
                error("test failure")
            }
            fail("exception must escape")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertNull(LocalModelRuntimeCoordinator.activeModelForTest())
    }
}
