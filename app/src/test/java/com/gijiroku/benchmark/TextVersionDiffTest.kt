package com.gijiroku.benchmark

import org.junit.Assert.assertTrue
import org.junit.Test

class TextVersionDiffTest {
    @Test
    fun marksChangedAndAddedLines() {
        val result = TextVersionDiff.compare("同じ\n古い", "同じ\n新しい\n追加")
        assertTrue(result.contains("  同じ"))
        assertTrue(result.contains("− 古い"))
        assertTrue(result.contains("＋ 新しい"))
        assertTrue(result.contains("＋ 追加"))
    }

    @Test
    fun boundsLargeComparisons() {
        val result = TextVersionDiff.compare("a\nb\nc", "a\nb\nd", maxLines = 2)
        assertTrue(result.contains("比較表示は2行まで"))
    }
}
