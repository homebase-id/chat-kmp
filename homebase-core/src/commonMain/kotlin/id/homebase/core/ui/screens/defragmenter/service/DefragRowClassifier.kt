package id.homebase.core.ui.screens.defragmenter.service

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DriveMainIndexWrapper.PagedScanRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.uuid.Uuid

/** Chat message file type (`appData.fileType` = 7878). */
private const val MESSAGE_FILE_TYPE = 7878

/** Chat conversation file type (`appData.fileType` = 8888). */
private const val CONVERSATION_FILE_TYPE = 8888

/** `ArchivalStatus.Removed.value` — soft-delete marker on the SQL column. */
internal const val ARCHIVAL_STATUS_REMOVED = 2L

/**
 * Pure classifier for a [PagedScanRow]. Has no DB / network side effects;
 * given a row + driveId + the result of a (possibly throwing) conversation-mapper
 * probe, decides which [CellState] the row maps to.
 *
 * Split from [LiveDefragSource] so it's unit-testable without spinning up a
 * full DefragSource graph. The `mapToBasicProbe` is hoisted out to keep this
 * file independent of homebase-chat — production wires `ConversationMapper.mapToBasic`
 * via the lambda; tests can inject any probe.
 */
internal suspend fun classifyRow(
    driveId: Uuid,
    row: PagedScanRow,
    /**
     * Returns null if the conversation file maps cleanly; non-null
     * (the throwable) when [ConversationMapper.mapToBasic] threw on this header.
     * Only invoked for rows whose strict deserialise succeeded AND whose
     * `appData.fileType == 8888`. May be null if the caller doesn't want to
     * run the conversation-mapper probe at all (treats every conversation as
     * Healthy from the classifier's point of view).
     */
    mapToBasicProbe: (suspend (HomebaseFile) -> Throwable?)? = null,
): CellState {
    // 1. Strict deserialise: failure → CorruptJsonHeader.
    val header: HomebaseFile = try {
        OdinSystemSerializer.deserialize(row.jsonHeader)
    } catch (t: Throwable) {
        return CellState.CorruptJsonHeader(
            driveId = driveId,
            fileId = row.fileId,
            rowId = row.rowId,
        )
    }

    val fileType = header.fileMetadata.appData.fileType ?: 0

    // 2. Conversation file that mapToBasic refuses → UnmappableConversation.
    if (fileType == CONVERSATION_FILE_TYPE && mapToBasicProbe != null) {
        val mapperError = mapToBasicProbe(header)
        if (mapperError != null) {
            return CellState.UnmappableConversation(
                driveId = driveId,
                fileId = row.fileId,
                rowId = row.rowId,
            )
        }
    }

    // 3. Soft-delete branches.
    if (header.isSoftDeleted()) {
        return if (fileType == MESSAGE_FILE_TYPE && row.archivalStatus != ARCHIVAL_STATUS_REMOVED) {
            CellState.SoftDeleteArchivalMismatch(
                driveId = driveId,
                fileId = row.fileId,
                rowId = row.rowId,
            )
        } else {
            CellState.SoftDeleted(
                ref = DeletedFileRef(driveId = driveId, fileId = row.fileId),
            )
        }
    }

    // 4. Legacy null-userDate (chat messages only).
    if (
        fileType == MESSAGE_FILE_TYPE &&
        row.userDate == 0L &&
        header.fileMetadata.appData.userDate == null &&
        header.fileMetadata.created.milliseconds > 0L
    ) {
        return CellState.LegacyUserDateZero(
            driveId = driveId,
            fileId = row.fileId,
            rowId = row.rowId,
            createdMs = header.fileMetadata.created.milliseconds,
        )
    }

    // 5. Default.
    return CellState.Healthy
}

/**
 * Build a [QuarantineCandidate] for a [CellState.CorruptJsonHeader] row,
 * doing a lenient JsonElement pass to salvage whatever fields we can read
 * from a partially-broken header. Always returns a non-null candidate —
 * `fileId` and `rowId` come from the SQL row directly, so the prompt can
 * always show *something* useful even if the JSON is fully unparseable.
 */
internal fun buildQuarantineCandidate(
    driveId: Uuid,
    row: PagedScanRow,
    deserialiseError: Throwable?,
): QuarantineCandidate {
    val parsed: JsonElement? = try {
        Json.parseToJsonElement(row.jsonHeader)
    } catch (_: Throwable) {
        null
    }
    val fileMetadata = (parsed as? JsonObject)?.get("fileMetadata") as? JsonObject
    val appData = fileMetadata?.get("appData") as? JsonObject

    fun JsonObject.string(field: String): String? =
        (this[field] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    fun JsonObject.long(field: String): Long? =
        (this[field] as? JsonPrimitive)?.longOrNull

    fun JsonObject.int(field: String): Int? =
        (this[field] as? JsonPrimitive)?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    fun JsonObject.uuid(field: String): Uuid? = string(field)?.let {
        try { Uuid.parse(it) } catch (_: Throwable) { null }
    }

    return QuarantineCandidate(
        driveId = driveId,
        fileId = row.fileId,
        rowId = row.rowId,
        deserialiseError = deserialiseError?.message?.take(200),
        senderOdinId = fileMetadata?.string("senderOdinId"),
        originalAuthor = fileMetadata?.string("originalAuthor"),
        createdMs = fileMetadata?.long("created"),
        fileType = appData?.int("fileType"),
        uniqueId = appData?.uuid("uniqueId"),
        rawHeaderPrefix = row.jsonHeader.take(200),
    )
}
