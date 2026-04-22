package id.homebase.core.sync

import id.homebase.core.config.chatLabeledDrive
import id.homebase.core.config.feedLabeledDrive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class DriveRegistryTest {

    @Test
    fun loadDrives_returnsSeedWhenNoEntryExists() {
        val db = createTestDatabaseManager()
        val registry = DriveRegistry(db)

        val drives = registry.loadDrives()

        assertEquals(listOf(feedLabeledDrive), drives)
        db.close()
    }

    @Test
    fun addDrive_persistsDriveVisibleOnNextLoad() = runTest {
        val db = createTestDatabaseManager()
        val registry = DriveRegistry(db)

        registry.addDrive(chatLabeledDrive)

        val drives = registry.loadDrives()
        assertTrue(drives.any { it.drive.alias == chatLabeledDrive.drive.alias })
        db.close()
    }

    @Test
    fun addDrive_isIdempotent() = runTest {
        val db = createTestDatabaseManager()
        val registry = DriveRegistry(db)

        registry.addDrive(chatLabeledDrive)
        registry.addDrive(chatLabeledDrive)

        val drives = registry.loadDrives()
        assertEquals(1, drives.count { it.drive.alias == chatLabeledDrive.drive.alias })
        db.close()
    }

    @Test
    fun removeDrive_removesOnlyTheTargetDrive() = runTest {
        val db = createTestDatabaseManager()
        val registry = DriveRegistry(db)

        // First addDrive on a fresh DB seeds [feed] internally and persists [feed, chat]
        registry.addDrive(chatLabeledDrive)
        registry.removeDrive(chatLabeledDrive.drive.alias)

        val drives = registry.loadDrives()
        assertFalse(drives.any { it.drive.alias == chatLabeledDrive.drive.alias })
        db.close()
    }

    @Test
    fun removeDrive_persistsEmptyList_doesNotReseedOnNextLoad() = runTest {
        val db = createTestDatabaseManager()
        val registry = DriveRegistry(db)

        // addDrive on fresh DB seeds [feed] internally, then persists [feed, chat]
        registry.addDrive(chatLabeledDrive)
        // Remove both drives so the key holds "[]" in the DB
        registry.removeDrive(feedLabeledDrive.drive.alias)
        registry.removeDrive(chatLabeledDrive.drive.alias)

        // Key exists with "[]" — seed path (entry == null) must NOT trigger
        val drives = registry.loadDrives()
        assertTrue(drives.isEmpty())
        db.close()
    }

    @Test
    fun hasDrive_returnsTrueForRegisteredDrive() = runTest {
        val db = createTestDatabaseManager()
        val registry = DriveRegistry(db)

        registry.addDrive(chatLabeledDrive)

        assertTrue(registry.hasDrive(chatLabeledDrive.drive.alias))
        db.close()
    }

    @Test
    fun hasDrive_returnsFalseForUnknownDrive() = runTest {
        val db = createTestDatabaseManager()
        val registry = DriveRegistry(db)

        assertFalse(registry.hasDrive(Uuid.random()))
        db.close()
    }
}
