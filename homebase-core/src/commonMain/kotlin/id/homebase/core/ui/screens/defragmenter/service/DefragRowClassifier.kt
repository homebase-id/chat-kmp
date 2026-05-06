package id.homebase.core.ui.screens.defragmenter.service

import id.homebase.api.client.drives.FileState
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
    /**
     * Set of conversation `appData.uniqueId`s that are present-and-mappable on
     * the chat drive (i.e. NOT classified as [CellState.UnmappableConversation]
     * and NOT soft-deleted). Used to flag chat-message rows whose
     * `appData.groupId` doesn't resolve. Null disables orphan-message detection
     * entirely (rows are then never classified as [CellState.OrphanChatMessage]).
     */
    healthyConversationIds: Set<Uuid>? = null,
    /**
     * Returns null if the chat-message file's `appData.content` decodes
     * successfully as the expected payload type; non-null (the throwable)
     * when deserialisation throws. Only invoked for `appData.fileType == 7878`
     * rows whose header parsed cleanly and that didn't already classify as
     * orphan. Null disables corrupt-content detection (probe-disabled mode);
     * tests can also leave this null to focus on header-level checks.
     */
    decodeMessageContentProbe: (suspend (HomebaseFile) -> Throwable?)? = null,
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
                conversationId = header.fileMetadata.appData.uniqueId,
                originalAuthor = header.fileMetadata.originalAuthor,
            )
        }
    }

    // 3. Soft-delete branches.
    //
    // The canonical deletion marker is `fileState == FileState.Deleted`.
    // The legacy in-JSON `appData.archivalStatus == Removed` marker still
    // appears on older rows (which is why HomebaseFile.isSoftDeleted() ORs
    // both), but the SQL projection's `archivalStatus` column may legitimately
    // stay 0 on canonically-deleted files — that's NOT drift.
    //
    // Drift = SQL.archivalStatus == Removed AND fileState != Deleted.
    // (SQL says removed but the canonical marker still says active. Either
    // the SQL projection diverged or a legacy archivalStatus-only deletion
    // was never migrated to fileState. Repair will sync SQL back to the
    // canonical truth.)
    val canonicallyDeleted = header.fileState == FileState.Deleted
    val sqlRemoved = row.archivalStatus == ARCHIVAL_STATUS_REMOVED

    if (canonicallyDeleted) {
        return CellState.SoftDeleted(
            ref = DeletedFileRef(driveId = driveId, fileId = row.fileId),
        )
    }
    if (fileType == MESSAGE_FILE_TYPE && sqlRemoved) {
        return CellState.SoftDeleteArchivalMismatch(
            driveId = driveId,
            fileId = row.fileId,
            rowId = row.rowId,
        )
    }
    // Legacy-only marker (appData.archivalStatus == Removed but fileState
    // != Deleted and SQL.archivalStatus != Removed) — treat as soft-deleted
    // so the row is hard-deleted in the next defrag pass. The legacy marker
    // is the user's intent to delete; we honour it.
    if (header.isSoftDeleted()) {
        return CellState.SoftDeleted(
            ref = DeletedFileRef(driveId = driveId, fileId = row.fileId),
        )
    }

    // 4. Orphan chat message — groupId points to a missing or unmappable
    //    conversation. Healable via ConversationService.recoverConversation.
    if (fileType == MESSAGE_FILE_TYPE && healthyConversationIds != null) {
        val groupId = header.fileMetadata.appData.groupId
        if (groupId != null && groupId !in healthyConversationIds) {
            return CellState.OrphanChatMessage(
                driveId = driveId,
                fileId = row.fileId,
                rowId = row.rowId,
                conversationId = groupId,
                originalAuthor = header.fileMetadata.originalAuthor,
                sender = header.fileMetadata.senderOdinId,
            )
        }
    }

    // 5. Chat message (7878) whose appData.content fails strict deserialise.
    //    Placed AFTER the OrphanChatMessage branch so an orphan that *also*
    //    has corrupt content stays classified as orphan — recovering the
    //    parent may resolve both at once. Placed BEFORE LegacyUserDateZero
    //    because a corrupt-content row often *also* has a null userDate
    //    (older corrupt rows commonly never had userDate stamped); without
    //    this ordering the row would be classified Legacy and "repaired"
    //    by stamping a userDate, which leaves the unreadable content in
    //    place and the consumer keeps throwing on every cold-load.
    //    Probe is null in tests / when the caller doesn't want runtime-typed
    //    decode (e.g. probe-disabled).
    if (fileType == MESSAGE_FILE_TYPE && decodeMessageContentProbe != null) {
        val err = decodeMessageContentProbe(header)
        if (err != null) {
            val content = header.fileMetadata.appData.content
            return CellState.CorruptMessageContent(
                driveId = driveId,
                fileId = row.fileId,
                rowId = row.rowId,
                conversationId = header.fileMetadata.appData.groupId,
                originalAuthor = header.fileMetadata.originalAuthor,
                sender = header.fileMetadata.senderOdinId,
                createdMs = header.fileMetadata.created.milliseconds,
                decodeError = err.message?.take(200),
                rawContentPrefix = content?.take(4000) ?: "",
            )
        }
    }

    // 6. Legacy null-userDate (chat messages only).
    //
    // Gates only on `appData.userDate == null` in the parsed JSON header
    // (the source of truth the consumer reads). Deliberately ignores
    // `row.userDate` — it may already be non-zero from a prior repair pass
    // that patched the SQL projection but didn't yet patch the header. We
    // need to re-flag those so the now-extended repair can rewrite the
    // jsonHeader column too.
    if (
        fileType == MESSAGE_FILE_TYPE &&
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

    // 7. Default.
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
        rawHeaderPrefix = row.jsonHeader.take(4000),
    )
}
