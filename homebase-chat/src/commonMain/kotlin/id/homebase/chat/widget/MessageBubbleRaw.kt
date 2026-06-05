package id.homebase.chat.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.chat.conversationlist.DecryptedFileKey
import id.homebase.chat.conversationlist.MessageClusterPosition
import id.homebase.chat.conversationlist.UploadStatus
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.dice.DiceRollBubble
import id.homebase.chat.event.EventBubble
import id.homebase.chat.groodle.GroodleBubble
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.content.MessageContent
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.ui.theme.DarkColors
import id.homebase.core.ui.theme.Dimens
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.ui.theme.LightColors
import id.homebase.core.util.applyDefaultStyling
import id.homebase.core.util.applyMarkDownContent
import id.homebase.core.util.formatMessageTimestamp
import id.homebase.core.util.ifTrue
import id.homebase.core.util.isEmojiContentOnly
import id.homebase.core.util.isMobile
import id.homebase.resources.MR
import id.homebase.resources.chat_message_deleted
import id.homebase.resources.chat_message_edited
import id.homebase.resources.show_more
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant
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
 *m
 * @param modifier Modifier to be applied to the message bubble surface.
 * @param message The message content
 * @param sentByYou Whether this message was sent by the current user (affects styling).
 * @param onLongClick Callback invoked when user performs a long-press on the bubble.
 * @param onMediaClick Callback invoked when user clicks on a media attachment.
 * @param sharedTransitionScope The shared transition scope for animations.
 * @param animatedVisibilityScope The animated visibility scope for animations.
 */
@OptIn(ExperimentalRichTextApi::class)
@Composable
fun MessageBubbleRaw(
    modifier: Modifier = Modifier,
    message: MessageUiModel,
    decryptedFiles: ImmutableMap<DecryptedFileKey, String>,
    sentByYou: Boolean,
    currentOdinId: String = "",
    clusterPosition: MessageClusterPosition = MessageClusterPosition.ALONE,
    authorName: String? = null,
    authorColor: Color? = null,
    onLongClick: () -> Unit,
    onMediaClick: (PayloadDescriptor) -> Unit,
    onClickMessageId: (Uuid) -> Unit,
    onRequestDecryptedFile: ((PayloadDescriptor) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    downloadingFiles: Set<String>,
    onShowMoreClick: (() -> Unit)? = null,
    isPendingSend: Boolean = false,
    uploadStatus: UploadStatus? = null,
    replyMessages: ImmutableMap<Uuid, MessageUiModel> = persistentMapOf(),
    searchQuery: String = "",
    isCurrentSearchResult: Boolean = false,
    chainCap: Int? = null,
) {

    // Typed rich-content (event today; poll/doodle later) bypasses the text+media
    // path entirely — each kind paints its own bubble, with its own background and
    // click handling. Long-press / reactions / replies are added per-kind in their
    // own slice.
    when (val content = message.messageContent) {
        is MessageContent.Event -> {
            EventBubble(
                descriptor = content.descriptor,
                modifier = modifier,
                messageId = message.id,
                conversationId = message.conversationId,
                ownReactions = message.ownReactions,
                reactionSummary = message.reactionPreview,
                organizer = message.originalAuthor,
                onLongClick = onLongClick,
            )
            return
        }
        is MessageContent.DiceRoll -> {
            DiceRollBubble(
                descriptor = content.descriptor,
                currentOdinId = currentOdinId,
                chainCap = chainCap,
                modifier = modifier,
            )
            return
        }
        is MessageContent.Groodle -> {
            GroodleBubble(
                descriptor = content.descriptor,
                modifier = modifier,
                messageId = message.id,
                conversationId = message.conversationId,
                ownReactions = message.ownReactions,
                reactionSummary = message.reactionPreview,
                organizer = message.originalAuthor,
                onLongClick = onLongClick,
            )
            return
        }
        is MessageContent.Unknown -> {
            UnknownMessageBubble(
                dataType = content.dataType,
                modifier = modifier,
            )
            return
        }
        null -> Unit // fall through to text + media rendering
    }

    val filteredPayloads = message.payloads?.filter {
        it.key != ChatProtocol.DefaultPayloadKey &&
                !it.key.startsWith(ChatProtocol.DEFAULT_PAYLOAD_DESCRIPTOR_KEY)
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

    val timestamp = formatMessageTimestamp(message.userDate)
    val messageInfoText =
        if (message.isEdited) "${stringResource(MR.string.chat_message_edited)} $timestamp" else timestamp
    val mediaOnly = remember { !message.content.hasContent() && hasMedia && message.messageAppData.replyPreview == null }
    val replyMediaOnly = remember { !message.content.hasContent() && hasMedia && message.messageAppData.replyPreview != null }
    val emojiOnly = remember { message.content.isEmojiContentOnly() && !hasMedia }

    // A media-only sticker message must float directly on the chat wallpaper, so its
    // transparent pixels show the background — not the bubble fill. Detect it the same
    // way MediaMessage does (solo, transparent image payload) and, when true, drop the
    // outer Surface fill/shape/elevation entirely and render it like an emoji-only
    // message (see StickerMessage). Non-sticker bubbles are unaffected.
    val isSticker = remember(filteredPayloads) {
        filteredPayloads?.size == 1 &&
            (filteredPayloads[0].descriptorInfo() as? DescriptorContent.ImageFile)?.isSticker == true
    }
    val isStickerBubble = isSticker && mediaOnly

    val backgroundColor =
        if (emojiOnly) Color.Unspecified
        else if (sentByYou) HomebaseTheme.extendedColors.bubbleSentSurface
        else MaterialTheme.colorScheme.surfaceContainerHigh
    // Stickers float on the wallpaper like emoji-only, so their tucked timestamp uses the
    // same wallpaper-readable onSurface color rather than a sent-bubble tint.
    val contentColor =
        if (emojiOnly || isStickerBubble) MaterialTheme.colorScheme.onSurface
        else if (sentByYou) HomebaseTheme.extendedColors.bubbleSentOnSurface
        else MaterialTheme.colorScheme.onSurface

    val deletedText = stringResource(MR.string.chat_message_deleted)
    val textState =
        remember(message.isDeleted, message.content) {
            RichTextState()
                .applyDefaultStyling(linkColor = if (sentByYou) DarkColors.Primary else LightColors.Primary)
                .applyMarkDownContent(if (message.isDeleted) deletedText else message.content)
        }
    // When a search query is active we render a plain AnnotatedString instead of RichText,
    // so that the string the highlight offsets are computed against is exactly the string
    // being drawn. RichTextState can reassemble its annotatedString to a different length
    // at layout time (markdown tokens), which would leave stale SpanStyle ranges and crash
    // String.subSequence during draw.
    val highlightedText: AnnotatedString? =
        remember(message.isDeleted, message.content, searchQuery, isCurrentSearchResult) {
            if (message.isDeleted) return@remember null
            val highlightColor = if (isCurrentSearchResult)
                Color(0xFFFF8C00).copy(alpha = 0.6f)
            else
                Color(0xFFFFEB3B).copy(alpha = 0.5f)
            buildSearchHighlightedText(
                plain = message.content,
                searchQuery = searchQuery,
                highlightColor = highlightColor,
            )
        }

    val big = Dimens.Message.cornerRadius
    val small = Dimens.Message.cornerCollapseRadius
    val shape = remember(sentByYou, clusterPosition, mediaOnly) {
        if (mediaOnly) {
            RoundedCornerShape(big)
        } else if (sentByYou) {
            when (clusterPosition) {
                MessageClusterPosition.ALONE -> RoundedCornerShape(big, big, small, big)
                MessageClusterPosition.START -> RoundedCornerShape(big, big, small, big)
                MessageClusterPosition.MIDDLE -> RoundedCornerShape(big, small, small, big)
                MessageClusterPosition.END -> RoundedCornerShape(big, small, big, big)
            }
        } else {
            when (clusterPosition) {
                MessageClusterPosition.ALONE -> RoundedCornerShape(big, big, big, small)
                MessageClusterPosition.START -> RoundedCornerShape(big, big, big, small)
                MessageClusterPosition.MIDDLE -> RoundedCornerShape(small, big, big, small)
                MessageClusterPosition.END -> RoundedCornerShape(small, big, big, big)
            }
        }
    }

    Surface(
        modifier = modifier
            .ifTrue(!isStickerBubble) { Modifier.clip(shape) }
            .ifTrue(isMobile()) {
                Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { handleLongClick() },
                    interactionSource = pressInteractionSource,
                    indication = null
                )
            }
            .graphicsLayer {
                scaleX = scaleAnim.value
                scaleY = scaleAnim.value
            },
        shape = if (isStickerBubble) RectangleShape else shape,
        color = if (isStickerBubble) Color.Transparent else backgroundColor,
    ) {
        Box {
            // Overlay Box that captures all long clicks
            if (isMobile()) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .pointerInput(message.id) {
                            detectTapGestures(
                                onLongPress = { handleLongClick() }
                            )
                        }
                )
            }

            if (isStickerBubble && !message.isDeleted) {
                // Stickers render exactly like emoji-only messages: a bare image floating
                // on the wallpaper with a tucked, scrim-free timestamp. This deliberately
                // bypasses MediaMessage's media chrome and MediaTimestampOverlay's gray
                // scrim — see StickerMessage. Non-sticker media-only messages fall through
                // to the unchanged path below.
                StickerMessage(
                    payloads = filteredPayloads?.toPersistentList() ?: persistentListOf(),
                    decryptedFiles = decryptedFiles,
                    keyHeader = message.keyHeader,
                    driveId = chatTargetDrive.alias,
                    fileId = message.fileId,
                    messageId = message.id,
                    previewThumbnail = message.previewThumbnail,
                    messageInfoText = messageInfoText,
                    sentByYou = sentByYou,
                    isPendingSend = isPendingSend,
                    deliveryStatus = message.messageAppData.deliveryStatus,
                    contentColor = contentColor,
                    pendingSince = message.userDate,
                    onMediaClick = onMediaClick,
                    onMediaLongPress = { handleLongClick() },
                    onRequestDecryptedFile = onRequestDecryptedFile,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    downloadingFiles = downloadingFiles,
                    uploadStatus = uploadStatus,
                )
            } else if (mediaOnly && !message.isDeleted) {
                Box(modifier = Modifier.wrapContentWidth()) {
                    MediaMessage(
                        payloads = filteredPayloads?.toPersistentList() ?: persistentListOf(),
                        fileId = message.fileId,
                        decryptedFiles = decryptedFiles,
                        keyHeader = message.keyHeader,
                        driveId = chatTargetDrive.alias,
                        previewThumbnail = message.previewThumbnail,
                        onMediaClick = onMediaClick,
                        onMediaLongPress = { _, _ -> handleLongClick() },
                        onRequestDecryptedFile = onRequestDecryptedFile,
                        shape = RoundedCornerShape(Dimens.Message.cornerRadius),
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        messageId = message.id,
                        downloadingFiles = downloadingFiles,
                        uploadStatus = uploadStatus,
                    )
                    MediaTimestampOverlay(
                        messageInfoText = messageInfoText,
                        sentByYou = sentByYou,
                        isPendingSend = isPendingSend,
                        deliveryStatus = message.messageAppData.deliveryStatus,
                        contentColor = contentColor,
                        pendingSince = message.userDate,
                    )
                }
            } else if (replyMediaOnly && !message.isDeleted) {
                val reply = message.messageAppData.replyPreview!!
                Layout(
                    content = {
                        InlineReplyPreview(
                            replyPreview = reply,
                            sentByYou = sentByYou,
                            onClick = { onClickMessageId(reply.replyUniqueId) },
                            replyMessage = replyMessages[reply.replyUniqueId],
                            driveId = chatTargetDrive.alias,
                        )
                        Box {
                            MediaMessage(
                                payloads = filteredPayloads?.toPersistentList() ?: persistentListOf(),
                                fileId = message.fileId,
                                decryptedFiles = decryptedFiles,
                                keyHeader = message.keyHeader,
                                driveId = chatTargetDrive.alias,
                                previewThumbnail = message.previewThumbnail,
                                onMediaClick = onMediaClick,
                                onMediaLongPress = { _, _ -> handleLongClick() },
                                onRequestDecryptedFile = onRequestDecryptedFile,
                                shape = RoundedCornerShape(0.dp),
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope,
                                messageId = message.id,
                                downloadingFiles = downloadingFiles,
                                uploadStatus = uploadStatus,
                            )
                            MediaTimestampOverlay(
                                messageInfoText = messageInfoText,
                                sentByYou = sentByYou,
                                isPendingSend = isPendingSend,
                                deliveryStatus = message.messageAppData.deliveryStatus,
                                contentColor = contentColor,
                                pendingSince = message.userDate,
                            )
                        }
                    },
                ) { measurables, constraints ->
                    val minContentWidth = Dimens.MediaBubble.minWidthWithContent.roundToPx()
                        .coerceAtMost(constraints.maxWidth)
                    val mediaPlaceable = measurables[1].measure(
                        constraints.copy(minWidth = minContentWidth)
                    )
                    val width = mediaPlaceable.width
                    val replyPlaceable = measurables[0].measure(
                        constraints.copy(minWidth = width, maxWidth = width)
                    )
                    layout(width, replyPlaceable.height + mediaPlaceable.height) {
                        replyPlaceable.placeRelative(0, 0)
                        mediaPlaceable.placeRelative(0, replyPlaceable.height)
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
                                        start = 12.dp,
                                        top = 8.dp,
                                        end = 12.dp,
                                        bottom = 4.dp,
                                    ),
                                    maxLines = 1,
                                )
                            }
                            // Inline reply preview if this message is a reply
                            message.messageAppData.replyPreview?.let { reply ->
                                InlineReplyPreview(
                                    replyPreview = reply,
                                    sentByYou = sentByYou,
                                    onClick = { onClickMessageId(reply.replyUniqueId) },
                                    replyMessage = replyMessages[reply.replyUniqueId],
                                    driveId = chatTargetDrive.alias,
                                )
                            }
                            if (hasMedia) {
                                MediaMessage(
                                    payloads = filteredPayloads.toPersistentList(),
                                    decryptedFiles = decryptedFiles,
                                    fileId = message.fileId,
                                    driveId = chatTargetDrive.alias,
                                    previewThumbnail = message.previewThumbnail,
                                    onMediaClick = onMediaClick,
                                    keyHeader = message.keyHeader,
                                    shape = if (authorName == null && message.messageAppData.replyPreview == null) RoundedCornerShape(
                                        topStart = Dimens.Message.cornerRadius,
                                        topEnd = Dimens.Message.cornerRadius
                                    ) else RoundedCornerShape(0.dp),
                                    onMediaLongPress = { _, _ -> handleLongClick() },
                                    onRequestDecryptedFile = onRequestDecryptedFile,
                                    sharedTransitionScope = sharedTransitionScope,
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    messageId = message.id,
                                    downloadingFiles = downloadingFiles,
                                    uploadStatus = uploadStatus,
                                )
                            }
                            Row(
                                modifier = Modifier.padding(
                                    horizontal = 12.dp, vertical = 12.dp
                                ),
                            ) {
                                if (emojiOnly) {
                                    // Render emoji-only messages prominently
                                    val size = if (message.content.length <= 6) 56.sp else 42.sp
                                    Text(
                                        text = message.content,
                                        onTextLayout = { textLayoutResult = it },
                                        fontSize = size,
                                        style = MaterialTheme.typography.displaySmall,
                                        color = contentColor
                                    )
                                } else if (highlightedText != null) {
                                    Text(
                                        text = highlightedText,
                                        onTextLayout = { textLayoutResult = it },
                                        style = MaterialTheme.typography.bodyLarge,
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
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (onShowMoreClick != null) Modifier.clickable(onClick = onShowMoreClick)
                                        else Modifier
                                    )
                            ) {
                                if (message.hasMore && onShowMoreClick != null) {
                                    Text(
                                        text = stringResource(MR.string.show_more),
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                        color = contentColor,
                                        modifier = Modifier
                                            .padding(
                                                start = 12.dp,
                                                end = 12.dp,
                                                top = 4.dp,
                                                bottom = 6.dp
                                            )
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .padding(start = 8.dp),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.End,
                            ) {
                                Text(
                                    text = messageInfoText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = contentColor.copy(alpha = 0.7f)
                                )
                                if (sentByYou && !message.isDeleted) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    DeliveryStatus(
                                        isPendingSend = isPendingSend,
                                        deliveryStatus = message.messageAppData.deliveryStatus,
                                        contentColor = contentColor.copy(alpha = 0.7f),
                                        pendingSince = message.userDate,
                                    )
                                }
                            }
                        }
                    ) { measurables, constraints ->
                        // Find MediaMessage index (after author and reply preview)
                        var mediaIndex = 0
                        if (authorName != null) mediaIndex++
                        val replyIndex =
                            if (message.messageAppData.replyPreview != null) mediaIndex else -1
                        if (message.messageAppData.replyPreview != null) mediaIndex++

                        val textIndex = if (hasMedia) mediaIndex + 1 else mediaIndex
                        val showMoreIndex = textIndex + 1
                        val infoIndex = showMoreIndex + 1

                        val placeables: MutableList<Placeable> = mutableListOf()
                        var mediaWidth = 0
                        var authorWidth = 0

                        // Measure up to text content
                        for (i in 0 until textIndex) {
                            if (i == replyIndex) continue
                            val placeable = measurables[i].measure(constraints)
                            placeables += placeable
                            if (hasMedia && i == mediaIndex) {
                                mediaWidth = placeable.width
                            }
                            val authorIndex = 0
                            if (authorName != null && i == authorIndex) {
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

                        // Calculate potential final width BEFORE measuring reply
                        val layoutResult = textLayoutResult
                        val rawPotentialFinalWidth: Int

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

                            rawPotentialFinalWidth = if (fitsOnLastLine) {
                                maxOf(
                                    mediaWidth,
                                    textPlaceable.width,
                                    (lastLineEnd + horizontalGap + infoPlaceable.width + textRowPadding),
                                    authorWidth
                                )
                            } else {
                                maxOf(
                                    mediaWidth,
                                    textPlaceable.width,
                                    infoPlaceable.width,
                                    authorWidth
                                )
                            }
                        } else {
                            rawPotentialFinalWidth =
                                maxOf(
                                    mediaWidth,
                                    textPlaceable.width,
                                    infoPlaceable.width,
                                    authorWidth
                                )
                        }

                        // Clamp to the parent's bound. The reply re-measure below forces an exact
                        // width, so an unclamped value here would propagate any computation drift
                        // straight into a child measurement — turning a one-off mismeasure into a
                        // sustained layout-invalidation loop. The clamp guarantees convergence.
                        val potentialFinalWidth =
                            rawPotentialFinalWidth.coerceAtMost(constraints.maxWidth)

                        // NOW measure reply with the correct width that accounts for info placement
                        val replyPlaceable = if (replyIndex != -1) measurables[replyIndex].measure(
                            constraints.copy(
                                minWidth = potentialFinalWidth,
                                maxWidth = potentialFinalWidth
                            )
                        ) else null
                        val replyWidth = replyPlaceable?.width ?: 0
                        val replyHeight = replyPlaceable?.height ?: 0

                        // Calculate final dimensions (now including reply width)
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
                                    replyWidth,
                                    textPlaceable.width,
                                    (lastLineEnd + horizontalGap + infoPlaceable.width + textRowPadding),
                                    authorWidth
                                )
                                val lastLineBottom = layoutResult.getLineBottom(lastLineIndex)
                                infoY =
                                    placeables.sumOf { it.height } + replyHeight + lastLineBottom.toInt() + 16.dp.roundToPx() - infoPlaceable.height
                                infoX = finalWidth - infoPlaceable.width - textRowPadding
                                finalHeight =
                                    placeables.sumOf { it.height } +
                                            replyHeight +
                                            textPlaceable.height +
                                            (showMorePlaceable.height)
                            } else {
                                finalWidth = maxOf(
                                    mediaWidth,
                                    replyWidth,
                                    textPlaceable.width,
                                    infoPlaceable.width,
                                    authorWidth
                                )
                                infoY =
                                    placeables.sumOf { it.height } + replyHeight + textPlaceable.height
                                infoX = finalWidth - infoPlaceable.width - textRowPadding
                                finalHeight =
                                    placeables.sumOf { it.height } +
                                            replyHeight +
                                            textPlaceable.height +
                                            (showMorePlaceable.height) +
                                            infoPlaceable.height +
                                            8.dp.roundToPx() +
                                            4.dp.roundToPx()
                            }
                        } else {
                            finalWidth = maxOf(
                                mediaWidth,
                                replyWidth,
                                textPlaceable.width,
                                infoPlaceable.width,
                                authorWidth
                            )
                            infoY =
                                placeables.sumOf { it.height } + replyHeight + textPlaceable.height
                            infoX = finalWidth - infoPlaceable.width
                            finalHeight =
                                placeables.sumOf { it.height } + replyHeight + textPlaceable.height + infoPlaceable.height + 4.dp.roundToPx()
                        }

                        // Same containment as `potentialFinalWidth`: never report a width
                        // wider than the parent allows. A bubble that returns finalWidth >
                        // constraints.maxWidth makes the parent re-measure us, which can race
                        // with `textLayoutResult` updates and spin. Pull `infoX` back inside
                        // the clamped width so the timestamp stays visible in the rare case
                        // the clamp kicked in.
                        val clampedFinalWidth = finalWidth.coerceAtMost(constraints.maxWidth)
                        val clampedInfoX = infoX.coerceAtMost(clampedFinalWidth - infoPlaceable.width)
                        layout(clampedFinalWidth, finalHeight) {
                            var yPos = 0
                            replyPlaceable?.let {
                                it.placeRelative(0, yPos)
                                yPos += it.height
                            }

                            placeables.forEach { placeable ->
                                placeable.placeRelative(0, yPos)
                                yPos += placeable.height
                            }
                            textPlaceable.placeRelative(0, yPos)
                            yPos += textPlaceable.height

                            showMorePlaceable.placeRelative(0, yPos)
                            yPos += showMorePlaceable.height

                            infoPlaceable.placeRelative(clampedInfoX, infoY)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.MediaTimestampOverlay(
    messageInfoText: String,
    sentByYou: Boolean,
    isPendingSend: Boolean,
    deliveryStatus: Int,
    contentColor: Color,
    pendingSince: Instant?,
) {
    Box(modifier = Modifier.matchParentSize().align(Alignment.BottomStart)) {
        Box(
            modifier = Modifier.fillMaxWidth().height(40.dp)
                .align(Alignment.BottomStart).background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.6f),
                        )
                    )
                ),
        ) {
            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = messageInfoText,
                    style = MaterialTheme.typography.labelSmall,
                    color = HomebaseTheme.extendedColors.bubbleSentOnSurface.copy(alpha = 0.7f),
                )
                if (sentByYou) {
                    Spacer(modifier = Modifier.width(4.dp))
                    DeliveryStatus(
                        isPendingSend = isPendingSend,
                        deliveryStatus = deliveryStatus,
                        contentColor = contentColor.copy(alpha = 0.7f),
                        pendingSince = pendingSince,
                    )
                }
            }
        }
    }
}

/**
 * Build an [AnnotatedString] that highlights every case-insensitive occurrence of
 * [searchQuery] inside [plain] with a background [highlightColor]. Returns null when
 * the query is empty or produces no matches, so the caller can decide whether to skip
 * the highlight code path entirely.
 *
 * Every produced span end is clamped to `plain.length`. This is an invariant the
 * renderer relies on: a span whose end exceeds the drawn string triggers
 * `String.subSequence` inside Compose's text layout and crashes the main thread.
 */
internal fun buildSearchHighlightedText(
    plain: String,
    searchQuery: String,
    highlightColor: Color,
): AnnotatedString? {
    if (searchQuery.isEmpty()) return null
    val lowerQuery = searchQuery.lowercase()
    val lowerText = plain.lowercase()
    if (!lowerText.contains(lowerQuery)) return null
    return buildAnnotatedString {
        append(plain)
        var startIndex = 0
        while (true) {
            val idx = lowerText.indexOf(lowerQuery, startIndex)
            if (idx == -1) break
            val endIdx = (idx + lowerQuery.length).coerceAtMost(plain.length)
            addStyle(SpanStyle(background = highlightColor), idx, endIdx)
            startIndex = endIdx
        }
    }
}