package id.homebase.core.ui.screens.webdrop.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.files.materializeForUpload
import id.homebase.core.ui.screens.vault.pathCompat
import id.homebase.core.ui.screens.webdrop.WebDropError
import id.homebase.core.ui.screens.webdrop.WebDropUiAction
import id.homebase.core.ui.screens.webdrop.WebDropUiState
import id.homebase.core.ui.screens.webdrop.model.PickedDropFile
import id.homebase.core.ui.screens.webdrop.model.WebDropTtlChoice
import id.homebase.core.util.contentType
import id.homebase.core.webdrop.WebDropProtocol
import id.homebase.resources.MR
import id.homebase.resources.webdrop_compose_title
import id.homebase.resources.webdrop_condition_no_retention
import id.homebase.resources.webdrop_condition_personal_data
import id.homebase.resources.webdrop_condition_recipient_only
import id.homebase.resources.webdrop_copy
import id.homebase.resources.webdrop_create
import id.homebase.resources.webdrop_creating
import id.homebase.resources.webdrop_error_create
import id.homebase.resources.webdrop_error_create_hint
import id.homebase.resources.webdrop_error_source_unreadable
import id.homebase.resources.webdrop_error_too_many
import id.homebase.resources.webdrop_expires_after
import id.homebase.resources.webdrop_link_ready
import id.homebase.resources.webdrop_link_ready_timed
import id.homebase.resources.webdrop_recipient_name
import id.homebase.resources.webdrop_share
import id.homebase.resources.webdrop_terms_header
import id.homebase.resources.webdrop_theme_choplifter
import id.homebase.resources.webdrop_theme_clean
import id.homebase.resources.webdrop_theme_header
import id.homebase.resources.webdrop_theme_mission
import id.homebase.resources.webdrop_theme_supporting
import id.homebase.resources.webdrop_try_again
import id.homebase.resources.webdrop_ttl_burn
import id.homebase.resources.webdrop_view_only_badge
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

private const val TAG = "WebDropComposeSheet"
private const val DIMMED_ALPHA = 0.38f

private val Themes = listOf(
    WebDropProtocol.ThemeMission to MR.string.webdrop_theme_mission,
    WebDropProtocol.ThemeClean to MR.string.webdrop_theme_clean,
    WebDropProtocol.ThemeChoplifter to MR.string.webdrop_theme_choplifter,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDropComposeSheet(
    uiState: WebDropUiState,
    onAction: (WebDropUiAction) -> Unit,
) {
    val fileOps = koinInject<FileOperationsProvider>()
    val scope = rememberCoroutineScope()
    val filePicker = rememberFilePickerLauncher(
        type = FileKitType.File(),
        mode = FileKitMode.Multiple(),
    ) { files ->
        if (!files.isNullOrEmpty()) {
            scope.launch {
                onAction(
                    WebDropUiAction.FilesPicked(
                        files.map { file ->
                            // Content type off the PICKED handle before the copy - the sandbox
                            // copy's picker-name may lack an extension to derive it from (#1149).
                            val contentType = file.contentType()
                            // Snapshot into the sandbox at pick time: Android's content:// grant
                            // and iOS's security scope are both transient, and createDrop reads
                            // the path much later (#1420). On failure keep the raw handle - the
                            // grant is freshest right now, and createDrop reports a typed,
                            // per-file error if it still cannot read it.
                            val snapshot = runCatching { file.materializeForUpload(fileOps) }
                                .getOrElse { e ->
                                    Logger.w(TAG, e) { "snapshot-on-pick failed for ${file.name}" }
                                    file
                                }
                            PickedDropFile(
                                path = snapshot.pathCompat,
                                name = file.name,
                                contentType = contentType,
                                size = runCatching { snapshot.size() }.getOrDefault(0L),
                            )
                        }
                    )
                )
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { onAction(WebDropUiAction.ComposeDismissed) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        WebDropType {
            val motion = MaterialTheme.motionScheme
            AnimatedContent(
                targetState = uiState.createdUrl,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                transitionSpec = {
                    (fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()))
                        .using(SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() })
                },
            ) { url ->
                if (url != null) {
                    LinkReadyStep(url = url, uiState = uiState, onAction = onAction)
                } else {
                    ComposeStep(uiState = uiState, onAction = onAction, onAddFiles = { filePicker.launch() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ComposeStep(
    uiState: WebDropUiState,
    onAction: (WebDropUiAction) -> Unit,
    onAddFiles: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val editable = !uiState.isCreating
    val inputsAlpha by animateFloatAsState(if (editable) 1f else DIMMED_ALPHA, motion.defaultEffectsSpec())
    val hasFiles = uiState.pickedFiles.isNotEmpty()

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(MR.string.webdrop_compose_title),
            style = MaterialTheme.typography.headlineSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = inputsAlpha },
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!hasFiles) {
                FileDropZone(maxFiles = WebDropProtocol.MaxFilesPerDrop, onClick = onAddFiles)
            } else {
                Column {
                    uiState.pickedFiles.forEach { file ->
                        PickedFileRow(
                            file = file,
                            enabled = editable,
                            onRemove = { onAction(WebDropUiAction.RemovePickedFile(file.path)) },
                        )
                    }
                    AddMoreRow(
                        enabled = editable && uiState.pickedFiles.size < WebDropProtocol.MaxFilesPerDrop,
                        onClick = onAddFiles,
                    )
                }
            }

            ExpiryPicker(
                selected = uiState.ttlChoice,
                enabled = editable,
                onPick = { onAction(WebDropUiAction.TtlChosen(it)) },
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WebDropViewOnlyRow(
                    checked = uiState.viewOnly,
                    enabled = editable,
                    onCheckedChange = { onAction(WebDropUiAction.ViewOnlyToggled(it)) },
                )
                ForSomeoneRow(
                    expanded = uiState.introExpanded,
                    enabled = editable,
                    onToggle = { onAction(WebDropUiAction.ToggleIntroSection) },
                )
            }

            Reveal(visible = uiState.introExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = uiState.recipientName,
                        onValueChange = { onAction(WebDropUiAction.RecipientNameChanged(it)) },
                        label = { Text(stringResource(MR.string.webdrop_recipient_name)) },
                        singleLine = true,
                        enabled = editable,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SectionCaption(
                        text = stringResource(MR.string.webdrop_terms_header),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Column {
                        listOf(
                            WebDropProtocol.ConditionRecipientOnly to MR.string.webdrop_condition_recipient_only,
                            WebDropProtocol.ConditionNoRetention to MR.string.webdrop_condition_no_retention,
                            WebDropProtocol.ConditionPersonalData to MR.string.webdrop_condition_personal_data,
                        ).forEach { (id, label) ->
                            ConditionRow(
                                label = stringResource(label),
                                checked = id in uiState.conditions,
                                enabled = editable,
                                onToggle = { onAction(WebDropUiAction.ConditionToggled(id)) },
                            )
                        }
                    }
                    SectionCaption(
                        text = stringResource(MR.string.webdrop_theme_header),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        text = stringResource(MR.string.webdrop_theme_supporting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ThemePicker(
                        themes = Themes,
                        selected = uiState.theme,
                        enabled = editable,
                        onPick = { onAction(WebDropUiAction.ThemeChosen(it)) },
                    )
                }
            }
        }

        val error = uiState.error
        Reveal(visible = error != null) {
            if (error != null) {
                Box(Modifier.padding(top = 16.dp)) {
                    ErrorBanner(
                        message = when (error) {
                            WebDropError.CreateFailed -> stringResource(MR.string.webdrop_error_create)
                            WebDropError.TooManyFiles ->
                                stringResource(MR.string.webdrop_error_too_many, WebDropProtocol.MaxFilesPerDrop)
                            is WebDropError.SourceUnreadable ->
                                stringResource(MR.string.webdrop_error_source_unreadable, error.fileName)
                        },
                        hint = if (error == WebDropError.CreateFailed) stringResource(MR.string.webdrop_error_create_hint) else null,
                    )
                }
            }
        }

        Reveal(visible = hasFiles) {
            CreateButton(
                creating = uiState.isCreating,
                retry = error == WebDropError.CreateFailed,
                onClick = { onAction(WebDropUiAction.CreateClicked) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CreateButton(creating: Boolean, retry: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = !creating,
        shapes = ButtonDefaults.shapes(),
        // Creating keeps the filled look: the spinner and label are the feedback, not a grey slab.
        colors = ButtonDefaults.buttonColors(
            disabledContainerColor = colors.primary,
            disabledContentColor = colors.onPrimary,
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp).heightIn(min = 56.dp),
    ) {
        Crossfade(targetState = creating, animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()) { busy ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    LoadingIndicator(modifier = Modifier.size(28.dp), color = colors.onPrimary)
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(MR.string.webdrop_creating))
                } else {
                    Text(stringResource(if (retry) MR.string.webdrop_try_again else MR.string.webdrop_create))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun LinkReadyStep(url: String, uiState: WebDropUiState, onAction: (WebDropUiAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val burn = uiState.ttlChoice == WebDropTtlChoice.BurnAfterOpen
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            modifier = Modifier.size(56.dp).background(colors.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Link, contentDescription = null, tint = colors.onPrimaryContainer)
        }
        Text(
            text = stringResource(if (burn) MR.string.webdrop_link_ready else MR.string.webdrop_link_ready_timed),
            style = MaterialTheme.typography.headlineSmallEmphasized,
            color = colors.onSurface,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SummaryPill(
                icon = if (burn) Icons.Outlined.LocalFireDepartment else Icons.Outlined.Schedule,
                text = if (burn) {
                    stringResource(MR.string.webdrop_ttl_burn)
                } else {
                    stringResource(MR.string.webdrop_expires_after, stringResource(uiState.ttlChoice.label))
                },
            )
            if (uiState.viewOnly) {
                SummaryPill(
                    icon = Icons.Outlined.Visibility,
                    text = stringResource(MR.string.webdrop_view_only_badge),
                    container = colors.tertiaryContainer,
                    content = colors.onTertiaryContainer,
                )
            }
        }
        Text(
            text = url,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceContainerHigh, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onAction(WebDropUiAction.CopyLinkClicked(url)) },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(MR.string.webdrop_copy), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilledTonalButton(
                onClick = { onAction(WebDropUiAction.ShareClicked(url)) },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(MR.string.webdrop_share), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SummaryPill(
    icon: ImageVector,
    text: String,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Row(
        modifier = Modifier
            .background(container, CircleShape)
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = content)
    }
}
