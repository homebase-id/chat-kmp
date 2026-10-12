package id.homebase.core.filesync

import kotlin.uuid.Uuid

/** The Filesync drive format: docs/filesync-browser.md, and filesync's docs/spec/node-schema-v1.md. */
object FilesyncProtocol {
    // Filesync's fixed ids (filesync app.rs); its desktop clients address the drive by them.
    val DriveAlias: Uuid = Uuid.parse("cfceb937-b868-496b-91cb-3402f18320f8")
    val DriveType: Uuid = Uuid.parse("5da48122-e946-487a-8b9d-589642f3c91d")
    const val DriveSlug = "filesync"
    const val DriveTypeSlug = "filesync"

    const val FolderFileType = 7101
    const val FileFileType = 7102

    /** Never stored: a node whose groupId is this is at the top level. */
    val RootNodeId: Uuid = Uuid.parse("2a1c0d55-8ad3-417e-a5d7-42c3d0baba0e")

    const val PayloadKey = "sync_data"

    // Until the first final release, the stable per-OS assets on filesync-release-production don't exist yet.
    const val DesktopDownloadUrl = "https://github.com/homebase-id/filesync/releases"
}
