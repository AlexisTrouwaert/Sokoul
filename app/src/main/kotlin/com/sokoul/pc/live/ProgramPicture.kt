package com.sokoul.pc.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sokoul.desktop.data.Program
import com.sokoul.pc.ui.Art

/** Programme pictures for the screens that show live programmes; null in tests and screens without a catalog. */
val LocalProgramArt = staticCompositionLocalOf<ProgramArtCache?> { null }

/** The picture of [program]: the guide's at once, then TMDB's once known; null when it has none. */
@Composable
fun rememberProgramImage(program: Program?): String? {
    val cache = LocalProgramArt.current
    val image by produceState(program?.icon, program?.title, program?.icon, cache) {
        value = if (program == null) null else cache?.imageOf(program) ?: program.icon
    }
    return image
}

/**
 * The programme's picture, cropped to fill; the channel's [logo] in its place when the programme has none, so the box
 * never stays empty and never shows another work.
 */
@Composable
fun ProgramPicture(program: Program?, channelName: String, logo: String?, modifier: Modifier = Modifier) {
    val image = rememberProgramImage(program)
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF2C2C2E)), contentAlignment = Alignment.Center) {
        // The logo stays under the picture: it shows while the picture loads, and if it never does.
        ChannelLogo(channelName, logo, Modifier.fillMaxSize().padding(14.dp).testTag("program-picture-logo"))
        if (image != null) {
            Art(image, Modifier.fillMaxSize().testTag("program-picture"), scale = ContentScale.Crop, placeholder = Color.Transparent)
        }
    }
}
