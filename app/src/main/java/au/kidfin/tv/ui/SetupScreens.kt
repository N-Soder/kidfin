// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

// ---- Jellyfin sign-in (parent) --------------------------------------------------------------

@Composable
internal fun ServerSetupScreen(vm: MainViewModel) {
    BackHandler { vm.onBackToParentMenu() }
    val setup by vm.setup.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf(setup.address) }
    var username by rememberSaveable { mutableStateOf(setup.username) }
    // Deliberately not saveable: the password lives only in this composition until sign-in.
    var password by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val focus = rememberInitialFocus()
    val submit = {
        val pw = password
        password = ""
        vm.onSignIn(address, username, pw)
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Heading("Jellyfin", "Kids account")
        Spacer(Modifier.height(24.dp))
        // Typing (on the TV keyboard, which our keys handler never sees) keeps the grown-up's grant.
        Field("Server", address, { address = it; vm.onParentActivity() }, Modifier.focusRequester(focus), KeyboardType.Uri) {
            focusManager.moveFocus(FocusDirection.Down)
        }
        Spacer(Modifier.height(14.dp))
        Field("Username", username, { username = it; vm.onParentActivity() }, Modifier, KeyboardType.Text) {
            focusManager.moveFocus(FocusDirection.Down)
        }
        Spacer(Modifier.height(14.dp))
        Field("Password", password, { password = it; vm.onParentActivity() }, Modifier, KeyboardType.Password, secret = true, imeAction = ImeAction.Done) {
            submit()
        }
        if (address.isNotBlank() && !address.trim().startsWith("https://", ignoreCase = true)) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Not encrypted: use only on your home network.",
                style = MaterialTheme.typography.titleMedium,
                color = Tones.Sun.ink,
            )
        }
        Spacer(Modifier.height(22.dp))
        ButtonRow {
            PillButton(if (setup.busy) "Signing in…" else "Sign in", { submit() }, enabled = !setup.busy)
            PillButton("Back", vm::onBackToParentMenu, kind = PillKind.QUIET)
        }
        setup.message?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, style = MaterialTheme.typography.titleMedium, color = Tones.Sun.ink)
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier,
    type: KeyboardType,
    secret: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onIme: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = Sb.Muted, modifier = Modifier.width(190.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            interactionSource = interaction,
            textStyle = MaterialTheme.typography.titleLarge.copy(color = Sb.Ink),
            cursorBrush = SolidColor(Sb.Accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = imeAction, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onAny = { onIme() }),
            modifier = modifier
                .width(520.dp)
                .background(Sb.Card, FieldShape)
                .border(if (focused) 3.dp else 2.dp, if (focused) Sb.Ink else Sb.LineStrong, FieldShape)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}

private val FieldShape = RoundedCornerShape(14.dp)

// ---- Libraries (parent) ---------------------------------------------------------------------

@Composable
internal fun PickLibrariesScreen(vm: MainViewModel) {
    BackHandler { vm.onBackToParentMenu() }
    val state by vm.libraries.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Heading("Videos to show")
        Spacer(Modifier.height(24.dp))
        when (val s = state) {
            LibraryPickState.Loading -> Text("Loading…", color = Sb.Muted)
            is LibraryPickState.Failed -> {
                val focus = rememberInitialFocus(s)
                Text(s.message, color = Tones.Sun.ink, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(14.dp))
                ButtonRow {
                    PillButton("Try again", vm::onPickLibraries, Modifier.focusRequester(focus))
                    PillButton("Back", vm::onBackToParentMenu, kind = PillKind.QUIET)
                }
            }
            is LibraryPickState.Loaded -> {
                val focus = rememberInitialFocus(s.libraries)
                val save = remember { FocusRequester() }
                if (s.libraries.isEmpty()) {
                    Text("No video libraries on this account.", color = Tones.Sun.ink, style = MaterialTheme.typography.titleMedium)
                }
                // Scrolls sideways when there are more libraries than fit. The scroll area clips, so
                // it gets room for the focused card's scale and ring, and is shifted back left by
                // the same amount so the cards still line up with the heading.
                Row(
                    Modifier.offset(x = -LibraryRowInset).horizontalScroll(rememberScrollState())
                        .padding(horizontal = LibraryRowInset, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    s.libraries.forEachIndexed { i, lib ->
                        LibraryToggle(
                            name = lib.name,
                            selected = lib.id in s.selected,
                            tone = Tones.at(i),
                            // Down from a library goes to Save, not to whichever button is nearest.
                            modifier = (if (i == 0) Modifier.focusRequester(focus) else Modifier)
                                .focusProperties { down = save },
                        ) { vm.onToggleLibrary(lib.id) }
                    }
                }
                if (s.accountSeesEverything) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "This account can see every library. Limit it in Jellyfin.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Tones.Sun.ink,
                    )
                }
                Spacer(Modifier.height(24.dp))
                ButtonRow {
                    PillButton("Save", vm::onSaveLibraries, Modifier.focusRequester(save), enabled = s.selected.isNotEmpty())
                    PillButton("Back", vm::onBackToParentMenu, kind = PillKind.QUIET)
                }
            }
        }
        Notice(vm)
    }
}

private val LibraryRowInset = 16.dp

@Composable
private fun LibraryToggle(name: String, selected: Boolean, tone: Tone, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) tone.soft else Sb.Card,
            focusedContainerColor = if (selected) tone.soft else Sb.Card,
            contentColor = if (selected) tone.ink else Sb.Ink,
            focusedContentColor = if (selected) tone.ink else Sb.Ink,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(3.dp, if (selected) tone.strong else Sb.LineStrong), shape = shape),
            focusedBorder = Border(BorderStroke(4.dp, Sb.Ink), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Text(
            (if (selected) "✓  " else "") + name,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 18.dp),
        )
    }
}
