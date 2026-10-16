package id.homebase.core.filesync

import kotlin.uuid.Uuid

/** The Filesync drive format: filesync's docs/spec/file-browser.md and docs/spec/node-schema-v1.md. */
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

    // filesync's update site (its RELEASING.md); latest/download/<version-free name> always resolves.
    const val DesktopReleasesUrl = "https://github.com/homebase-id/filesync-release-production/releases/latest"
}

/** The top folders a computer syncs in place from the OS's own, told apart by id (never by name), in the order they're listed. */
enum class FilesyncRoleFolder(val nodeId: Uuid) {
    Documents(Uuid.parse("0274575b-c2cf-4ac7-b39b-d56b2c121b75")),
    Pictures(Uuid.parse("c2c9af9b-4873-48d8-a8ff-4779802f038f")),
    Music(Uuid.parse("0f4cd8f9-a914-476d-86d0-b1d1a5215a85")),
    Videos(Uuid.parse("b133291b-9309-46c5-9bfd-ac6c81669049")),
    Downloads(Uuid.parse("5290e709-bc6d-4e12-827e-96d6dd006403"));

    companion object {
        fun of(nodeId: Uuid): FilesyncRoleFolder? = entries.firstOrNull { it.nodeId == nodeId }
    }
}
