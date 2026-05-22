package id.homebase.core.ui.screens.moments.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import id.homebase.api.video.VideoProcessingPhase
import id.homebase.core.ui.screens.moments.UploadStatus
import id.homebase.resources.MR
import id.homebase.resources.cd_upload_complete
import id.homebase.resources.cd_upload_failed
import id.homebase.resources.upload_compressing
import id.homebase.resources.upload_done
import id.homebase.resources.upload_encrypting
import id.homebase.resources.upload_failed
import id.homebase.resources.upload_finalizing
import id.homebase.resources.upload_preparing
import id.homebase.resources.upload_retrying
import id.homebase.resources.upload_segmenting
import id.homebase.resources.upload_sending
import id.homebase.resources.upload_uploading
import org.jetbrains.compose.resources.stringResource

/**
 * Moments-local clone of `id.homebase.chat.widget.UploadProgressOverlay`. Sized
 * by the caller (typically `Modifier.matchParentSize()` over a
 * `MomentMediaGallery`). Renders a translucent scrim plus a status-specific
 * spinner/icon + label centered on the tile.
 */
@Composable
internal fun MomentUploadProgressOverlay(
    status: UploadStatus,
    modifier: Modifier = Modifier,
    onPermanentFailureTap: (() -> Unit)? = null,
) {
    // Only the permanent-Failed state has an actionable tap (opens the
    // action sheet). Hooking the clickable on the outer Box (rather than
    // on the inner content) gives the whole scrim a comfortable hit target,
    // which matters for small gallery cells.
    val tapModifier =
        if (status is UploadStatus.Failed && status.permanent && onPermanentFailureTap != null) {
            Modifier.clickable(onClick = onPermanentFailureTap)
        } else {
            Modifier
        }
    Box(
        modifier = modifier.then(tapModifier).background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (status) {
                UploadStatus.Preparing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.2f),
                    )
                    Text(
                        text = stringResource(MR.string.upload_preparing),
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                UploadStatus.Sending -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.2f),
                    )
                    Text(
                        text = stringResource(MR.string.upload_sending),
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                is UploadStatus.Processing -> {
                    if (status.progress > 0f) {
                        val progressText = "${(status.progress * 100).toInt()}%"
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { status.progress },
                                modifier = Modifier.size(40.dp),
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.2f),
                            )
                            Text(
                                text = progressText,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.2f),
                        )
                    }
                    Text(
                        text = when (status.phase) {
                            VideoProcessingPhase.THUMBNAIL -> stringResource(MR.string.upload_preparing)
                            VideoProcessingPhase.COMPRESSING -> stringResource(MR.string.upload_compressing)
                            VideoProcessingPhase.SEGMENTING -> stringResource(MR.string.upload_segmenting)
                            VideoProcessingPhase.ENCRYPTING -> stringResource(MR.string.upload_encrypting)
                            VideoProcessingPhase.COMPLETE -> stringResource(MR.string.upload_done)
                        },
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                is UploadStatus.Uploading -> {
                    if (status.progress >= 1f) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(40.dp),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.2f),
                        )
                        Text(
                            text = stringResource(MR.string.upload_finalizing),
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else {
                        val progressText = "${(status.progress * 100).toInt()}%"
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { status.progress },
                                modifier = Modifier.size(40.dp),
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.2f),
                            )
                            Text(
                                text = progressText,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text(
                            text = stringResource(MR.string.upload_uploading),
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                UploadStatus.Completed -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(MR.string.cd_upload_complete),
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        text = stringResource(MR.string.upload_done),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                is UploadStatus.Failed -> {
                    // Transient: white SyncProblem + "Retrying…" — softer
                    // signal because the outbox is still working on it.
                    // Permanent: red Error + "Upload failed" — terminal
                    // state, max retries exhausted. Color hardcoded for
                    // legibility against the dark scrim (same convention
                    // the chat-side overlay uses for white).
                    if (status.permanent) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = stringResource(MR.string.cd_upload_failed),
                            tint = Color(0xFFEF5350),
                            modifier = Modifier.size(40.dp),
                        )
                        Text(
                            text = stringResource(MR.string.upload_failed),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.SyncProblem,
                            contentDescription = stringResource(MR.string.cd_upload_failed),
                            tint = Color.White,
                            modifier = Modifier.size(40.dp),
                        )
                        Text(
                            text = stringResource(MR.string.upload_retrying),
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}
