package id.homebase.agent

import id.homebase.api.browser.LocalCallbackServer
import id.homebase.api.browser.RedirectConfig
import id.homebase.api.client.HttpClientProvider
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.EccKeySize
import id.homebase.api.crypto.generateEccKeyPair
import id.homebase.api.crypto.publicKeyToJwkBase64Url
import id.homebase.api.decodeUrl
import id.homebase.api.generateUuidBytes
import id.homebase.api.generateUuidString
import id.homebase.api.youauth.AppAuthorizationParams
import id.homebase.api.youauth.AppPermissionType
import id.homebase.api.youauth.ClientType
import id.homebase.api.youauth.CredentialStorage
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.TargetDriveAccessRequest
import id.homebase.api.youauth.YouAuthProvider
import id.homebase.api.youauth.YouAuthorizationParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

// homebase-common (AppConfig.kt) isn't headless; mirror of its circle ids.
private const val CONFIRMED_CONNECTIONS_CIRCLE_ID = "bb2683fa402aff866e771a6495765a15"
private const val AUTO_CONNECTIONS_CIRCLE_ID = "9e22b42952f74d2580e11250b651d343"

private val chatDrive = SystemDriveConstants.chatDrive

private fun chatDriveRequest(permissions: List<DrivePermission>) =
    TargetDriveAccessRequest(
        alias = chatDrive.alias.toString(),
        type = chatDrive.type.toString(),
        name = "Chat Drive",
        description = "Drive which contains all the chat messages",
        permissions = permissions,
        driveSlug = "chat",
        driveTypeSlug = "chat",
    )

private val agentPermissions =
    listOf(
        AppPermissionType.ReadConnections,
        AppPermissionType.SendDataToOtherIdentitiesOnMyBehalf,
        AppPermissionType.ReceiveDataFromOtherIdentitiesOnMyBehalf,
    )

suspend fun login(identityDomain: String) {
    val identity = OdinId(identityDomain)
    val callback = CompletableDeferred<String>()
    LocalCallbackServer.start(onCallbackUrl = { callback.complete(it) })

    val password = SecureByteArray(generateUuidBytes())
    val keyPair = generateEccKeyPair(password, EccKeySize.P384, 1)
    val state = generateUuidString()
    val redirectUri = RedirectConfig.buildRedirectUri(Profile.APP_ID)
    val friendlyName = "Chat Agent (${System.getProperty("user.name")})"

    val permissionRequest =
        AppAuthorizationParams.create(
            appName = Profile.APP_NAME,
            appId = Profile.APP_ID,
            appSlug = Profile.APP_SLUG,
            friendlyName = friendlyName,
            drives = listOf(chatDriveRequest(listOf(DrivePermission.Read, DrivePermission.Write, DrivePermission.React))),
            circleDrives = listOf(chatDriveRequest(listOf(DrivePermission.Write, DrivePermission.React))),
            circles = listOf(CONFIRMED_CONNECTIONS_CIRCLE_ID, AUTO_CONNECTIONS_CIRCLE_ID),
            permissions = agentPermissions.map { it.value },
            returnUrl = redirectUri,
        )
    val authRequest =
        YouAuthorizationParams(
            clientId = Profile.APP_ID,
            clientType = ClientType.app,
            clientInfo = friendlyName,
            publicKey = publicKeyToJwkBase64Url(keyPair.publicKey),
            permissionRequest = permissionRequest.toJson(),
            state = state,
            redirectUri = redirectUri,
        )
    val url = "https://$identity/api/owner/v1/youauth/authorize?${authRequest.toQueryString()}"

    println("Open this URL in your browser to approve the Chat Agent app:\n$url")
    runCatching { ProcessBuilder("open", url).start() }

    val callbackUrl = try {
        withTimeout(5 * 60_000L) { callback.await() }
    } finally {
        LocalCallbackServer.stop()
    }

    val params = callbackUrl.substringAfter("?", "").split("&").associate {
        val parts = it.split("=", limit = 2)
        parts[0] to decodeUrl(parts.getOrElse(1) { "" })
    }
    check(params["state"] == state) { "callback state mismatch" }
    val result =
        YouAuthProvider(HttpClientProvider.create(), identity)
            .finalizeAuthentication(
                identity = OdinId(params["identity"].orEmpty()),
                keyPair = keyPair,
                password = password,
                publicKey = params["public_key"].orEmpty(),
                salt = params["salt"].orEmpty(),
            )
    CredentialStorage.saveCredentials(
        result.identity,
        result.clientAuthToken,
        kotlin.io.encoding.Base64.decode(result.sharedSecret),
    )
    println("Logged in as ${result.identity}")
}
