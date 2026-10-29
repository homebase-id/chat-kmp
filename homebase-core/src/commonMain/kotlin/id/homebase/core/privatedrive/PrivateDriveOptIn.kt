@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.privatedrive

import co.touchlab.kermit.Logger
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.api.youauth.YouAuthState
import id.homebase.api.youauth.ZkKeyStore
import id.homebase.core.config.AppConfig
import id.homebase.core.config.appPermissions
import id.homebase.core.config.circleDriveTargetRequest
import id.homebase.core.config.emailLabeledDrive
import id.homebase.core.config.targetDriveAccessRequest
import id.homebase.core.config.vaultLabeledDrive
import id.homebase.core.filesync.FilesyncProtocol
import id.homebase.core.settings.UserPreferences
import io.ktor.http.formUrlEncode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "PrivateDriveOptIn"

/** A drive a mini-app can make zero knowledge, and the owner console's reason for converting it. */
data class PrivateDrive(val alias: Uuid, val redeal: List<Pair<String, String>>)

val VaultPrivateDrive = PrivateDrive(vaultLabeledDrive.drive.alias, listOf("redeal" to "vault-private"))

val FilesyncPrivateDrive = PrivateDrive(
    FilesyncProtocol.DriveAlias,
    listOf("redeal" to "drive-private", "drive" to FilesyncProtocol.DriveAlias.toString(), "model" to "selectiveShare"),
)

val EmailPrivateDrive = PrivateDrive(
    emailLabeledDrive.drive.alias,
    listOf("redeal" to "drive-private", "drive" to emailLabeledDrive.drive.alias.toString(), "model" to "private"),
)

/** [needsSignIn]: the drive is private and this device signed in before it could get the key. [offerMakePrivate]: developer menu only, while it isn't private. */
data class PrivateDriveState(val needsSignIn: Boolean = false, val offerMakePrivate: Boolean = false)

/** A mini-app's way to make its drive zero knowledge in the owner console, and to sign in again for the key. */
class PrivateDriveOptIn(
    private val youAuthFlowManager: YouAuthFlowManager,
    private val userPreferences: UserPreferences,
) {
    fun state(drive: PrivateDrive): Flow<PrivateDriveState> = combine(
        ZkKeyStore.lockedDrives,
        ZkKeyStore.zeroKnowledgeDrives,
        userPreferences.preferenceState.map { it.showDeveloperMenu }.distinctUntilChanged(),
    ) { locked, zeroKnowledge, developer -> privateDriveState(drive.alias, locked, zeroKnowledge, developer) }.distinctUntilChanged()

    fun currentState(drive: PrivateDrive): PrivateDriveState = privateDriveState(
        drive.alias,
        ZkKeyStore.lockedDrives.value,
        ZkKeyStore.zeroKnowledgeDrives.value,
        userPreferences.preferenceState.value.showDeveloperMenu,
    )

    private val signedInIdentity get() = (youAuthFlowManager.authState.value as? YouAuthState.Authenticated)?.identity

    /** A new token, asked with the same permissions as at login, brings the key; the current one keeps working. Nothing happens when signed out. */
    suspend fun signInAgain(open: suspend (url: String) -> Unit, failed: suspend () -> Unit) {
        val identity = signedInIdentity ?: return
        val url = try {
            youAuthFlowManager.authorize(
                identity = identity,
                appId = AppConfig.APP_ID,
                appName = AppConfig.APP_NAME,
                appSlug = AppConfig.APP_SLUG,
                drives = targetDriveAccessRequest,
                permissions = appPermissions,
                circleDrives = circleDriveTargetRequest,
                again = true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Couldn't start signing in again" }
            failed()
            return
        }
        open(url)
    }

    /** The owner console's password page, which converts [drive] and returns to [returnUrl]. Null when signed out. */
    fun makePrivateUrl(drive: PrivateDrive, returnUrl: String): String? =
        signedInIdentity?.let { makePrivateUrl(it.domainName, drive, returnUrl) }

    suspend fun handleSignInCallback(url: String) = youAuthFlowManager.handleCallback(url)
}

internal fun privateDriveState(drive: Uuid, locked: Set<Uuid>, zeroKnowledge: Set<Uuid>, developer: Boolean) =
    PrivateDriveState(needsSignIn = drive in locked, offerMakePrivate = developer && drive !in zeroKnowledge)

internal fun makePrivateUrl(domain: String, drive: PrivateDrive, returnUrl: String): String =
    "https://$domain/owner/login?" + (drive.redeal + ("returnUrl" to returnUrl)).formUrlEncode()
