// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import au.kidfin.tv.security.PinFormat

@Composable
internal fun PinCorruptScreen() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Heading(
            "Parent PIN can't be read",
            "For safety the app stays locked. A grown-up needs to clear this app's storage in the TV's " +
                "Settings, then set a new PIN.",
        )
    }
}

@Composable
internal fun PinScreen(vm: MainViewModel, title: String) {
    val pin by vm.pin.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val pending by vm.pendingItem.collectAsStateWithLifecycle()
    BackHandler(enabled = screen == Screen.EnterPin || screen == Screen.ChangePin || screen == Screen.LeaveKidfin) {
        if (screen == Screen.LeaveKidfin) vm.onBackFromLeave() else vm.onBackFromParent()
    }
    val moves = pin.format == PinFormat.MOVES
    val focus = rememberInitialFocus(moves)
    Column(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val key = (if (moves) MOVE_KEYS else DIGIT_KEYS)[e.key]
            val ok = e.key == Key.DirectionCenter || e.key == Key.Enter
            // A held key repeats: only the first press counts. Repeats are swallowed so they
            // neither add moves nor move focus off the hidden target.
            if (e.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent key != null || (moves && ok)
            when {
                key != null -> { vm.onPinKey(key); true }
                moves && ok -> { vm.onPinClear(); true }
                e.key == Key.Delete -> { vm.onPinDelete(); true }
                else -> false
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(6.dp))
        val sub = pin.message
            ?: pending?.let { listOfNotNull(it.title, runtimeLabel(it)).joinToString(" · ") }
            ?: if (moves) "Secret moves" else "Enter PIN"
        Text(sub, style = MaterialTheme.typography.titleMedium, color = if (pin.message != null) Tones.Sun.ink else Sb.Muted)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(pin.format.length) { i ->
                Box(Modifier.size(20.dp).background(if (i < pin.entered) Tones.Sun.strong else Sb.LineStrong, CircleShape))
            }
        }
        if (moves) {
            // Nothing on screen shows which arrow was pressed: no keys, no moving highlight.
            // An invisible focus target takes the presses; OK clears and starts again.
            Box(Modifier.size(1.dp).focusRequester(focus).focusable())
            Spacer(Modifier.height(20.dp))
            Text("Arrows · OK to start again", style = MaterialTheme.typography.bodyMedium, color = Sb.Muted)
            return@Column
        }
        Spacer(Modifier.height(20.dp))
        listOf("123", "456", "789").forEachIndexed { r, keys ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                keys.forEachIndexed { k, ch ->
                    val mod = if (r == 0 && k == 0) Modifier.focusRequester(focus) else Modifier
                    KeyButton(ch.toString(), mod) { vm.onPinKey(ch) }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.width(KeyWidth))
            KeyButton("0", Modifier) { vm.onPinKey('0') }
            KeyButton("⌫", Modifier, vm::onPinDelete)
        }
    }
}

private val KeyWidth = 88.dp

@Composable
private fun KeyButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.width(KeyWidth),
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(percent = 50)),
        colors = ButtonDefaults.colors(
            containerColor = Sb.Card, contentColor = Sb.Ink,
            focusedContainerColor = Sb.Ink, focusedContentColor = Sb.Bg,
            pressedContainerColor = Sb.Ink, pressedContentColor = Sb.Bg,
        ),
    ) {
        Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge)
    }
}

private val MOVE_KEYS = mapOf(
    Key.DirectionUp to 'U', Key.DirectionDown to 'D', Key.DirectionLeft to 'L', Key.DirectionRight to 'R',
)

private val DIGIT_KEYS = mapOf(
    Key.Zero to '0', Key.One to '1', Key.Two to '2', Key.Three to '3', Key.Four to '4',
    Key.Five to '5', Key.Six to '6', Key.Seven to '7', Key.Eight to '8', Key.Nine to '9',
    Key.NumPad0 to '0', Key.NumPad1 to '1', Key.NumPad2 to '2', Key.NumPad3 to '3', Key.NumPad4 to '4',
    Key.NumPad5 to '5', Key.NumPad6 to '6', Key.NumPad7 to '7', Key.NumPad8 to '8', Key.NumPad9 to '9',
)
