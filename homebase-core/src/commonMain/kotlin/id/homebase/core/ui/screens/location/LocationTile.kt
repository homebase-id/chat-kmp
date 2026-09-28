package id.homebase.core.ui.screens.location

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.location_tile_badge_count
import id.homebase.resources.location_tile_off
import id.homebase.resources.location_tile_on
import org.jetbrains.compose.resources.stringResource

enum class LocationTileStyle { Emergency, History, Live, Settings }

@Composable
fun LocationTile(
    style: LocationTileStyle,
    icon: ImageVector,
    title: String,
    on: Boolean,
    statusText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    warningCount: Int = 0,
    warningContentDescription: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val warning = HomebaseTheme.extendedColors.warning
    val gradientStart: Color
    val gradientEnd: Color
    val content: Color
    val iconTint: Color
    when {
        !on -> {
            gradientStart = scheme.surfaceContainerLow
            gradientEnd = scheme.surfaceContainerLow
            content = scheme.onSurfaceVariant
            iconTint = scheme.onSurfaceVariant
        }

        style == LocationTileStyle.Emergency -> {
            gradientStart = scheme.tertiaryContainer
            gradientEnd = scheme.surfaceContainerHighest
            content = scheme.onTertiaryContainer
            iconTint = scheme.onTertiaryContainer
        }

        style == LocationTileStyle.History -> {
            gradientStart = scheme.primaryContainer
            gradientEnd = scheme.surfaceContainerHigh
            content = scheme.onPrimaryContainer
            iconTint = scheme.onPrimaryContainer
        }

        style == LocationTileStyle.Live -> {
            gradientStart = scheme.secondaryContainer
            gradientEnd = scheme.surfaceContainerHigh
            content = scheme.onSecondaryContainer
            iconTint = HomebaseTheme.extendedColors.liveSharing
        }

        else -> {
            gradientStart = scheme.surfaceContainerHigh
            gradientEnd = scheme.surfaceContainerHighest
            content = scheme.onSurface
            iconTint = scheme.onSurface
        }
    }
    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val animatedStart by animateColorAsState(gradientStart, spec)
    val animatedEnd by animateColorAsState(gradientEnd, spec)
    val animatedContent by animateColorAsState(content, spec)
    val animatedIconTint by animateColorAsState(iconTint, spec)
    val iconCircleColor by animateColorAsState(
        if (on) scheme.surfaceContainerLowest else scheme.surfaceContainerHigh,
        spec,
    )
    val borderColor by animateColorAsState(
        if (on) scheme.outlineVariant.copy(alpha = 0f) else scheme.outlineVariant,
        spec,
    )

    Card(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Brush.linearGradient(listOf(animatedStart, animatedEnd)))) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val iconCircle: @Composable () -> Unit = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(iconCircleColor),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = animatedIconTint,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    if (warningCount > 0) {
                        BadgedBox(
                            badge = {
                                Badge(
                                    containerColor = warning,
                                    contentColor = scheme.onSurface,
                                    modifier = if (warningContentDescription != null) {
                                        Modifier.semantics { contentDescription = warningContentDescription }
                                    } else {
                                        Modifier
                                    },
                                ) {
                                    Text(stringResource(MR.string.location_tile_badge_count, warningCount))
                                }
                            },
                        ) { iconCircle() }
                    } else {
                        iconCircle()
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(if (on) MR.string.location_tile_on else MR.string.location_tile_off),
                        style = MaterialTheme.typography.labelSmall,
                        color = animatedContent,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = animatedContent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (warningCount > 0) warning else animatedContent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
