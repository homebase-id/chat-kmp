package id.homebase.core.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object Dimens {

    object Spacing {
        val label = 4.dp
        val item = 8.dp
        val row = 12.dp
        val gutter = 16.dp
    }

    object Message {
        val cornerRadius = 18.dp
        val cornerCollapseRadius = 4.dp
        val senderAvatarSize = 28.dp
    }

    object MediaBubble {
        val minWidthSolo = 150.dp
        val minWidthWithContent = 240.dp
        val minHeight = 100.dp
        val maxHeight = 320.dp
        val galleryWidth = 210.dp
    }

    object Sticker {
        // Max edge of the sticker IMAGE, not of the visible cut-out: ~12% of each axis is
        // white halo + crop margin, so the subject renders ~158.dp. Coupled to
        // StickerImageProcessor.OUTLINE_RADIUS_FRACTION — retune the two together.
        val baseSize = 180.dp

        // Clamps [baseSize] against the viewport so a sticker can never dominate a short or
        // landscape window; on a phone (~700-800.dp tall) it lands above [baseSize].
        const val maxHeightFraction = 0.35f
    }

    object ConversationRow {
        val cornerRadius = 8.dp

        // Height is deliberately not part of the pointer/touch split: 72px around a 48dp
        // avatar already matched Signal and WhatsApp Desktop, so only the horizontal
        // metrics were tightened (#1365).
        val verticalPadding = Spacing.row
        val avatarGap = Spacing.row

        data class Metrics(
            val listGutter: Dp,
            val horizontalPadding: Dp,
            val avatarPadding: Dp,
            val titlePreviewGap: Dp,
            val previewIconSize: Dp,
        )

        // Pointer-vs-touch, not window width: a touch tablet at desktop width still needs
        // the 48dp target, so callers select on isDesktopOrWeb(), never on a size class.
        val touch = Metrics(
            listGutter = Spacing.gutter,
            horizontalPadding = Spacing.row,
            avatarPadding = Spacing.item,
            titlePreviewGap = Spacing.label,
            previewIconSize = 16.dp,
        )

        val pointer = Metrics(
            listGutter = Spacing.item,
            horizontalPadding = 10.dp,
            avatarPadding = 0.dp,
            titlePreviewGap = 0.dp,
            previewIconSize = 14.dp,
        )

        fun metrics(isPointer: Boolean): Metrics = if (isPointer) pointer else touch
    }
}
