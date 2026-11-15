package com.sokoul.pc.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.sokoul.desktop.ui.ImageLoader
import com.sokoul.pc.data.Mood
import com.sokoul.pc.data.Platform
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.CardOrigin
import com.sokoul.pc.ui.FocusCard
import com.sokoul.pc.ui.Label

private val TileWidth = 414.dp
private val TileHeight = 232.dp
private val PlatformSize = 232.dp

/** One color pair per mood, in Sokoul's own abstract style. */
private fun moodColors(mood: Mood): Pair<Color, Color> = when (mood) {
    Mood.Chills -> Color(0xFF3A0A12) to Color(0xFFE53A4A)
    Mood.Light -> Color(0xFF7A3B12) to Color(0xFFF2B350)
    Mood.Family -> Color(0xFF0F4A44) to Color(0xFF7FE0C0)
    Mood.Puzzle -> Color(0xFF3F0F63) to Color(0xFFC13ACB)
    Mood.Emotions -> Color(0xFF4A1A5A) to Color(0xFFF07AB0)
    Mood.Adrenaline -> Color(0xFF0E1E4A) to Color(0xFF58C0F0)
}

@Composable
fun MoodTile(mood: Mood, tag: String, requester: FocusRequester, onClick: (CardOrigin) -> Unit) {
    val (dark, bright) = moodColors(mood)
    FocusCard(TileWidth, TileHeight, onClick, tag = tag, requester = requester) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(dark)
            val w = size.width
            val h = size.height
            val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(w * 0.62f, h * 0.5f), h * 0.72f)) }
            drawPath(circle, Brush.radialGradient(listOf(bright, bright.copy(alpha = 0.05f)), Offset(w * 0.62f, h * 0.5f), h * 0.72f))
            val veil = Path().apply {
                moveTo(0f, h)
                lineTo(w * 0.55f, h)
                lineTo(w * 0.28f, 0f)
                lineTo(0f, 0f)
                close()
            }
            drawPath(veil, dark.copy(alpha = 0.55f))
        }
        Label(mood.title.text(), Type.RowTitle, modifier = Modifier.align(Alignment.CenterStart).padding(start = 28.dp, end = 22.dp), maxLines = 2)
    }
}

/** A platform's bubble: its logo, as served by TMDB, fills it entirely. */
@Composable
fun PlatformTile(platform: Platform, logo: String?, tag: String, requester: FocusRequester, onClick: (CardOrigin) -> Unit) {
    val image by produceState(logo?.let(ImageLoader::cached), logo) { value = logo?.let { ImageLoader.load(it) } }
    FocusCard(PlatformSize, PlatformSize, onClick, tag = tag, requester = requester, corner = 44.dp) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, platform.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(platform.accent), Color(platform.color)))))
            Label(platform.label, Type.Button, modifier = Modifier.align(Alignment.Center), maxLines = 1)
        }
    }
}
