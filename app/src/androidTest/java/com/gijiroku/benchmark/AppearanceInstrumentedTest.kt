package com.gijiroku.benchmark

import android.content.res.Configuration
import android.view.LayoutInflater
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceInstrumentedTest {
    @Test fun bothPalettesInflateAllProductScreens() {
        val base = ApplicationProvider.getApplicationContext<android.content.Context>()
        val backgrounds = mutableListOf<Int>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES).forEach { night ->
                val config = Configuration(base.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                }
                val themed = ContextThemeWrapper(base.createConfigurationContext(config), R.style.Theme_GijirokuBenchmark)
                listOf(R.layout.activity_meeting_library, R.layout.activity_record,
                    R.layout.activity_meeting_detail, R.layout.activity_provider_settings).forEach {
                    LayoutInflater.from(themed).inflate(it, null)
                }
                backgrounds += themed.getColor(R.color.voice_background)
            }
        }
        assertNotEquals(backgrounds[0], backgrounds[1])
    }
}
