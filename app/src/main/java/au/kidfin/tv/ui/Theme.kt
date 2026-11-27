// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import au.kidfin.tv.R

/**
 * "Storybook" night palette. Dark on the TV because it is used in the evening on a large
 * screen. The brand colour is plum.
 */
internal object Sb {
    val Bg = Color(0xFF18161D)
    val Card = Color(0xFF232029)
    val Ink = Color(0xFFF6EFE4)
    val Muted = Color(0xFFB3AA9C)
    val Line = Color(0xFF332F3A)
    val LineStrong = Color(0xFF423D4A)
    val Accent = Color(0xFFF07A61)
    val OnAccent = Color(0xFF1C1418)
    val Brand = Color(0xFFB48AE0)
}

/** A colour family: strong (borders, glyphs), soft (fills) and ink (text on soft). */
internal data class Tone(val strong: Color, val soft: Color, val ink: Color)

internal object Tones {
    val Tomato = Tone(Color(0xFFF07A61), Color(0xFF45261F), Color(0xFFFFAB98))
    val Teal = Tone(Color(0xFF3FC1B1), Color(0xFF163C38), Color(0xFF84E2D6))
    val Sun = Tone(Color(0xFFF6B955), Color(0xFF43331B), Color(0xFFFFD38A))
    val Plum = Tone(Color(0xFFB48AE0), Color(0xFF352A47), Color(0xFFD6BFF2))
    val Sky = Tone(Color(0xFF6EA6EA), Color(0xFF1F3049), Color(0xFFA9CBF5))

    /** Same cycle as the dashboard's steps. */
    private val cycle = listOf(Tomato, Teal, Sun, Plum, Sky)
    fun at(index: Int): Tone = cycle[Math.floorMod(index, cycle.size)]
}

private fun fraunces(weight: Int) = Font(
    R.font.fraunces,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("SOFT", 100f),
        FontVariation.Setting("WONK", 1f),
        FontVariation.Setting("opsz", 72f),
    ),
)

private fun outfit(weight: Int) = Font(
    R.font.outfit,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Headings and the wordmark: soft, slightly wonky serif (OFL). */
internal val Serif = FontFamily(fraunces(650))

/** Everything else: round sans (OFL). */
internal val Sans = FontFamily(outfit(400), outfit(500), outfit(600), outfit(700))

// No colour in the styles: text takes LocalContentColor, so buttons can recolour it on focus.
private val Heading = TextStyle(fontFamily = Serif, fontWeight = FontWeight(650), letterSpacing = (-0.3).sp)
private val Body = TextStyle(fontFamily = Sans)

internal val StoryTypography = Typography(
    displayLarge = Heading.copy(fontSize = 52.sp, lineHeight = 56.sp),
    displayMedium = Heading.copy(fontSize = 42.sp, lineHeight = 46.sp),
    displaySmall = Heading.copy(fontSize = 34.sp, lineHeight = 38.sp),
    headlineLarge = Heading.copy(fontSize = 30.sp, lineHeight = 34.sp),
    headlineMedium = Heading.copy(fontSize = 26.sp, lineHeight = 30.sp),
    headlineSmall = Heading.copy(fontSize = 22.sp, lineHeight = 26.sp),
    titleLarge = Body.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight(600)),
    titleMedium = Body.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight(600)),
    titleSmall = Body.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight(600)),
    bodyLarge = Body.copy(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = Body.copy(fontSize = 16.sp, lineHeight = 22.sp),
    bodySmall = Body.copy(fontSize = 14.sp, lineHeight = 18.sp),
    labelLarge = Body.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight(600)),
    labelMedium = Body.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight(500)),
    labelSmall = Body.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight(600), letterSpacing = 1.4.sp),
)

internal val StoryColours = darkColorScheme(
    primary = Sb.Accent,
    onPrimary = Sb.OnAccent,
    background = Sb.Bg,
    onBackground = Sb.Ink,
    surface = Sb.Bg,
    onSurface = Sb.Ink,
    surfaceVariant = Sb.Card,
    onSurfaceVariant = Sb.Muted,
    border = Sb.LineStrong,
)

@Composable
internal fun StoryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StoryColours, typography = StoryTypography, content = content)
}

// ---- Buttons --------------------------------------------------------------------------------

private val Pill = RoundedCornerShape(percent = 50)

internal enum class PillKind { PRIMARY, QUIET, LINK }

/**
 * Pill button. PRIMARY: card fill, accent when focused. QUIET: hairline outline, ink when
 * focused. LINK: accent text, accent pill when focused. Disabled options stay visible but dim.
 */
@Composable
internal fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: PillKind = PillKind.PRIMARY,
    enabled: Boolean = true,
    large: Boolean = false,
) {
    val colors = when (kind) {
        PillKind.PRIMARY -> ButtonDefaults.colors(
            containerColor = Sb.Card, contentColor = Sb.Ink,
            focusedContainerColor = Sb.Accent, focusedContentColor = Sb.OnAccent,
            pressedContainerColor = Sb.Accent, pressedContentColor = Sb.OnAccent,
            disabledContainerColor = Sb.Card.copy(alpha = 0.4f), disabledContentColor = Sb.Muted.copy(alpha = 0.5f),
        )
        PillKind.QUIET -> ButtonDefaults.colors(
            containerColor = Color.Transparent, contentColor = Sb.Muted,
            focusedContainerColor = Sb.Ink, focusedContentColor = Sb.Bg,
            pressedContainerColor = Sb.Ink, pressedContentColor = Sb.Bg,
            disabledContainerColor = Color.Transparent, disabledContentColor = Sb.Muted.copy(alpha = 0.4f),
        )
        PillKind.LINK -> ButtonDefaults.colors(
            containerColor = Color.Transparent, contentColor = Sb.Accent,
            focusedContainerColor = Sb.Accent, focusedContentColor = Sb.OnAccent,
            pressedContainerColor = Sb.Accent, pressedContentColor = Sb.OnAccent,
            disabledContainerColor = Color.Transparent, disabledContentColor = Sb.Muted.copy(alpha = 0.4f),
        )
    }
    val ring = Border(BorderStroke(3.dp, Sb.Ink), shape = Pill)
    val outline = if (kind == PillKind.QUIET) Border(BorderStroke(2.dp, Sb.LineStrong), shape = Pill) else Border.None
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = ButtonDefaults.shape(shape = Pill),
        colors = colors,
        border = ButtonDefaults.border(border = outline, focusedBorder = ring, pressedBorder = ring),
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        contentPadding = if (large) PaddingValues(horizontal = 26.dp, vertical = 12.dp) else PaddingValues(horizontal = 20.dp, vertical = 9.dp),
    ) {
        Text(text, style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
    }
}

/** Marigold "18 min left" pill with a small clock. */
@Composable
internal fun TimePill(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Tones.Sun.soft, Pill).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val r = size.minDimension / 2
            val w = size.minDimension * 0.14f
            drawCircle(Tones.Sun.strong, r - w / 2, style = Stroke(w))
            drawLine(Tones.Sun.strong, center, Offset(center.x, center.y - r * 0.5f), w, StrokeCap.Round)
            drawLine(Tones.Sun.strong, center, Offset(center.x + r * 0.35f, center.y + r * 0.2f), w, StrokeCap.Round)
        }
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = Tones.Sun.ink)
    }
}

/** Round settings button with a drawn cog. Quiet until focused, like a QUIET pill. */
@Composable
internal fun CogButton(onClick: () -> Unit, modifier: Modifier = Modifier) = RoundIconButton(onClick, modifier) { drawCog(it) }

/** Round heart button: outline until hearted, then filled pink. */
@Composable
internal fun HeartButton(hearted: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) =
    RoundIconButton(onClick, modifier) { tint ->
        val w = size.width
        val h = size.height
        val heart = Path().apply {
            moveTo(w * 0.5f, h * 0.80f)
            cubicTo(w * 0.12f, h * 0.55f, w * 0.14f, h * 0.22f, w * 0.34f, h * 0.22f)
            cubicTo(w * 0.43f, h * 0.22f, w * 0.48f, h * 0.28f, w * 0.5f, h * 0.34f)
            cubicTo(w * 0.52f, h * 0.28f, w * 0.57f, h * 0.22f, w * 0.66f, h * 0.22f)
            cubicTo(w * 0.86f, h * 0.22f, w * 0.88f, h * 0.55f, w * 0.5f, h * 0.80f)
            close()
        }
        if (hearted) {
            drawPath(heart, HeartPink)
        } else {
            drawPath(heart, tint, style = Stroke(size.minDimension * 0.11f, join = StrokeJoin.Round))
        }
    }

private val HeartPink = Color(0xFFFF6F91)

/** Round search button with a drawn magnifying glass. */
@Composable
internal fun SearchButton(onClick: () -> Unit, modifier: Modifier = Modifier) = RoundIconButton(onClick, modifier) { tint ->
    val r = size.minDimension * 0.30f
    val c = Offset(size.width * 0.42f, size.height * 0.42f)
    val w = size.minDimension * 0.13f
    drawCircle(tint, radius = r, center = c, style = Stroke(w))
    val d = r * 0.72f
    drawLine(tint, Offset(c.x + d, c.y + d), Offset(size.width * 0.9f, size.height * 0.9f), w * 1.2f, StrokeCap.Round)
}

@Composable
private fun RoundIconButton(onClick: () -> Unit, modifier: Modifier, icon: DrawScope.(Color) -> Unit) {
    val ring = Border(BorderStroke(3.dp, Sb.Ink), shape = androidx.compose.foundation.shape.CircleShape)
    val outline = Border(BorderStroke(2.dp, Sb.LineStrong), shape = androidx.compose.foundation.shape.CircleShape)
    Button(
        onClick = onClick,
        modifier = modifier.size(58.dp),
        shape = ButtonDefaults.shape(shape = androidx.compose.foundation.shape.CircleShape),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent, contentColor = Sb.Muted,
            focusedContainerColor = Sb.Ink, focusedContentColor = Sb.Bg,
            pressedContainerColor = Sb.Ink, pressedContentColor = Sb.Bg,
        ),
        border = ButtonDefaults.border(border = outline, focusedBorder = ring, pressedBorder = ring),
        scale = ButtonDefaults.scale(focusedScale = 1.08f),
        contentPadding = PaddingValues(0.dp),
    ) {
        val tint = androidx.tv.material3.LocalContentColor.current
        Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier
                    .size(28.dp)
                    // Offscreen so a cleared hole shows the button behind, not the window.
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen },
            ) { icon(tint) }
        }
    }
}

/** Eight rounded teeth around a ring, with a hole: reads as "settings" at TV distance. */
private fun DrawScope.drawCog(colour: Color) {
    val r = size.minDimension / 2
    val toothW = r * 0.42f
    val toothH = r * 0.34f
    for (i in 0 until 8) {
        rotate(degrees = i * 45f, pivot = center) {
            drawRoundRect(
                colour,
                topLeft = Offset(center.x - toothW / 2, center.y - r),
                size = androidx.compose.ui.geometry.Size(toothW, toothH + r * 0.2f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(toothW * 0.25f),
            )
        }
    }
    drawCircle(colour, r * 0.72f, center)
    drawCircle(Color.Transparent, r * 0.30f, center, blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
}

@Composable
internal fun ButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp), content = content)
}

// ---- Brand ----------------------------------------------------------------------------------

/**
 * The Kidfin jellyfish, drawn on a 64-unit grid: a dome and three tentacles. Used as the dot on
 * the wordmark's i, the launcher icon (res/drawable/icon.xml has the same paths) and, larger and
 * sleepy, on the goodbye screen.
 */
internal fun DrawScope.drawJelly(
    topLeft: Offset,
    side: Float,
    colour: Color,
    sleepy: Boolean = false,
    eyeColour: Color = Sb.Bg,
    sway: Float = 0f,
) {
    val u = side / 64f
    fun p(x: Float, y: Float) = Offset(topLeft.x + x * u, topLeft.y + y * u)
    val dome = Path().apply {
        moveTo(p(10f, 34f).x, p(10f, 34f).y)
        cubicTo(p(10f, 18f).x, p(10f, 18f).y, p(20f, 8f).x, p(20f, 8f).y, p(32f, 8f).x, p(32f, 8f).y)
        cubicTo(p(44f, 8f).x, p(44f, 8f).y, p(54f, 18f).x, p(54f, 18f).y, p(54f, 34f).x, p(54f, 34f).y)
        close()
    }
    drawPath(dome, colour)
    // Tentacles curve with [sway] (-1..1); at 0 they are straight.
    val tentacle = Stroke(5f * u, cap = StrokeCap.Round)
    listOf(20f to 54f, 32f to 58f, 44f to 54f).forEachIndexed { i, (x, end) ->
        val bend = sway * (if (i == 1) -4f else 4f)
        val path = Path().apply {
            moveTo(p(x, 40f).x, p(x, 40f).y)
            quadraticTo(p(x + bend, (40f + end) / 2).x, p(x + bend, (40f + end) / 2).y, p(x - bend * 0.5f, end).x, p(x - bend * 0.5f, end).y)
        }
        drawPath(path, colour, style = tentacle)
    }
    if (sleepy) {
        val eye = 2.2f * u
        listOf(25f, 39f).forEach { x ->
            val arc = Path().apply {
                moveTo(p(x - 3.5f, 23f).x, p(x - 3.5f, 23f).y)
                quadraticTo(p(x, 26.5f).x, p(x, 26.5f).y, p(x + 3.5f, 23f).x, p(x + 3.5f, 23f).y)
            }
            drawPath(arc, eyeColour, style = Stroke(eye, cap = StrokeCap.Round))
        }
    }
}

/**
 * "Kidfin" in Fraunces with a dotless i whose dot is the purple jellyfish (logo option C,
 * chosen 2026-11-27). Ligatures are off so the f and i stay separate.
 */
@Composable
internal fun Wordmark(fontSize: TextUnit, modifier: Modifier = Modifier, colour: Color = Sb.Ink) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        "Kıdfin",
        style = TextStyle(
            fontFamily = Serif,
            fontWeight = FontWeight(650),
            fontSize = fontSize,
            letterSpacing = (-0.02).em,
            fontFeatureSettings = "liga 0",
            color = colour,
        ),
        onTextLayout = { layout = it },
        modifier = modifier.drawWithContent {
            drawContent()
            val l = layout ?: return@drawWithContent
            val em = fontSize.toPx()
            val box = l.getBoundingBox(1)
            val side = em * 0.40f
            val baseline = l.getLineBaseline(0)
            drawJelly(Offset(box.center.x - side / 2, baseline - em * 0.87f), side, Sb.Brand)
        },
    )
}

/** Big jellyfish illustration (goodbye screen). */
@Composable
internal fun JellyArt(modifier: Modifier, sleepy: Boolean, sway: Float = 0f) {
    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            drawJelly(Offset.Zero, size.minDimension, Sb.Brand, sleepy = sleepy, sway = sway)
        }
    }
}
