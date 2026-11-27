// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults

@Composable
internal fun KidFinApp(vm: MainViewModel) {
    StoryTheme {
        Surface(modifier = Modifier.fillMaxSize(), colors = SurfaceDefaults.colors(containerColor = Sb.Bg)) {
            val screen by vm.screen.collectAsStateWithLifecycle()
            val activity = LocalActivity.current
            LaunchedEffect(vm) {
                // Like the system's own Back on a home app: to the background, not finished.
                vm.leaveRequests.collect { activity?.moveTaskToBack(true) }
            }
            if (screen == Screen.Player) {
                // Full bleed: no overscan padding around the video.
                PlayerScreen(vm)
                return@Surface
            }
            // Scrolling grids run to the bottom edge (cut-off posters there are fine); their
            // content padding keeps the end of the list clear of TV overscan.
            val scrolls = screen == Screen.Shelf || screen is Screen.Episodes || screen == Screen.Search
            Box(
                Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent {
                        // A press on a parent screen means the grown-up is still there.
                        if (it.type == KeyEventType.KeyDown && screen.isParentScreen()) vm.onParentActivity()
                        false
                    }
                    .padding(start = 48.dp, end = 48.dp, top = 27.dp, bottom = if (scrolls) 0.dp else 27.dp),
            ) {
                when (val s = screen) {
                    Screen.Loading -> Unit
                    Screen.SetupPin, Screen.ChangePin -> PinScreen(vm, title = "Secret moves")
                    Screen.PinCorrupt -> PinCorruptScreen()
                    Screen.Shelf -> ShelfScreen(vm)
                    is Screen.Episodes -> EpisodesScreen(vm, s.series)
                    Screen.Search -> SearchScreen(vm)
                    Screen.EnterPin -> PinScreen(vm, title = "Grown-ups only")
                    Screen.LeaveKidfin -> PinScreen(vm, title = "Leave Kidfin?")
                    Screen.ParentMenu -> ParentMenuScreen(vm)
                    is Screen.Approve -> ApproveScreen(vm, s.item, s.offer)
                    Screen.Browse -> BrowseScreen(vm)
                    Screen.Resume -> ResumeScreen(vm)
                    Screen.Player -> Unit
                    Screen.Goodbye -> GoodbyeScreen()
                    Screen.TvPower -> TvPowerScreen(vm)
                    Screen.ConfirmTvPowerTest -> ConfirmTvPowerTestScreen(vm)
                    Screen.ServerSetup -> ServerSetupScreen(vm)
                    Screen.PickLibraries -> PickLibrariesScreen(vm)
                }
            }
        }
    }
}

private fun Screen.isParentScreen(): Boolean = when (this) {
    Screen.ParentMenu, Screen.TvPower, Screen.ConfirmTvPowerTest,
    Screen.ServerSetup, Screen.PickLibraries, Screen.ChangePin -> true
    else -> false
}
