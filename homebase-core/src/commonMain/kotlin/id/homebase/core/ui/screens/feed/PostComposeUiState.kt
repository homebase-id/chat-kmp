package id.homebase.core.ui.screens.feed

import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.link.LinkPreview
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.core.feed.services.ReactAccess

/**
 * Flat compose-screen state for a new feed post. Mirrors [MomentComposeUiState] minus the
 * Moments-only EXIF/date-override machinery — a feed post is a caption plus optional media
 * (or a single link preview when no media is attached) targeting one channel + audience.
 *
 * The full-fidelity per-attachment [AttachmentPendingFile] model carries the `PlatformFile`,
 * video trim, etc.; it is converted to `AttachmentInput` at the post boundary (see
 * [PostComposeViewModel.submit]). [linkPreview] is auto-derived from a URL in the caption only
 * while no media is attached — attaching a file hides it, matching the post sender's contract
 * (`if (attachments.isEmpty() && linkPreview != null)` in [id.homebase.core.feed.services.FeedPostSenderService]).
 */
data class PostComposeUiState(
    val caption: String = "",
    val attachments: List<AttachmentPendingFile> = emptyList(),
    /** Auto-fetched preview for a URL in the caption; only shown/uploaded when no media is attached. */
    val linkPreview: LinkPreview? = null,
    /** True while a link-preview fetch is in flight, so the card can show a spinner. */
    val isFetchingLinkPreview: Boolean = false,
    /** The audience security group the post is published to. Defaults to a fully-public post. */
    val audience: SecurityGroupType = SecurityGroupType.Anonymous,
    val reactAccess: ReactAccess = ReactAccess.All,
    val isPosting: Boolean = false,
    val errorMessage: String? = null,
) {
    /** Effective preview to render: only when media is absent (the sender drops it otherwise). */
    val effectiveLinkPreview: LinkPreview?
        get() = if (attachments.isEmpty()) linkPreview else null

    /** A post needs either some text or at least one attachment. */
    val canPost: Boolean
        get() = (caption.isNotBlank() || attachments.isNotEmpty()) && !isPosting
}
