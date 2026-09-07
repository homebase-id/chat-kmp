@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.webdrop.model

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.webdrop.WebDropProtocol
import id.homebase.core.webdrop.WebDropReceiptContent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

enum class WebDropTtlChoice(val lifetime: Duration?) {
    BurnAfterOpen(null),
    OneDay(1.days),
    SevenDays(7.days),
    ThirtyDays(30.days);

    fun toTtl(nowMs: Long): Long = when (this) {
        BurnAfterOpen -> WebDropProtocol.burnTtl()
        else -> WebDropProtocol.absoluteTtl(nowMs, lifetime!!)
    }
}

/** A picked file on its way into a drop; [path] must already be a real filesystem path. */
data class PickedDropFile(
    val path: String,
    val name: String,
    val contentType: String,
    val size: Long,
)

sealed interface DropStatus {
    /** A burn drop nobody has opened yet — its clock has not started. */
    data object Waiting : DropStatus

    /** A burn drop somebody opened; it dies at [diesAtMs]. */
    data class Opened(val diesAtMs: Long) : DropStatus

    /** A fixed-lifetime drop counting down; opening it is not observable. */
    data class Expiring(val diesAtMs: Long) : DropStatus

    /** Expired, burned, or revoked — the drop file is a tombstone or gone. */
    data object Removed : DropStatus
}

/** One row of the drops list: the receipt supplies identity, the drop file supplies status. */
data class DropRow(
    val dropId: Uuid,
    val receiptFileId: Uuid,
    /** null once the drop file is hard-gone; revoke needs it while it exists. */
    val dropFileId: Uuid?,
    val receipt: WebDropReceiptContent,
    val status: DropStatus,
)

fun HomebaseFile.toReceiptContent(): WebDropReceiptContent? {
    val content = fileMetadata.appData.content ?: return null
    return runCatching {
        OdinSystemSerializer.deserialize<WebDropReceiptContent>(content)
    }.getOrNull()
}

/**
 * Status straight off the drop file, no bookkeeping: the owner's device syncs the drop back
 * complete with its resolved ttl and — because expiry soft-deletes — its tombstone. Whether a
 * positive ttl means "opened" depends on what the drop started as, which the receipt remembers:
 * only a burn drop's ttl flips sign on first read.
 */
fun dropStatusOf(dropFile: HomebaseFile?, receiptTtl: Long): DropStatus {
    if (dropFile == null || dropFile.isSoftDeleted()) return DropStatus.Removed
    val ttl = dropFile.fileMetadata.ttl ?: 0
    val startedAsBurn = receiptTtl < 0
    return when {
        ttl > 0 && startedAsBurn -> DropStatus.Opened(diesAtMs = ttl)
        ttl > 0 -> DropStatus.Expiring(diesAtMs = ttl)
        else -> DropStatus.Waiting
    }
}
