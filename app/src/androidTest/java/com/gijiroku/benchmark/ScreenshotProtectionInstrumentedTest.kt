package com.gijiroku.benchmark

import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenshotProtectionInstrumentedTest {
    @Test
    fun flagSecureFollowsIndependentScreenshotSetting() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = ScreenshotProtectionSettings(context)
        val original = settings.screenshotsAllowed
        try {
            settings.screenshotsAllowed = false
            ActivityScenario.launch(MeetingLibraryActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue(
                        activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
                    )
                }
            }

            settings.screenshotsAllowed = true
            ActivityScenario.launch(MeetingLibraryActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertFalse(
                        activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
                    )
                }
            }
        } finally {
            settings.screenshotsAllowed = original
        }
    }
}
