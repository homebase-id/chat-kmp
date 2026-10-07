package id.homebase.chat.viewonce

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.ReactionEntry
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.MessageAppData
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ViewOnceRulesTest {

    private val me = OdinId("me.test")
    private val alice = OdinId("alice.test")
    private val created = 1_000_000L
    private val max = ViewOnceRules.MAX_LIFESPAN_MS

    private fun summary(code: String, count: Int) = ReactionSummary(
        reactions = mapOf("k$code" to ReactionEntry("k$code", count, """{"emoji":"$code"}"""))
    )

    private fun message(
        author: OdinId?,
        own: List<String> = emptyList(),
        summary: ReactionSummary? = null,
        isDeleted: Boolean = false,
        updatedMs: Long = created,
    ) = MessageUiModel(
        id = Uuid.random(),
        globalTransitId = null,
        fileId = Uuid.random(),
        conversationId = Uuid.random(),
        content = "",
        userDate = Instant.fromEpochMilliseconds(created),
        modified = Instant.fromEpochMilliseconds(updatedMs),
        created = Instant.fromEpochMilliseconds(created),
        originalAuthor = author,
        sender = author,
        displayName = "",
        ownReactions = persistentListOf(*own.toTypedArray()),
        messageAppData = MessageAppData(),
        reactionPreview = summary,
        previewThumbnail = null,
        payloads = null,
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16))),
        isDeleted = isDeleted,
        versionTag = Uuid.random(),
        isPendingSend = false,
        hasMore = false,
    )

    private fun state(m: MessageUiModel, nowMs: Long) = ViewOnceRules.stateOf(m, nowMs, me)

    @Test
    fun lifespanIsThirtyDays() = assertEquals(2_592_000_000L, max)

    @Test
    fun recipient_freshAndUnopened() =
        assertEquals(ViewOnceState.Unopened, state(message(alice), created + 1))

    @Test
    fun recipient_ownOpenSignalIsOpenedEvenBeforeDelete() =
        assertEquals(ViewOnceState.Opened, state(message(alice, own = listOf("_vo")), created + 1))

    @Test
    fun recipient_openedBeatsAgeWhenSignalPresent() =
        assertEquals(ViewOnceState.Opened, state(message(alice, own = listOf("_vo")), created + max + 5))

    @Test
    fun recipient_unopenedOneMsBeforeBoundary() =
        assertEquals(ViewOnceState.Unopened, state(message(alice), created + max - 1))

    @Test
    fun recipient_unopenedAtExactBoundaryIsExpired() =
        assertEquals(ViewOnceState.Expired, state(message(alice), created + max))

    @Test
    fun recipient_tombstoneBeforeBoundaryIsOpened() =
        assertEquals(
            ViewOnceState.Opened,
            state(message(alice, isDeleted = true, updatedMs = created + max - 1), created + max * 2),
        )

    @Test
    fun recipient_tombstoneAtExactBoundaryIsExpired() =
        assertEquals(
            ViewOnceState.Expired,
            state(message(alice, isDeleted = true, updatedMs = created + max), created + max * 2),
        )

    @Test
    fun recipient_otherPeoplesSignalDoesNotCountAsOwn() =
        assertEquals(ViewOnceState.Unopened, state(message(alice, summary = summary("_vo", 1)), created + 1))

    @Test
    fun sender_sent() = assertEquals(ViewOnceState.Sent, state(message(me), created + 1))

    @Test
    fun sender_nullAuthorIsSelf() = assertEquals(ViewOnceState.Sent, state(message(null), created + 1))

    @Test
    fun sender_openedWhenOneOpenSignal() {
        val m = message(me, summary = summary("_vo", 1))
        assertEquals(ViewOnceState.Opened, state(m, created + 1))
        assertEquals(1, ViewOnceRules.openedCount(m))
    }

    @Test
    fun sender_groupCountsOpeners() {
        val m = message(me, summary = summary("_vo", 3))
        assertEquals(ViewOnceState.Opened, state(m, created + 1))
        assertEquals(3, ViewOnceRules.openedCount(m))
    }

    @Test
    fun sender_expiredWithNoOpen_atBoundary() {
        assertEquals(ViewOnceState.Sent, state(message(me), created + max - 1))
        assertEquals(ViewOnceState.Expired, state(message(me), created + max))
    }

    @Test
    fun sender_openedStaysOpenedPastBoundary() =
        assertEquals(ViewOnceState.Opened, state(message(me, summary = summary("_vo", 1)), created + max * 2))

    @Test
    fun screenshotCount_readsOnlyVs() {
        val m = message(me, summary = summary("_vs", 2))
        assertEquals(2, ViewOnceRules.screenshotCount(m))
        assertEquals(0, ViewOnceRules.openedCount(m))
        assertEquals(ViewOnceState.Sent, state(m, created + 1))
    }

    @Test
    fun sender_screenshotTakenWhenReactionPreviewHasVs() {
        assertEquals(true, ViewOnceRules.screenshotTaken(message(me, summary = summary("_vs", 1)), me))
        assertEquals(false, ViewOnceRules.screenshotTaken(message(me, summary = summary("_vo", 1)), me))
        assertEquals(false, ViewOnceRules.screenshotTaken(message(me), me))
    }

    @Test
    fun recipient_neverCarriesTheScreenshotLine() =
        assertEquals(false, ViewOnceRules.screenshotTaken(message(alice, summary = summary("_vs", 1)), me))

    @Test
    fun signalScopesAreDistinct() {
        assertEquals("viewonce", ViewOnceSignal.openedChange().scope)
        assertEquals("viewonce_shot", ViewOnceSignal.screenshotChange().scope)
        assertEquals(setOf("_vo"), ViewOnceSignal.openedChange().add)
        assertEquals(setOf("_vs"), ViewOnceSignal.screenshotChange().add)
    }
}
