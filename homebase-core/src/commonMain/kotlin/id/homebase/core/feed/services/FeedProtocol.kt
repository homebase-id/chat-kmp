package id.homebase.core.feed.services

import id.homebase.api.crypto.Md5
import kotlin.uuid.Uuid

/**
 * Wire constants for the native feed, mirroring [id.homebase.core.moments.services.MomentsProtocol].
 *
 * Values are verbatim from the dotyoucore-js feed app (`PostTypes.ts`) so the KMP client
 * converges on the same file types, dataTypes, payload keys and drive ids as the existing
 * TypeScript clients. Posts are files (`fileType = 101`) on channel drives (default = the
 * public-channel drive); comments are `fileType = 801` files linked to their post by `groupId`.
 */
object FeedProtocol {

    // File types (PostTypes.ts) ------------------------------------------------

    const val PostFileType = 101

    const val DraftPostFileType = 102

    const val ChannelDefinitionFileType = 103

    const val CommentFileType = 801

    // dataTypes (PostTypes.ts) -------------------------------------------------

    const val TweetDataType = 100

    const val MediaDataType = 200

    const val ArticleDataType = 300

    // Content versions ---------------------------------------------------------

    const val PostVersion = 1

    const val CommentVersion = 1

    // Payload keys (must match ^[a-z0-9_]{8,10}$) ------------------------------

    /** Prefix for per-media payload keys; combine with [mediaPayloadKey] (e.g. `pst_mdi_00`). */
    const val MediaPayloadKeyPrefix = "pst_mdi"

    /** Link-preview payload key (9 chars). */
    const val LinksPayloadKey = "pst_links"

    /** Full-text overflow payload key, used when content exceeds the header budget (8 chars). */
    const val FullTextPayloadKey = "pst_text"

    /** Comment-media payload key (8 chars). */
    const val CommentMediaPayloadKey = "cmmnt_md"

    // Drives -------------------------------------------------------------------

    /** GUID type shared by every channel drive (default channel = the public-channel drive). */
    val ChannelDriveType: Uuid = Uuid.parse("8f448716-e34c-edf9-0141-45e043ca6612")

    /** Alias of the default public-channel drive — `Md5.toGuidId("public_channel_drive")`. */
    val PublicChannelDriveAlias: Uuid = Md5.toGuidId("public_channel_drive")

    /**
     * Per-media payload key for the [index]th attachment, e.g. `mediaPayloadKey(0) == "pst_mdi_00"`.
     * The two-digit zero-padded suffix keeps the key 10 chars long, within `^[a-z0-9_]{8,10}$`.
     */
    fun mediaPayloadKey(index: Int): String =
        "${MediaPayloadKeyPrefix}_${index.toString().padStart(2, '0')}"
}
