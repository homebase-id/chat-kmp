@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.webdrop

import id.homebase.api.HomebaseProtocol
import id.homebase.api.crypto.Base64UrlEncoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

/**
 * The WebDrop wire contract (odin-core docs/web-drop-plan.md). A drop is a file the server treats
 * as unencrypted with an anonymous ACL — the only shape the platform allows a stranger to read —
 * whose payload bytes are AES-CBC-encrypted under a random key that travels solely in the URL
 * fragment. Payload IVs cannot ride in the upload manifest (the server requires them null on an
 * unencrypted file), so they ride in cleartext appData.content instead; IVs are not secret.
 */
object WebDropProtocol {
    const val ContentVersion = 1

    const val DropFileType = 100
    const val ReceiptFileType = 101

    const val ManifestPayloadKey = "wdr_meta"

    /** The manifest takes one payload slot; every remaining slot is an attachment. */
    const val MaxFilesPerDrop = HomebaseProtocol.MaxPayloadsPerFile - 1

    fun dataPayloadKey(index: Int): String = "wdr_dat${index + 1}"

    const val KeyBytes = 16

    val BurnAfterOpen: Duration = 20.minutes
    val MaxLifetime: Duration = 30.days

    /** The server's Ttl encoding: < 0 is "this many ms after the first payload read". */
    fun burnTtl(): Long = -BurnAfterOpen.inWholeMilliseconds

    fun absoluteTtl(nowMs: Long, lifetime: Duration): Long =
        nowMs + lifetime.coerceAtMost(MaxLifetime).inWholeMilliseconds

    fun buildLink(identity: String, driveAlias: Uuid, dropId: Uuid, key: ByteArray): String =
        "https://$identity/apps/web-drop/d/$driveAlias/$dropId#${Base64UrlEncoder.encode(key)}"
}

/** Cleartext appData.content of a drop file. Nothing here may leak content. */
@Serializable
data class WebDropDropContent(
    val v: Int = WebDropProtocol.ContentVersion,
    /** payload key → base64 IV, one per encrypted payload including the manifest. */
    val ivs: Map<String, String>,
)

/** One entry of the encrypted wdr_meta manifest — the recipient's file list. */
@Serializable
data class WebDropManifestEntry(
    val key: String,
    val name: String,
    val contentType: String,
    val size: Long,
)

/**
 * Encrypted appData.content of the owner-only receipt file. Carries what the drop file cannot:
 * names and the full link (fragment key included), so the owner can re-copy it later. The drop
 * file itself stays the authority for status.
 */
@Serializable
data class WebDropReceiptContent(
    val v: Int = WebDropProtocol.ContentVersion,
    val name: String,
    val files: List<WebDropManifestEntry>,
    val url: String,
    val ttl: Long,
    val createdAt: Long,
)
