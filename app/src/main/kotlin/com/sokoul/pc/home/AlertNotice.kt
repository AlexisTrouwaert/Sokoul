package com.sokoul.pc.home

import androidx.compose.animation.AnimatedVisibility
import com.sokoul.pc.ui.glassSurface
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.sokoul.pc.AppDeps
import com.sokoul.pc.data.AlertNews
import com.sokoul.pc.data.L
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Art
import com.sokoul.pc.ui.Label
import kotlinx.coroutines.delay

private const val FIRST_CHECK_MS = 20_000L
private const val CHECK_EVERY_MS = 6 * 60 * 60_000L
private const val SHOWN_MS = 9_000L

/**
 * The news of followed titles, one notice at a time in the top right corner. It takes no focus, so it never steals the
 * remote; each piece of news is shown once.
 */
@Composable
fun BoxScope.AlertNotices(deps: AppDeps) {
    val queue = remember { mutableStateListOf<AlertNews>() }
    // On the wall clock, off the interface thread: an endless delay loop there would never let UI tests reach idle.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            delay(FIRST_CHECK_MS)
            while (true) {
                if (deps.alerts.items.isNotEmpty()) {
                    val news = runCatching { deps.alertNews() }.getOrDefault(emptyList())
                    androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                        news.forEach { n -> if (queue.none { it.alert.id == n.alert.id && it.key == n.key }) queue += n }
                    }
                }
                delay(CHECK_EVERY_MS)
            }
        }
    }
    var current by remember { mutableStateOf<AlertNews?>(null) }
    LaunchedEffect(queue.size, current) {
        if (current == null && queue.isNotEmpty()) {
            val next = queue.removeAt(0)
            deps.alerts.told(next)
            current = next
            delay(SHOWN_MS)
            current = null
        }
    }
    AnimatedVisibility(
        current != null,
        Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 60.dp).zIndex(50f),
        enter = fadeIn() + slideInHorizontally { it / 3 },
        exit = fadeOut() + slideOutHorizontally { it / 3 },
    ) {
        current?.let { Notice(it) }
    }
}

/** The app's short messages ([AppDeps.notice]), in the same glass as the alerts, at the top of the screen. */
@Composable
fun BoxScope.AppNotice(deps: AppDeps) {
    val text = deps.notice
    LaunchedEffect(text) {
        if (text != null) {
            delay(NOTICE_MS)
            deps.notice = null
        }
    }
    AnimatedVisibility(text != null, Modifier.align(Alignment.TopCenter).padding(top = 48.dp).zIndex(60f), enter = fadeIn(), exit = fadeOut()) {
        Label(
            text.orEmpty(), Type.Meta.copy(fontWeight = FontWeight.SemiBold), Palette.OnBackground,
            Modifier.glassSurface(RoundedCornerShape(50), 40.dp).padding(horizontal = 30.dp, vertical = 14.dp).testTag("app-notice"),
        )
    }
}

private const val NOTICE_MS = 4_000L

@Composable
private fun Notice(n: AlertNews) {
    Row(
        Modifier.width(620.dp).glassSurface(RoundedCornerShape(28.dp), 28.dp).padding(16.dp).testTag("alert-notice"),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(160.dp, 90.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Background)) {
            Art(n.alert.image, Modifier.matchParentSize(), placeholder = Palette.Background)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.NotificationsActive, null, tint = Palette.OnBackground, modifier = Modifier.size(24.dp))
                Label(
                    (if (n.season == null) L("Now available", "Disponible") else L("New episode", "Nouvel épisode")).text(),
                    Type.Body.copy(fontWeight = FontWeight.SemiBold), Palette.Secondary, maxLines = 1,
                )
            }
            Label(n.alert.title, Type.Meta.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            if (n.season != null) Label(L("Season ${n.season} · Episode ${n.episode}", "Saison ${n.season} · Épisode ${n.episode}").text(), Type.Body, Palette.BarText, maxLines = 1)
        }
    }
}
