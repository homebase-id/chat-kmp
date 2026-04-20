package id.homebase.chat.conversationlist

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Locks down the preference order in [synthesizeOwnerSession]: the
 * live (fully-resolved) session must win over the credential-derived
 * fallback. Inverting the order would replace a resolved display
 * name / profile image with a bare odinId on every emit.
 */
class ConversationListSessionSynthesisTest {

    private val me = OdinId("owner.test")

    private val liveSession = OwnerSession(
        odinId = me,
        displayName = "Owner",
        firstName = "Owen",
        surName = "Er",
        profileImageFileId = "fid",
        profileImageFileKey = "fkey",
        profileImagePreviewThumbnail = "thumb",
        profileImageLastModified = 123L,
        status = "around",
    )

    private fun creds(domain: OdinId = me): ApiCredentials = ApiCredentials.create(
        domain = domain,
        clientAccessToken = "token",
        sharedSecret = SecureByteArray(byteArrayOf(1, 2, 3)),
    )

    @Test
    fun nullLive_nullCredentials_returnsNull() {
        assertNull(synthesizeOwnerSession(live = null, credentials = null))
    }

    @Test
    fun nullLive_withCredentials_synthesizesMinimalSession() {
        val result = synthesizeOwnerSession(live = null, credentials = creds())

        assertEquals(me, result?.odinId)
        // All profile fields are null in the synthesized fallback —
        // the UI falls back to odinId.domainName at render time.
        assertNull(result?.displayName)
        assertNull(result?.firstName)
        assertNull(result?.surName)
        assertNull(result?.profileImageFileId)
        assertNull(result?.profileImageFileKey)
        assertNull(result?.profileImagePreviewThumbnail)
        assertNull(result?.profileImageLastModified)
        assertNull(result?.status)
    }

    @Test
    fun liveSession_nullCredentials_returnsLive() {
        val result = synthesizeOwnerSession(live = liveSession, credentials = null)
        assertSame(liveSession, result)
    }

    @Test
    fun liveSession_withCredentials_prefersLive() {
        // Preference invariant: even when credentials are available,
        // the fully-resolved live session wins. Inverting this would
        // clobber the display name / profile image.
        val result = synthesizeOwnerSession(
            live = liveSession,
            credentials = creds(domain = OdinId("different.test")),
        )
        assertSame(liveSession, result)
    }
}
