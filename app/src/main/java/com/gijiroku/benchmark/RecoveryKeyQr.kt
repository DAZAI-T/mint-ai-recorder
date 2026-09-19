package com.gijiroku.benchmark

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object RecoveryKeyQr {
    fun matrix(recoveryKey: String, size: Int): BitMatrix {
        require(size in 128..2048) { "Invalid QR size" }
        // Validate the checksum before showing a code that the user may rely on.
        RecoveryKeyCodec.decode(recoveryKey).fill(0)
        return try {
            QRCodeWriter().encode(
                recoveryKey,
                BarcodeFormat.QR_CODE,
                size,
                size,
                mapOf(
                    EncodeHintType.CHARACTER_SET to "US-ASCII",
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
                    EncodeHintType.MARGIN to 2
                )
            )
        } catch (error: WriterException) {
            throw PortableBackupException("Could not create recovery QR", error)
        }
    }

    fun bitmap(recoveryKey: String, size: Int): Bitmap {
        val matrix = matrix(recoveryKey, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, size, 0, 0, size, size)
            pixels.fill(Color.WHITE)
        }
    }
}
