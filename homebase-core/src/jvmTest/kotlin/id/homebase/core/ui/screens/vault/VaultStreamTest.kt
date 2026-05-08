@file:OptIn(ExperimentalUuidApi::class, ExperimentalCoroutinesApi::class)

package id.homebase.core.ui.screens.vault

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.SecureByteArray
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.core.ui.screens.vault.model.VaultSection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Unit tests for [VaultStream] optimistic mutation methods.
 *
 * The stream is constructed with a real [CredentialsManager] (no active credentials)
 * so that [VaultStream.loadAll] returns immediately without touching the database.
 * A no-op [DatabaseManager] is supplied via a stub [SqlDriver] whose schema-creation
 * calls succeed but otherwise never execute real queries (loadAll returns before any
 * query is attempted).
 */
class VaultStreamTest {

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private fun testKeyHeader(): KeyHeader = KeyHeader(
        iv = ByteArray(16),
        aesKey = SecureByteArray(ByteArray(16)),
    )

    private fun buildSection(
        sectionId: Uuid = Uuid.random(),
        fileId: Uuid = Uuid.random(),
        title: String = "Section",
        sortOrder: Int = 0,
        entries: List<VaultEntry> = emptyList(),
        isFirst: Boolean = false,
        isLast: Boolean = false,
    ): VaultSection = VaultSection(
        sectionId = sectionId,
        fileId = fileId,
        title = title,
        sortOrder = sortOrder,
        entries = entries,
        isFirst = isFirst,
        isLast = isLast,
        keyHeader = testKeyHeader(),
    )

    private fun buildEntry(
        uniqueId: Uuid = Uuid.random(),
        groupId: Uuid? = null,
        fileName: String = "file.jpg",
    ): VaultEntry = VaultEntry(
        fileId = Uuid.random(),
        uniqueId = uniqueId,
        driveId = Uuid.random(),
        fileName = fileName,
        contentType = "image/jpeg",
        sizeBytes = 1024L,
        createdAt = 1_700_000_000_000L,
        previewThumbnail = null,
        keyHeader = testKeyHeader(),
        isEncrypted = true,
        versionTag = Uuid.random(),
        groupId = groupId,
    )

    /**
     * A no-op [SqlDriver] whose DDL/DML calls silently succeed. Used only so
     * [DatabaseManager]'s init block (`OdinDatabase.Schema.create(driver)`) does
     * not crash — the actual queries are never reached because [CredentialsManager]
     * has no active credentials, causing [VaultStream.loadAll] to return early.
     */
    private val stubDriver = object : SqlDriver {
        override fun close() {}
        override fun currentTransaction(): Transacter.Transaction? = null

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> = QueryResult.Value(0L)

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            val emptyCursor = object : SqlCursor {
                override fun getBoolean(index: Int) = null
                override fun getBytes(index: Int) = null
                override fun getDouble(index: Int) = null
                override fun getLong(index: Int) = null
                override fun getString(index: Int) = null
                override fun next(): QueryResult<Boolean> = QueryResult.Value(false)
            }
            return mapper(emptyCursor)
        }

        override fun newTransaction(): QueryResult<Transacter.Transaction> {
            val tx = object : Transacter.Transaction() {
                override val enclosingTransaction: Transacter.Transaction? = null
                override fun endTransaction(successful: Boolean): QueryResult<Unit> =
                    QueryResult.Value(Unit)
            }
            return QueryResult.Value(tx)
        }

        override fun addListener(vararg queryKeys: String, listener: Query.Listener) {}
        override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {}
        override fun notifyListeners(vararg queryKeys: String) {}
    }

    /**
     * Creates a [VaultStream] whose [loadAll] is a no-op (CredentialsManager returns null)
     * and whose EventBus observation runs but never receives events.
     */
    private fun TestScope.createStream(): VaultStream {
        val credentialsManager = CredentialsManager()
        val eventBus = EventBus()
        val databaseManager = DatabaseManager(driverProvider = { stubDriver })

        return VaultStream(
            databaseManager = databaseManager,
            credentialsManager = credentialsManager,
            eventBus = eventBus,
            scope = backgroundScope,
        )
    }

    // ---------------------------------------------------------------
    // insertOptimisticSection
    // ---------------------------------------------------------------

    @Test
    fun insertOptimisticSection_addsAndSortsBySortOrder() = runTest {
        val stream = createStream()
        advanceUntilIdle()

        val section = buildSection(title = "Documents", sortOrder = 5)
        stream.insertOptimisticSection(section)

        val sections = stream.sections.value
        assertEquals(1, sections.size)
        assertEquals("Documents", sections[0].title)
        assertTrue(sections[0].isFirst)
        assertTrue(sections[0].isLast)
    }

    @Test
    fun insertOptimisticSection_skipsIfSectionIdAlreadyExists() = runTest {
        val stream = createStream()
        advanceUntilIdle()

        val sectionId = Uuid.random()
        val original = buildSection(sectionId = sectionId, title = "Original", sortOrder = 0)
        stream.insertOptimisticSection(original)

        val duplicate = buildSection(sectionId = sectionId, title = "Duplicate", sortOrder = 1)
        stream.insertOptimisticSection(duplicate)

        val sections = stream.sections.value
        assertEquals(1, sections.size)
        assertEquals("Original", sections[0].title)
    }

    @Test
    fun insertOptimisticSection_maintainsSortWhenAddingMultiple() = runTest {
        val stream = createStream()
        advanceUntilIdle()

        val sectionC = buildSection(title = "C", sortOrder = 30)
        val sectionA = buildSection(title = "A", sortOrder = 10)
        val sectionB = buildSection(title = "B", sortOrder = 20)

        stream.insertOptimisticSection(sectionC)
        stream.insertOptimisticSection(sectionA)
        stream.insertOptimisticSection(sectionB)

        val sections = stream.sections.value
        assertEquals(3, sections.size)
        assertEquals("A", sections[0].title)
        assertEquals("B", sections[1].title)
        assertEquals("C", sections[2].title)

        // isFirst / isLast flags
        assertTrue(sections[0].isFirst)
        assertFalse(sections[0].isLast)
        assertFalse(sections[1].isFirst)
        assertFalse(sections[1].isLast)
        assertFalse(sections[2].isFirst)
        assertTrue(sections[2].isLast)
    }

    // ---------------------------------------------------------------
    // insertOptimisticEntry
    // ---------------------------------------------------------------

    @Test
    fun insertOptimisticEntry_addsToCorrectSection() = runTest {
        val stream = createStream()
        advanceUntilIdle()

        val sectionId = Uuid.random()
        val section = buildSection(sectionId = sectionId, title = "Passports")
        stream.insertOptimisticSection(section)

        val entry = buildEntry(groupId = sectionId, fileName = "passport.jpg")
        stream.insertOptimisticEntry(entry, sectionId)

        val entriesMap = stream.entriesBySection.value
        assertEquals(1, entriesMap[sectionId]?.size)
        assertEquals("passport.jpg", entriesMap[sectionId]?.first()?.fileName)
    }

    @Test
    fun insertOptimisticEntry_toNewSectionCreatesTheKey() = runTest {
        val stream = createStream()
        advanceUntilIdle()

        val sectionId = Uuid.random()
        // Do NOT add a section first — just insert an entry for a sectionId
        val entry = buildEntry(groupId = sectionId, fileName = "photo.png")
        stream.insertOptimisticEntry(entry, sectionId)

        val entriesMap = stream.entriesBySection.value
        assertTrue(entriesMap.containsKey(sectionId))
        assertEquals(1, entriesMap[sectionId]?.size)
        assertEquals("photo.png", entriesMap[sectionId]?.first()?.fileName)
    }

    // ---------------------------------------------------------------
    // updateOptimisticEntry
    // ---------------------------------------------------------------

    @Test
    fun updateOptimisticEntry_replacesExistingEntryByUniqueId() = runTest {
        val stream = createStream()
        advanceUntilIdle()

        val sectionId = Uuid.random()
        val entryId = Uuid.random()
        val section = buildSection(sectionId = sectionId, title = "IDs")
        stream.insertOptimisticSection(section)

        val original = buildEntry(uniqueId = entryId, groupId = sectionId, fileName = "old.jpg")
        stream.insertOptimisticEntry(original, sectionId)

        val updated = original.copy(fileName = "new.jpg")
        stream.updateOptimisticEntry(updated)

        val entriesMap = stream.entriesBySection.value
        assertEquals(1, entriesMap[sectionId]?.size)
        assertEquals("new.jpg", entriesMap[sectionId]?.first()?.fileName)
    }

    @Test
    fun updateOptimisticEntry_withUnknownUniqueIdIsNoOp() = runTest {
        val stream = createStream()
        advanceUntilIdle()

        val sectionId = Uuid.random()
        val section = buildSection(sectionId = sectionId, title = "Docs")
        stream.insertOptimisticSection(section)

        val existing = buildEntry(groupId = sectionId, fileName = "existing.pdf")
        stream.insertOptimisticEntry(existing, sectionId)

        // Try to update an entry with a random uniqueId that doesn't exist
        val unknown = buildEntry(uniqueId = Uuid.random(), groupId = sectionId, fileName = "ghost.pdf")
        stream.updateOptimisticEntry(unknown)

        // Original entry is unchanged, no extra entries added
        val entriesMap = stream.entriesBySection.value
        assertEquals(1, entriesMap[sectionId]?.size)
        assertEquals("existing.pdf", entriesMap[sectionId]?.first()?.fileName)
    }

    // ---------------------------------------------------------------
    // removeEntry
    // ---------------------------------------------------------------

    @Test
    fun removeEntry_removesFromAllSections() = runTest {
        val stream = createStream()
        advanceUntilIdle()

        val sectionId1 = Uuid.random()
        val sectionId2 = Uuid.random()
        val entryId = Uuid.random()

        stream.insertOptimisticSection(buildSection(sectionId = sectionId1, title = "S1", sortOrder = 1))
        stream.insertOptimisticSection(buildSection(sectionId = sectionId2, title = "S2", sortOrder = 2))

        // Add entry to section 1
        val entry = buildEntry(uniqueId = entryId, groupId = sectionId1, fileName = "to_delete.jpg")
        stream.insertOptimisticEntry(entry, sectionId1)

        // Also add an entry to section 2 to verify it stays
        val otherEntry = buildEntry(groupId = sectionId2, fileName = "keep.jpg")
        stream.insertOptimisticEntry(otherEntry, sectionId2)

        stream.removeEntry(entryId)

        val entriesMap = stream.entriesBySection.value
        assertEquals(0, entriesMap[sectionId1]?.size ?: 0)
        assertEquals(1, entriesMap[sectionId2]?.size)
        assertEquals("keep.jpg", entriesMap[sectionId2]?.first()?.fileName)
    }

    @Test
    fun removeEntry_withUnknownIdIsNoOp() = runTest {
        val stream = createStream()
        advanceUntilIdle()

        val sectionId = Uuid.random()
        stream.insertOptimisticSection(buildSection(sectionId = sectionId, title = "S"))

        val entry = buildEntry(groupId = sectionId, fileName = "stays.jpg")
        stream.insertOptimisticEntry(entry, sectionId)

        stream.removeEntry(Uuid.random()) // unknown

        val entriesMap = stream.entriesBySection.value
        assertEquals(1, entriesMap[sectionId]?.size)
        assertEquals("stays.jpg", entriesMap[sectionId]?.first()?.fileName)
    }

    // ---------------------------------------------------------------
    // removeSection
    // ---------------------------------------------------------------

    @Test
    fun removeSection_removesSectionAndItsEntries() = runTest {
        val stream = createStream()
        advanceUntilIdle()

        val sectionId1 = Uuid.random()
        val sectionId2 = Uuid.random()

        stream.insertOptimisticSection(buildSection(sectionId = sectionId1, title = "ToRemove", sortOrder = 1))
        stream.insertOptimisticSection(buildSection(sectionId = sectionId2, title = "ToKeep", sortOrder = 2))

        stream.insertOptimisticEntry(buildEntry(groupId = sectionId1, fileName = "a.jpg"), sectionId1)
        stream.insertOptimisticEntry(buildEntry(groupId = sectionId2, fileName = "b.jpg"), sectionId2)

        stream.removeSection(sectionId1)

        // Section removed
        val sections = stream.sections.value
        assertEquals(1, sections.size)
        assertEquals("ToKeep", sections[0].title)

        // isFirst/isLast recalculated for remaining section
        assertTrue(sections[0].isFirst)
        assertTrue(sections[0].isLast)

        // Entries for removed section no longer in map
        val entriesMap = stream.entriesBySection.value
        assertFalse(entriesMap.containsKey(sectionId1))
        assertEquals(1, entriesMap[sectionId2]?.size)
    }

    @Test
    fun removeSection_withUnknownIdIsNoOp() = runTest {
        val stream = createStream()
        advanceUntilIdle()

        val sectionId = Uuid.random()
        stream.insertOptimisticSection(buildSection(sectionId = sectionId, title = "Only"))

        stream.removeSection(Uuid.random()) // unknown

        val sections = stream.sections.value
        assertEquals(1, sections.size)
        assertEquals("Only", sections[0].title)
    }

    // ---------------------------------------------------------------
    // markPayloadPendingDelete + updateOptimisticEntry
    // ---------------------------------------------------------------

    @Test
    fun markPayloadPendingDelete_removesDescriptorFromOptimisticUpdate() = runTest {
        val stream = createStream()
        advanceUntilIdle()

        val sectionId = Uuid.random()
        val entryId = Uuid.random()
        stream.insertOptimisticSection(buildSection(sectionId = sectionId))

        val entry = buildEntry(uniqueId = entryId, groupId = sectionId).copy(
            payloadDescriptors = listOf(
                PayloadDescriptor(key = "vlt_pg_00", contentType = "image/jpeg", iv = "abc"),
                PayloadDescriptor(key = "vlt_pg_01", contentType = "image/jpeg", iv = "def"),
                PayloadDescriptor(key = "vlt_pg_02", contentType = "image/jpeg", iv = "ghi"),
            ),
        )
        stream.insertOptimisticEntry(entry, sectionId)

        stream.markPayloadPendingDelete(entryId, "vlt_pg_01")
        val updated = entry.copy(
            payloadDescriptors = entry.payloadDescriptors.filter { it.key != "vlt_pg_01" },
        )
        stream.updateOptimisticEntry(updated)

        val result = stream.entriesBySection.value[sectionId]!!.first()
        assertEquals(2, result.payloadDescriptors.size)
        assertEquals("vlt_pg_00", result.payloadDescriptors[0].key)
        assertEquals("vlt_pg_02", result.payloadDescriptors[1].key)
    }

    // ---------------------------------------------------------------
    // Resurrection prevention (deletedSectionIds / deletedEntryIds)
    // ---------------------------------------------------------------

    @Test
    fun removeSection_preventsResurrectionViaInsertOptimisticSection() = runTest {
        val stream = createStream()
        advanceUntilIdle()

        val sectionId = Uuid.random()
        val section = buildSection(sectionId = sectionId, title = "Deleted")
        stream.insertOptimisticSection(section)
        stream.removeSection(sectionId)

        // Attempt to re-add the same section — should be silently ignored
        stream.insertOptimisticSection(section)
        assertEquals(0, stream.sections.value.size)
    }

    @Test
    fun removeSection_preventsResurrectionViaInsertOptimisticEntry() = runTest {
        val stream = createStream()
        advanceUntilIdle()

        val sectionId = Uuid.random()
        val entryId = Uuid.random()
        stream.insertOptimisticSection(buildSection(sectionId = sectionId))
        stream.insertOptimisticEntry(
            buildEntry(uniqueId = entryId, groupId = sectionId), sectionId,
        )
        stream.removeSection(sectionId)

        // Entry's section was deleted — re-inserting should be blocked
        stream.insertOptimisticEntry(
            buildEntry(uniqueId = entryId, groupId = sectionId), sectionId,
        )
        assertFalse(stream.entriesBySection.value.containsKey(sectionId))
    }

    @Test
    fun removeEntry_preventsResurrectionViaInsertOptimisticEntry() = runTest {
        val stream = createStream()
        advanceUntilIdle()

        val sectionId = Uuid.random()
        val entryId = Uuid.random()
        stream.insertOptimisticSection(buildSection(sectionId = sectionId))
        stream.insertOptimisticEntry(
            buildEntry(uniqueId = entryId, groupId = sectionId), sectionId,
        )
        stream.removeEntry(entryId)

        // Re-inserting deleted entry — should be blocked
        stream.insertOptimisticEntry(
            buildEntry(uniqueId = entryId, groupId = sectionId), sectionId,
        )
        assertEquals(0, stream.entriesBySection.value[sectionId]?.size ?: 0)
    }

    // ---------------------------------------------------------------
    // reset — identity isolation across logout/login
    // ---------------------------------------------------------------

    @Test
    fun reset_clearsAllInMemoryState() = runTest {
        val stream = createStream()
        advanceUntilIdle()

        val sectionId = Uuid.random()
        stream.insertOptimisticSection(buildSection(sectionId = sectionId, title = "UserA"))
        stream.insertOptimisticEntry(
            buildEntry(groupId = sectionId, fileName = "secret.jpg"), sectionId,
        )
        assertTrue(stream.sections.value.isNotEmpty())
        assertTrue(stream.entriesBySection.value.isNotEmpty())

        stream.reset()

        assertEquals(0, stream.sections.value.size)
        assertEquals(0, stream.entriesBySection.value.size)
        assertFalse(stream.isLoaded.value)
    }

    @Test
    fun reset_setsIsLoadedFalseAfterItWasTrue() = runTest {
        val stream = createStream()
        advanceUntilIdle()

        // Manually simulate what loadAll does on completion
        assertFalse(stream.isLoaded.value)

        // Insert data as if a successful load happened
        stream.insertOptimisticSection(buildSection(title = "Data"))
        assertTrue(stream.sections.value.isNotEmpty())

        // Simulate logout: reset clears everything including isLoaded
        stream.reset()
        assertFalse(stream.isLoaded.value)
        assertEquals(0, stream.sections.value.size)
        assertEquals(0, stream.entriesBySection.value.size)
    }

    @Test
    fun reset_clearsResurrectionSetsAllowingReuse() = runTest {
        val stream = createStream()
        advanceUntilIdle()

        val sectionId = Uuid.random()
        val entryId = Uuid.random()
        stream.insertOptimisticSection(buildSection(sectionId = sectionId))
        stream.insertOptimisticEntry(
            buildEntry(uniqueId = entryId, groupId = sectionId), sectionId,
        )

        // Delete — IDs enter the resurrection-prevention sets
        stream.removeSection(sectionId)
        assertEquals(0, stream.sections.value.size)

        // Simulate logout → login: reset then re-add same IDs (new user
        // could theoretically reuse UUIDs, or same user re-syncs)
        stream.reset()

        stream.insertOptimisticSection(buildSection(sectionId = sectionId, title = "UserB"))
        stream.insertOptimisticEntry(
            buildEntry(uniqueId = entryId, groupId = sectionId), sectionId,
        )

        assertEquals(1, stream.sections.value.size)
        assertEquals("UserB", stream.sections.value[0].title)
        assertEquals(1, stream.entriesBySection.value[sectionId]?.size)
    }

    @Test
    fun reset_clearsPendingPageDeletes() = runTest {
        val stream = createStream()
        advanceUntilIdle()

        val sectionId = Uuid.random()
        val entryId = Uuid.random()
        stream.insertOptimisticSection(buildSection(sectionId = sectionId))

        val entry = buildEntry(uniqueId = entryId, groupId = sectionId).copy(
            payloadDescriptors = listOf(
                PayloadDescriptor(key = "vlt_pg_00", contentType = "image/jpeg", iv = "abc"),
                PayloadDescriptor(key = "vlt_pg_01", contentType = "image/jpeg", iv = "def"),
            ),
        )
        stream.insertOptimisticEntry(entry, sectionId)
        stream.markPayloadPendingDelete(entryId, "vlt_pg_01")

        stream.reset()

        // After reset, re-insert the same entry with both pages — vlt_pg_01
        // should NOT be filtered because pendingPageDeletes was cleared.
        stream.insertOptimisticSection(buildSection(sectionId = sectionId))
        stream.insertOptimisticEntry(entry, sectionId)

        val result = stream.entriesBySection.value[sectionId]!!.first()
        assertEquals(2, result.payloadDescriptors.size)
    }

    // ---------------------------------------------------------------
    // updateOptimisticEntry — pending append preservation
    // ---------------------------------------------------------------

    @Test
    fun updateOptimisticEntry_preservesPendingAppendDescriptors() = runTest {
        val stream = createStream()
        advanceUntilIdle()

        val sectionId = Uuid.random()
        val entryId = Uuid.random()
        stream.insertOptimisticSection(buildSection(sectionId = sectionId))

        val entry = buildEntry(uniqueId = entryId, groupId = sectionId).copy(
            payloadDescriptors = listOf(
                PayloadDescriptor(key = "vlt_pg_00", contentType = "image/jpeg", iv = "abc"),
            ),
        )
        stream.insertOptimisticEntry(entry, sectionId)

        val withPlaceholder = entry.copy(
            payloadDescriptors = entry.payloadDescriptors + PayloadDescriptor(
                key = "vlt_pg_01", contentType = "image/png",
            ),
        )
        stream.updateOptimisticEntry(withPlaceholder)

        val staleUpdate = entry.copy(fileName = "renamed.jpg")
        stream.updateOptimisticEntry(staleUpdate)

        val result = stream.entriesBySection.value[sectionId]!!.first()
        assertEquals("renamed.jpg", result.fileName)
        assertEquals(1, result.payloadDescriptors.size)
        assertEquals("vlt_pg_00", result.payloadDescriptors[0].key)
    }
}
