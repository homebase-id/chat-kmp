package id.homebase.chat.conversationlist

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.MessageAppData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Regression guard for the cold-start "own messages render grey" bug: the message bubble's
 * side is decided by `MessageUiModel.isAuthoredBy(uiState.ownerSession?.odinId)`, and
 * `ownerSessionRepository.user` stays null until the connect chain reaches `loadProfile()`.
 * Feeding [MessageListUiState] the raw repository flow made every own bubble render as a
 * peer's for the length of that chain; the credential domain is known locally the whole time.
 */
class MessageListOwnerSessionFallbackTest {

    private val me = OdinId("owner.test")
    private val peer = OdinId("peer.test")

    private fun creds(domain: OdinId = me): ApiCredentials = ApiCredentials.create(
        domain = domain,
        clientAccessToken = "token",
        sharedSecret = SecureByteArray(byteArrayOf(1, 2, 3)),
    )

    private fun liveSession(domain: OdinId = me) = OwnerSession(
        odinId = domain,
        displayName = "Owner",
        firstName = "Owen",
        surName = "Er",
        profileImageFileId = "fid",
        profileImageFileKey = "fkey",
        profileImagePreviewThumbnail = "thumb",
        profileImageLastModified = 123L,
        status = "around",
    )

    private fun message(author: OdinId) = MessageUiModel(
        id = Uuid.random(),
        globalTransitId = null,
        fileId = Uuid.random(),
        conversationId = Uuid.random(),
        content = "hello",
        userDate = Instant.fromEpochMilliseconds(0),
        modified = null,
        created = Instant.fromEpochMilliseconds(0),
        originalAuthor = author,
        sender = author,
        displayName = author.domainName,
        localReadTimestamp = null,
        isDeleted = false,
        isPendingSend = false,
        versionTag = Uuid.NIL,
        messageAppData = MessageAppData(),
        reactionPreview = null,
        previewThumbnail = null,
        payloads = null,
        keyHeader = KeyHeader.empty(),
        hasMore = false,
    )

    private suspend fun messageListState(
        live: OwnerSession?,
        credentials: ApiCredentials?,
    ): MessageListUiState = MessageListUiState(
        ownerSession = effectiveOwnerSessionFlow(
            live = MutableStateFlow(live),
            credentials = MutableStateFlow(credentials),
        ).first()
    )

    @Test
    fun ownMessageIsAuthoredByMe_beforeTheProfileLoadCompletes() = runTest {
        val state = messageListState(live = null, credentials = creds())

        assertEquals(me, state.ownerSession?.odinId)
        assertTrue(
            message(author = me).isAuthoredBy(state.ownerSession?.odinId),
            "own message must render as own before any network call completes",
        )
    }

    @Test
    fun peerMessageIsNotAuthoredByMe_beforeTheProfileLoadCompletes() = runTest {
        val state = messageListState(live = null, credentials = creds())

        assertEquals(
            false,
            message(author = peer).isAuthoredBy(state.ownerSession?.odinId),
        )
    }

    @Test
    fun noCredentialsYet_leavesTheSessionNull() = runTest {
        val state = messageListState(live = null, credentials = null)

        assertEquals(null, state.ownerSession)
    }

    @Test
    fun resolvedSessionWins_onceTheProfileLoadLands() = runTest {
        val state = messageListState(live = liveSession(), credentials = creds())

        assertEquals("Owner", state.ownerSession?.displayName)
        assertEquals("thumb", state.ownerSession?.profileImagePreviewThumbnail)
    }
}
