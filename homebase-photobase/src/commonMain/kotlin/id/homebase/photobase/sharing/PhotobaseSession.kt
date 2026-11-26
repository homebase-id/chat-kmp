package id.homebase.photobase.sharing

import id.homebase.api.youauth.AppPermissionType
import id.homebase.api.youauth.AppSignIn
import id.homebase.core.config.photobaseAppRegistration

/** This device's sign-in to Photobase, which owns the Photos drive its albums are shared from. */
object PhotobaseSession : AppSignIn(
    keyPrefix = "photobase_session",
    registration = photobaseAppRegistration,
    sharingPermissions = listOf(AppPermissionType.ReadConnections),
    requester = "photobase-sharing",
)
