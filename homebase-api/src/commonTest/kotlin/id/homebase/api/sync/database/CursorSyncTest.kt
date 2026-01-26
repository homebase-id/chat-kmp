package id.homebase.homebasekmppoc.prototype.lib.database

import app.cash.sqldelight.db.SqlDriver
import id.homebase.homebasekmppoc.lib.database.OdinDatabase
import id.homebase.homebasekmppoc.prototype.lib.core.time.UnixTimeUtc
import id.homebase.homebasekmppoc.prototype.lib.drives.query.QueryBatchCursor
import id.homebase.homebasekmppoc.prototype.lib.drives.query.TimeRowCursor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class CursorSyncTest
{
    @Test
    fun testSaveAndLoadQueryBatchCursor_withAllFields() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->        // Create a QueryBatchCursor with all fields populated
            val originalCursor = QueryBatchCursor(
                paging = TimeRowCursor(
                    time = UnixTimeUtc(1704067200000L), // 2024-01-01 00:00:00 UTC
                    row = 12345L
                ),
                stop = TimeRowCursor(
                    time = UnixTimeUtc(1704153600000L), // 2024-01-02 00:00:00 UTC
                    row = 67890L
                ),
                next = TimeRowCursor(
                    time = UnixTimeUtc(1704240000000L), // 2024-01-03 00:00:00 UTC
                    row = 11111L
                )
            )
            val cursorStorage = CursorStorage(dbm, Uuid.random())

            // Save the cursor
            cursorStorage.saveCursor(originalCursor)

            // Load the cursor back
            val loadedCursor = cursorStorage.loadCursor()

            // Verify that cursor was loaded successfully
            assertNotNull(loadedCursor, "Cursor should be loaded after saving")

            // Verify all fields are loaded correctly
            assertNotNull(loadedCursor.paging, "Paging cursor should not be null")
            assertNotNull(loadedCursor.stop, "Stop at boundary cursor should not be null")
            assertNotNull(loadedCursor.next, "Next boundary cursor should not be null")

            // Verify paging cursor fields
            assertEquals(
                originalCursor.paging!!.time,
                loadedCursor.paging.time,
                "Paging cursor time should match"
            )
            assertEquals(
                originalCursor.paging.row,
                loadedCursor.paging.row,
                "Paging cursor row ID should match"
            )

            // Verify stop at boundary cursor fields
            assertEquals(
                originalCursor.stop!!.time,
                loadedCursor.stop.time,
                "Stop at boundary cursor time should match"
            )
            assertEquals(
                originalCursor.stop.row,
                loadedCursor.stop.row,
                "Stop at boundary cursor row ID should match"
            )

            // Verify next boundary cursor fields
            assertEquals(
                originalCursor.next!!.time,
                loadedCursor.next.time,
                "Next boundary cursor time should match"
            )
            assertEquals(
                originalCursor.next.row,
                loadedCursor.next.row,
                "Next boundary cursor row ID should match"
            )
        }
    }

    @Test
    fun testLoadCursor_whenNoCursorExists_returnsNull() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->        // Create a QueryBatchCursor with all fields populated
            val cursorStorage = CursorStorage(dbm, Uuid.random())

            // Try to load cursor when none exists
            val loadedCursor = cursorStorage.loadCursor()

            // Verify that null is returned
            assertNull(loadedCursor, "Should return null when no cursor exists")
        }
    }

    @Test
    fun testSaveAndLoadQueryBatchCursor_withNullFields() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->        // Create a QueryBatchCursor with all fields populated
            // Create a QueryBatchCursor with some null fields
            val originalCursor = QueryBatchCursor(
                paging = TimeRowCursor(
                    time = UnixTimeUtc(1704067200000L),
                    row = null
                ),
                stop = null,
                next = null
            )

            // Save the cursor
            val cursorStorage = CursorStorage(dbm, Uuid.random())
            cursorStorage.saveCursor(originalCursor)

            // Load the cursor back
            val loadedCursor = cursorStorage.loadCursor()

            // Verify that cursor was loaded successfully
            assertNotNull(loadedCursor, "Cursor should be loaded after saving")

            // Verify paging cursor fields
            assertNotNull(loadedCursor.paging, "Paging cursor should not be null")
            assertEquals(
                originalCursor.paging!!.time,
                loadedCursor.paging.time,
                "Paging cursor time should match"
            )
            assertEquals(
                originalCursor.paging.row,
                loadedCursor.paging.row,
                "Paging cursor row ID should match"
            )

            // Verify that null fields remain null
            assertNull(loadedCursor.stop, "Stop at boundary cursor should remain null")
            assertNull(loadedCursor.next, "Next boundary cursor should remain null")
        }
    }

    @Test
    fun testDeleteCursor() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->        // Create a QueryBatchCursor with all fields populated
            // Create and save a cursor
            val originalCursor = QueryBatchCursor(
                paging = TimeRowCursor(
                    time = UnixTimeUtc(1704067200000L),
                    row = 12345L
                ),
                stop = null,
                next = null
            )
            val cursorStorage = CursorStorage(dbm, Uuid.random())
            cursorStorage.saveCursor(originalCursor)

            // Verify cursor exists
            assertNotNull(cursorStorage.loadCursor(), "Cursor should exist after saving")

            // Delete the cursor
            cursorStorage.deleteCursor()

            // Verify cursor is deleted
            assertNull(cursorStorage.loadCursor(), "Cursor should be null after deletion")
        }
    }


    @Test
    fun testUpdateCursor() = runTest {
        DatabaseManager { createInMemoryDatabase() }.use { dbm ->        // Create a QueryBatchCursor with all fields populated
            // Create and save initial cursor
            val initialCursor = QueryBatchCursor(
                paging = TimeRowCursor(
                    time = UnixTimeUtc(1704067200000L),
                    row = 12345L
                ),
                stop = null,
                next = null
            )
            val cursorStorage = CursorStorage(dbm, Uuid.random())
            cursorStorage.saveCursor(initialCursor)

            // Verify initial cursor
            val loadedInitial = cursorStorage.loadCursor()
            assertNotNull(loadedInitial)
            assertEquals(1704067200000L, loadedInitial.paging!!.time.milliseconds)

            // Update with new cursor
            val updatedCursor = QueryBatchCursor(
                paging = TimeRowCursor(
                    time = UnixTimeUtc(1704153600000L), // Different time
                    row = 54321L // Different row ID
                ),
                stop = TimeRowCursor(
                    time = UnixTimeUtc(1704240000000L),
                    row = 98765L
                ),
                next = TimeRowCursor(
                    time = UnixTimeUtc(1704326400000L),
                    row = 11111L
                )
            )
            cursorStorage.saveCursor(updatedCursor)

            // Verify cursor was updated
            val loadedUpdated = cursorStorage.loadCursor()
            assertNotNull(loadedUpdated)
            assertEquals(
                1704153600000L,
                loadedUpdated.paging!!.time.milliseconds,
                "Paging cursor should be updated"
            )
            assertEquals(
                54321L,
                loadedUpdated.paging.row,
                "Paging cursor row ID should be updated"
            )
            assertEquals(
                1704240000000L,
                loadedUpdated.stop!!.time.milliseconds,
                "Stop at boundary should be updated"
            )
            assertEquals(
                98765L,
                loadedUpdated.stop.row,
                "Stop at boundary row ID should be updated"
            )
            assertEquals(
                1704326400000L,
                loadedUpdated.next!!.time.milliseconds,
                "Next boundary should be updated"
            )
            assertEquals(11111L, loadedUpdated.next.row, "Next boundary row ID should be updated")
        }
    }
    @Test
    fun testQueryBatchCursorFromJson_allFieldsPopulated() = runTest {
        // Test JSON string that matches the server response format
        val jsonCursor = """
        {
            "paging" : {
                "time" : 1752846588053,
                "row" : 3729
            },
            "stop" : {
                "time" : 1752846589000,
                "row" : 4000
            },
            "next" : {
                "time" : 1752846590000,
                "row" : 4500
            }
        }
        """.trimIndent()

        // Parse JSON string to QueryBatchCursor
        val cursor = QueryBatchCursor.fromJson(jsonCursor)

        // Verify cursor is not null
        assertNotNull(cursor, "Cursor should be parsed successfully")

        // Verify paging field
        assertNotNull(cursor.paging, "Paging cursor should not be null")
        assertEquals(
            UnixTimeUtc(1752846588053L),
            cursor.paging.time,
            "Paging time should match"
        )
        assertEquals(3729L, cursor.paging.row, "Paging row should match")

        // Verify stop field
        assertNotNull(cursor.stop, "Stop cursor should not be null")
        assertEquals(UnixTimeUtc(1752846589000L), cursor.stop.time, "Stop time should match")
        assertEquals(4000L, cursor.stop.row, "Stop row should match")

        // Verify next field
        assertNotNull(cursor.next, "Next cursor should not be null")
        assertEquals(UnixTimeUtc(1752846590000L), cursor.next.time, "Next time should match")
        assertEquals(4500L, cursor.next.row, "Next row should match")
    }

    @Test
    fun testQueryBatchCursorFromJson_mixedNullFields() = runTest {
        // Test JSON string with some fields null (like your server example)
        val jsonCursor = """
        {
            "paging" : {
                "time" : 1752846588053,
                "row" : 3729
            },
            "stop" : null,
            "next" : null
        }
        """.trimIndent()

        // Parse JSON string to QueryBatchCursor
        val cursor = QueryBatchCursor.fromJson(jsonCursor)

        // Verify cursor is not null
        assertNotNull(cursor, "Cursor should be parsed successfully")

        // Verify paging field (populated)
        assertNotNull(cursor.paging, "Paging cursor should not be null")
        assertEquals(UnixTimeUtc(1752846588053L), cursor.paging.time, "Paging time should match")
        assertEquals(3729L, cursor.paging.row, "Paging row should match")

        // Verify stop field (null)
        assertNull(cursor.stop, "Stop cursor should be null")

        // Verify next field (null)
        assertNull(cursor.next, "Next cursor should be null")
    }

    @Test
    fun testQueryBatchCursorFromJson_allNullFields() = runTest {
        // Test JSON string with all fields null
        val jsonCursor = """
        {
            "paging" : null,
            "stop" : null,
            "next" : null
        }
        """.trimIndent()

        // Parse JSON string to QueryBatchCursor
        val cursor = QueryBatchCursor.fromJson(jsonCursor)

        // Verify cursor is not null
        assertNotNull(cursor, "Cursor should be parsed successfully")

        // Verify all fields are null
        assertNull(cursor.paging, "Paging cursor should be null")
        assertNull(cursor.stop, "Stop cursor should be null")
        assertNull(cursor.next, "Next cursor should be null")
    }

    @Test
    fun testQueryBatchCursorFromJson_rowFieldOptional() = runTest {
        // Test JSON string where row field is optional (not provided)
        val jsonCursor = """
        {
            "paging" : {
                "time" : 1752846588053
            },
            "stop" : {
                "time" : 1752846589000,
                "row" : null
            },
            "next" : {
                "time" : 1752846590000,
                "row" : 4500
            }
        }
        """.trimIndent()

        // Parse JSON string to QueryBatchCursor
        val cursor = QueryBatchCursor.fromJson(jsonCursor)

        // Verify cursor is not null
        assertNotNull(cursor, "Cursor should be parsed successfully")

        // Verify paging field (row not provided, should be null)
        assertNotNull(cursor.paging, "Paging cursor should not be null")
        assertEquals(UnixTimeUtc(1752846588053L), cursor.paging.time, "Paging time should match")
        assertNull(cursor.paging.row, "Paging row should be null when not provided")

        // Verify stop field (row explicitly null)
        assertNotNull(cursor.stop, "Stop cursor should not be null")
        assertEquals(UnixTimeUtc(1752846589000L), cursor.stop.time, "Stop time should match")
        assertNull(cursor.stop.row, "Stop row should be null when explicitly null")

        // Verify next field (row provided)
        assertNotNull(cursor.next, "Next cursor should not be null")
        assertEquals(UnixTimeUtc(1752846590000L), cursor.next.time, "Next time should match")
        assertEquals(4500L, cursor.next.row, "Next row should match")
    }

    @Test
    fun testQueryBatchCursorFromJson_roundTrip() = runTest {
        // Test round-trip conversion: JSON -> Object -> JSON
        val originalJson = """
        {
            "paging" : {
                "time" : 1752846588053,
                "row" : 3729
            },
            "stop" : null,
            "next" : null
        }
        """.trimIndent()

        // Parse JSON string to QueryBatchCursor
        val cursor = QueryBatchCursor.fromJson(originalJson)
        assertNotNull(cursor, "Cursor should be parsed successfully")

        // Convert back to JSON
        val serializedJson = cursor.toJson()

        // Parse the serialized JSON again
        val reparsedCursor = QueryBatchCursor.fromJson(serializedJson)
        assertNotNull(reparsedCursor, "Reparsed cursor should not be null")

        // Verify the original and reparsed cursors match
        assertEquals(cursor.paging?.time, reparsedCursor.paging?.time, "Paging time should match after round-trip")
        assertEquals(cursor.paging?.row, reparsedCursor.paging?.row, "Paging row should match after round-trip")
        assertEquals(cursor.stop, reparsedCursor.stop, "Stop should match after round-trip")
        assertEquals(cursor.next, reparsedCursor.next, "Next should match after round-trip")
    }
}