package com.gijiroku.benchmark

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryKeyQrTest {

    @Test
    fun generatedQrDecodesToChecksumProtectedRecoveryKey() {
        val raw = ByteArray(32) { (it * 11 + 3).toByte() }
        val key = RecoveryKeyCodec.encode(raw)
        raw.fill(0)
        val size = 256
        val matrix = RecoveryKeyQr.matrix(key, size)
        val pixels = IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            if (matrix[x, y]) 0xff000000.toInt() else 0xffffffff.toInt()
        }

        val decoded = QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels)))
        )

        assertEquals(key, decoded.text)
        assertTrue(pixels.any { it == 0xff000000.toInt() })
        assertTrue(pixels.any { it == 0xffffffff.toInt() })
    }
}
