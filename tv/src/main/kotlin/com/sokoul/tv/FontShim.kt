@file:Suppress("PackageDirectoryMismatch")

package androidx.compose.ui.text.platform

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.sokoul.tv.TvApp

fun Font(resource: String, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal) =
    androidx.compose.ui.text.font.Font(resource, TvApp.context.assets, weight, style)
