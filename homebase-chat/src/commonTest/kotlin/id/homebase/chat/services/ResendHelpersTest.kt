package id.homebase.chat.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class ResendHelpersTest {

    // ---- retryRecoverability matrix ----

    @Test
    fun serverAbsentWithAllMediaAvailableIsCreate() {
        assertEquals(
            RetryRecoverability.Create,
            retryRecoverability(
                serverPresent = false,
                mediaKeys = setOf("img_key1", "img_key2"),
                availableMediaKeys = setOf("img_key1", "img_key2"),
            ),
        )
    }

    @Test
    fun serverAbsentTextOnlyIsCreate() {
        assertEquals(
            RetryRecoverability.Create,
            retryRecoverability(
                serverPresent = false,
                mediaKeys = emptySet(),
                availableMediaKeys = emptySet(),
            ),
        )
    }

    @Test
    fun serverAbsentWithEvictedMediaIsUnrecoverable() {
        assertEquals(
            RetryRecoverability.UnrecoverableMedia,
            retryRecoverability(
                serverPresent = false,
                mediaKeys = setOf("img_key1", "img_key2"),
                availableMediaKeys = setOf("img_key1"),
            ),
        )
    }

    @Test
    fun serverPresentIsAlwaysUpdate() {
        // Present wins regardless of local availability — bytes re-fetch from the server.
        assertEquals(
            RetryRecoverability.Update,
            retryRecoverability(
                serverPresent = true,
                mediaKeys = setOf("img_key1"),
                availableMediaKeys = emptySet(),
            ),
        )
        assertEquals(
            RetryRecoverability.Update,
            retryRecoverability(
                serverPresent = true,
                mediaKeys = emptySet(),
                availableMediaKeys = emptySet(),
            ),
        )
    }

    // ---- tagsForRetry ----

    private val otherTag = Uuid.parse("11111111-2222-3333-4444-555555555555")

    @Test
    fun failedTagSwapsToPending() {
        val result = tagsForRetry(listOf(otherTag, ChatProtocol.isFailedSendTag))
        assertEquals(listOf(otherTag, ChatProtocol.isPendingSendTag), result)
    }

    @Test
    fun alreadyPendingIsUnchanged() {
        val tags = listOf(otherTag, ChatProtocol.isPendingSendTag)
        assertEquals(tags, tagsForRetry(tags))
    }

    @Test
    fun failedAndPendingCollapsesToPendingOnce() {
        val result = tagsForRetry(
            listOf(ChatProtocol.isFailedSendTag, ChatProtocol.isPendingSendTag, otherTag),
        )
        assertEquals(listOf(ChatProtocol.isPendingSendTag, otherTag), result)
        assertEquals(1, result.count { it == ChatProtocol.isPendingSendTag })
    }

    @Test
    fun emptyTagsGainPending() {
        assertEquals(listOf(ChatProtocol.isPendingSendTag), tagsForRetry(emptyList()))
    }
}
