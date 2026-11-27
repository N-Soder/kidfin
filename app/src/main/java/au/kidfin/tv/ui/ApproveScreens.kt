// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import au.kidfin.tv.media.MediaItem
import au.kidfin.tv.media.MediaType
import au.kidfin.tv.media.RuntimeRules
import au.kidfin.tv.session.ApprovalOffer

@Composable
internal fun ApproveScreen(vm: MainViewModel, item: MediaItem, offer: ApprovalOffer) {
    BackHandler { vm.onBackFromParent() }
    val favourites by vm.favouriteIds.collectAsStateWithLifecycle()
    val approving by vm.approving.collectAsStateWithLifecycle()
    val isFavourite = (item.seriesId ?: item.id) in favourites
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        FeatureCard(item, Tones.at(Math.floorMod(item.id.hashCode(), 5)), 200.dp)
        Spacer(Modifier.width(44.dp))
        Column(Modifier.weight(1f)) {
            val focus = rememberInitialFocus(item.id)
            if (offer.isLong) {
                // Longer than the presets, or no length on the server: say which.
                Text(
                    if (RuntimeRules.isPlayable(item)) "It's a long one" else "We don't know how long this one is",
                    style = MaterialTheme.typography.displaySmall,
                )
                Spacer(Modifier.height(24.dp))
                ButtonRow {
                    PillButton("Play just this one", { vm.onApproveJustThis(item) }, Modifier.focusRequester(focus), large = true)
                    PillButton("Pick something else", vm::onBackFromParent, kind = PillKind.QUIET, large = true)
                }
                Spacer(Modifier.height(12.dp))
                FavouriteButton(item, isFavourite) { vm.onToggleFavourite(item) }
            } else {
                Text("How long?", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(24.dp))
                ButtonRow {
                    offer.options.forEach { o ->
                        val mod = if (o.allowance == offer.suggested) Modifier.focusRequester(focus) else Modifier
                        val label = MainViewModel.allowanceLabel(o.allowance)
                        PillButton(label, { vm.onApproveTimed(item, o.allowance) }, mod, enabled = o.fitsPickedTitle, large = true)
                    }
                }
                Spacer(Modifier.height(12.dp))
                ButtonRow {
                    PillButton("Just this one", { vm.onApproveJustThis(item) }, kind = PillKind.LINK)
                    FavouriteButton(item, isFavourite) { vm.onToggleFavourite(item) }
                }
            }
            if (approving) {
                // Checking the title with the server; further presses are ignored meanwhile.
                Spacer(Modifier.height(12.dp))
                Text("Starting…", style = MaterialTheme.typography.titleMedium, color = Sb.Muted)
            }
            Notice(vm)
        }
    }
}

@Composable
private fun FavouriteButton(item: MediaItem, isFavourite: Boolean, onClick: () -> Unit) {
    // An episode hearts its whole show, so say so by name ("♡ Favourite Bluey").
    val show = item.seriesName?.takeIf { item.type == MediaType.EPISODE }
    val label = when {
        show != null && isFavourite -> "♥ $show"
        show != null -> "♡ Favourite $show"
        isFavourite -> "♥ Favourite"
        else -> "♡ Add to favourites"
    }
    PillButton(label, onClick, kind = PillKind.LINK)
}
