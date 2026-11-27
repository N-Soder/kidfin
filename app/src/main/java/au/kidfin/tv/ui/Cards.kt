// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import au.kidfin.tv.media.DurationLabels
import au.kidfin.tv.media.MediaItem
import au.kidfin.tv.media.RuntimeRules
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.launch

/** Jellyfin artwork over a soft tone fill with the title's initial (shown while loading or if none). */
@Composable
internal fun Artwork(item: MediaItem, tone: Tone, modifier: Modifier, initialSize: Int, poster: Boolean = false, onTone: (Tone) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember(item.id) { mutableStateOf(false) }
    Box(modifier.background(tone.soft), contentAlignment = Alignment.Center) {
        if (!loaded) Text(
            item.title.trimStart().take(1).uppercase(),
            style = MaterialTheme.typography.displayLarge.copy(fontSize = initialSize.sp, lineHeight = initialSize.sp),
            color = tone.strong,
        )
        // A poster fills a portrait frame; wide art is shown whole (letterboxed) if there's no poster.
        val url = if (poster) item.posterUrl ?: item.imageUrl else item.imageUrl
        val fill = poster && item.posterUrl != null
        url?.let { url ->
            val request = remember(url) {
                // Software bitmap so Palette can read the pixels for the card colour.
                ImageRequest.Builder(context).data(url).allowHardware(false).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                // Whole picture, never cropped: title text in the art stays readable.
                contentScale = if (fill) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.matchParentSize(),
                onSuccess = { state ->
                    loaded = true
                    val bitmap = state.result.image.toBitmap()
                    scope.launch { ArtworkTones.extract(item.id, bitmap)?.let(onTone) }
                },
            )
        }
    }
}

internal val CardShape = RoundedCornerShape(18.dp)

/** Storybook poster card: artwork-coloured border with a lip underneath; ink ring and lift when focused. */
@Composable
internal fun StoryCard(item: MediaItem, fallback: Tone, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    var tone by remember(item.id) { mutableStateOf(ArtworkTones.cached(item.id) ?: fallback) }
    Box(modifier) {
        Box(Modifier.matchParentSize().offset(y = 5.dp).background(tone.strong, CardShape))
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocused() },
            shape = CardDefaults.shape(shape = CardShape),
            colors = CardDefaults.colors(containerColor = Sb.Card, focusedContainerColor = Sb.Card),
            border = CardDefaults.border(
                border = Border(BorderStroke(3.dp, tone.strong), shape = CardShape),
                focusedBorder = Border(BorderStroke(4.dp, Sb.Ink), shape = CardShape),
                pressedBorder = Border(BorderStroke(4.dp, Sb.Ink), shape = CardShape),
            ),
            scale = CardDefaults.scale(focusedScale = 1.07f),
        ) {
            Box {
                Artwork(item, tone, Modifier.fillMaxWidth().aspectRatio(2f / 3f), initialSize = 40, poster = true) { tone = it }
                item.progress?.let { p ->
                    // How far through it they are (Keep watching).
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(6.dp).background(Sb.Bg.copy(alpha = 0.7f)))
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(p).height(6.dp).background(tone.strong))
                }
            }
        }
    }
}

@Composable
private fun CardLabel(item: MediaItem, small: Boolean) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = if (small) 8.dp else 12.dp)) {
        Text(
            item.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = if (small) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleLarge,
        )
        Text(
            listOfNotNull(item.seriesName?.takeUnless { item.title.startsWith(it) }, runtimeLabel(item)).joinToString(" · "),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyLarge,
            color = Sb.Muted,
        )
    }
}

internal fun runtimeLabel(item: MediaItem): String? =
    item.runtimeTicks?.takeIf { RuntimeRules.isPlayable(item) }?.let(DurationLabels::runtime)

/** Non-focusable large card for the approve screen. */
@Composable
internal fun FeatureCard(item: MediaItem, fallback: Tone, width: Dp) {
    var tone by remember(item.id) { mutableStateOf(ArtworkTones.cached(item.id) ?: fallback) }
    Box(Modifier.width(width)) {
        Box(Modifier.matchParentSize().offset(y = 6.dp).background(tone.strong, CardShape))
        Column(Modifier.fillMaxWidth().clip(CardShape).background(Sb.Card).border(3.dp, tone.strong, CardShape)) {
            Artwork(
                item,
                tone,
                Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                initialSize = 72,
                poster = true,
            ) { tone = it }
            CardLabel(item, small = false)
        }
    }
}
