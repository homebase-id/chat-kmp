package id.homebase.chat.groupsettings

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.HomebaseFile

/**
 * Projection helpers for the group-info file health diagnostic. Pure
 * functions over a single [HomebaseFile] (or null for "no row"); all the
 * branching that decides what each diagnostic row displays lives here so
 * it can be unit-tested without ViewModel scaffolding.
 *
 * Notes on the rules:
 *
 * - **DB Placeholder** is the local-only marker written by
 *   `OptimisticWriter.writeLocalOnlyConversationPlaceholder` /
 *   `writeLocalOnlyAdminPlaceholder` (versionTag=null). It exists in
 *   the local DB but never on the server.
 * - **Server Placeholder** is impossible by construction — the server
 *   never accepts a versionTag=null write. If the server ever returns one
 *   we treat it as Absent and log so we notice.
 * - A DB row with a real versionTag but no author is malformed; surface
 *   as Placeholder rather than crashing on the unwrap and log.
 */
internal fun toDbRow(file: HomebaseFile?): DbFileRow {
    if (file == null) return DbFileRow.Absent
    val vt = file.fileMetadata.versionTag
    if (vt == null) return DbFileRow.Placeholder(fileId = file.fileId)
    val author = file.fileMetadata.originalAuthor ?: file.fileMetadata.senderOdinId
    if (author == null) {
        Logger.w { "toDbRow: file ${file.fileId} has versionTag=$vt but no author — surfacing as Placeholder" }
        return DbFileRow.Placeholder(fileId = file.fileId)
    }
    return DbFileRow.Present(versionTag = vt, originalAuthor = author, fileId = file.fileId)
}

internal fun toServerRow(file: HomebaseFile?): ServerFileRow {
    if (file == null) return ServerFileRow.Absent
    val vt = file.fileMetadata.versionTag
    if (vt == null) {
        Logger.w { "toServerRow: server returned a file with versionTag=null (fileId=${file.fileId}) — surfacing as Absent" }
        return ServerFileRow.Absent
    }
    val author = file.fileMetadata.originalAuthor ?: file.fileMetadata.senderOdinId
    if (author == null) {
        Logger.w { "toServerRow: server returned a file with no author (fileId=${file.fileId}, vt=$vt) — surfacing as Absent" }
        return ServerFileRow.Absent
    }
    return ServerFileRow.Present(versionTag = vt, originalAuthor = author, fileId = file.fileId)
}
