@file:Suppress("PackageDirectoryMismatch")

package androidx.compose.ui.res

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import com.sokoul.tv.TvApp

@Composable
fun painterResource(path: String): Painter = remember(path) {
    BitmapPainter(TvApp.context.assets.open(path).use { BitmapFactory.decodeStream(it) }.asImageBitmap())
}
