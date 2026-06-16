package id.homebase.core.config

import id.homebase.api.youauth.DrivePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ListsDriveConfigTest {

    @Test
    fun `lists drive reuses the moments app-content drive type with its own unique alias`() {
        // `type` is a shared category (Moments is the app-content drive type the server
        // provisions); `alias` is the unique per-drive id. Lists deliberately reuses the
        // Moments type and MUST keep a distinct alias so it is a separate drive. If someone
        // "fixes" the type back to a random GUID, the server won't recognise the drive.
        assertEquals(momentsLabeledDrive.drive.type, listsLabeledDrive.drive.type)
        assertNotEquals(momentsLabeledDrive.drive.alias, listsLabeledDrive.drive.alias)
        assertNotEquals(listsLabeledDrive.drive.alias, listsLabeledDrive.drive.type)
        assertEquals("Lists", listsLabeledDrive.label)
    }

    @Test
    fun `lists drive alias is unique across all optional drives`() {
        // The alias is the effective driveId — a collision would silently mount the wrong drive.
        val aliases = listOf(
            momentsLabeledDrive, vaultLabeledDrive, stickerLabeledDrive,
            locationLabeledDrive, listsLabeledDrive,
        ).map { it.drive.alias }
        assertEquals(aliases.size, aliases.toSet().size)
    }

    @Test
    fun `lists drive is not a mandatory sync drive`() {
        assertTrue(mandatorySyncDrives.none { it.drive.alias == listsLabeledDrive.drive.alias })
    }

    @Test
    fun `lists permission request grants read and write on the lists drive`() {
        val req = listsTargetDriveAccessRequest.single()
        assertEquals(listsLabeledDrive.drive.alias.toString(), req.alias)
        assertEquals(listsLabeledDrive.drive.type.toString(), req.type)
        assertTrue(DrivePermission.Read in req.permissions)
        assertTrue(DrivePermission.Write in req.permissions)
    }

    @Test
    fun `lists permission extension config exposes the lists drive only`() {
        val config = getListsPermissionExtensionConfig()
        assertEquals(1, config.drives.size)
        assertEquals(listsLabeledDrive.drive.alias.toString(), config.drives.single().alias)
        assertEquals(null, config.circleDrives)
    }
}
