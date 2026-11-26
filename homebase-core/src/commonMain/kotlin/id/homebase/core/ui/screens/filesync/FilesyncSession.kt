package id.homebase.core.ui.screens.filesync

import id.homebase.api.youauth.AppPermissionType
import id.homebase.api.youauth.AppSignIn
import id.homebase.core.config.filesyncAppRegistration

/** This device's sign-in to Filesync, whose drive the shares are on. ReadConnections is what sharing needs. */
object FilesyncSession : AppSignIn(
    keyPrefix = "filesync_session",
    registration = filesyncAppRegistration,
    sharingPermissions = listOf(AppPermissionType.ReadConnections),
    requester = "filesync",
)
