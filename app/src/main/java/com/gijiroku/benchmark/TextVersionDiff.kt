package com.gijiroku.benchmark

/** Compact, bounded line comparison for the on-device revision viewer. */
internal object TextVersionDiff {
    fun compare(older: String, current: String, maxLines: Int = 400): String {
        require(maxLines > 0)
        val oldLines = older.lines()
        val currentLines = current.lines()
        val compared = maxOf(oldLines.size, currentLines.size).coerceAtMost(maxLines)
        val output = buildString {
            for (index in 0 until compared) {
                val old = oldLines.getOrNull(index)
                val now = currentLines.getOrNull(index)
                when {
                    old == now && old != null -> append("  ").appendLine(old)
                    else -> {
                        old?.let { append("− ").appendLine(it) }
                        now?.let { append("＋ ").appendLine(it) }
                    }
                }
            }
            if (maxOf(oldLines.size, currentLines.size) > maxLines) {
                appendLine(AppLanguage.text("…比較表示は${maxLines}行までです", "…Comparison limited to ${maxLines} lines"))
            }
        }
        return output.ifBlank { AppLanguage.text("変更はありません", "No changes") }
    }
}
