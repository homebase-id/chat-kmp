package id.homebase.homebasekmppoc.prototype.lib.database

import io.ktor.utils.io.core.toByteArray
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.uuid.Uuid

class OutboxTest {
    @BeforeTest
    fun setup() {
    }

    @AfterTest
    fun tearDown() {
    }

    @Test
    fun testInsertSelectDeleteOutboxItem() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->        // Create a QueryBatchCursor with all fields populated
            // Test data
            val data = "test data".toByteArray()
            val files = "test files".toByteArray()

            // Insert into outbox
            dbm.outbox.insert(
                driveId = Uuid.random(),
                fileId = Uuid.random(),
                dependencyFileId = Uuid.random(),
                priority = 0L,
                uploadType = 0L,
                json = data,
                files = files
            )

            // Get count to verify insertion
            val countAfterInsert = dbm.outbox.count()
            assertEquals(1L, countAfterInsert, "Should have exactly one item in outbox")

            // Checkout the item
            val checkoutResult = dbm.outbox.checkout()
            assertNotNull(checkoutResult, "Should successfully checkout the item")

            // Verify the retrieved item
            assertNotNull(checkoutResult, "Should retrieve the inserted item")
            assertEquals(0, checkoutResult.lastAttempt, "Last attempt should be zero")
            assertEquals(0, checkoutResult.checkOutCount, "Check out count should match")


            // Select the checked out item
            val nextItem = dbm.outbox.selectCheckedOut(checkoutResult.checkOutStamp!!)
            assertNotNull(nextItem, "Should retrieve the checked out item")

            // Verify the retrieved item
            assertNotNull(nextItem, "Should retrieve the inserted item")
            assertEquals(0, nextItem.lastAttempt, "Last attempt should be zero")
            assertEquals(0, nextItem.checkOutCount, "Check out count should match")
            assertEquals(
                data.contentToString(),
                nextItem.json.contentToString(),
                "Data should match"
            )
            assertEquals(
                files.contentToString(),
                nextItem.files.contentToString(),
                "Files should match"
            )

            // Delete the item
            dbm.outbox.deleteByRowId(nextItem.rowId)

            // Verify deletion
            val countAfterDelete = dbm.outbox.count()
            assertEquals(0L, countAfterDelete, "Should have no items after deletion")

            // Try to checkout from empty outbox
            val checkoutResult2 = dbm.outbox.checkout()
            assertNull(checkoutResult2, "Should return null when checking out from empty outbox")
        }
    }

    @Test
    fun testCheckoutWithEmptyOutbox() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->        // Create a QueryBatchCursor with all fields populated
            // Try to checkout from empty outbox
            val checkoutResult = dbm.outbox.checkout()

            // Verify no rows updated
            assertNull(checkoutResult, "Should return null when checking out from empty outbox")

            // Verify count is zero
            val count = dbm.outbox.count()
            assertEquals(0L, count, "Count should be zero for empty outbox")
        }
    }

    @Test
    fun testMultipleItemsSequentialOrdering() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->        // Create a QueryBatchCursor with all fields populated
            // Test data
            val data = "test data".toByteArray()

            // Insert item
            dbm.outbox.insert(
                driveId = Uuid.random(),
                fileId = Uuid.random(),
                dependencyFileId = Uuid.random(),
                priority = 0L,
                uploadType = 0L,
                json = data,
                files = null
            )

            // Verify count
            val count = dbm.outbox.count()
            assertEquals(1L, count, "Should have one item in outbox")

            // Checkout and select item
            val res = dbm.outbox.checkout()
            val item = dbm.outbox.selectCheckedOut(res!!.checkOutStamp!!)
            assertEquals(
                data.contentToString(), item!!.json.contentToString(),
                "Item should be the inserted one"
            )

            // Delete item and verify outbox is empty
            dbm.outbox.deleteByRowId(item.rowId)
            val checkoutResult = dbm.outbox.checkout()
            assertNull(checkoutResult, "Should return null when checking out from empty outbox")
        }
    }

    @Test
    fun testOutboxItemWithNullFiles() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->
            // Test data with null files
            val data = "test data".toByteArray()

            // Insert item with null files
            dbm.outbox.insert(
                driveId = Uuid.random(),
                fileId = Uuid.random(),
                dependencyFileId = Uuid.random(),
                priority = 0L,
                uploadType = 0L,
                json = data,
                files = null
            )

            // Checkout and select
            val checkoutResult = dbm.outbox.checkout()
            assertNotNull(checkoutResult, "Should successfully checkout item")

            val item = dbm.outbox.selectCheckedOut(checkoutResult.checkOutStamp!!)
            assertNotNull(item, "Should retrieve the inserted item")
            assertEquals(0, item.lastAttempt, "Last attempt should match")
            assertEquals(0, item.checkOutCount, "Check out count should match")
            assertEquals(
                data.contentToString(),
                item.json.contentToString(),
                "Data should match"
            )
            assertNull(item.files, "Files should be null")

            // Clean up
            dbm.outbox.deleteByRowId(item.rowId)
        }

        @Test
        fun testUpdateCheckOutCount() = runTest {
            DatabaseManager { createInMemoryDatabase() }.use { dbm ->
                // Insert initial item
                val data = "test data".toByteArray()

                val insertSuccess = dbm.outbox.insert(
                    driveId = Uuid.random(),
                    fileId = Uuid.random(),
                    dependencyFileId = Uuid.random(),
                    priority = 0L,
                    uploadType = 0L,
                    json = data,
                    files = null
                )
                assertTrue(insertSuccess > 0, "Insert should succeed")

                // Checkout and select the item
                val checkoutResult = dbm.outbox.checkout()
                assertNotNull(checkoutResult, "Should successfully checkout item")

                val item = dbm.outbox.selectCheckedOut(checkoutResult.checkOutStamp!!)
                assertNotNull(item, "Should retrieve the checked out item")
                assertEquals(
                    0,
                    item.checkOutCount,
                    "Initial check out count should be 1"
                )

                // Simulate an update by inserting a new record with updated check out count
                // (Note: Outbox.sq doesn't have an update operation, so we'd typically delete and reinsert)

                dbm.outbox.deleteByRowId(item.rowId)
                val insertSuccess2 = dbm.outbox.insert(
                    driveId = Uuid.random(),
                    fileId = Uuid.random(),
                    dependencyFileId = Uuid.random(),
                    priority = 0L,
                    uploadType = 0L,
                    json = data,
                    files = null
                )
                assertTrue(insertSuccess2 > 0, "Second insert should succeed")

                // Checkout and verify the update
                val checkoutResult2 = dbm.outbox.checkout()
                assertNotNull(checkoutResult2, "Should successfully checkout updated item")

                val updatedItem = dbm.outbox.selectCheckedOut(checkoutResult2.checkOutStamp!!)
                assertNotNull(updatedItem, "Should retrieve the updated item")
                assertEquals(
                    1,
                    updatedItem.checkOutCount,
                    "Check out count should be updated"
                )
                assertEquals(
                    0,
                    updatedItem.lastAttempt,
                    "Last attempt should be updated"
                )
                assertEquals(
                    data.contentToString(),
                    updatedItem.json.contentToString(),
                    "Data should remain the same"
                )
            }
        }

        @Test
        fun testPopTest() = runTest {
            DatabaseManager { createInMemoryDatabase() }.use { dbm ->
                val driveId = Uuid.random()
                val fileIds = List(5) { Uuid.random() }
                val values = List(5) { Uuid.random().toByteArray() }
                val priorities = 0L..4L

                // Insert items with priorities 0 to 4
                priorities.forEachIndexed { index, priority ->
                    val insertSuccess = dbm.outbox.insert(
                        driveId = driveId,
                        fileId = fileIds[index],
                        dependencyFileId = null,
                        priority = priority,
                        uploadType = 0L,
                        json = values[index],
                        files = null
                    )
                    assertTrue(insertSuccess > 0, "Insert should succeed")
                }

                // Verify total count
                assertEquals(5L, dbm.outbox.count())

                // Checkout items in priority order (0,1,2,3,4)
                priorities.forEachIndexed { index, expectedPriority ->
                    val checkoutResult = dbm.outbox.checkout()
                    assertNotNull(checkoutResult, "Should successfully checkout item")

                    val item = dbm.outbox.selectCheckedOut(checkoutResult.checkOutStamp!!)
                    assertNotNull(item, "Should retrieve checked out item")
                    assertEquals(expectedPriority, item.priority, "Priority should match")
                    assertEquals(
                        values[index].contentToString(),
                        item.json.contentToString(),
                        "Data should match"
                    )

                    // "Complete" by deleting
                    dbm.outbox.deleteByRowId(item.rowId)
                }

                // Verify all items processed
                assertEquals(0L, dbm.outbox.count())
            }
        }

        @Test
        fun testPriorityTest() = runTest {
            DatabaseManager { createInMemoryDatabase() }.use { dbm ->
                val driveId = Uuid.random()
                val fileIds = List(5) { Uuid.random() }
                val values = List(5) { Uuid.random().toByteArray() }
                val priorities = listOf(4L, 1L, 2L, 3L, 0L) // Insert order
                val expectedOrder = listOf(0L, 1L, 2L, 3L, 4L) // Checkout order

                // Insert items with mixed priorities
                priorities.forEachIndexed { index, priority ->
                    val insertSuccess = dbm.outbox.insert(
                        driveId = driveId,
                        fileId = fileIds[index],
                        dependencyFileId = null,
                        priority = priority,
                        uploadType = 0L,
                        json = values[index],
                        files = null
                    )
                    assertTrue(insertSuccess > 0, "Insert should succeed")
                }

                // Checkout items in priority order (lowest first)
                expectedOrder.forEach { expectedPriority ->
                    val checkoutResult = dbm.outbox.checkout()
                    assertNotNull(checkoutResult, "Should successfully checkout item")

                    val item = dbm.outbox.selectCheckedOut(checkoutResult.checkOutStamp!!)
                    assertNotNull(item, "Should retrieve checked out item")
                    assertEquals(
                        expectedPriority,
                        item.priority,
                        "Should checkout priority $expectedPriority"
                    )

                    dbm.outbox.deleteByRowId(item.rowId)
                }
            }
        }

        @Test
        fun testNextRunTest() = runTest {
            DatabaseManager { createInMemoryDatabase() }.use { dbm ->
                val driveId = Uuid.random()
                val fileIds = List(5) { Uuid.random() }
                val values = List(5) { Uuid.random().toByteArray() }
                val recipients = listOf("1", "2", "3", "4", "5") // Expected order

                // Insert items with same priority, different nextRunTime
                val baseTime = 1704067200000L
                val nextRunTimes = listOf(
                    baseTime,
                    baseTime + 1,
                    baseTime + 2,
                    baseTime + 3,
                    baseTime + 4
                ) // earliest first

                val combined = recipients.zip(fileIds).zip(values).zip(nextRunTimes)
                combined.forEach { (pair1, nextRunTime) ->
                    val (pair2, value) = pair1
                    val (recipient, fileId) = pair2
                    val insertSuccess = dbm.outbox.insert(
                        driveId = driveId,
                        fileId = fileId,
                        dependencyFileId = null,
                        priority = 0L,
                        uploadType = 0L,
                        json = "$recipient-data".toByteArray(), // Use recipient in data for identification
                        files = null
                    )
                    assertTrue(insertSuccess > 0, "Insert should succeed")
                }

                // Checkout items in nextRunTime order (earliest first)
                recipients.forEach { expectedRecipient ->
                    val checkoutResult = dbm.outbox.checkout()
                    assertNotNull(checkoutResult, "Should successfully checkout item")

                    val item = dbm.outbox.selectCheckedOut(checkoutResult.checkOutStamp!!)
                    assertNotNull(item, "Should retrieve checked out item")
                    assertEquals(
                        "$expectedRecipient-data".toByteArray().contentToString(),
                        item.json.contentToString(),
                        "Should checkout $expectedRecipient"
                    )

                    dbm.outbox.deleteByRowId(item.rowId)
                }
            }
        }

        @Test
        fun testDependencyTest() = runTest {
            DatabaseManager { createInMemoryDatabase() }.use { dbm ->
                val driveId = Uuid.random()
                val fileIds = List(5) { Uuid.random() }
                val values = List(5) { Uuid.random().toByteArray() }
                val f1 = fileIds[0];
                val f2 = fileIds[1];
                val f3 = fileIds[2];
                val f4 = fileIds[3];
                val f5 = fileIds[4]

                // Insert with dependencies: f2->f3, f3->null, f4->f2, f5->f4, f1->f5
                assertTrue(
                    dbm.outbox.insert(
                        driveId,
                        f2,
                        f3,
                        0L,
                        0L,
                        values[1],
                        null
                    ) > 0
                )
                assertTrue(
                    dbm.outbox.insert(
                        driveId,
                        f3,
                        null,
                        0L,
                        0L,
                        values[2],
                        null
                    ) > 0
                )
                assertTrue(
                    dbm.outbox.insert(
                        driveId,
                        f4,
                        f2,
                        0L,
                        0L,
                        values[3],
                        null
                    ) > 0
                )
                assertTrue(
                    dbm.outbox.insert(
                        driveId,
                        f5,
                        f4,
                        0L,
                        0L,
                        values[4],
                        null
                    ) > 0
                )
                assertTrue(
                    dbm.outbox.insert(
                        driveId,
                        f1,
                        f5,
                        0L,
                        0L,
                        values[0],
                        null
                    ) > 0
                )

                // Should checkout f3 first (no dependency)
                val checkoutResult1 = dbm.outbox.checkout()
                assertNotNull(checkoutResult1, "Should successfully checkout f3")
                var item = dbm.outbox.selectCheckedOut(checkoutResult1.checkOutStamp!!)
                assertTrue(item!!.fileId == f3, "Expected item to be f3")
                dbm.outbox.deleteByRowId(item.rowId)

                // Now f2 (depends on f3, depends on f3 which is done)
                val checkoutResult2 = dbm.outbox.checkout()
                assertNotNull(checkoutResult2, "Should successfully checkout f2")
                item = dbm.outbox.selectCheckedOut(checkoutResult2.checkOutStamp!!)
                assertTrue(item!!.fileId == f2, "Expected item to be f2")
                dbm.outbox.deleteByRowId(item.rowId)

                // Now f4 (depends on f2, depends on f2 which is done)
                val checkoutResult3 = dbm.outbox.checkout()
                assertNotNull(checkoutResult3, "Should successfully checkout f4")
                item = dbm.outbox.selectCheckedOut(checkoutResult3.checkOutStamp!!)
                assertTrue(item!!.fileId == f4, "Expected item to be f4")
                dbm.outbox.deleteByRowId(item.rowId)

                // Now f5 (depends on f4, depends on f4 which is done)
                val checkoutResult4 = dbm.outbox.checkout()
                assertNotNull(checkoutResult4, "Should successfully checkout f5")
                item = dbm.outbox.selectCheckedOut(checkoutResult4.checkOutStamp!!)
                assertTrue(item!!.fileId == f5, "Expected item to be f5")
                dbm.outbox.deleteByRowId(item.rowId)

                // Finally f1 (depends on f5, depends on f5 which is done)
                val checkoutResult5 = dbm.outbox.checkout()
                assertNotNull(checkoutResult5, "Should successfully checkout f1")
                item = dbm.outbox.selectCheckedOut(checkoutResult5.checkOutStamp!!)
                assertTrue(item!!.fileId == f1, "Expected item to be f1")
                dbm.outbox.deleteByRowId(item.rowId)
            }
        }

        @Test
        fun testDependencyTestGetNextRun() = runTest {
            DatabaseManager { createInMemoryDatabase() }.use { dbm ->
                val driveId = Uuid.random()
                val fileIds = List(5) { Uuid.random() }
                val values = List(5) { Uuid.random().toByteArray() }
                val f1 = fileIds[0];
                val f2 = fileIds[1];
                val f3 = fileIds[2];
                val f4 = fileIds[3];
                val f5 = fileIds[4]

                val t1 = 1704067200000L
                val t2 = t1 + 10
                val t3 = t1 + 20
                val t4 = t1 + 30
                val t5 = t1 + 40

                // Insert with dependencies and nextRunTimes
                assertTrue(
                    dbm.outbox.insert(
                        driveId,
                        f2,
                        f3,
                        0L,
                        0L,
                        values[1],
                        null
                    ) > 0
                )
                assertTrue(
                    dbm.outbox.insert(
                        driveId,
                        f3,
                        null,
                        0L,
                        0L,
                        values[2],
                        null
                    ) > 0
                )
                assertTrue(
                    dbm.outbox.insert(
                        driveId,
                        f4,
                        f2,
                        0L,
                        0L,
                        values[3],
                        null
                    ) > 0
                )
                assertTrue(
                    dbm.outbox.insert(
                        driveId,
                        f5,
                        f4,
                        0L,
                        0L,
                        values[4],
                        null
                    ) > 0
                )
                assertTrue(
                    dbm.outbox.insert(
                        driveId,
                        f1,
                        f5,
                        0L,
                        0L,
                        values[0],
                        null
                    ) > 0
                )

                // Next scheduled should be t3 (f3, no dependency)
                var nextTime = dbm.outbox.nextScheduled()
                assertEquals(t3, nextTime!!.milliseconds)

                // Checkout f3
                val checkoutResult1 = dbm.outbox.checkout()
                assertNotNull(checkoutResult1, "Should successfully checkout f3")
                val item = dbm.outbox.selectCheckedOut(checkoutResult1.checkOutStamp!!)
                assertTrue(item!!.fileId == f3, "Expected item to be f3")

                // Next scheduled should be null (f2 depends on f3, which is checked out)
                nextTime = dbm.outbox.nextScheduled()
                assertEquals(null, nextTime)

                dbm.outbox.deleteByRowId(item.rowId)

                // Now next should be t2 (f2, depends on f3 which is done)
                nextTime = dbm.outbox.nextScheduled()
                assertEquals(t2, nextTime!!.milliseconds)

                // Checkout f2
                val checkoutResult2 = dbm.outbox.checkout()
                assertNotNull(checkoutResult2, "Should successfully checkout f2")
                var item2 = dbm.outbox.selectCheckedOut(checkoutResult2.checkOutStamp!!)
                assertTrue(item2!!.fileId == f2, "Expected item to be f2")

                // Next scheduled should be null (f4 depends on f2, which is checked out)
                nextTime = dbm.outbox.nextScheduled()
                assertEquals(null, nextTime)

                dbm.outbox.deleteByRowId(item2.rowId)

                // Now next should be t4 (f4, depends on f2 which is done)
                nextTime = dbm.outbox.nextScheduled()
                assertEquals(t4, nextTime!!.milliseconds)

                // Checkout f4
                val checkoutResult3 = dbm.outbox.checkout()
                assertNotNull(checkoutResult3, "Should successfully checkout f4")
                item2 = dbm.outbox.selectCheckedOut(checkoutResult3.checkOutStamp!!)
                assertTrue(item2!!.fileId == f4, "Expected item to be f4")

                // Next scheduled should be null (f5 depends on f4, which is checked out)
                nextTime = dbm.outbox.nextScheduled()
                assertEquals(null, nextTime)

                dbm.outbox.deleteByRowId(item2.rowId)

                // Now next should be t5 (f5, depends on f4 which is done)
                nextTime = dbm.outbox.nextScheduled()
                assertEquals(t5, nextTime!!.milliseconds)

                // Checkout f5
                val checkoutResult4 = dbm.outbox.checkout()
                assertNotNull(checkoutResult4, "Should successfully checkout f5")
                item2 = dbm.outbox.selectCheckedOut(checkoutResult4.checkOutStamp!!)
                assertTrue(item2!!.fileId == f5, "Expected item to be f5")

                // Next scheduled should be null (f1 depends on f5, which is checked out)
                nextTime = dbm.outbox.nextScheduled()
                assertEquals(null, nextTime)

                dbm.outbox.deleteByRowId(item2.rowId)

                // Now next should be t1 (f1, depends on f5 which is done)
                nextTime = dbm.outbox.nextScheduled()
                assertEquals(t1, nextTime!!.milliseconds)

                // Checkout f1
                val checkoutResult5 = dbm.outbox.checkout()
                assertNotNull(checkoutResult5, "Should successfully checkout f1")
                item2 = dbm.outbox.selectCheckedOut(checkoutResult5.checkOutStamp!!)
                assertTrue(item2!!.fileId == f1, "Expected item to be f1")

                // Next scheduled should be null
                nextTime = dbm.outbox.nextScheduled()
                assertEquals(null, nextTime)

                dbm.outbox.deleteByRowId(item2.rowId)

                // No more items
                nextTime = dbm.outbox.nextScheduled()
                assertNull(nextTime)
            }
        }
    }
}
