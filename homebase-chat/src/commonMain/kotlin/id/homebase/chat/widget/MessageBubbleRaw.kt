package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.ReplyPreview
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.ui.theme.DarkColors
import id.homebase.core.ui.theme.Dimens
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.ui.theme.LightColors
import id.homebase.core.util.applyDefaultStyling
import id.homebase.core.util.applyMarkDownContent
import id.homebase.core.util.ifTrue
import id.homebase.core.util.isEmojiContentOnly
import id.homebase.core.util.isMobile
import id.homebase.resources.MR
import id.homebase.resources.chat_message_deleted
import id.homebase.resources.chat_message_edited
import id.homebase.resources.show_more
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

/**
 * Core message bubble composable that renders message content with smart layout.
 *
 * Features:
 * - Renders rich HTML text content with proper formatting
 * - Displays media attachments (images, videos, etc.)
 * - Smart timestamp positioning: fits on last line of text when space permits, otherwise creates
 * new line
 * - Long-press animation with spring physics on mobile devices
 * - Gradient overlay on media-only messages for timestamp readability
 * - Different styling for sent vs received messages
 *
 * @param modifier Modifier to be applied to the message bubble surface.
 * @param text The message text content (can be HTML formatted).
 * @param timestamp The formatted timestamp string to display.
 * @param sentByYou Whether this message was sent by the current user (affects styling).
 * @param payloads Optional list of media/file attachments associated with the message.
 * @param fileId The unique identifier for the message file.
 * @param previewThumbnail Optional embedded thumbnail for media preview.
 * @param replyPreview Optional reply preview data for the message.
 * @param keyHeader The key header for the message.
 * @param onLongClick Callback invoked when user performs a long-press on the bubble.
 * @param onMediaClick Callback invoked when user clicks on a media attachment.
 * @param sharedTransitionScope The shared transition scope for animations.
 * @param animatedVisibilityScope The animated visibility scope for animations.
 */
@Composable
fun MessageBubbleRaw(
    modifier: Modifier = Modifier,
    text: String,
    timestamp: String,
    sentByYou: Boolean,
    isEdited: Boolean,
    isDeleted: Boolean,
    deliveryStatus: Int,
    payloads: ImmutableList<PayloadDescriptor>? = null,
    fileId: Uuid,
    previewThumbnail: EmbeddedThumb? = null,
    replyPreview: ReplyPreview? = null,
    keyHeader: KeyHeader,
    authorName: String? = null,
    authorColor: Color? = null,
    onLongClick: () -> Unit,
    onMediaClick: (PayloadDescriptor) -> Unit,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    messageId: Uuid,
    downloadingFiles: Set<String>,
    showMore: Boolean = false,
    onShowMoreClick: (() -> Unit)? = null,
    isPendingSend: Boolean = false
) {
    val filteredPayloads = payloads?.filter {
        !listOf(
            ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB,
            ChatProtocol.DefaultPayloadKey,
            ChatProtocol.DEFAULT_PAYLOAD_DESCRIPTOR_KEY
        ).contains(it.key)
    }
    val hasMedia = !filteredPayloads.isNullOrEmpty()
    // We store the result of the text layout to know where the last line ends
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val pressInteractionSource = remember { MutableInteractionSource() }
    val isPressed by pressInteractionSource.collectIsPressedAsState()

    // Animatable controls the applied scale
    val scaleAnim = remember { Animatable(1f) }
    val isAnimatingLongPress = remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Use a spring for smoother, natural motion and avoid tiny abrupt tweens
    val springSpec = remember {
        spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy, // less bounce on emulator
            stiffness = Spring.StiffnessLow
        )
    }

    // Keep quick press feedback when not running the long-press animation
    LaunchedEffect(isPressed) {
        if (isAnimatingLongPress.value) return@LaunchedEffect
        if (isPressed) {
            scaleAnim.animateTo(0.96f, animationSpec = springSpec)
        } else {
            scaleAnim.animateTo(1f, animationSpec = springSpec)
        }
    }

    fun handleLongClick() {
        if (isAnimatingLongPress.value) return
        isAnimatingLongPress.value = true
        coroutineScope.launch {
            try {
                scaleAnim.animateTo(0.94f, animationSpec = springSpec)
                onLongClick()
                scaleAnim.animateTo(1f, animationSpec = springSpec)
            } finally {
                isAnimatingLongPress.value = false
            }
        }
    }

    val messageInfoText =
        if (isEdited) "${stringResource(MR.string.chat_message_edited)} $timestamp" else timestamp
    val mediaOnly = remember { !text.hasContent() && hasMedia }
    val emojiOnly = remember { text.isEmojiContentOnly() && !hasMedia }
    val backgroundColor =
        if (emojiOnly) Color.Unspecified
        else if (sentByYou) HomebaseTheme.extendedColors.bubbleSentSurface
        else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor =
        if (emojiOnly) MaterialTheme.colorScheme.onSurface
        else if (sentByYou) HomebaseTheme.extendedColors.bubbleSentOnSurface
        else MaterialTheme.colorScheme.onSurface

    val deletedText = stringResource(MR.string.chat_message_deleted)
    val textState = remember {
        RichTextState()
            .applyDefaultStyling(linkColor = if (sentByYou) DarkColors.Primary else LightColors.Primary)
            .applyMarkDownContent(if (isDeleted) deletedText else text)
    }

    val shape = remember {
        RoundedCornerShape(
            topStart = Dimens.Message.cornerRadius,
            topEnd = Dimens.Message.cornerRadius,
            bottomStart =
                if (!sentByYou && !mediaOnly) 4.dp else Dimens.Message.cornerRadius,
            bottomEnd = if (sentByYou && !mediaOnly) 4.dp else Dimens.Message.cornerRadius,
        )
    }

    Surface(
        modifier = modifier.clip(shape).ifTrue(isMobile()) {
            Modifier.combinedClickable(
                onClick = {},
                onLongClick = { handleLongClick() },
                interactionSource = pressInteractionSource,
                indication = null
            )
        }.graphicsLayer {
            scaleX = scaleAnim.value
            scaleY = scaleAnim.value
        },
        shape = shape,
        color = backgroundColor,
    ) {
        if (mediaOnly && !isDeleted) {
            Box(modifier = Modifier.wrapContentWidth()) {
                MediaMessage(
                    payloads = filteredPayloads?.toPersistentList() ?: persistentListOf(),
                    fileId = fileId,
                    keyHeader = keyHeader,
                    driveId = chatTargetDrive.alias,
                    previewThumbnail = previewThumbnail,
                    onMediaClick = onMediaClick,
                    onMediaLongPress = { _, _ -> handleLongClick() },
                    shape = RoundedCornerShape(Dimens.Message.cornerRadius),
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    messageId = messageId,
                    downloadingFiles = downloadingFiles
                )
                Box(modifier = Modifier.matchParentSize().align(Alignment.BottomStart)) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                            .align(Alignment.BottomStart).background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.Black.copy(
                                            alpha = 0.6f
                                        ),
                                    )
                                )
                            ),
                    ) {
                        Row(
                            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                text = messageInfoText,
                                style = MaterialTheme.typography.labelSmall,
                                color = HomebaseTheme.extendedColors.bubbleSentOnSurface.copy(alpha = 0.7f)
                            )
                            if (sentByYou) {
                                Spacer(modifier = Modifier.width(4.dp))
                                DeliveryStatus(isPendingSend = isPendingSend, deliveryStatus = deliveryStatus)
                            }
                        }
                    }
                }
            }
        } else {
            // Note: If adding composables to Layout here, remember to update layout code to take new widget into account
            Column {
                Layout(
                    content = {
                        authorName?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelMedium,
                                color = authorColor ?: contentColor,
                                modifier = Modifier.padding(
                                    start = 12.dp, top = 8.dp, end = 12.dp, bottom = 8.dp,
                                ),
                                maxLines = 1,
                            )
                        }
                        // Inline reply preview if this message is a reply
                        replyPreview?.let { reply ->
                            InlineReplyPreview(
                                replyPreview = reply, sentByYou = sentByYou
                            )
                        }
                        if (hasMedia) {
                            MediaMessage(
                                payloads = filteredPayloads.toPersistentList(),
                                fileId = fileId,
                                driveId = chatTargetDrive.alias,
                                previewThumbnail = previewThumbnail,
                                onMediaClick = onMediaClick,
                                keyHeader = keyHeader,
                                shape = if (authorName == null) RoundedCornerShape(
                                    topStart = Dimens.Message.cornerRadius,
                                    topEnd = Dimens.Message.cornerRadius
                                ) else RoundedCornerShape(0.dp),
                                onMediaLongPress = { _, _ -> handleLongClick() },
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope,
                                messageId = messageId,
                                downloadingFiles = downloadingFiles,
                            )
                        }
                        Row(
                            modifier = Modifier.padding(
                                horizontal = 12.dp, vertical = 12.dp
                            ),
                        ) {
                            if (emojiOnly) {
                                // Render emoji-only messages prominently
                                val size = if (text.length <= 6) 56.sp else 42.sp
                                Text(
                                    text = text,
                                    onTextLayout = { textLayoutResult = it },
                                    fontSize = size,
                                    style = MaterialTheme.typography.displaySmall,
                                    color = contentColor
                                )
                            } else {
                                RichText(
                                    state = textState,
                                    onTextLayout = { textLayoutResult = it },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = contentColor
                                )
                            }
                        }
                        Box {
                            if (showMore && onShowMoreClick != null) {
                                Text(
                                    text = stringResource(MR.string.show_more),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = contentColor.copy(alpha = 0.85f),
                                    modifier = Modifier
                                        .padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
                                        .combinedClickable(
                                            onClick = { onShowMoreClick() },
                                            onLongClick = {},
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        )
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.padding(start = 8.dp),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Text(
                                text = messageInfoText,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = 0.7f)
                            )
                            if (sentByYou) {
                                Spacer(modifier = Modifier.width(4.dp))
                                DeliveryStatus(isPendingSend = isPendingSend, deliveryStatus = deliveryStatus)
                            }
                        }
                    }
                ) { measurables, constraints ->
                    // Find MediaMessage index (after author and reply preview)
                    var mediaIndex = 0
                    if (authorName != null) mediaIndex++
                    if (replyPreview != null) mediaIndex++

                    //val authorIndex = if (authorName != null) 0 else -1
                    val textIndex = if (hasMedia) mediaIndex + 1 else mediaIndex
                    val showMoreIndex = textIndex + 1
                    val infoIndex = showMoreIndex + 1

                    val placeables: MutableList<Placeable> = mutableListOf()
                    var mediaWidth = 0
                    var authorWidth = 0

                    // Measure up to text content
                    for (i in 0 until textIndex) {
                        val placeable = measurables[i].measure(constraints)
                        placeables += placeable
                        if (hasMedia && i == mediaIndex) {
                            mediaWidth = placeable.width
                        }
                        if (authorName != null && i == 0) {
                            authorWidth = placeable.width
                        }
                    }

                    // Measure text content
                    val textPlaceable = measurables[textIndex].measure(
                        if (mediaWidth > 0) constraints.copy(
                            minWidth = mediaWidth,
                            maxWidth = mediaWidth
                        )
                        else constraints
                    )

                    // Measure show more text
                    val showMorePlaceable = measurables[showMoreIndex].measure(constraints)

                    // Measure info text
                    val infoPlaceable = measurables[infoIndex].measure(constraints)

                    val layoutResult = textLayoutResult
                    val finalWidth: Int
                    val finalHeight: Int
                    val infoX: Int
                    val infoY: Int

                    if (layoutResult != null && layoutResult.lineCount > 0) {
                        val lastLineIndex = layoutResult.lineCount - 1
                        val lastLineRight = layoutResult.getLineRight(lastLineIndex)
                        val horizontalGap = 8.dp.roundToPx()

                        val textRowPadding = 12.dp.roundToPx()
                        val availableWidth =
                            if (mediaWidth > 0) mediaWidth else constraints.maxWidth
                        val lastLineEnd = textRowPadding + lastLineRight.toInt()
                        val fitsOnLastLine =
                            (lastLineEnd + horizontalGap + infoPlaceable.width + textRowPadding) <= availableWidth

                        if (fitsOnLastLine) {
                            finalWidth = maxOf(
                                mediaWidth,
                                textPlaceable.width,
                                (lastLineEnd + horizontalGap + infoPlaceable.width + textRowPadding),
                                authorWidth
                            )
                            val lastLineBottom = layoutResult.getLineBottom(lastLineIndex)
                            infoY =
                                placeables.sumOf { it.height } + lastLineBottom.toInt() + 8.dp.roundToPx() - infoPlaceable.height
                            infoX = finalWidth - infoPlaceable.width - textRowPadding
                            finalHeight =
                                placeables.sumOf { it.height } +
                                        textPlaceable.height +
                                        (showMorePlaceable.height)
                        } else {
                            finalWidth = maxOf(mediaWidth, textPlaceable.width, infoPlaceable.width, authorWidth)
                            infoY = placeables.sumOf { it.height } + textPlaceable.height
                            infoX = finalWidth - infoPlaceable.width - 8.dp.roundToPx()
                            finalHeight =
                                placeables.sumOf { it.height } +
                                        textPlaceable.height +
                                        (showMorePlaceable.height) +
                                        infoPlaceable.height +
                                        8.dp.roundToPx()
                        }
                    } else {
                        finalWidth = maxOf(mediaWidth, textPlaceable.width, infoPlaceable.width, authorWidth)
                        infoY = placeables.sumOf { it.height } + textPlaceable.height
                        infoX = finalWidth - infoPlaceable.width
                        finalHeight =
                            placeables.sumOf { it.height } + textPlaceable.height + infoPlaceable.height
                    }

                    layout(finalWidth, finalHeight) {
                        var yPos = 0
                        placeables.forEach { placeable ->
                            placeable.placeRelative(0, yPos)
                            yPos += placeable.height
                        }
                        textPlaceable.placeRelative(0, yPos)
                        yPos += textPlaceable.height

                        showMorePlaceable.placeRelative(0, yPos)
                        yPos += showMorePlaceable.height

                        infoPlaceable.placeRelative(infoX, infoY)
                    }
                }
            }
        }
    }
}