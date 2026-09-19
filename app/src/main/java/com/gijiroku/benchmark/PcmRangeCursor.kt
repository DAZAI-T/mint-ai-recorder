package com.gijiroku.benchmark

internal data class PcmChunkSlice(val offset: Int, val length: Int)

/** Selects a byte range while encrypted PCM chunks are consumed in order. */
internal class PcmRangeCursor(
    private val startByte: Long,
    private val endByteExclusive: Long
) {
    private var consumedBytes = 0L

    init {
        require(startByte >= 0)
        require(endByteExclusive > startByte)
    }

    val reachedEnd: Boolean
        get() = consumedBytes >= endByteExclusive

    fun take(chunkBytes: Int): PcmChunkSlice? {
        require(chunkBytes >= 0)
        val chunkStart = consumedBytes
        val chunkEnd = (chunkStart + chunkBytes.toLong()).coerceAtMost(Long.MAX_VALUE)
        consumedBytes = chunkEnd
        val overlapStart = maxOf(chunkStart, startByte)
        val overlapEnd = minOf(chunkEnd, endByteExclusive)
        if (overlapStart >= overlapEnd) return null
        return PcmChunkSlice(
            offset = (overlapStart - chunkStart).toInt(),
            length = (overlapEnd - overlapStart).toInt()
        )
    }

    companion object {
        fun forTimeRange(
            startMs: Long,
            endMs: Long,
            sampleRate: Int,
            channels: Int,
            bitsPerSample: Int
        ): PcmRangeCursor {
            require(sampleRate > 0)
            require(channels > 0)
            require(bitsPerSample > 0 && bitsPerSample % Byte.SIZE_BITS == 0)
            val safeStart = startMs.coerceIn(0, MAX_POSITION_MS)
            val safeEnd = endMs.coerceIn(safeStart + 1, MAX_POSITION_MS + 1)
            val frameBytes = channels * (bitsPerSample / Byte.SIZE_BITS)
            fun offset(milliseconds: Long): Long {
                val wholeSeconds = milliseconds / 1_000
                val remainder = milliseconds % 1_000
                val frames = wholeSeconds * sampleRate + remainder * sampleRate / 1_000
                return frames * frameBytes
            }
            return PcmRangeCursor(offset(safeStart), offset(safeEnd))
        }

        private const val MAX_POSITION_MS = 7L * 24 * 60 * 60 * 1_000
    }
}
