@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.contactbook

import id.homebase.api.client.contacts.Contact
import id.homebase.api.client.contacts.ContactContent
import id.homebase.api.client.contacts.toCanonicalAppId
import id.homebase.api.client.peer.temporal.TemporalAccessStatus
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.core.config.AppConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class EmergencyContactServiceTest {

    private val ourSlot = AppConfig.APP_ID.toCanonicalAppId()
    private val now = 20L * 24 * 60 * 60_000L

    private fun contact(domain: String, locatable: Boolean = true) = Contact(
        uniqueId = Uuid.random(),
        versionTag = null,
        content = ContactContent(
            odinId = domain,
            appData = if (locatable) mapOf(ourSlot to """{"iCanLocate":true}""") else null,
        ),
    )

    private class Harness(
        contacts: List<Contact>,
        val replies: MutableMap<String, () -> TemporalAccessStatus>,
        scope: kotlinx.coroutines.CoroutineScope,
        nowMs: Long,
    ) {
        val calls = mutableListOf<String>()
        val contactsFlow = MutableStateFlow(contacts)
        val service = EmergencyContactService(
            contacts = contactsFlow,
            verify = { peer ->
                calls += peer.domainName
                replies[peer.domainName]?.invoke() ?: error("no reply for ${peer.domainName}")
            },
            isOnline = MutableStateFlow(true),
            selfDomain = { "me.example" },
            scope = scope,
            now = { nowMs },
        )
    }

    private fun access(newestMs: Long) =
        TemporalAccessStatus(hasAccess = true, newestFileModified = UnixTimeUtc(newestMs))

    @Test
    fun refreshMapsEveryOutcome() = runTest {
        val h = Harness(
            contacts = emptyList(),
            replies = mutableMapOf(
                "active.example" to { access(now - 1_000) },
                "nodata.example" to { access(0) },
                "broken.example" to { TemporalAccessStatus(hasAccess = false) },
                "down.example" to { throw IllegalStateException("boom") },
            ),
            scope = backgroundScope,
            nowMs = now,
        )
        assertEquals(LocateVerifyStatus.Active(now - 1_000, now), h.service.refresh(OdinId("active.example")))
        assertEquals(LocateVerifyStatus.Active(null, now), h.service.refresh(OdinId("nodata.example")))
        assertIs<LocateVerifyStatus.Broken>(h.service.refresh(OdinId("broken.example")))
        assertIs<LocateVerifyStatus.Unreachable>(h.service.refresh(OdinId("down.example")))
        assertEquals(4, h.service.status.value.size)
    }

    @Test
    fun refreshAllOnlyTouchesTheGivenIds() = runTest {
        val h = Harness(
            contacts = listOf(contact("a.example"), contact("b.example"), contact("c.example", locatable = false)),
            replies = mutableMapOf(
                "a.example" to { access(now) },
                "b.example" to { access(now) },
            ),
            scope = backgroundScope,
            nowMs = now,
        )
        h.service.refreshAll(only = setOf("b.example"))
        assertEquals(listOf("b.example"), h.calls)

        h.calls.clear()
        h.service.refreshAll()
        // b is still inside its TTL; the unflagged c is never verified.
        assertEquals(listOf("a.example"), h.calls)
    }

    @Test
    fun ownIdentityIsNeverLocatable() = runTest {
        val h = Harness(
            contacts = listOf(contact("me.example"), contact("a.example")),
            replies = mutableMapOf("a.example" to { access(now) }),
            scope = backgroundScope,
            nowMs = now,
        )
        h.service.refreshAll()
        runCurrent()
        assertEquals(listOf("a.example"), h.calls)
        assertEquals(listOf("a.example"), h.service.locatable.value.map { it.odinId.domainName })
    }

    @Test
    fun staleIdsCountOnlyLocatableContactsWithOldData() = runTest {
        val old = now - LOCATE_STALE_WARN_MS - 1
        val h = Harness(
            contacts = listOf(contact("quiet.example"), contact("fresh.example")),
            replies = mutableMapOf(
                "quiet.example" to { access(old) },
                "fresh.example" to { access(now) },
                "stranger.example" to { access(old) },
            ),
            scope = backgroundScope,
            nowMs = now,
        )
        h.service.refreshAll()
        h.service.refresh(OdinId("stranger.example"))
        runCurrent()
        assertEquals(setOf("quiet.example"), h.service.staleIds.value)
        assertTrue(h.service.hasStale.value)

        // Access lost is a broken link, not a stale signal.
        h.service.recordAccessLost(OdinId("quiet.example"), now)
        runCurrent()
        assertTrue(h.service.staleIds.value.isEmpty())
        assertFalse(h.service.hasStale.value)
    }

    @Test
    fun resetClearsEverything() = runTest {
        val h = Harness(
            contacts = listOf(contact("a.example")),
            replies = mutableMapOf("a.example" to { access(now) }),
            scope = backgroundScope,
            nowMs = now,
        )
        h.service.refreshAll()
        h.service.reset()
        assertNull(h.service.status.value["a.example"])
    }
}
