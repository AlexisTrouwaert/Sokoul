package com.sokoul.pc.startup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sokoul.pc.theme.Palette

/*
 * "Breaking the evil eye": the intro of the Figma file "sokoul" (frame 3:1461, 960 x 400, 5.2 s timeline).
 * Every time, position and colour below comes from that frame's keyframes; the design is drawn in its own 960 x 400
 * space and scaled to the screen. Additions over the file: a metallic sheen sweeping the letters once the eye has
 * broken, and a fade into the app's background so the first screen follows without a cut.
 */

/** Length of the intro, sheen and fade included. */
const val INTRO_MILLIS = 2_900L

private const val FADE_FROM = 2_450L
private const val FRAME_W = 960f
private const val FRAME_H = 400f
private const val SCALE = 1.6f

/** Figma ease-out and ease-in-out curves, and the letters' own curve. */
private val EaseOut: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val EaseInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/** The impact: everything that breaks starts at 27.272 percent of 5.2 s. */
private const val IMPACT = 1_418f

/** Keyframes as (time ms, value); the easing given applies from that key to the next. */
private class Track(vararg val keys: Triple<Float, Float, Easing>) {
    fun at(t: Float): Float {
        if (t <= keys.first().first) return keys.first().second
        for (i in 0 until keys.size - 1) {
            val (t0, v0, e) = keys[i]
            val (t1, v1, _) = keys[i + 1]
            if (t < t1) return v0 + (v1 - v0) * e.transform(((t - t0) / (t1 - t0)).coerceIn(0f, 1f))
        }
        return keys.last().second
    }
}

private fun k(t: Float, v: Float, e: Easing = LinearEasing) = Triple(t, v, e)
private fun pct(p: Float) = p / 100f * 5_200f

private val Audiowide by lazy { FontFamily(Font("font/Audiowide-Regular.ttf", FontWeight.Normal)) }

/** Brushed pale gold: the gradient of the letters and of the eye, top to bottom. */
private val GoldStops = arrayOf(
    0f to Color(0xFFF4E8D1), 0.3f to Color(0xFFD8C7A8), 0.43f to Color(0xFFF7F1E8),
    0.52f to Color(0xFFB8A88A), 0.76f to Color(0xFFE8D9C1), 1f to Color(0xFFC9B08A),
)
private val StreakStops = arrayOf(
    0f to Color(0xFF85755E), 0.3f to Color(0xFFC6B18D), 0.52f to Color(0xFFFFF2D9), 0.72f to Color(0xFFE4C38D), 1f to Color(0xFF9F8C72),
)

/** The four quarters of the eye (134 x 55 each), their place in the frame and where the impact throws them. */
private class Fragment(val path: String, val x: Float, val y: Float, val gradientTop: Float, val rotate: Float, val dx: Float, val dy: Float)

private val Fragments = listOf(
    Fragment("M134 0C85 0 44 24 0 55H26C59 29 95 15 134 15V0ZM88 55C88 42.8 92.8464 31.0998 101.473 22.4731C110.1 13.8464 121.8 9 134 9V21C124.983 21 116.335 24.5821 109.958 30.9584C103.582 37.3346 100 45.9826 100 55H88Z", 346f, 145f, -17f, 0.314159f, -68f, -48f),
    Fragment("M0 0C49 0 90 24 134 55H108C75 29 39 15 0 15V0ZM0 9C12.2 9 23.9002 13.8464 32.5269 22.4731C41.1536 31.0998 46 42.8 46 55H34C34 45.9826 30.4179 37.3346 24.0416 30.9584C17.6654 24.5821 9.01736 21 0 21V9Z", 480f, 145f, -17f, -0.366519f, 76f, -42f),
    Fragment("M0 0C44 31 85 55 134 55V40C95 40 59 26 26 0H0ZM100 0C100 9.01736 103.582 17.6654 109.958 24.0416C116.335 30.4179 124.983 34 134 34V46C121.8 46 110.1 41.1536 101.473 32.5269C92.8464 23.9002 88 12.2 88 0H100Z", 346f, 200f, -72f, -0.436332f, -82f, 60f),
    Fragment("M134 0C90 31 49 55 0 55V40C39 40 75 26 108 0H134ZM0 34C9.01736 34 17.6654 30.4179 24.0416 24.0416C30.4179 17.6654 34 9.01736 34 0H46C46 12.2 41.1536 23.9002 32.5269 32.5269C23.9002 41.1536 12.2 46 0 46V34Z", 480f, 200f, -72f, 0.383972f, 86f, 63f),
)

private val FragmentOpacity = Track(k(0f, 0f), k(pct(4.231f), 0f, EaseOut), k(pct(13.077f), 1f), k(IMPACT, 1f, EaseOut), k(pct(33.81f), 0.62f, EaseOut), k(pct(43.618f), 0f))
private val FragmentBurst = Track(k(IMPACT, 0f, EaseOut), k(pct(43.618f), 1f))

/** Mineral shards: (x, y, w, h, rotation, dx, dy); two shapes and two golds alternate. */
private val Shards = listOf(
    floatArrayOf(466f, 190f, 8f, 6f, -0.523599f, -126f, -38f),
    floatArrayOf(476f, 202f, 10f, 5f, 0.785398f, -81f, -94f),
    floatArrayOf(486f, 190f, 8f, 6f, -1.134464f, 130f, -58f),
    floatArrayOf(466f, 202f, 10f, 5f, 0.610865f, 95f, 86f),
    floatArrayOf(476f, 190f, 8f, 6f, -1.396263f, -120f, 70f),
    floatArrayOf(486f, 202f, 10f, 5f, -0.698132f, 148f, 24f),
    floatArrayOf(466f, 190f, 8f, 6f, 1.134464f, -42f, 100f),
)
private const val SHARD_A = "M0 1L8 0L4 6L0 1Z"
private const val SHARD_B = "M0 0L10 3L3 5L0 0Z"
private val ShardOpacity = Track(k(pct(27.56f), 0f, EaseOut), k(pct(28.522f), 0.85f, EaseOut), k(pct(32.656f), 0.7f, EaseOut), k(pct(41.695f), 0f))
private val ShardBurst = Track(k(IMPACT, 0f, EaseOut), k(pct(41.695f), 1f))

private val CoreOpacity = Track(k(pct(4.231f), 0f, EaseOut), k(pct(11.538f), 0.9f), k(IMPACT, 0.9f, EaseOut), k(pct(28.618f), 0f))
private val CoreScale = Track(k(IMPACT, 1f, EaseOut), k(pct(29.964f), 0.1f))
private val CrackOpacity = Track(k(pct(26.118f), 0f, EaseOut), k(pct(27.656f), 0.9f, EaseOut), k(pct(32.08f), 0.3f, EaseOut), k(pct(35.733f), 0f))
private val WaveScale = Track(k(IMPACT, 0.2f, EaseOut), k(pct(33.426f), 2.8f))
private val WaveOpacity = Track(k(IMPACT, 0f, EaseOut), k(pct(28.137f), 0.45f, EaseOut), k(pct(33.426f), 0f))
private val FlashOpacity = Track(k(pct(26.791f), 0f, EaseOut), k(pct(28.041f), 0.24f, EaseOut), k(pct(33.426f), 0f))
private val HeatOpacity = Track(k(0f, 0f, EaseInOut), k(pct(12.5f), 0.012f, EaseInOut), k(IMPACT, 0.015f, EaseInOut), k(pct(28.81f), 0.045f, EaseInOut), k(pct(48.077f), 0.025f, EaseInOut), k(5_200f, 0.012f))

private val ShakeX = Track(k(IMPACT, 0f), k(pct(27.753f), 2f), k(pct(28.522f), -1.3f), k(pct(29.483f), 0.5f), k(pct(30.733f), 0f))
private val ShakeY = Track(k(IMPACT, 0f), k(pct(27.753f), -0.6f), k(pct(28.522f), 0.3f), k(pct(29.483f), 0f))

/** The light: it crosses the frame along one curve, from 20.192 to 34.615 percent of the timeline. */
private const val STREAK_FROM = 1_050f
private const val STREAK_TO = 1_800f
private val HeatStroke = Track(k(STREAK_FROM, 0f, EaseOut), k(pct(22.115f), 0.7f), k(STREAK_TO, 0.7f, EaseOut), k(pct(44.231f), 0f))
private val TrajectoryStroke = Track(k(STREAK_FROM, 0f, EaseOut), k(pct(21.538f), 0.9f), k(STREAK_TO, 0.9f, EaseOut), k(pct(46.154f), 0.12f, EaseOut), k(pct(57.692f), 0.04f))
private val WakeOpacity = Track(k(STREAK_FROM, 0f, EaseOut), k(pct(21.538f), 1f), k(STREAK_TO, 1f, EaseOut), k(pct(38.077f), 0f))
private val WakeEnd = Track(k(STREAK_FROM, 0f), k(pct(22.692f), 0.1733f), k(STREAK_TO, 1f), k(pct(38.077f), 1f))
private val WakeStart = Track(k(pct(22.692f), 0f), k(STREAK_TO, 0.86f), k(pct(38.077f), 1f))
private val TipOpacity = Track(k(pct(20.173f), 0f, EaseOut), k(pct(21.154f), 1f), k(STREAK_TO, 1f, EaseOut), k(pct(37.692f), 0f))
private val AuraOpacity = Track(k(pct(20.173f), 0f, EaseOut), k(pct(21.154f), 0.65f, EaseOut), k(STREAK_TO, 0.7f, EaseOut), k(pct(37.692f), 0f))
private val AuraScale = Track(k(0f, 0.5f, EaseOut), k(pct(21.154f), 0.8f, EaseOut), k(STREAK_TO, 0.9f, EaseOut), k(pct(37.692f), 0.3f))

/** Letters s o k o u l: x in the wordmark, and when each starts (380 ms, scale 1.02 to 1, blur 2.5 px to 0). */
private val Letters = listOf("s" to 0f, "o" to 63f, "k" to 138f, "o" to 212f, "u" to 287f, "l" to 364f)
private const val LETTER_FIRST = 634.6f
private const val LETTER_STEP = (832.4f - 634.6f) / 5f
private const val LETTER_MS = 380f
private val LetterEase: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/** Added: a narrow band of light sweeping the letters left to right, once they have taken the shock. */
private const val SHEEN_FROM = 1_700f
private const val SHEEN_TO = 2_350f

@Composable
fun SokoulIntro(modifier: Modifier = Modifier, onFinished: () -> Unit = {}) {
    var t by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (t < INTRO_MILLIS) t = withFrameMillis { it } - start
        onFinished()
    }
    val time = t.toFloat()
    val fade = ((time - FADE_FROM) / (INTRO_MILLIS - FADE_FROM)).coerceIn(0f, 1f)
    val fragments = remember { Fragments.map { PathParser().parsePathString(it.path).toPath() } }
    val shardPaths = remember { listOf(SHARD_A, SHARD_B).map { PathParser().parsePathString(it).toPath() } }
    val crack = remember { PathParser().parsePathString("M10 0L0 29L13 46L5 55L23 77L15 110").toPath() }
    val streak = remember { PathParser().parsePathString("M0 134C216 128 466 23 672 0").toPath() }
    val measure = remember { PathMeasure().apply { setPath(streak, false) } }

    Box(modifier.fillMaxSize().background(lerp(Color(0xFF050506), Palette.Background, fade)), contentAlignment = Alignment.Center) {
        Box(
            Modifier.requiredSize(FRAME_W.dp, FRAME_H.dp).graphicsLayer {
                scaleX = SCALE
                scaleY = SCALE
                alpha = 1f - fade
            },
        ) {
            // Behind the letters: residual heat, the contained flash, the eye and its shock.
            Canvas(Modifier.fillMaxSize()) {
                val u = size.width / FRAME_W
                scale(u, u, pivot = Offset.Zero) {
                    // Residual heat: a wide warm glow, a little stronger than the file's so it reads on a TV.
                    glow(Offset(480f, 205f), Size(600f, 200f), Color(0xFF9E7543), (HeatOpacity.at(time) * 4f).coerceAtMost(0.2f))
                    glow(Offset(480f, 200f), Size(140f, 60f), Color(0xFFFFF2DC), FlashOpacity.at(time))
                    val burst = FragmentBurst.at(time)
                    val fa = FragmentOpacity.at(time)
                    if (fa > 0f) Fragments.forEachIndexed { i, f ->
                        translate(f.x + f.dx * burst, f.y + f.dy * burst) {
                            rotate(Math.toDegrees((f.rotate * burst).toDouble()).toFloat(), pivot = Offset(67f, 27.5f)) {
                                scale(1f - 0.28f * burst, pivot = Offset(67f, 27.5f)) {
                                    drawPath(fragments[i], Brush.verticalGradient(*GoldStops, startY = f.gradientTop, endY = f.gradientTop + 138f), alpha = fa)
                                    drawPath(fragments[i], Color(0xFF876E4A).copy(alpha = 0.58f), alpha = fa, style = Stroke(0.65f))
                                }
                            }
                        }
                    }
                    val ca = CoreOpacity.at(time)
                    if (ca > 0f) {
                        val r = 8.5f * CoreScale.at(time)
                        drawCircle(Color(0xFF0B0E14), r, Offset(480f, 200f), alpha = ca)
                        drawCircle(Color(0xFFD8C7A8).copy(alpha = 0.95f), r * 0.95f, Offset(480f, 200f), alpha = ca, style = Stroke(0.85f))
                    }
                    CrackOpacity.at(time).takeIf { it > 0f }?.let { a ->
                        translate(463f, 145f) { drawPath(crack, Color(0xFFFFEFD1), alpha = a, style = Stroke(1.2f)) }
                    }
                    WaveOpacity.at(time).takeIf { it > 0f }?.let { a ->
                        drawCircle(Color(0xFFD8C7A8), 29.55f * WaveScale.at(time), Offset(480f, 200f), alpha = a, style = Stroke(0.9f / WaveScale.at(time).coerceAtLeast(0.2f) * 1.6f))
                    }
                }
            }
            // The wordmark, letter by letter, shaken by the impact.
            Box(Modifier.offset((281f + ShakeX.at(time)).dp, (128f + ShakeY.at(time)).dp)) {
                Letters.forEachIndexed { i, (letter, x) ->
                    val p = ((time - (LETTER_FIRST + LETTER_STEP * i)) / LETTER_MS).coerceIn(0f, 1f)
                    val e = LetterEase.transform(p)
                    if (e > 0f) {
                        val blur = 2.5f * (1f - e)
                        BasicText(
                            letter,
                            Modifier.offset(x.dp, 0.dp).graphicsLayer {
                                alpha = e
                                val s = 1.02f - 0.02f * e
                                scaleX = s
                                scaleY = s
                                if (blur > 0.05f) renderEffect = BlurEffect(blur * SCALE, blur * SCALE, TileMode.Decal)
                            },
                            style = TextStyle(
                                fontFamily = Audiowide,
                                fontSize = 108.sp,
                                brush = goldText(time, x),
                                shadow = Shadow(Color(0xBF040209), Offset(0f, 2f), 1f),
                            ),
                        )
                    }
                }
            }
            // The light crossing: warm trail, thin trajectory, the comet's wake, its aura and its point.
            Canvas(Modifier.fillMaxSize()) {
                val u = size.width / FRAME_W
                scale(u, u, pivot = Offset.Zero) {
                    translate(144f, 122f) {
                        val drawn = ((time - STREAK_FROM) / (STREAK_TO - STREAK_FROM)).coerceIn(0f, 1f)
                        if (drawn > 0f) {
                            val part = Path()
                            measure.getSegment(0f, measure.length * drawn, part, true)
                            val brush = Brush.horizontalGradient(*StreakStops, startX = 0f, endX = 672f)
                            drawPath(part, brush, alpha = HeatStroke.at(time) * 0.35f, style = Stroke(9f, cap = StrokeCap.Round))
                            drawPath(part, brush, alpha = HeatStroke.at(time) * 0.6f, style = Stroke(5f, cap = StrokeCap.Round))
                            drawPath(part, brush, alpha = TrajectoryStroke.at(time), style = Stroke(1f, cap = StrokeCap.Round))
                        }
                        val wa = WakeOpacity.at(time)
                        val from = WakeStart.at(time)
                        val to = WakeEnd.at(time)
                        if (wa > 0f && to > from) {
                            val wake = Path()
                            measure.getSegment(measure.length * from, measure.length * to, wake, true)
                            drawPath(wake, Brush.horizontalGradient(*StreakStops, startX = 0f, endX = 672f), alpha = wa, style = Stroke(2f, cap = StrokeCap.Round))
                        }
                        val ta = TipOpacity.at(time)
                        if (ta > 0f) {
                            val at = measure.getPosition(measure.length * drawn)
                            val gold = if (time < IMPACT) lerp(Color(0xFFC6A26C), Color(0xFFF0C88C), (time - STREAK_FROM) / (IMPACT - STREAK_FROM)) else lerp(Color(0xFFF0C88C), Color(0xFFBD945E), (time - IMPACT) / (STREAK_TO - IMPACT))
                            glow(at, Size(22f, 22f) * (AuraScale.at(time) * 1.6f), gold, AuraOpacity.at(time))
                            glow(at, Size(12f, 12f), Color(0xFFFFEFD0), ta * 0.8f)
                            drawCircle(Color(0xFFFFF4DE), 2.25f, at, alpha = ta)
                        }
                    }
                    // Mineral shards, thrown from the heart of the eye.
                    val sa = ShardOpacity.at(time)
                    if (sa > 0f) {
                        val b = ShardBurst.at(time)
                        Shards.forEachIndexed { i, s ->
                            translate(s[0] + s[5] * b, s[1] + s[6] * b) {
                                rotate(Math.toDegrees((s[4] * b).toDouble()).toFloat(), pivot = Offset(s[2] / 2, s[3] / 2)) {
                                    scale(0.8f - 0.55f * b, pivot = Offset(s[2] / 2, s[3] / 2)) {
                                        drawPath(shardPaths[i % 2], if (i % 2 == 0) Color(0xFFF2DEC1) else Color(0xFFD5B57E), alpha = sa)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The letters' brushed gold, with the added sheen passing over [x] (the letter's place in the wordmark). */
private fun goldText(time: Float, x: Float): Brush {
    val p = ((time - SHEEN_FROM) / (SHEEN_TO - SHEEN_FROM))
    if (p <= 0f || p >= 1.2f) return Brush.verticalGradient(*GoldStops)
    // The band's centre, in the wordmark's width (398 px), moved into this letter's own space.
    val centre = -60f + p * 520f - x
    return Brush.linearGradient(
        0f to Color(0xFFE8D9C1), 0.42f to Color(0xFFD8C7A8), 0.5f to Color(0xFFFFFBF2), 0.58f to Color(0xFFD8C7A8), 1f to Color(0xFFC9B08A),
        start = Offset(centre - 70f, 0f), end = Offset(centre + 70f, 140f),
    )
}

/** A soft round light: a radial gradient fading to nothing, in place of Figma's layer blur. */
private fun DrawScope.glow(center: Offset, size: Size, color: Color, alpha: Float) {
    if (alpha <= 0f || size.width <= 0f) return
    val r = maxOf(size.width, size.height) / 2f
    scale(size.width / (2 * r), size.height / (2 * r), pivot = center) {
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.4f), Color.Transparent), center, r * 1.4f), r * 1.4f, center)
    }
}

private fun lerp(a: Color, b: Color, f: Float) = androidx.compose.ui.graphics.lerp(a, b, f.coerceIn(0f, 1f))
