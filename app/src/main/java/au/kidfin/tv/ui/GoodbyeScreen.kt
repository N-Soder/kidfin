// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun GoodbyeScreen() {
    // Non-interactive: Back does nothing while the sign-off plays.
    BackHandler { }
    val swim = rememberInfiniteTransition(label = "goodbye")
    // One slow swim stroke: the bell squeezes, then relaxes, the tentacles trail behind.
    val stroke by swim.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "stroke",
    )
    val phase = stroke * 2f * PI.toFloat()
    val squeeze = (sin(phase) + 1f) / 2f
    // Winds down over the whole sign-off: drifts up, slows to a doze and the screen dims.
    val windDown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        windDown.animateTo(1f, tween(MainViewModel.GOODBYE_MS.toInt(), easing = LinearEasing))
    }
    val w = windDown.value
    val dim = ((w - 0.8f) / 0.2f).coerceIn(0f, 1f)
    Box(Modifier.fillMaxSize().alpha(1f - dim * 0.85f), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.TopEnd) {
                JellyArt(
                    Modifier
                        .size(180.dp)
                        .offset { IntOffset(0, (sin(phase - 0.6f) * 10f - w * 40f).dp.roundToPx()) }
                        .graphicsLayer {
                            scaleX = 1f - 0.07f * squeeze
                            scaleY = 1f + 0.06f * squeeze
                            rotationZ = sin(phase / 2f) * 4f
                        },
                    sleepy = true,
                    sway = cos(phase),
                )
                Zzz(stroke, Modifier.offset(x = 40.dp, y = (-10).dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("All done for now", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(8.dp))
            Text("See you next time", style = MaterialTheme.typography.titleLarge, color = Sb.Muted)
        }
    }
}

/** Little z's that float up and fade, one per swim stroke. */
@Composable
private fun Zzz(t: Float, modifier: Modifier) {
    Box(modifier.size(70.dp, 90.dp)) {
        listOf(0f, 0.33f, 0.66f).forEachIndexed { i, start ->
            val local = (t - start + 1f) % 1f
            Text(
                "z",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = (16 + i * 6).sp),
                color = Sb.Brand,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (local * 30f).dp, y = (-local * 70f).dp)
                    .alpha(sin(local * PI.toFloat())),
            )
        }
    }
}
