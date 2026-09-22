package id.homebase.core.ui.screens.card

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.youauth.CallerContext
import id.homebase.api.youauth.DriveGrant
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.DriveReference
import id.homebase.api.youauth.PermissionCheckResult
import id.homebase.api.youauth.PermissionContext
import id.homebase.api.youauth.PermissionExtensionManager
import id.homebase.api.youauth.PermissionGroup
import id.homebase.api.youauth.PermissionSet
import id.homebase.api.youauth.PermissionedDrive
import id.homebase.api.youauth.SecurityContext
import id.homebase.api.youauth.SecurityContextProvider
import id.homebase.core.config.AppConfig
import id.homebase.core.config.getEmailPermissionExtensionConfig
import id.homebase.core.config.getFeedPermissionExtensionConfig
import id.homebase.core.config.getLocationPermissionExtensionConfig
import id.homebase.core.config.getMomentsPermissionExtensionConfig
import id.homebase.core.config.getPermissionExtensionConfig
import id.homebase.core.config.getStickerPermissionExtensionConfig
import id.homebase.core.config.getVaultPermissionExtensionConfig
import id.homebase.core.config.getWebDropPermissionExtensionConfig
import id.homebase.core.feed.services.FeedProtocol
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class CardChannelAccessTest {

    private val channelType = FeedProtocol.ChannelDriveType.toHexString()
    private val keyed = Uuid.random()
    private val anonymousOnly = Uuid.random()
    private val writeOnly = Uuid.random()
    private val ungranted = Uuid.random()

    private val manager = PermissionExtensionManager(
        SecurityContextProvider(HttpClient(MockEngine { error("the check must use the context it is given") }), CredentialsManager()),
        "frodo.dotyou.cloud",
    )

    private fun grant(alias: String, type: String, permissions: List<DrivePermission>, hasStorageKey: Boolean) =
        DriveGrant(PermissionedDrive(DriveReference(alias, type), permissions), hasStorageKey)

    private fun context(vararg groups: List<DriveGrant>, keys: List<Int> = emptyList()) = SecurityContext(
        caller = CallerContext(odinId = "frodo.dotyou.cloud", securityLevel = "owner", isGrantedConnectedIdentitiesSystemCircle = true),
        permissionContext = PermissionContext(
            groups.map { PermissionGroup(driveGrants = it, permissionSet = PermissionSet(keys)) },
        ),
    )

    private val channelContext = context(
        listOf(
            grant(keyed.toHexString(), channelType, listOf(DrivePermission.Read, DrivePermission.Write), hasStorageKey = true),
            grant(writeOnly.toHexString(), channelType, listOf(DrivePermission.Write, DrivePermission.React), hasStorageKey = false),
        ),
        // The server's anonymous-drives group: Read without the storage key.
        listOf(
            grant(keyed.toHexString(), channelType, listOf(DrivePermission.Read), hasStorageKey = false),
            grant(anonymousOnly.toString(), channelType, listOf(DrivePermission.Read), hasStorageKey = false),
        ),
    )

    @Test
    fun onlyChannelsWithoutAKeyedReadAreRequested() {
        val config = channelAccessConfig(listOf(keyed, anonymousOnly, writeOnly, ungranted), channelContext)
        val result = manager.getMissingPermissions(config, channelContext)

        assertTrue(result is PermissionCheckResult.Missing, "expected Missing, got $result")
        val missing = result.details.missingDrives
        assertEquals(listOf(anonymousOnly, writeOnly, ungranted).map { it.toHexString() }, missing.map { it.alias })
        assertTrue(missing.all { it.type == channelType && it.requireStorageKey && it.name.isEmpty() })
        assertEquals(emptyList(), result.details.missingPermissions)
    }

    @Test
    fun aRequestKeepsWhatTheAppHasOnTheChannelAndAddsRead() {
        val requests = channelAccessConfig(listOf(anonymousOnly, writeOnly, ungranted), channelContext).drives
            .associate { it.alias to it.permissions.toSet() }

        assertEquals(setOf(DrivePermission.Read), requests[anonymousOnly.toHexString()])
        assertEquals(setOf(DrivePermission.Read, DrivePermission.Write, DrivePermission.React), requests[writeOnly.toHexString()])
        assertEquals(setOf(DrivePermission.Read), requests[ungranted.toHexString()])
    }

    @Test
    fun theRequestNamesEachChannelByAliasAndTypeForThisApp() {
        // The JVM returnUrl() starts a local callback server; the test only needs the drive list.
        val config = channelAccessConfig(listOf(ungranted), channelContext).copy(returnUrl = { "homebase-fchat://permission-callback" })
        val result = manager.getMissingPermissions(config, channelContext)
        assertTrue(result is PermissionCheckResult.Missing)

        val url = result.details.buildExtendPermissionUrl()
        assertTrue(url.startsWith("https://frodo.dotyou.cloud/owner/appupdate?appId=${AppConfig.APP_ID}&d="), url)
        val drives = url.substringAfter("&d=").substringBefore('&')
        assertTrue(ungranted.toHexString() in drives && channelType in drives, drives)
    }

    @Test
    fun keyedReadOnEveryChannelNeedsNothing() {
        assertEquals(
            PermissionCheckResult.AllGranted,
            manager.getMissingPermissions(channelAccessConfig(listOf(keyed), channelContext), channelContext),
        )
    }

    @Test
    fun existingAddOnChecksStillAcceptKeylessGrants() {
        val configs = listOf(
            getPermissionExtensionConfig(),
            getFeedPermissionExtensionConfig(),
            getMomentsPermissionExtensionConfig(),
            getVaultPermissionExtensionConfig(),
            getEmailPermissionExtensionConfig(),
            getWebDropPermissionExtensionConfig(),
            getLocationPermissionExtensionConfig(),
            getStickerPermissionExtensionConfig(),
        )
        configs.forEach { config ->
            assertTrue(config.drives.none { it.requireStorageKey }, config.appName)
            val keyless = context(
                config.drives.map { grant(it.alias, it.type, it.permissions, hasStorageKey = false) },
                keys = config.permissions.map { it.value },
            )
            assertEquals(PermissionCheckResult.AllGranted, manager.getMissingPermissions(config, keyless), config.drives.toString())
        }
    }
}
