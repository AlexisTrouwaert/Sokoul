package com.sokoul.pc.ui

import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Sokoul's own navigation icons: soft, filled and rounded, drawn on a 24-unit grid. */
object NavIcons {
    val Home by lazy {
        icon("home") {
            fill("M12 2.6c.5 0 1 .2 1.4.5l7 6c.4.4.6.9.6 1.4v8.800c0 1.200-1 2.200-2.200 2.200H15v-5.800c0-.7-.5-1.200-1.200-1.200h-3.600c-.7 0-1.200.5-1.200 1.200v5.800H5.200C4 21.500 3 20.500 3 19.300V10.500c0-.5.2-1 .6-1.400l7-6c.4-.3.9-.5 1.400-.5z")
        }
    }

    val MyList by lazy {
        icon("my-list") {
            fill("M5.500 7h10.500A2.500 2.500 0 0 1 18.500 9.500v9.500A2.500 2.500 0 0 1 16 21.500H5.500A2.500 2.500 0 0 1 3 19V9.500A2.500 2.500 0 0 1 5.500 7zM9.750 10.250v3h-3v2h3v3h2v-3h3v-2h-3v-3z")
            line("M7.500 3.500h10.500a3 3 0 0 1 3 3v10", 1.9f)
        }
    }

    val Movies by lazy {
        icon("movies") {
            fill("M5 3.500h14A2.500 2.500 0 0 1 21.500 6v12a2.500 2.500 0 0 1-2.500 2.500H5A2.500 2.500 0 0 1 2.500 18V6A2.500 2.500 0 0 1 5 3.500zM4.600 6v2.400h2V6zM17.400 6v2.400h2V6zM4.600 10.800v2.400h2v-2.400zM17.400 10.800v2.400h2v-2.400zM4.600 15.600V18h2v-2.400zM17.400 15.600V18h2v-2.400zM8.700 6v5h6.600V6zM8.700 13v5h6.600v-5z")
        }
    }

    val Series by lazy {
        icon("series") {
            fill("M5 3.800h14A2.500 2.500 0 0 1 21.500 6.300v9.400a2.500 2.500 0 0 1-2.500 2.500H5a2.500 2.500 0 0 1-2.500-2.500V6.300A2.500 2.500 0 0 1 5 3.800zM10 7.600v6.800l5.600-3.400z")
            line("M8 20.800h8", 2f)
        }
    }

    /** A stack of titles: the front card with the edges of the ones behind it. */
    val Collections by lazy {
        icon("collections") {
            fill("M5.500 9.500h13A2.500 2.500 0 0 1 21 12v7a2.500 2.500 0 0 1-2.500 2.500h-13A2.500 2.500 0 0 1 3 19v-7a2.500 2.500 0 0 1 2.500-2.500z")
            line("M6 6.300h12", 2f)
            line("M9 3.100h6", 2f)
        }
    }

    /** A star with a smaller one: the sparkle of animation, without any brand's sign. */
    val Anime by lazy {
        icon("anime") {
            fill("M10 3l2.200 6.300L18.500 11.500l-6.300 2.200L10 20l-2.200-6.300L1.500 11.500l6.300-2.200L10 3z")
            fill("M18.500 2.500l1 2.800 2.800 1-2.800 1-1 2.800-1-2.800-2.800-1 2.800-1 1-2.800z")
        }
    }

    /** A screen with its antenna: unlike Series, this is live television. */
    val Manga by lazy { androidx.compose.material.icons.Icons.Filled.AutoStories }
    val Sports by lazy { androidx.compose.material.icons.Icons.Filled.EmojiEvents }

    val Tv by lazy {
        icon("tv") {
            fill("M4.500 8h15A2.500 2.500 0 0 1 22 10.500v8a2.500 2.500 0 0 1-2.500 2.500h-15A2.500 2.500 0 0 1 2 18.500v-8A2.500 2.500 0 0 1 4.500 8z")
            line("M7.500 2.800L12 7.200l4.500-4.400", 2f)
        }
    }

    val Search by lazy {
        icon("search") {
            line("M10.500 3.500a7 7 0 1 1 0 14 7 7 0 0 1 0-14z", 2.3f)
            line("M15.800 15.800l4.800 4.800", 2.6f)
        }
    }

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.fill(data: String) {
        addPath(addPathNodes(data), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.White))
    }

    private fun ImageVector.Builder.line(data: String, width: Float) {
        addPath(
            addPathNodes(data),
            stroke = SolidColor(Color.White),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
}
