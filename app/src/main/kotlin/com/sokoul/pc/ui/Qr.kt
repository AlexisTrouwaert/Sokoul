package com.sokoul.pc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** The modules of a QR code for [text], without the quiet zone (the white card draws it). */
fun qrMatrix(text: String): BitMatrix = QRCodeWriter().encode(
    text, BarcodeFormat.QR_CODE, 0, 0,
    mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8"),
)

/** A QR code on a white rounded card: black modules, a quiet zone of [margin], readable by any phone camera. */
@Composable
fun QrCode(text: String, description: String, size: Dp = 320.dp, margin: Dp = 22.dp, modifier: Modifier = Modifier) {
    val matrix = remember(text) { qrMatrix(text) }
    Box(
        modifier.size(size).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(margin)
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.size(size - margin * 2)) {
            val cell = this.size.width / matrix.width
            for (y in 0 until matrix.height) {
                var x = 0
                while (x < matrix.width) {
                    if (!matrix[x, y]) { x++; continue }
                    var run = 1
                    while (x + run < matrix.width && matrix[x + run, y]) run++
                    // One rectangle per horizontal run, slightly overlapped so no hairline shows between modules.
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(run * cell + 0.6f, cell + 0.6f))
                    x += run
                }
            }
        }
    }
}
