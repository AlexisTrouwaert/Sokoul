package com.sokoul.pc.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sokoul.desktop.ui.ImageLoader
import com.sokoul.iptv.data.net.TmdbInfo
import com.sokoul.iptv.data.net.TmdbItem
import com.sokoul.pc.data.CatalogApi
import com.sokoul.pc.data.kind
import com.sokoul.pc.theme.InterDisplay

/** What a poster card draws, decided from the artwork that exists. */
sealed interface PosterArt {
    val image: String?

    /** A textless poster with the title's logo centered on top. */
    data class WithLogo(override val image: String, val logo: String) : PosterArt

    /** A textless poster and no logo: the title in styled text. */
    data class WithTitle(override val image: String, val title: String) : PosterArt

    /** No textless poster: the original poster, which already carries its title. */
    data class Original(override val image: String?) : PosterArt
}

fun posterArt(item: TmdbItem, info: TmdbInfo?): PosterArt {
    val clean = info?.cleanPoster ?: return PosterArt.Original(item.poster)
    val logo = info.logo
    return if (logo != null) PosterArt.WithLogo(clean, logo) else PosterArt.WithTitle(clean, item.title)
}

/** A landscape card for a title: its image, with the logo on top, or the title in bold when it has none. */
@Composable
fun BoxScope.SmartLandscape(catalog: CatalogApi, item: TmdbItem) {
    // The artwork is asked once; whatever it gives (or fails to give), the title stays readable.
    val artwork by produceState<Pair<Boolean, TmdbInfo?>>(false to null, item.id) { value = true to runCatching { catalog.artwork(item.kind(), item.id) }.getOrNull() }
    val info = artwork.second
    Art(info?.backdrop ?: item.backdrop ?: item.poster, Modifier.fillMaxSize())
    Box(Modifier.fillMaxSize().background(Color(0x40000000)))
    val url = info?.logo
    val logo by produceState<Pair<Boolean, androidx.compose.ui.graphics.ImageBitmap?>>(url?.let(ImageLoader::cached).let { (it != null) to it }, url) {
        value = true to url?.let { runCatching { ImageLoader.load(it) }.getOrNull() }
    }
    val bitmap = logo.second
    val logoSettled = url == null || logo.first
    if (bitmap != null) {
        Image(
            bitmap, item.title,
            Modifier.align(Alignment.Center).fillMaxWidth(0.72f).height(110.dp),
            Alignment.Center, ContentScale.Fit,
        )
    } else if (artwork.first && logoSettled) {
        BasicText(
            item.title,
            Modifier.align(Alignment.Center).fillMaxWidth(0.84f),
            style = androidx.compose.ui.text.TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 33.sp, textAlign = TextAlign.Center, color = Color.White),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val LogoFade = Brush.verticalGradient(0f to Color(0x14000000), 0.5f to Color(0x59000000), 1f to Color(0xA6000000))

/** The poster with its logo or title. Until the artwork is known, the original poster holds the place. */
@Composable
fun BoxScope.SmartPoster(catalog: CatalogApi, item: TmdbItem) {
    val info by produceState<TmdbInfo?>(null, item.id) { value = catalog.artwork(item.kind(), item.id) }
    val art = posterArt(item, info)
    Art(art.image, Modifier.fillMaxSize())
    when (art) {
        is PosterArt.Original -> Unit
        is PosterArt.WithLogo -> {
            Box(Modifier.fillMaxSize().background(LogoFade))
            val logo by produceState(ImageLoader.cached(art.logo), art.logo) { value = ImageLoader.load(art.logo) }
            logo?.let {
                Image(
                    it, item.title,
                    Modifier.align(Alignment.Center).padding(horizontal = 20.dp).fillMaxWidth().height(130.dp),
                    Alignment.Center, ContentScale.Fit,
                )
            }
        }
        is PosterArt.WithTitle -> {
            Box(Modifier.fillMaxSize().background(LogoFade))
            BasicText(
                art.title,
                Modifier.align(Alignment.Center).padding(horizontal = 16.dp).fillMaxWidth(),
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = InterDisplay,
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp,
                    lineHeight = 33.sp,
                    letterSpacing = 0.5.sp,
                    textAlign = TextAlign.Center,
                    color = Color.White,
                    shadow = Shadow(Color(0x99000000), Offset(0f, 2f), 8f),
                ),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
