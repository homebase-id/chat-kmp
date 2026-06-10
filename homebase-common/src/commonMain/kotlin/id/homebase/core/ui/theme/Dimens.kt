package id.homebase.core.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Homebase dimension constants. Converted from Signal's Android XML dimens.xml to Compose Dp/Sp
 * values.
 *
 * Usage: Dimens.toolbarHeight, Dimens.Message.cornerRadius, etc.
 */
object Dimens {
    // Toolbar & Navigation
    val toolbarHeight = 64.dp
    val toolbarAvatarSize = 28.dp
    val toolbarAvatarMargin = 26.dp

    // Bottom Sheet
    val bottomSheetCornerSize = 18.dp

    // Keyboard
    object Keyboard {
        val minSize = 60.dp
        val defaultCustomSize = 260.dp
        val minCustomSize = 110.dp
        val minCustomTopMarginPortrait = 170.dp
        val minCustomTopMarginLandscapeBubble = 56.dp
        val toolbarHeight = 44.dp
        val emojiDrawerItemWidth = 46.dp
    }

    // Conversation
    object Conversation {
        val itemBodyTextSize = 16.sp
        val itemDateTextSize = 12.sp
        val bottomPadding = 2.dp
        val composeHeight = 44.dp
        val individualRightGutter = 16.dp
        val individualLeftGutter = 16.dp
        val individualReceivedLeftGutter = 8.dp
        val groupLeftGutter = 52.dp
        val verticalMessageSpacingDefault = 8.dp
        val verticalMessageSpacingCollapse = 1.dp
        val headerPadding = 24.dp
        val headerPaddingExpanded = 32.dp
        val headerMaxSize = 308.dp
        val headerMargin = 40.dp
        val listAvatarSize = 48.dp
        val itemReplySize = 38.dp
        val itemAvatarSize = 28.dp
        val updateVerticalMargin = 4.dp
        val updateVerticalPadding = 20.dp
        val updateVerticalPaddingCollapsed = 4.dp
    }

    // Contact
    val contactPhotoTargetSize = 64.dp
    val contactSelectionActionsTapArea = 10.dp
    val contactSelectionItemHeight = 64.dp

    // Album/Media Grid
    object Album {
        val totalWidth = 210.dp
        val twoTotalHeight = 105.dp
        val twoCellWidth = 104.dp
        val threeTotalHeight = 140.dp
        val threeCellWidthBig = 139.dp
        val threeCellSizeSmall = 69.dp
        val fourTotalHeight = 210.dp
        val fourCellSize = 104.dp
        val fiveTotalHeight = 175.dp
        val fiveCellSizeBig = 104.dp
        val fiveCellSizeSmall = 69.dp
    }

    // Camera
    object Camera {
        val contactsHorizontalMargin = 16.dp
        val captureButtonSize = 124.dp
        val captureImageButtonSize = 76.dp
    }

    // Message Bubble
    object Message {
        val cornerRadius = 18.dp
        val cornerCollapseRadius = 4.dp
        val bubbleCornerRadius = 2.dp
        val bubbleShadowDistance = 1.5.dp
        val bubbleHorizontalPadding = 12.dp
        val bubbleTopPadding = 7.dp
        val bubbleTopImageMargin = 4.dp
        val bubbleTextOnlyTopMargin = 4.dp
        val bubbleTopPaddingAudio = 12.dp
        val bubbleCollapsedFooterPadding = 6.dp
        val bubbleEdgeMargin = 32.dp
        val bubbleBottomPadding = 7.dp
        val bubbleFooterBottomPadding = 5.dp
        val bubbleCollapsedBottomPadding = 7.dp
        val bubbleRevealablePadding = 12.dp
        val bubbleDefaultFooterBottomMargin = (-4).dp
        val audioWidth = 212.dp
    }

    // Media Bubble
    object MediaBubble {
        val removeButtonSize = 24.dp
        val editButtonSize = 24.dp
        val defaultDimens = 210.dp
        val minWidthSolo = 150.dp
        val minWidthWithContent = 240.dp
        val maxWidth = 240.dp
        val minHeight = 100.dp
        val maxHeight = 320.dp
        val maxHeightCondensed = 150.dp
        val stickerDimens = 175.dp
        val gifWidth = 240.dp
    }

    // Progress & Loading
    val progressDialogSize = 120.dp

    // Thumbnail
    val thumbnailDefaultRadius = 4.dp

    // Reactions
    object Reaction {
        val scrubberAnimStartTranslationY = 25.dp
        val scrubberAnimEndTranslationY = 0.dp
        val scrubberWidth = 320.dp
        val scrubberHeight = 136.dp
        val touchDeadzoneSize = 40.dp
        val scrubDeadzoneDistanceFromTouchBottom = 30.dp
        val scrubVerticalTranslation = 25.dp
        val scrubHorizontalMargin = 16.dp
    }

    // Calling Reactions
    object CallingReaction {
        val scrubberMargin = 4.dp
        val emojiHeight = 48.dp
        val popupMenuWidth = 320.dp
        val popupMenuHeight = 120.dp
        val raiseHandSnackbarMargin = 16.dp
    }

    // Quote
    object Quote {
        val cornerRadiusLarge = 10.dp
        val cornerRadiusBottom = 4.dp
        val cornerRadiusPreview = 18.dp
        val thumbSize = 60.dp
        val storyThumbWidth = 40.dp
        val storyEmojiMargin = 24.dp
        val storyThumbHeight = 64.dp
    }

    // Mention
    val mentionCornerRadius = 4.dp

    // Media Overview
    val mediaOverviewDetailItemHeight = 72.dp

    // Sticker
    object Sticker {
        val pageItemPadding = 16.dp
        val pageItemWidth = 72.dp
        val previewStickerSize = 96.dp
        val previewGutterSize = 16.dp

        // Max edge of a sticker rendered in a chat bubble. Comparable to the emoji-only
        // big glyph size (and well under Signal's 512 logical-px sticker) so the bare
        // image floats on the wallpaper without dominating the column.
        val maxSize = 160.dp
    }

    // Tooltip
    val tooltipPopupMargin = 8.dp

    // Conversation List
    val conversationListArchivePadding = 12.dp

    // Unread Count
    val unreadCountBubbleRadius = 12.5.dp

    // Invite
    val inviteEditTextMinHeight = 84.dp

    // FAB
    val fabMargin = 16.dp

    // Recording
    val recordingVoiceLockTarget = (-150).dp

    // Selection
    object Selection {
        val itemHeaderHeight = 64.dp
        val itemHeaderWidth = 48.dp
    }

    // Storage
    val storageLegendCircleSize = 8.dp

    // Debug
    val debugLogTextSize = 12.sp

    // Picture in Picture
    object PictureInPicture {
        val framePadding = 16.dp
        val pipWidth = 90.dp
        val pipHeight = 160.dp
    }

    // Emoji Sheet
    val emojiBottomSheetMinHeight = 340.dp

    // Waveform
    val waveFormBarWidth = 2.dp

    // Transfer
    object Transfer {
        val topPadding = 64.dp
        val splitTopPadding = 32.dp
        val itemSpacing = 24.dp
        val progressbarToTextViewMargin = 2.dp
        val parentToTextViewMargin = 4.dp
        val primaryBackgroundHeight = 44.dp
    }

    // Payment
    object Payment {
        val keyWidth = 80.dp
        val keyHeight = 70.dp
        val keyBottomPadding = 16.dp
        val keyBottomRowMarginBottom = 33.dp
        val keyTopRowMarginTop = 11.dp
        val recoveryPhraseAdapterMargin = 49.dp
        val recoveryPhraseOutlineMargin = 32.dp
    }

    // Gutters & Margins
    object Gutter {
        val bankTransferMandate = 16.dp
        val dslSettings = 16.dp
        val activeSubscriptionStart = 14.dp
        val mediaOverviewToggle = 2.dp
        val wallpaperSelection = 8.dp
        val safetyNumberRecipientRowItem = 4.dp
    }

    // Selectable List
    object SelectableList {
        val itemMargin = 8.dp
        val itemPadding = 8.dp
    }

    // Chat Colors
    val chatColorsPreviewBubbleMaxWidth = 240.dp

    // Conversation Settings
    val conversationSettingsButtonStripSpacingHalf = 16.dp

    // Avatar Picker
    val avatarPickerImageWidth = 100.dp

    // Verify Identity
    val verifyIdentityVerticalMargin = 26.dp

    // Context Menu
    val contextMenuCornerRadius = 18.dp

    // WebRTC / Calling
    object WebRtc {
        val buttonSize = 48.dp
        val audioIndicatorMargin = 8.dp
    }

    // Segmented Progress Bar
    object SegmentedProgressBar {
        val defaultSegmentMargin = 8.dp
        val defaultCornerRadius = 0.dp
        val defaultSegmentStrokeWidth = 0.dp
    }

    // Stories
    object Stories {
        val landingItemThumbWidth = 48.dp
        val landingItemThumbHeight = 72.dp
        val landingItemThumbSecondaryWidth = 36.dp
        val landingItemThumbSecondaryHeight = 64.dp
        val landingItemThumbOutlineWidth = 52.dp
        val landingItemThumbOutlineHeight = 76.dp
        val landingItemTextHorizontalMargin = 20.dp
    }

    // Media Preview
    object MediaPreview {
        val videoTimestampInset = 20.dp
        val bottomBarVerticalOuterMargin = 16.dp
        val buttonWidth = 48.dp
        val buttonHeight = 48.dp
        val buttonHorizontalMargin = 8.dp
        val lottieButtonDimen = 36.dp
        val railItemSize = 46.dp
        val railThumbnailSize = 44.dp
    }

    // Registration
    val registrationTextViewPadding = 32.dp

    // Safety Number
    val safetyNumberQrPeek = 24.dp

    // Video Timeline
    object VideoTimeline {
        val heightExpanded = 44.dp
        val heightCollapsed = 1.dp
    }

    // Image Editor
    object ImageEditor {
        val hudToolFilledCircleDiameter = 40.dp
        val hudToolFilledCirclePadding = 6.dp
        val hudToolInvisibleCircleDiameter = 40.dp
        val hudToolInvisibleCirclePadding = 6.dp
    }

    // Contact Permission
    val contactPermissionButtonMinWidth = 140.dp

    // Call Screen
    object CallScreen {
        val controlsButtonHorizontalMargin = 8.dp
        val controlsButtonBottomMargin = 30.dp
        val largeHeaderAvatarSize = 96.dp
        val largeHeaderAvatarMarginTop = 106.dp
        val participantItemMarginEnd = 4.dp
        val participantItemMarginBottom = 0.dp
        val overflowItemSize = 72.dp
    }

    // Chat Folder
    val chatFolderRowHeight = 64.dp

    // Donation
    val donationPillMaxWidth = 150.dp
}
