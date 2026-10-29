@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.privatedrive

import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.api.youauth.YouAuthState
import id.homebase.api.youauth.ZkKeyStore
import id.homebase.core.config.AppConfig
import id.homebase.core.config.appPermissions
import id.homebase.core.config.circleDriveTargetRequest
import id.homebase.core.config.targetDriveAccessRequest
import id.homebase.core.settings.UserPreferences
import io.ktor.http.formUrlEncode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** [needsSignIn]: the drive is private and this device signed in before it could get the key. [offerMakePrivate]: developer menu only, while it isn't private. */
data class PrivateDriveState(val needsSignIn: Boolean = false, val offerMakePrivate: Boolean = false)

/** A mini-app's way to make its drive zero knowledge in the owner console, and to sign in again for the key. */
class PrivateDriveOptIn(
    private val youAuthFlowManager: YouAuthFlowManager,
    private val userPreferences: UserPreferences,
) {
    fun state(drive: Uuid): Flow<PrivateDriveState> = combine(
        ZkKeyStore.lockedDrives,
        ZkKeyStore.zeroKnowledgeDrives,
        userPreferences.preferenceState.map { it.showDeveloperMenu }.distinctUntilChanged(),
    ) { locked, zeroKnowledge, developer -> privateDriveState(drive, locked, zeroKnowledge, developer) }.distinctUntilChanged()

    fun currentState(drive: Uuid): PrivateDriveState = privateDriveState(
        drive,
        ZkKeyStore.lockedDrives.value,
        ZkKeyStore.zeroKnowledgeDrives.value,
        userPreferences.preferenceState.value.showDeveloperMenu,
    )

    private val signedInIdentity get() = (youAuthFlowManager.authState.value as? YouAuthState.Authenticated)?.identity

    /** A new token, asked with the same permissions as at login, brings the key; the current one keeps working. Null when signed out. */
    suspend fun signInAgainUrl(): String? {
        val identity = signedInIdentity ?: return null
        return youAuthFlowManager.authorize(
            identity = identity,
            appId = AppConfig.APP_ID,
            appName = AppConfig.APP_NAME,
            appSlug = AppConfig.APP_SLUG,
            drives = targetDriveAccessRequest,
            permissions = appPermissions,
            circleDrives = circleDriveTargetRequest,
            again = true,
        )
    }

    /** The owner console's password page for [redeal], which converts the drive and returns to [returnUrl]. Null when signed out. */
    fun makePrivateUrl(redeal: List<Pair<String, String>>, returnUrl: String): String? =
        signedInIdentity?.let { makePrivateUrl(it.domainName, redeal, returnUrl) }

    suspend fun handleSignInCallback(url: String) = youAuthFlowManager.handleCallback(url)
}

internal fun privateDriveState(drive: Uuid, locked: Set<Uuid>, zeroKnowledge: Set<Uuid>, developer: Boolean) =
    PrivateDriveState(needsSignIn = drive in locked, offerMakePrivate = developer && drive !in zeroKnowledge)

internal fun makePrivateUrl(domain: String, redeal: List<Pair<String, String>>, returnUrl: String): String =
    "https://$domain/owner/login?" + (redeal + ("returnUrl" to returnUrl)).formUrlEncode()
