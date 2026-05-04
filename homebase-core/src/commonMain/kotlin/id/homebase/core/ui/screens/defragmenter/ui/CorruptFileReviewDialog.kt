package id.homebase.core.ui.screens.defragmenter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.homebase.core.ui.screens.defragmenter.service.MessageContentQuarantineCandidate
import id.homebase.core.ui.screens.defragmenter.service.QuarantineCandidate
import id.homebase.resources.MR
import id.homebase.resources.defragmenter_review_button_close
import id.homebase.resources.defragmenter_review_button_delete
import id.homebase.resources.defragmenter_review_button_skip
import id.homebase.resources.defragmenter_review_decode_error
import id.homebase.resources.defragmenter_review_dialog_title
import id.homebase.resources.defragmenter_review_kind_header
import id.homebase.resources.defragmenter_review_kind_message_content
import id.homebase.resources.defragmenter_review_label_author
import id.homebase.resources.defragmenter_review_label_conversation
import id.homebase.resources.defragmenter_review_label_created
import id.homebase.resources.defragmenter_review_label_drive
import id.homebase.resources.defragmenter_review_label_file
import id.homebase.resources.defragmenter_review_label_filetype
import id.homebase.resources.defragmenter_review_label_sender
import id.homebase.resources.defragmenter_review_label_unique
import id.homebase.resources.defragmenter_review_raw_json
import org.jetbrains.compose.resources.stringResource

/**
 * Walks the corrupt-file review queue one entry at a time. Renders the
 * candidate's identifying metadata, the decode failure, and the bad JSON
 * snippet so the user has enough context to decide whether to hard-delete
 * the file. Branches on candidate type (header vs message-content) for the
 * subset of fields available — the Skip / Delete / Close actions are
 * identical across both.
 */
@Composable
fun CorruptFileReviewDialog(
    headerCandidates: List<QuarantineCandidate>,
    messageCandidates: List<MessageContentQuarantineCandidate>,
    reviewIndex: Int,
    onSkip: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val total = headerCandidates.size + messageCandidates.size
    if (total == 0 || reviewIndex !in 0 until total) return

    val isHeader = reviewIndex < headerCandidates.size
    val title = stringResource(
        MR.string.defragmenter_review_dialog_title,
        reviewIndex + 1,
        total,
    )

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 640.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Win98Palette.GrayFace),
        ) {
            Win98WindowFrame(
                title = title,
                onClose = onClose,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    KindBanner(isHeader = isHeader)

                    val scroll = rememberScrollState()
                    Win98BeveledPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        sunken = true,
                        background = Win98Palette.White,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(scroll)
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (isHeader) {
                                HeaderBody(headerCandidates[reviewIndex])
                            } else {
                                MessageBody(messageCandidates[reviewIndex - headerCandidates.size])
                            }
                        }
                    }

                    Spacer(Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Win98Button(
                            text = stringResource(MR.string.defragmenter_review_button_delete),
                            onClick = onDelete,
                        )
                        Win98Button(
                            text = stringResource(MR.string.defragmenter_review_button_skip),
                            onClick = onSkip,
                        )
                        Win98Button(
                            text = stringResource(MR.string.defragmenter_review_button_close),
                            onClick = onClose,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KindBanner(isHeader: Boolean) {
    val (label, color) = if (isHeader) {
        stringResource(MR.string.defragmenter_review_kind_header) to Win98Palette.IssueCorruptJsonFill
    } else {
        stringResource(MR.string.defragmenter_review_kind_message_content) to
                Win98Palette.IssueCorruptMessageContentFill
    }
    Box(
        modifier = Modifier
            .background(color)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Win98Palette.White,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

@Composable
private fun HeaderBody(c: QuarantineCandidate) {
    LabelLine(stringResource(MR.string.defragmenter_review_label_drive, c.driveId.toString()))
    LabelLine(stringResource(MR.string.defragmenter_review_label_file, c.fileId.toString()))
    c.fileType?.let {
        LabelLine(stringResource(MR.string.defragmenter_review_label_filetype, it.toString()))
    }
    c.uniqueId?.let {
        LabelLine(stringResource(MR.string.defragmenter_review_label_unique, it.toString()))
    }
    c.originalAuthor?.let {
        LabelLine(stringResource(MR.string.defragmenter_review_label_author, it))
    }
    c.senderOdinId?.let {
        LabelLine(stringResource(MR.string.defragmenter_review_label_sender, it))
    }
    c.createdMs?.let {
        LabelLine(stringResource(MR.string.defragmenter_review_label_created, it.toString()))
    }
    c.deserialiseError?.let { ErrorLine(stringResource(MR.string.defragmenter_review_decode_error, it)) }
    RawJsonBlock(c.rawHeaderPrefix)
}

@Composable
private fun MessageBody(c: MessageContentQuarantineCandidate) {
    LabelLine(stringResource(MR.string.defragmenter_review_label_drive, c.driveId.toString()))
    LabelLine(stringResource(MR.string.defragmenter_review_label_file, c.fileId.toString()))
    c.conversationId?.let {
        LabelLine(stringResource(MR.string.defragmenter_review_label_conversation, it.toString()))
    }
    c.originalAuthor?.let {
        LabelLine(stringResource(MR.string.defragmenter_review_label_author, it))
    }
    c.sender?.let {
        LabelLine(stringResource(MR.string.defragmenter_review_label_sender, it))
    }
    LabelLine(stringResource(MR.string.defragmenter_review_label_created, c.createdMs.toString()))
    c.decodeError?.let { ErrorLine(stringResource(MR.string.defragmenter_review_decode_error, it)) }
    RawJsonBlock(c.rawContentPrefix)
}

@Composable
private fun LabelLine(text: String) {
    Text(
        text = text,
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Win98Palette.Black,
        ),
    )
}

@Composable
private fun ErrorLine(text: String) {
    Text(
        text = text,
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color(0xFFB00020),
            fontWeight = FontWeight.Bold,
        ),
    )
}

@Composable
private fun RawJsonBlock(raw: String) {
    if (raw.isEmpty()) return
    Spacer(Modifier.height(2.dp))
    Text(
        text = stringResource(MR.string.defragmenter_review_raw_json),
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Win98Palette.Black,
            fontWeight = FontWeight.Bold,
        ),
    )
    val hScroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFEFEFEF))
            .padding(6.dp)
            .horizontalScroll(hScroll),
    ) {
        Text(
            text = raw,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Win98Palette.Black,
            ),
        )
    }
}
