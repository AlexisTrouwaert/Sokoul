package com.sokoul.pc.startup

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sokoul.desktop.data.ProviderConfig
import com.sokoul.pc.i18n.Text
import com.sokoul.pc.i18n.tr
import com.sokoul.pc.theme.Metrics
import com.sokoul.pc.theme.Palette
import com.sokoul.pc.theme.Type
import com.sokoul.pc.ui.Label
import com.sokoul.pc.ui.PillButton
import com.sokoul.pc.ui.SectionPill
import com.sokoul.pc.ui.moveWithArrow
import com.sokoul.pc.ui.tvFocusable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val SPLASH_MILLIS = INTRO_MILLIS

internal fun fieldIsEditableOnTv(tv: Boolean, editing: Boolean): Boolean = !tv || editing

/** The animated Sokoul intro from the Figma file (see SokoulIntro), then the next screen with no user action. */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(SPLASH_MILLIS)
        onFinished()
    }
    SokoulIntro(Modifier.testTag("splash"))
}

/**
 * Same layout as the reference settings screen: large icon on the left, list of rows on the right.
 * The actions stay under the logo so a pre-filled form needs a single click.
 */
@Composable
fun SetupScreen(controller: SetupController, onDone: () -> Unit, onLater: (() -> Unit)?, onBack: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val primary = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { primary.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Palette.Background).testTag("setup")) {
        Column(
            Modifier.width(Metrics.SettingsColumnX).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(painterResource("sokoul.png"), "Sokoul", Modifier.size(300.dp))
            Spacer(Modifier.height(72.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(
                    label = tr(if (controller.busy) Text.Checking else Text.Continue),
                    onClick = { scope.launch { if (controller.submit()) onDone() } },
                    modifier = Modifier.testTag("continue"),
                    requester = primary,
                )
                if (onLater != null) {
                    PillButton(
                        label = tr(Text.ConfigureLater),
                        onClick = {
                            controller.defer()
                            onLater()
                        },
                        modifier = Modifier.testTag("later"),
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            val general = controller.errors[GENERAL_ERROR]
            Box(Modifier.height(64.dp).width(760.dp), contentAlignment = Alignment.TopCenter) {
                if (general != null) {
                    Label(tr(general), Type.Body.copy(textAlign = TextAlign.Center), Palette.Error, Modifier.testTag("general-error"))
                }
            }
        }

        Column(
            Modifier
                .offset(x = Metrics.SettingsColumnX)
                .width(Metrics.SettingsColumnWidth)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 96.dp),
            verticalArrangement = Arrangement.spacedBy(Metrics.RowGap),
        ) {
            SectionHeader(tr(Text.SectionIptv))
            val saved = controller.hasSavedIptv()
            val mask = if (saved) tr(Text.Saved) else tr(Text.NotSet)
            FieldRow(FIELD_SERVER, tr(Text.FieldServer), mask, controller)
            FieldRow(FIELD_USERNAME, tr(Text.FieldUsername), mask, controller)
            FieldRow(FIELD_PASSWORD, tr(Text.FieldPassword), mask, controller, secret = true)
            FieldRow(
                FIELD_EPG_URL,
                tr(Text.FieldEpgUrl),
                if (controller.hasSavedEpg()) tr(Text.Saved) else com.sokoul.pc.data.L("Optional · use provider default", "Facultatif · utiliser le guide fournisseur").text(),
                controller,
            )
            Label(
                com.sokoul.pc.data.L(
                    "Paste the XMLTV address supplied for your IPTV account. Enter off to clear it; leave empty to keep the saved source.",
                    "Collez l’adresse XMLTV fournie pour votre compte IPTV. Saisissez off pour l’effacer ; laissez vide pour conserver la source enregistrée.",
                ).text(),
                Type.Body, Palette.Secondary, Modifier.padding(start = 30.dp, end = 30.dp),
            )
            Spacer(Modifier.height(24.dp))
            SectionHeader(tr(Text.SectionApiKeys))
            ProviderConfig.providers.forEach { (id, label) ->
                FieldRow(id, label, controller.keyHint(id) ?: tr(Text.NotSet), controller, secret = true)
            }
            Spacer(Modifier.height(24.dp))
            SectionHeader(com.sokoul.pc.data.L("Anime sources", "Sources anime").text())
            FieldRow(
                FIELD_ANIME_BACKEND,
                com.sokoul.pc.data.L("AniProject backend URL", "Adresse du backend AniProject").text(),
                controller.animeBackendHint() ?: com.sokoul.pc.data.L("Optional · enter off to disable", "Facultatif · saisir off pour désactiver").text(),
                controller,
            )
            Label(
                com.sokoul.pc.data.L("Use your deployed website backend address. Sokoul's Mainapi address is separate.", "Saisissez l'adresse du backend du site déployé. Le serveur Mainapi de Sokoul est distinct.").text(),
                Type.Body, Palette.Secondary, Modifier.padding(start = 30.dp, end = 30.dp),
            )
        }

        SectionPill(
            Icons.Filled.Settings,
            tr(Text.SetupTitle),
            onClick = { onBack?.invoke() },
            modifier = Modifier.padding(start = 40.dp, top = 58.dp),
            onChevron = onBack,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Label(text, Type.SectionLabel, Palette.Secondary, Modifier.padding(start = 20.dp, bottom = 4.dp))
}

/** A row with its label on the left and its value on the right. Saved values appear only as a mask. */
@Composable
private fun FieldRow(id: String, label: String, placeholder: String, controller: SetupController, secret: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editorHadFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val fieldRequester = remember { FocusRequester() }
    val rowRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val value = controller.edits[id].orEmpty()
    val textColor = if (focused) Palette.OnFocus else Palette.OnBackground
    val hintColor = if (focused) Palette.OnFocus.copy(alpha = 0.55f) else Palette.Secondary
    val error = controller.errors[id]
    val tv = com.sokoul.pc.Platform.IS_TV
    fun finishEditing(restore: Boolean = true, direction: androidx.compose.ui.focus.FocusDirection? = null) {
        editing = false
        editorHadFocus = false
        if (controller.editingField == id) controller.editingField = null
        keyboard?.hide()
        if (restore) scope.launch {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { rowRequester.requestFocus() }
            if (direction != null) focusManager.moveFocus(direction)
        }
    }
    DisposableEffect(controller, id) {
        onDispose { if (controller.editingField == id) controller.editingField = null }
    }
    LaunchedEffect(editing) {
        if (editing && tv) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { fieldRequester.requestFocus() }
            androidx.compose.runtime.withFrameNanos { }
            keyboard?.show()
        }
    }
    val rowModifier = Modifier
        .fillMaxWidth()
        .height(Metrics.RowHeight)
        .clip(RoundedCornerShape(50))
        .background(if (focused) Palette.FocusFill else Palette.RowFill)
        .padding(horizontal = 30.dp)
        .let { row ->
            if (tv) {
                row.focusProperties { canFocus = !editing }.tvFocusable(
                    onFocusChanged = { focused = it },
                    onClick = { if (!editing) { editorHadFocus = false; controller.editingField = id; editing = true } },
                    requester = rowRequester,
                )
            } else {
                row
            }
        }
    Column {
        Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
            Label(label, Type.Button.copy(fontSize = Type.Meta.fontSize * 1.1f), textColor)
            Spacer(Modifier.width(32.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (!fieldIsEditableOnTv(tv, editing)) {
                    if (value.isEmpty()) {
                        Label(placeholder, Type.Meta, hintColor)
                    } else {
                        Label(
                            if (secret) "•••• ${value.takeLast(4)}" else value,
                            Type.Meta,
                            hintColor,
                        )
                    }
                } else {
                    if (value.isEmpty()) Label(placeholder, Type.Meta, hintColor)
                    BasicTextField(
                        value = value,
                        onValueChange = { controller.update(id, it) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            if (tv) {
                                finishEditing()
                            } else keyboard?.hide()
                        }),
                        textStyle = Type.Meta.copy(color = textColor, textAlign = TextAlign.End),
                        cursorBrush = SolidColor(textColor),
                        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(fieldRequester)
                            .testTag("field-$id")
                            .onFocusChanged {
                                focused = it.isFocused
                                if (it.isFocused) editorHadFocus = true
                                else if (tv && editorHadFocus) finishEditing(restore = false)
                            }
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) {
                                    false
                                } else if (tv && (e.key == Key.Escape || e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                                    finishEditing()
                                    true
                                } else if (e.key == Key.DirectionUp || e.key == Key.DirectionDown) {
                                    if (tv) {
                                        finishEditing(direction = if (e.key == Key.DirectionUp) androidx.compose.ui.focus.FocusDirection.Up else androidx.compose.ui.focus.FocusDirection.Down)
                                        true
                                    } else focusManager.moveWithArrow(e)
                                } else {
                                    false
                                }
                            },
                    )
                }
            }
        }
        if (error != null) {
            Label(tr(error), Type.Body, Palette.Error, Modifier.padding(start = 30.dp, top = 8.dp).testTag("error-$id"))
        }
    }
}
