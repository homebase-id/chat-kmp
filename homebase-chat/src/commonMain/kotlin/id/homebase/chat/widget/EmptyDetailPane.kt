package id.homebase.chat.widget

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.theme.Dimens
import id.homebase.resources.MR
import id.homebase.resources.chat_select_a_conversation_privacy
import org.jetbrains.compose.resources.stringResource
import kotlin.math.cos
import kotlin.math.sin

private const val DegToRad = 0.017453292f

@Composable
fun EmptyDetailPane(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
) {
    val scheme = MaterialTheme.colorScheme

    // One-shot only: a looping animation here pins the Skiko render loop for as long as the
    // pane is empty, which on desktop is most of the session.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val enter by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing),
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.surfaceContainerLowest),
        contentAlignment = Alignment.Center,
    ) {
        val artSide = minOf(maxWidth * 0.5f, maxHeight * 0.34f, 244.dp)
        val showArt = artSide >= 132.dp
        val showPrivacyNote = maxHeight >= 400.dp && maxWidth >= 320.dp
        val textWidth = (maxWidth - Dimens.Spacing.gutter * 4).coerceIn(0.dp, 360.dp)

        Column(
            modifier = Modifier.padding(horizontal = Dimens.Spacing.gutter * 2),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (showArt) {
                ConversationHalo(
                    side = artSide,
                    enter = enter,
                    modifier = Modifier.padding(bottom = 28.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer { alpha = enter },
            )
            Spacer(Modifier.height(Dimens.Spacing.row))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = textWidth)
                    .graphicsLayer { alpha = enter },
            )
        }

        if (showPrivacyNote) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(Dimens.Spacing.gutter * 2)
                    .graphicsLayer { alpha = enter },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.size(Dimens.Spacing.item))
                Text(
                    text = stringResource(MR.string.chat_select_a_conversation_privacy),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ConversationHalo(
    side: Dp,
    enter: Float,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val hue = scheme.primary
    val hairline = scheme.outlineVariant
    val disc = scheme.primaryContainer
    val glyph = scheme.onPrimaryContainer

    Box(
        modifier = modifier
            .size(side)
            .graphicsLayer {
                alpha = enter
                val grow = 0.94f + 0.06f * enter
                scaleX = grow
                scaleY = grow
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)

            drawGlow(c, r * 1.46f, hue, peak = 0.19f)
            drawGlow(
                Offset(size.width * 0.36f, size.height * 0.32f),
                r * 0.78f,
                hue,
                peak = 0.17f,
            )

            drawOrbit(c, r * 1.62f, 204f, 124f, hue.copy(alpha = 0.13f), 1.dp.toPx())
            drawOrbit(c, r * 1.26f, 188f, 162f, hue.copy(alpha = 0.22f), 1.dp.toPx())
            drawOrbit(c, r * 1.26f, 18f, 46f, hue.copy(alpha = 0.15f), 1.dp.toPx())
            drawOrbit(c, r * 0.97f, -128f, 196f, hue.copy(alpha = 0.52f), 1.5.dp.toPx())
            drawOrbit(c, r * 0.97f, 104f, 46f, hairline, 1.5.dp.toPx())

            drawNode(c, r * 1.26f, 350f, 3.dp.toPx(), hue.copy(alpha = 0.3f))
            drawNode(c, r * 0.97f, -128f, 4.dp.toPx(), hue.copy(alpha = 0.78f))
            drawNode(c, r * 0.97f, 68f, 2.5.dp.toPx(), hue.copy(alpha = 0.42f))

            val medallion = r * 0.44f
            drawCircle(disc, radius = medallion, center = c)
            drawCircle(hue.copy(alpha = 0.2f), radius = medallion, center = c)
            drawCircle(
                color = hue.copy(alpha = 0.24f),
                radius = medallion,
                center = c,
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Outlined.Chat,
            contentDescription = null,
            tint = glyph,
            modifier = Modifier.size(side * 0.19f),
        )
    }
}

private fun DrawScope.drawGlow(
    center: Offset,
    radius: Float,
    hue: Color,
    peak: Float,
) = drawCircle(
    brush = Brush.radialGradient(
        0f to hue.copy(alpha = peak),
        0.34f to hue.copy(alpha = peak * 0.42f),
        0.68f to hue.copy(alpha = peak * 0.1f),
        1f to hue.copy(alpha = 0f),
        center = center,
        radius = radius,
    ),
    radius = radius,
    center = center,
)

private fun DrawScope.drawOrbit(
    center: Offset,
    radius: Float,
    startAngle: Float,
    sweepAngle: Float,
    color: Color,
    strokeWidth: Float,
) = drawArc(
    color = color,
    startAngle = startAngle,
    sweepAngle = sweepAngle,
    useCenter = false,
    topLeft = Offset(center.x - radius, center.y - radius),
    size = Size(radius * 2f, radius * 2f),
    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
)

private fun DrawScope.drawNode(
    center: Offset,
    radius: Float,
    angle: Float,
    dotRadius: Float,
    color: Color,
) = drawCircle(
    color = color,
    radius = dotRadius,
    center = Offset(
        center.x + radius * cos(angle * DegToRad),
        center.y + radius * sin(angle * DegToRad),
    ),
)
