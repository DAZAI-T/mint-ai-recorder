package com.gijiroku.benchmark

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MeetingLibraryActivityInstrumentedTest {
    @Test
    fun launcherDisplaysPrimaryActions() {
        ActivityScenario.launch(MeetingLibraryActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertTrue(activity.findViewById<android.view.View>(R.id.buttonNewRecording).isShown)
                assertTrue(activity.findViewById<android.view.View>(R.id.buttonSettings).isShown)
            }
        }
    }
}
