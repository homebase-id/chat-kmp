package id.homebase.core.feed.services

import kotlinx.serialization.Serializable

// Tied to its post by the file's `groupId` (= the post id), not by a field here. Envelope fields (author
// odinId, timestamps) come from the HomebaseFile.
@Serializable
data class PostCommentContent(
    // dotyoucore-js comments carry no `version` field, so requiring it made every web-authored comment
    // deserialize to an empty body.
    val version: Int = FeedProtocol.CommentVersion,
    val body: String,
    val bodyRichText: String? = null,
    val mediaPayloadKey: String? = null,
)
