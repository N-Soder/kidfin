// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import au.kidfin.tv.jellyfin.ConnectionState
import au.kidfin.tv.session.Extension
import au.kidfin.tv.session.SessionState

// ---- Settings (locked) ----------------------------------------------------------------------

@Composable
private fun SettingsList(vm: MainViewModel, focus: FocusRequester) {
    val connection by vm.connection.collectAsStateWithLifecycle()
    val connected by vm.standbyConnected.collectAsStateWithLifecycle()
    val config = when (val c = connection) {
        is ConnectionState.Ready -> c.config
        is ConnectionState.NeedsLibraries -> c.config
        is ConnectionState.SignedOut -> c.config
        ConnectionState.NotSetUp -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsRow(
            "Jellyfin",
            when (connection) {
                is ConnectionState.SignedOut -> "Signed out"
                ConnectionState.NotSetUp -> "Not connected"
                else -> config?.userName.orEmpty()
            },
            Modifier.focusRequester(focus),
            vm::onServerSetup,
        )
        SettingsRow(
            "Videos to show",
            config?.libraryNames?.takeIf { it.isNotEmpty() }?.joinToString() ?: "None",
            Modifier,
            vm::onPickLibraries,
            enabled = connection is ConnectionState.Ready || connection is ConnectionState.NeedsLibraries,
        )
        SettingsRow(
            "TV power",
            if (connected) "On" else "Off",
            Modifier,
            vm::onTvPower,
        )
    }
}

@Composable
private fun SettingsRow(title: String, value: String, modifier: Modifier, onClick: () -> Unit, enabled: Boolean = true) {
    val shape = RoundedCornerShape(18.dp)
    androidx.tv.material3.Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.width(900.dp),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Sb.Card,
            focusedContainerColor = Sb.Card,
            contentColor = Sb.Ink,
            focusedContentColor = Sb.Ink,
            disabledContainerColor = Sb.Card.copy(alpha = 0.5f),
            disabledContentColor = Sb.Muted,
        ),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(2.dp, Sb.LineStrong), shape = shape),
            focusedBorder = Border(BorderStroke(4.dp, Sb.Ink), shape = shape),
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(horizontal = 28.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(240.dp))
            Text(value, style = MaterialTheme.typography.bodyLarge, color = Sb.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("›", style = MaterialTheme.typography.headlineMedium, color = Sb.Muted)
        }
    }
}

// ---- Parent menu ----------------------------------------------------------------------------

@Composable
internal fun ParentMenuScreen(vm: MainViewModel) {
    BackHandler { vm.onBackFromParent() }
    val session by vm.session.collectAsStateWithLifecycle()
    val remaining by vm.remainingLabel.collectAsStateWithLifecycle()
    val focus = rememberInitialFocus()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        when (val s = session) {
            is SessionState.Locked, is SessionState.Ending -> {
                Text("Settings", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(24.dp))
                SettingsList(vm, focus)
                Spacer(Modifier.height(24.dp))
                PillButton("Done", vm::onBackFromParent, large = true)
            }
            is SessionState.Timed -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Watching", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.width(20.dp))
                    remaining?.let { TimePill(it) }
                }
                Spacer(Modifier.height(28.dp))
                ButtonRow {
                    Extension.entries.forEachIndexed { i, ext ->
                        val mod = if (i == 0) Modifier.focusRequester(focus) else Modifier
                        PillButton("+${ext.minutes} min", { vm.onExtend(ext) }, mod)
                    }
                }
                Spacer(Modifier.height(24.dp))
                ButtonRow {
                    PillButton("End now", vm::onParentEnd, kind = PillKind.QUIET)
                    PillButton("Choose something else", vm::onChooseSomethingElse, kind = PillKind.QUIET)
                    PillButton("Back", vm::onBackFromParent, kind = PillKind.QUIET)
                }
            }
            is SessionState.OneTitle -> {
                Heading(s.approvedItemTitle)
                Spacer(Modifier.height(28.dp))
                ButtonRow {
                    PillButton("Back", vm::onBackFromParent, Modifier.focusRequester(focus))
                    PillButton("End now", vm::onParentEnd, kind = PillKind.QUIET)
                    PillButton("Choose something else", vm::onChooseSomethingElse, kind = PillKind.QUIET)
                }
            }
        }
        Notice(vm)
    }
}

// ---- TV power (parent) ----------------------------------------------------------------------

@Composable
internal fun TvPowerScreen(vm: MainViewModel) {
    BackHandler { vm.onBackFromParent() }
    val connected by vm.standbyConnected.collectAsStateWithLifecycle()
    val last by vm.lastPowerResult.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focus = rememberInitialFocus()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Heading("TV off at the end", if (connected) "On" else "Off")
        if (!connected) {
            Spacer(Modifier.height(16.dp))
            listOf(
                "Accessibility › Services › Kidfin TV power › On",
                "Not listed? Set the TV language to English (United States) first.",
            ).forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }
        last?.let {
            Spacer(Modifier.height(8.dp))
            Text("Last: $it", style = MaterialTheme.typography.bodyLarge, color = Sb.Muted)
        }
        Spacer(Modifier.height(28.dp))
        ButtonRow {
            PillButton("Back", vm::onBackFromParent, Modifier.focusRequester(focus))
            PillButton("Open Accessibility", {
                try {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: ActivityNotFoundException) {
                    vm.onSettingsUnreachable()
                }
            }, kind = PillKind.QUIET)
            if (connected) PillButton("Test", vm::onTvPowerTest, kind = PillKind.QUIET)
        }
        Notice(vm)
    }
}

@Composable
internal fun ConfirmTvPowerTestScreen(vm: MainViewModel) {
    BackHandler { vm.onCancelTvPowerTest() }
    val focus = rememberInitialFocus()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Heading("Turn the TV off now?")
        Spacer(Modifier.height(28.dp))
        ButtonRow {
            // Safe action first and focused.
            PillButton("Cancel", vm::onCancelTvPowerTest, Modifier.focusRequester(focus))
            PillButton("Turn TV off", vm::onConfirmTvPowerTest, kind = PillKind.QUIET)
        }
    }
}
