package com.sokoul.pc.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.sokoul.desktop.player.DesktopPlayer
import com.sokoul.tv.R

/** False until the current stream shows a picture: the artwork stays instead of a black rectangle. */
@Composable
fun rememberHasFrames(player: DesktopPlayer?): Boolean {
    if (player == null) return false
    val has by player.hasPicture.collectAsState()
    return has
}

/** The video, under the Compose layers: a texture view, so bars and menus draw over it. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoFill(player: DesktopPlayer, modifier: Modifier = Modifier, fit: ContentScale = ContentScale.Crop) {
    AndroidView(
        factory = { c ->
            (android.view.LayoutInflater.from(c).inflate(if (fit == ContentScale.Fit) R.layout.video_surface_full else R.layout.video_surface, null) as PlayerView).also { v -> v.player = player.exo }
        },
        // A cropped video is larger than its place: without this it shows through whatever lies below.
        modifier = modifier.clipToBounds(),
        update = { v ->
            v.player = player.exo
            v.resizeMode = when (fit) {
                ContentScale.Crop -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                ContentScale.FillBounds -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        onRelease = { v -> v.player = null },
    )
}
