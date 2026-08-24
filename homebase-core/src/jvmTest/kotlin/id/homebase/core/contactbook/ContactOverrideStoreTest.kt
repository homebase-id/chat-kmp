@file:OptIn(ExperimentalUuidApi::class, ExperimentalCoroutinesApi::class)

package id.homebase.core.contactbook

import id.homebase.api.client.contacts.Contact
import id.homebase.api.client.contacts.ContactContent
import id.homebase.api.client.contacts.ContactsProvider
import id.homebase.api.client.eventbus.EventBus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * [ContactOverrideStore.hydrateAll] is the only hydrator the received-contact-card save flow has:
 * it runs the duplicate check's override load, and its caller reads `overrides` on the very next
 * line. Returning before the payloads land silently blinds the check to every value that lives in
 * an override — which is most of what a contact card collides on.
 */
class ContactOverrideStoreTest {

    private val payloadKey = ContactsProvider.CONTACT_APP_EXT_DATA_PAYLOAD_KEY
    private val storeScopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun cancelStoreScopes() = storeScopes.forEach { it.cancel() }

    private fun contact(
        id: Uuid = Uuid.random(),
        versionTag: Uuid? = Uuid.random(),
        advertisesPayload: Boolean = true,
    ) = Contact(
        uniqueId = id,
        versionTag = versionTag,
        content = ContactContent(),
        fileId = Uuid.random(),
        payloadKeys = if (advertisesPayload) setOf(payloadKey) else emptySet(),
    )

    // Not backgroundScope: advanceUntilIdle does not run background work, so a store hosted there
    // would make no progress between assertions.
    private fun TestScope.store(read: suspend (Contact) -> String?): ContactOverrideStore {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        storeScopes += scope
        return ContactOverrideStore(
            eventBus = EventBus(),
            scope = scope,
            readOverride = read,
            writeOverride = { _, _, _ -> null },
        )
    }

    private fun overlayJson(givenName: String) = """{"givenName":"$givenName"}"""

    @Test
    fun `every override is readable the moment hydrateAll returns`() = runTest {
        val ada = contact()
        val grace = contact()
        val store = store { c ->
            when (c.uniqueId) {
                ada.uniqueId -> overlayJson("Ada")
                grace.uniqueId -> overlayJson("Grace")
                else -> null
            }
        }

        store.hydrateAll(listOf(ada, grace))

        assertEquals("Ada", store.overrides.value[ada.uniqueId]?.givenName)
        assertEquals("Grace", store.overrides.value[grace.uniqueId]?.givenName)
    }

    @Test
    fun `a contact that no longer advertises the payload is not fetched and loses its cached override`() =
        runTest {
            val id = Uuid.random()
            val withOverride = contact(id = id)
            val reads = mutableListOf<Uuid>()
            val store = store { c ->
                reads += c.uniqueId
                overlayJson("Ada")
            }

            store.hydrateAll(listOf(withOverride))
            assertEquals(listOf(id), reads)
            assertEquals("Ada", store.overrides.value[id]?.givenName)

            val payloadGone = withOverride.copy(versionTag = Uuid.random(), payloadKeys = emptySet())
            store.hydrateAll(listOf(payloadGone))

            assertEquals(listOf(id), reads, "a contact with no payload must not be fetched")
            assertNull(store.overrides.value[id], "the stale override must be evicted")
        }

    @Test
    fun `hydrateAll waits for a fetch hydrate already started rather than returning empty`() = runTest {
        val ada = contact()
        val gate = CompletableDeferred<Unit>()
        val store = store {
            gate.await()
            overlayJson("Ada")
        }

        store.hydrate(ada)
        advanceUntilIdle()

        var returned = false
        launch {
            store.hydrateAll(listOf(ada))
            returned = true
        }
        advanceUntilIdle()

        assertFalse(returned, "hydrateAll returned while the in-flight fetch was still parked")
        assertTrue(store.overrides.value.isEmpty())

        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(returned)
        assertEquals("Ada", store.overrides.value[ada.uniqueId]?.givenName)
    }

    @Test
    fun `a fetch parked under an older versionTag does not answer a caller holding the new one`() =
        runTest {
            val id = Uuid.random()
            val v1 = contact(id = id)
            val v2 = v1.copy(versionTag = Uuid.random())
            val gates = mutableMapOf<Uuid?, CompletableDeferred<Unit>>()
            val asked = mutableListOf<Uuid?>()
            val store = store { c ->
                asked += c.versionTag
                gates.getOrPut(c.versionTag) { CompletableDeferred() }.await()
                overlayJson(if (c.versionTag == v1.versionTag) "Stale" else "Fresh")
            }

            store.hydrate(v1)
            advanceUntilIdle()
            assertEquals(listOf(v1.versionTag), asked)

            var returned = false
            launch {
                store.hydrateAll(listOf(v2))
                returned = true
            }
            advanceUntilIdle()

            assertFalse(returned, "hydrateAll joined the fetch parked under the older versionTag")
            assertEquals(
                listOf(v1.versionTag, v2.versionTag),
                asked,
                "the advanced versionTag must get a read of its own",
            )

            gates.getValue(v2.versionTag).complete(Unit)
            advanceUntilIdle()

            assertTrue(returned)
            assertEquals("Fresh", store.overrides.value[id]?.givenName)
        }

    @Test
    fun `a stale fetch landing after a newer one does not overwrite it`() = runTest {
        val id = Uuid.random()
        val v1 = contact(id = id)
        val v2 = v1.copy(versionTag = Uuid.random())
        val gates = mutableMapOf<Uuid?, CompletableDeferred<Unit>>()
        val store = store { c ->
            gates.getOrPut(c.versionTag) { CompletableDeferred() }.await()
            overlayJson(if (c.versionTag == v1.versionTag) "Stale" else "Fresh")
        }

        store.hydrate(v1)
        advanceUntilIdle()
        store.hydrate(v2)
        advanceUntilIdle()

        gates.getValue(v2.versionTag).complete(Unit)
        advanceUntilIdle()
        assertEquals("Fresh", store.overrides.value[id]?.givenName)

        gates.getValue(v1.versionTag).complete(Unit)
        advanceUntilIdle()

        assertEquals(
            "Fresh",
            store.overrides.value[id]?.givenName,
            "the abandoned fetch must not resurrect the overlay of a version that has moved on",
        )
    }

    @Test
    fun `a completed fetch is dropped rather than retained for the whole session`() = runTest {
        val ada = contact()
        val store = store { overlayJson("Ada") }

        store.hydrateAll(listOf(ada))
        advanceUntilIdle()

        assertEquals(0, store.inFlightFetchCount(), "a settled fetch pins its Contact until reset")
    }

    @Test
    fun `a failed fetch leaves the version unclaimed so the next pass retries it`() = runTest {
        val ada = contact()
        var attempts = 0
        val store = store {
            attempts++
            if (attempts == 1) error("payload fetch blew up") else overlayJson("Ada")
        }

        store.hydrateAll(listOf(ada))
        assertEquals(1, attempts)
        assertTrue(store.overrides.value.isEmpty())

        store.hydrateAll(listOf(ada))

        assertEquals(2, attempts)
        assertEquals("Ada", store.overrides.value[ada.uniqueId]?.givenName)
    }
}
