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
import kotlinx.coroutines.channels.Channel
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

suspend fun login(identityDomain: String, noBrowser: Boolean = false, callbackPort: Int = 0) {
    val identity = OdinId(identityDomain)
    val inputs = Channel<String>(Channel.UNLIMITED)
    val port = LocalCallbackServer.start(onCallbackUrl = { inputs.trySend(it) }, preferredPort = callbackPort)
    if (callbackPort > 0 && port != callbackPort) {
        LocalCallbackServer.stop()
        error("could not listen on --callback-port $callbackPort (in use?)")
    }

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

    println("Open this on any device to approve the Chat Agent app:\n$url\n")
    terminalQr(url)?.let { println(it) }
    println("If the page fails to load after you approve, copy its address and paste it here:")
    browserOpener(System.getProperty("os.name"), System.getenv(), noBrowser)?.let { opener ->
        runCatching { ProcessBuilder(opener, url).redirectErrorStream(true).start() }
    }
    val reader = Thread { pumpLines(System.`in`.bufferedReader()) { inputs.trySend(it) } }
    reader.isDaemon = true
    reader.start()

    val params = try {
        withTimeout(5 * 60_000L) { awaitCallback(inputs, state, identity.toString()) { System.err.println("error: $it\nPaste the callback address again:") } }
    } finally {
        LocalCallbackServer.stop()
    }
    val result =
        YouAuthProvider(HttpClientProvider.create(), identity)
            .finalizeAuthentication(
                identity = OdinId(params.identity),
                keyPair = keyPair,
                password = password,
                publicKey = params.publicKey,
                salt = params.salt,
            )
    CredentialStorage.saveCredentials(
        result.identity,
        result.clientAuthToken,
        kotlin.io.encoding.Base64.decode(result.sharedSecret),
    )
    println("Logged in as ${result.identity}")
}
