package id.homebase.core.ui.screens.feed

import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.link.LinkPreview
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.core.feed.services.EmbeddedPost
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.ReactAccess
import kotlin.uuid.Uuid

/** A channel the composer can post to: its drive alias [id] and display [name]. */
data class ChannelOption(val id: Uuid, val name: String)

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
    /** Channels the post can target; the public channel is always present (first). */
    val channels: List<ChannelOption> = emptyList(),
    /** Drive alias of the selected channel; defaults to the public channel. */
    val selectedChannelId: Uuid = FeedProtocol.PublicChannelDriveAlias,
    /** The source post being quoted, when this compose is a repost; null for a fresh post. */
    val embeddedPost: EmbeddedPost? = null,
    /** True when editing an existing post (caption-only): hides media/channel controls, retitles. */
    val isEditing: Boolean = false,
    val isPosting: Boolean = false,
    val errorMessage: String? = null,
) {
    /** Effective preview to render: only when media is absent (the sender drops it otherwise). */
    val effectiveLinkPreview: LinkPreview?
        get() = if (attachments.isEmpty()) linkPreview else null

    /**
     * A post needs some text, at least one attachment, or a quoted post (a bare quote is valid).
     */
    val canPost: Boolean
        get() = (caption.isNotBlank() || attachments.isNotEmpty() || embeddedPost != null) &&
            !isPosting
}
