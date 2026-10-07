package id.homebase.core.ui.screens.webdrop.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.common.util.formatBytes
import id.homebase.core.ui.screens.profile.ConnectedChoices
import id.homebase.core.ui.screens.webdrop.model.PickedDropFile
import id.homebase.core.ui.screens.webdrop.model.WebDropTtlChoice
import id.homebase.resources.MR
import id.homebase.resources.webdrop_add_files
import id.homebase.resources.webdrop_add_files_hint
import id.homebase.resources.webdrop_add_more_files
import id.homebase.resources.webdrop_expiry_header
import id.homebase.resources.webdrop_for_someone
import id.homebase.resources.webdrop_for_someone_supporting
import id.homebase.resources.webdrop_remove_file
import id.homebase.resources.webdrop_ttl_burn_short
import id.homebase.resources.webdrop_ttl_burn_supporting
import id.homebase.resources.webdrop_ttl_one_day
import id.homebase.resources.webdrop_ttl_seven_days
import id.homebase.resources.webdrop_ttl_thirty_days
import id.homebase.resources.webdrop_ttl_timed_supporting
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal val WebDropTtlChoice.label: StringResource
    get() = when (this) {
        WebDropTtlChoice.BurnAfterOpen -> MR.string.webdrop_ttl_burn_short
        WebDropTtlChoice.OneDay -> MR.string.webdrop_ttl_one_day
        WebDropTtlChoice.SevenDays -> MR.string.webdrop_ttl_seven_days
        WebDropTtlChoice.ThirtyDays -> MR.string.webdrop_ttl_thirty_days
    }

@Composable
internal fun SectionCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLargeEmphasized,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** The empty sheet's hero: one big, obviously tappable target instead of a disabled button. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FileDropZone(maxFiles: Int, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceContainerHigh)
            .drawBehind {
                drawOutline(
                    outline = shape.createOutline(size, layoutDirection, this),
                    color = colors.outline,
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
                    ),
                )
            }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.size(64.dp).background(colors.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.UploadFile, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = stringResource(MR.string.webdrop_add_files),
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = colors.onSurface,
        )
        Text(
            text = stringResource(MR.string.webdrop_add_files_hint, maxFiles),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun PickedDropFile.icon(): ImageVector = when {
    contentType.startsWith("image/") -> Icons.Outlined.Image
    contentType.startsWith("video/") -> Icons.Outlined.Movie
    contentType.startsWith("audio/") -> Icons.Outlined.AudioFile
    contentType == "application/pdf" -> Icons.Outlined.PictureAsPdf
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

private fun PickedDropFile.detail(): String? = when {
    size > 0 -> formatBytes(size)
    '.' in name -> name.substringAfterLast('.').uppercase()
    else -> null
}

// Char.directionality is JVM-only; these are the Hebrew, Arabic, Syriac and Thaana blocks.
private fun String.isRtlText(): Boolean {
    val first = firstOrNull { it.isLetter() } ?: return false
    return first in '\u0590'..'\u08FF' || first in '\uFB1D'..'\uFDFF' || first in '\uFE70'..'\uFEFF'
}

/** Ellipsises the stem only, so the extension always shows which file this is. */
@Composable
private fun FileName(name: String) {
    val dot = name.lastIndexOf('.')
    val hasExtension = dot > 0 && name.length - dot <= 8
    val stem = if (hasExtension) name.substring(0, dot) else name
    val extension = if (hasExtension) name.substring(dot) else ""
    val style = MaterialTheme.typography.bodyLarge
    val color = MaterialTheme.colorScheme.onSurface
    // The pieces of one name keep the name's own direction, whatever the layout's.
    CompositionLocalProvider(
        LocalLayoutDirection provides if (name.isRtlText()) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        Row(modifier = Modifier.semantics(mergeDescendants = true) {}) {
            Text(
                text = stem,
                style = style,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (extension.isNotEmpty()) Text(text = extension, style = style, color = color, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PickedFileRow(file: PickedDropFile, enabled: Boolean, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(40.dp).background(colors.secondaryContainer, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(file.icon(), contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            FileName(file.name)
            file.detail()?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
            }
        }
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(MR.string.webdrop_remove_file, file.name))
        }
    }
}

@Composable
internal fun AddMoreRow(enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(40.dp).background(colors.surfaceContainerHighest, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
        }
        Text(
            text = stringResource(MR.string.webdrop_add_more_files),
            style = MaterialTheme.typography.labelLargeEmphasized,
            color = colors.primary,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChoiceToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    shapes: ToggleButtonShapes,
    modifier: Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    ToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        shapes = shapes,
        colors = ToggleButtonDefaults.toggleButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = modifier.semantics { role = Role.RadioButton },
        content = content,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExpiryPicker(selected: WebDropTtlChoice, enabled: Boolean, onPick: (WebDropTtlChoice) -> Unit) {
    val choices = WebDropTtlChoice.entries
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionCaption(stringResource(MR.string.webdrop_expiry_header))
        BoxWithConstraints {
            // On a narrow phone the flame moves to the line below, so every duration keeps its full label.
            val roomy = maxWidth >= 340.dp
            ConnectedChoices(
                count = choices.size,
                weight = { if (choices[it] == WebDropTtlChoice.BurnAfterOpen) (if (roomy) 1.45f else 1.2f) else 1f },
            ) { index, shapes, sizing ->
                val choice = choices[index]
                val burn = choice == WebDropTtlChoice.BurnAfterOpen
                ChoiceToggleButton(
                    checked = selected == choice,
                    onCheckedChange = { onPick(choice) },
                    enabled = enabled,
                    shapes = shapes,
                    modifier = sizing,
                ) {
                    if (burn && roomy) {
                        Icon(
                            Icons.Outlined.LocalFireDepartment,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 4.dp).size(ToggleButtonDefaults.IconSize),
                        )
                    }
                    Text(
                        text = stringResource(choice.label),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                if (selected == WebDropTtlChoice.BurnAfterOpen) Icons.Outlined.LocalFireDepartment else Icons.Outlined.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = if (selected == WebDropTtlChoice.BurnAfterOpen) {
                    stringResource(MR.string.webdrop_ttl_burn_supporting)
                } else {
                    stringResource(MR.string.webdrop_ttl_timed_supporting, stringResource(selected.label))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ForSomeoneRow(expanded: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, motion.defaultSpatialSpec())
    val container by animateColorAsState(
        if (expanded) colors.secondaryContainer else colors.surfaceContainerHigh,
        motion.defaultEffectsSpec(),
    )
    val corner by animateDpAsState(if (expanded) 28.dp else 16.dp, motion.defaultSpatialSpec())
    val shape = RoundedCornerShape(corner)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(container)
            .toggleable(value = expanded, enabled = enabled, role = Role.Button, onValueChange = { onToggle() })
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.PersonOutline, contentDescription = null, tint = colors.onSurfaceVariant)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(MR.string.webdrop_for_someone),
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = colors.onSurface,
            )
            Text(
                text = stringResource(MR.string.webdrop_for_someone_supporting),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.graphicsLayer { rotationZ = rotation },
        )
    }
}

@Composable
internal fun ConditionRow(label: String, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(start = 10.dp, end = 16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ThemePicker(
    themes: List<Pair<String, StringResource>>,
    selected: String?,
    enabled: Boolean,
    onPick: (String?) -> Unit,
) {
    ConnectedChoices(count = themes.size) { index, shapes, sizing ->
        val (id, label) = themes[index]
        ChoiceToggleButton(
            checked = selected == id,
            onCheckedChange = { onPick(if (selected == id) null else id) },
            enabled = enabled,
            shapes = shapes,
            modifier = sizing,
        ) {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ErrorBanner(message: String, hint: String?) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.errorContainer,
        contentColor = colors.onErrorContainer,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = message, style = MaterialTheme.typography.titleSmall)
                hint?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
internal fun Reveal(visible: Boolean, content: @Composable () -> Unit) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
        exit = shrinkVertically(motion.defaultSpatialSpec()) + fadeOut(motion.defaultEffectsSpec()),
    ) { content() }
}
