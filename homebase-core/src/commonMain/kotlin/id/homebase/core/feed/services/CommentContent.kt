package id.homebase.core.feed.services

import kotlinx.serialization.Serializable

/**
 * Descriptor for a comment on a feed post (`fileType = 801`), serialized through
 * [id.homebase.api.serialization.OdinSystemSerializer]. Mirrors
 * [id.homebase.core.moments.services] comment content and dotyoucore-js `CommentContent`.
 *
 * The comment is tied to its post by the file's `groupId` (= the post id), not by a field here.
 * Envelope fields (author odinId, timestamps) come from the
 * [id.homebase.api.client.drives.files.HomebaseFile]. [body] is user-entered text. Comment
 * threading is strictly one level by design.
 */
@Serializable
data class PostCommentContent(
    val version: Int,
    /** User-entered comment text. */
    val body: String,
    /** Optional rich-text representation of [body]. */
    val bodyRichText: String? = null,
    /** Payload key of a single attached image (see [FeedProtocol.CommentMediaPayloadKey]). */
    val mediaPayloadKey: String? = null,
)
