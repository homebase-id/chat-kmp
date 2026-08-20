package id.homebase.core.feed.services

import id.homebase.api.crypto.Md5
import kotlin.uuid.Uuid

// Values are verbatim from the dotyoucore-js feed app so the KMP client converges on the same file types,
// dataTypes, payload keys and drive ids as the TypeScript clients.
object FeedProtocol {


    const val PostFileType = 101

    const val DraftPostFileType = 102

    const val ChannelDefinitionFileType = 103

    const val CommentFileType = 801


    const val TweetDataType = 100

    const val MediaDataType = 200

    const val ArticleDataType = 300


    const val PostVersion = 1

    const val CommentVersion = 1

    // Payload keys must match ^[a-z0-9_]{8,10}$.

    /** Combine with [mediaPayloadKey] (e.g. `pst_mdi_00`). */
    const val MediaPayloadKeyPrefix = "pst_mdi"

    const val LinksPayloadKey = "pst_links"

    /** Used when content exceeds the header budget. */
    const val FullTextPayloadKey = "pst_text"

    const val CommentMediaPayloadKey = "cmmnt_md"


    /** Shared by every channel drive (default channel = the public-channel drive). */
    val ChannelDriveType: Uuid = Uuid.parse("8f448716-e34c-edf9-0141-45e043ca6612")

    /** `Md5.toGuidId("public_channel_drive")`. */
    val PublicChannelDriveAlias: Uuid = Md5.toGuidId("public_channel_drive")

    /** The two-digit zero-padded suffix keeps the key 10 chars, within `^[a-z0-9_]{8,10}$`. */
    fun mediaPayloadKey(index: Int): String =
        "${MediaPayloadKeyPrefix}_${index.toString().padStart(2, '0')}"
}
