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
    // Optional: dotyoucore-js comments (CommentReaction) carry no `version` field, so requiring it
    // made every web-authored comment fail to deserialize (empty body, envelope name only). Default
    // to the current version so our own comments are unaffected.
    val version: Int = FeedProtocol.CommentVersion,
    /** User-entered comment text. */
    val body: String,
    /** Optional rich-text representation of [body]. */
    val bodyRichText: String? = null,
    /** Payload key of a single attached image (see [FeedProtocol.CommentMediaPayloadKey]). */
    val mediaPayloadKey: String? = null,
)
