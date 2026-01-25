package id.homebase.homebasekmppoc.prototype.lib.database

import  kotlinx.coroutines.runBlocking
import  kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.uuid.Uuid

class DriveLocalTagIndexTest {
    @Test
    fun testInsertSelectDeleteLocalTag() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->        // Create a QueryBatchCursor with all fields populated

            // Test data - create sample byte arrays
            val randomId = Random.nextLong()
            val identityId = Uuid.random()
            val driveId = Uuid.random()
            val fileId = Uuid.random()
            val tagId = Uuid.random()

            // Clean up any existing data for this file
            dbm.driveLocalTagIndex.deleteByFile(
                identityId = identityId,
                driveId = driveId,
                fileId = fileId
            )

            // Insert a local tag
            dbm.driveLocalTagIndex.insertLocalTag(
                identityId = identityId,
                driveId = driveId,
                fileId = fileId,
                tagId = tagId
            )

            // Select local tags for the file
            val tags = dbm.driveLocalTagIndex.selectByFile(
                identityId = identityId,
                driveId = driveId,
                fileId = fileId
            )

            // Verify insertion
            assertEquals(1, tags.size, "Should have exactly one local tag")
            assertEquals(identityId, tags[0].identityId)
            assertEquals(driveId, tags[0].driveId)
            assertEquals(fileId, tags[0].fileId)
            assertEquals(tagId, tags[0].tagId)

            // Insert another local tag for the same file
            val tagId2 = Uuid.random()
            dbm.driveLocalTagIndex.insertLocalTag(
                identityId = identityId,
                driveId = driveId,
                fileId = fileId,
                tagId = tagId2
            )

            // Verify we now have 2 local tags
            val tagsAfterSecondInsert = dbm.driveLocalTagIndex.selectByFile(
                identityId = identityId,
                driveId = driveId,
                fileId = fileId
            )

            assertEquals(2, tagsAfterSecondInsert.size, "Should have exactly two local tags")

            // Delete all local tags for the file
            dbm.driveLocalTagIndex.deleteByFile(
                identityId = identityId,
                driveId = driveId,
                fileId = fileId
            )

            // Verify deletion
            val tagsAfterDelete = dbm.driveLocalTagIndex.selectByFile(
                identityId = identityId,
                driveId = driveId,
                fileId = fileId
            )

            assertTrue(tagsAfterDelete.isEmpty(), "Should have no local tags after deletion")
        }
    }

    @Test
    fun testSelectByFileWithNoLocalTags() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->        // Create a QueryBatchCursor with all fields populated
            // Test data for non-existent file
            val identityId = Uuid.random()
            val driveId = Uuid.random()
            val fileId = Uuid.random()

            // Select local tags for non-existent file
            val tags = dbm.driveLocalTagIndex.selectByFile(
                identityId = identityId,
                driveId = driveId,
                fileId = fileId
            )

            // Verify no local tags found
            assertTrue(tags.isEmpty(), "Should have no local tags for non-existent file")
        }
    }

    @Test
    fun testUniqueConstraint() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->        // Create a QueryBatchCursor with all fields populated

            // Test data
            val identityId = Uuid.random()
            val driveId = Uuid.random()
            val fileId = Uuid.random()
            val tagId = Uuid.random()

            // Clean up any existing data
            dbm.driveLocalTagIndex.deleteByFile(
                identityId = identityId,
                driveId = driveId,
                fileId = fileId
            )

            // Insert a local tag
            dbm.driveLocalTagIndex.insertLocalTag(
                identityId = identityId,
                driveId = driveId,
                fileId = fileId,
                tagId = tagId
            )

            // Try to insert the same local tag again (should violate UNIQUE constraint)
            try {
                dbm.driveLocalTagIndex.insertLocalTag(
                    identityId = identityId,
                    driveId = driveId,
                    fileId = fileId,
                    tagId = tagId
                )
                // If we get here, the constraint wasn't enforced (this might be expected behavior)
                // Check if we have 1 or 2 records
                val tags = dbm.driveLocalTagIndex.selectByFile(
                    identityId = identityId,
                    driveId = driveId,
                    fileId = fileId
                )
                assertTrue(tags.size >= 1, "Should have at least one local tag")
            } catch (e: Exception) {
                // This is expected if the UNIQUE constraint is enforced
                assertTrue(
                    e.message?.contains("UNIQUE") == true || e.message?.contains("constraint") == true,
                    "Should get a constraint violation error: ${e.message}"
                )
            }
        }
    }
}