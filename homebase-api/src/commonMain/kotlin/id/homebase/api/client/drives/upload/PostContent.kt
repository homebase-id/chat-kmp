package id.homebase.api.client.drives.upload

import kotlinx.serialization.Serializable

/** Types of posts */
enum class PostType {
    Article,
    Media,
    Tweet
}

/** React access configuration */
@Serializable
data class ReactAccess(
        val enabled: Boolean = true
)

/** Primary media file reference */
@Serializable
data class PrimaryMediaFile(
        val fileId: String? = null,
        val fileKey: String? = null,
        val type: String? = null
)

/** Embedded post reference */
@Serializable
data class EmbeddedPost(
        val globalTransitId: String? = null,
        val odinId: String? = null
)

/** Rich text content */
@Serializable
data class RichText(
        val type: String? = null,
        val content: String? = null
)

/**
 * Post content structure for uploads.
 * Based on TypeScript interface PostContent from odin-js.
 */
@Serializable
data class PostContent(
    /** ID that is set once and never changes; Used for permalink */
        val id: String,
    val channelId: String,
    val reactAccess: ReactAccess? = null,
    /** A collaborative post; => Anyone with access can edit it */
        val isCollaborative: Boolean? = null,
    val caption: String,
    val captionAsRichText: RichText? = null,
    val slug: String,
    val primaryMediaFile: PrimaryMediaFile? = null,
    val type: PostType = PostType.Tweet,
    val embeddedPost: EmbeddedPost? = null,
    /** For posts from external sources */
        val sourceUrl: String? = null
)
