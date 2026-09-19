package com.gijiroku.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetElapsedLabelTest {
    @Test
    fun formatsAStoppedDurationWithoutAClock() {
        assertEquals("00:00", widgetElapsedLabel(0))
        assertEquals("01:05", widgetElapsedLabel(65_999))
        assertEquals("1:01:05", widgetElapsedLabel(3_665_999))
    }

    @Test
    fun clampsNegativeDurations() {
        assertEquals("00:00", widgetElapsedLabel(-1))
    }
}
