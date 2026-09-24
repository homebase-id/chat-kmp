package id.homebase.core.ui.screens.feed.widget

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

const val FEED_SKELETON_COUNT = 4

// Each placeholder owns one rememberInfiniteTransition, but all slots share a duration and start together, so
// they read as one loading wave rather than independent flickers.
@Composable
fun PostSkeleton(modifier: Modifier = Modifier) {
    val brush = rememberShimmerBrush()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(bottom = 8.dp),
    ) {
        Row(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            ShimmerBlock(brush = brush, shape = CircleShape, modifier = Modifier.size(40.dp))
            Spacer(Modifier.size(12.dp))
            Column {
                ShimmerBar(brush = brush, widthFraction = 0.45f, height = 14.dp)
                Spacer(Modifier.height(8.dp))
                ShimmerBar(brush = brush, widthFraction = 0.30f, height = 10.dp)
            }
        }

        Spacer(Modifier.height(14.dp))

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            ShimmerBar(brush = brush, widthFraction = 1f, height = 12.dp)
            Spacer(Modifier.height(8.dp))
            ShimmerBar(brush = brush, widthFraction = 0.7f, height = 12.dp)
        }

        Spacer(Modifier.height(14.dp))

        ShimmerBlock(
            brush = brush,
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
        )

        Spacer(Modifier.height(12.dp))

        ShimmerBar(
            brush = brush,
            widthFraction = 0.35f,
            height = 12.dp,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun ShimmerBar(
    brush: ShimmerBrush,
    widthFraction: Float,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    ShimmerBlock(
        brush = brush,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height),
    )
}

@Composable
private fun ShimmerBlock(
    brush: ShimmerBrush,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier = modifier
            .clip(shape)
            .drawBehind {
                val translate = brush.translate.value
                translate(left = translate) {
                    drawRect(brush.gradient, topLeft = Offset(-translate, 0f), size = size)
                }
            },
    )
}

private class ShimmerBrush(val gradient: Brush, val translate: State<Float>)

// The sweep is read only in the draw phase, so a frame of it redraws the blocks without recomposing them.
@Composable
private fun rememberShimmerBrush(): ShimmerBrush {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f)

    val transition = rememberInfiniteTransition(label = "feed-skeleton-shimmer")
    val translate = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100),
            repeatMode = RepeatMode.Restart,
        ),
        label = "feed-skeleton-translate",
    )

    return remember(base, highlight, translate) {
        ShimmerBrush(
            gradient = Brush.linearGradient(
                colors = listOf(base, highlight, base),
                start = Offset(-500f, 0f),
                end = Offset.Zero,
            ),
            translate = translate,
        )
    }
}
