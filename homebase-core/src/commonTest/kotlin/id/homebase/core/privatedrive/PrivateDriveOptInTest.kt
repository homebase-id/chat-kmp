@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.privatedrive

import id.homebase.core.filesync.FilesyncProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class PrivateDriveOptInTest {

    private val back = "http://localhost:52870/permission-callback?for=filesync"

    @Test
    fun filesyncAsksTheConsoleToConvertItsDriveById() {
        assertEquals(
            "https://frodo.dotyou.cloud/owner/login?redeal=drive-private&drive=cfceb937-b868-496b-91cb-3402f18320f8" +
                "&returnUrl=http%3A%2F%2Flocalhost%3A52870%2Fpermission-callback%3Ffor%3Dfilesync",
            makePrivateUrl("frodo.dotyou.cloud", FilesyncPrivateDrive, back),
        )
        assertEquals(Uuid.parse("cfceb937-b868-496b-91cb-3402f18320f8"), FilesyncProtocol.DriveAlias)
    }

    @Test
    fun vaultKeepsItsOwnReason() {
        assertEquals(
            "https://frodo.dotyou.cloud/owner/login?redeal=vault-private&returnUrl=http%3A%2F%2Flocalhost%2Fpermission-callback%3Ffor%3Dvault",
            makePrivateUrl("frodo.dotyou.cloud", VaultPrivateDrive, "http://localhost/permission-callback?for=vault"),
        )
    }

    @Test
    fun makingPrivateIsOfferedOnlyWithTheDeveloperMenuAndOnlyUntilTheDriveIsPrivate() {
        val drive = Uuid.random()
        assertEquals(PrivateDriveState(offerMakePrivate = true), privateDriveState(drive, emptySet(), emptySet(), developer = true))
        assertEquals(PrivateDriveState(), privateDriveState(drive, emptySet(), emptySet(), developer = false))
        assertEquals(PrivateDriveState(), privateDriveState(drive, emptySet(), setOf(drive), developer = true))
        assertEquals(PrivateDriveState(offerMakePrivate = true), privateDriveState(drive, emptySet(), setOf(Uuid.random()), developer = true))
    }

    @Test
    fun aPrivateDriveWithoutItsKeyAsksForASignIn() {
        val drive = Uuid.random()
        assertEquals(PrivateDriveState(needsSignIn = true), privateDriveState(drive, setOf(drive), setOf(drive), developer = false))
        assertEquals(PrivateDriveState(), privateDriveState(drive, setOf(Uuid.random()), setOf(drive), developer = false))
    }
}
