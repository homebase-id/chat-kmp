package id.homebase.core.config

import id.homebase.api.youauth.DrivePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ListsDriveConfigTest {

    @Test
    fun `lists drive has valid distinct guids`() {
        assertNotEquals(listsLabeledDrive.drive.alias, listsLabeledDrive.drive.type)
        assertNotEquals(listsLabeledDrive.drive.alias, locationLabeledDrive.drive.alias)
        assertNotEquals(listsLabeledDrive.drive.type, locationLabeledDrive.drive.type)
        assertEquals("Lists", listsLabeledDrive.label)
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
