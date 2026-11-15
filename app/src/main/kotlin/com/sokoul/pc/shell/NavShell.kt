package com.sokoul.pc.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.sokoul.pc.theme.NavMetrics
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.FocusSource
import com.sokoul.pc.ui.Form
import com.sokoul.pc.ui.Label
import com.sokoul.pc.ui.LocalActiveTab
import com.sokoul.pc.ui.NavIcons
import com.sokoul.pc.ui.tvFocusable

data class NavEntry(val id: String, val label: String, val icon: ImageVector)

const val SECTION_SEARCH = "search"
const val SECTION_SETTINGS = "settings"
const val SECTION_HOME = "home"

/** Whether the top bar is on screen; immersive TV pages reclaim its height while it is hidden. */
val LocalNavBarVisible = androidx.compose.runtime.compositionLocalOf { true }

/**
 * Top navigation: avatar on the left, sections in a floating translucent capsule in the middle, search on the right.
 * It stays above the page, and knows nothing about the screens.
 */
@Composable
fun NavShell(
    entries: List<NavEntry>,
    active: String,
    onSelect: (String) -> Unit,
    defaultFocus: FocusRequester? = null,
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // A phone has no remote to bring a hidden bar back: there it always stays.
    val immersive = com.sokoul.pc.Platform.IS_TV && !Form.isPhone
    val portrait = Form.isPortrait
    var barFocused by remember { mutableStateOf(false) }
    var barVisible by remember(active) { mutableStateOf(true) }
    LaunchedEffect(active, barFocused) {
        if (barFocused || !immersive) barVisible = true
        else {
            delay(if (active == "tv") 700 else 1200)
            barVisible = false
        }
    }
    val visibility by animateFloatAsState(if (barVisible) 1f else 0f, tween(220))
    val requesters = remember { HashMap<String, FocusRequester>() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun requester(id: String) = requesters.getOrPut(id) { FocusRequester() }

    // The page's first element when it exists, otherwise the section's own tab: focus is never lost.
    LaunchedEffect(active, defaultFocus) {
        if (runCatching { defaultFocus?.requestFocus() ?: error("none") }.isFailure) runCatching { requester(active).requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape && (onBack != null || (immersive && !barVisible))) {
                    // A page that can go back does so on the first press, even with the bar hidden: revealing the
                    // bar first made Back from the TV guide take two presses to reach Home.
                    if (immersive && !barVisible && onBack == null) {
                        barVisible = true
                        runCatching { requester(active).requestFocus() }
                        return@onKeyEvent true
                    }
                    onBack?.invoke()
                    true
                } else {
                    false
                }
            },
    ) {
        // Upright, the sections move to a bar at the bottom, within reach of the thumb; the page ends above it.
        Box(Modifier.fillMaxSize().padding(bottom = if (portrait) NavMetrics.BottomBar else 0.dp)) {
            CompositionLocalProvider(LocalActiveTab provides requester(active), LocalNavBarVisible provides barVisible) { content() }
        }
        if (portrait) {
            BottomTabs(entries, active, onSelect, ::requester, Modifier.align(Alignment.BottomCenter))
        }
        Box(Modifier.fillMaxWidth().height(NavMetrics.ScrimHeight).graphicsLayer { alpha = visibility }.background(Brush.verticalGradient(0f to Palette.BarScrim, 0.72f to Palette.BarScrim.copy(alpha = 0.72f), 1f to Color.Transparent)))
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = NavMetrics.Top)
                .graphicsLayer { alpha = visibility; translationY = -24.dp.toPx() * (1f - visibility) }
                .onFocusChanged {
                    barFocused = it.hasFocus
                    if (it.hasFocus) barVisible = true
                }
                .onPreviewKeyEvent { e ->
                    // Down always returns to the page, whichever tab has focus.
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown && defaultFocus != null) {
                        FocusSource.lastWasMouse = false
                        scope.launch {
                            androidx.compose.runtime.withFrameNanos { }
                            runCatching { defaultFocus.requestFocus() }
                        }
                        true
                    } else {
                        false
                    }
                }
                .focusGroup(),
        ) {
            Avatar(
                onClick = { onSelect(SECTION_SETTINGS) },
                isActive = active == SECTION_SETTINGS,
                requester = requester(SECTION_SETTINGS),
                modifier = Modifier.align(Alignment.CenterStart).padding(start = NavMetrics.Side),
            )
            if (!portrait) Row(
                Modifier
                    .align(Alignment.Center)
                    .height(NavMetrics.Height)
                    .clip(RoundedCornerShape(50))
                    .background(Palette.BarFill)
                    .border(1.dp, Palette.Hairline, RoundedCornerShape(50))
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                entries.forEach { entry ->
                    Tab(entry, entry.id == active, { onSelect(entry.id) }, requester(entry.id))
                }
            }
            RoundBarButton(
                icon = NavIcons.Search,
                onClick = { onSelect(SECTION_SEARCH) },
                isActive = active == SECTION_SEARCH,
                requester = requester(SECTION_SEARCH),
                tag = "menu-$SECTION_SEARCH",
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = NavMetrics.Side),
            )
        }
    }
}

/** The sections of a phone held upright: icon over name, the current one white, the others grey. */
@Composable
private fun BottomTabs(entries: List<NavEntry>, active: String, onSelect: (String) -> Unit, requester: (String) -> FocusRequester, modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(NavMetrics.BottomBar)
            .background(Palette.BarFill)
            .border(1.dp, Palette.Hairline)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEach { entry ->
            val isActive = entry.id == active
            val tint = if (isActive) Palette.OnBackground else Palette.Secondary
            androidx.compose.foundation.layout.Column(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .testTag("menu-${entry.id}")
                    .tvFocusable(onFocusChanged = {}, onClick = { onSelect(entry.id) }, requester = requester(entry.id)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(52.dp, 32.dp).clip(RoundedCornerShape(50)).background(if (isActive) Palette.ActiveFill else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(entry.icon, null, tint = tint, modifier = Modifier.size(NavMetrics.Icon))
                }
                Spacer(Modifier.height(4.dp))
                Label(entry.label, Type.TabLabel, tint, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Tab(entry: NavEntry, isActive: Boolean, onClick: () -> Unit, requester: FocusRequester) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(
        when {
            focused -> Palette.FocusFill
            isActive -> Palette.ActiveFill
            else -> Color.Transparent
        },
    )
    val scale by animateFloatAsState(if (focused) 1.05f else 1f)
    val tint = when {
        focused -> Palette.OnFocus
        isActive -> Palette.OnBackground
        else -> Palette.BarText
    }
    Row(
        Modifier
            .height(NavMetrics.Height - 12.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(50))
            .background(fill)
            .testTag("menu-${entry.id}")
            .tvFocusable(onFocusChanged = { focused = it }, onClick = onClick, requester = requester)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(entry.icon, null, tint = tint, modifier = Modifier.size(NavMetrics.Icon))
        Spacer(Modifier.width(11.dp))
        Label(entry.label, Type.Nav, tint, maxLines = 1)
    }
}

@Composable
private fun RoundBarButton(icon: ImageVector, onClick: () -> Unit, isActive: Boolean, requester: FocusRequester, tag: String, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(
        when {
            focused -> Palette.FocusFill
            isActive -> Palette.ActiveFill
            else -> Palette.BarFill
        },
    )
    Box(modifier) {
        Box(
            Modifier
                .size(NavMetrics.Height)
                .clip(CircleShape)
                .background(fill)
                .border(1.dp, Palette.Hairline, CircleShape)
                .testTag(tag)
                .tvFocusable(onFocusChanged = { focused = it }, onClick = onClick, requester = requester),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (focused) Palette.OnFocus else Palette.OnBackground, modifier = Modifier.size(NavMetrics.Icon + 2.dp))
        }
    }
}

/** The profile's initial on its colour; it leads to the settings. */
@Composable
private fun Avatar(onClick: () -> Unit, isActive: Boolean, requester: FocusRequester, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val profile = com.sokoul.pc.data.LocalProfile.current
    val name = profile?.name ?: remember { com.sokoul.pc.data.Profiles.defaultName() }
    val own = profile?.let { androidx.compose.ui.graphics.Color(com.sokoul.pc.data.Profiles.COLORS[it.color % com.sokoul.pc.data.Profiles.COLORS.size]) }
    val scale by animateFloatAsState(if (focused) 1.1f else 1f)
    val fill by animateColorAsState(if (focused) Palette.FocusFill else if (isActive) Palette.ActiveFill else own ?: Palette.AvatarFill)
    Box(modifier) {
        Box(
            Modifier
                .size(NavMetrics.Height)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(fill)
                .testTag("menu-$SECTION_SETTINGS")
                .tvFocusable(onFocusChanged = { focused = it }, onClick = onClick, requester = requester),
            contentAlignment = Alignment.Center,
        ) {
            Label(name.take(1).uppercase(), Type.Pill, if (focused) Palette.OnFocus else Palette.OnBackground)
        }
    }
}
