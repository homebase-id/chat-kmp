package id.homebase.chat.conversationlist

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.chat.services.sticker.SavedSticker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Guards the sticker-send fix (bug: "takes 5 seconds and each press sends a sticker").
 *
 * The slow part of a send is [StickerHandler]'s `resolveStickerForSend` — a cold drive
 * fetch that can take seconds the first time. Without an in-flight guard, every tap during
 * that window launched another full send. These tests pin the guard's three behaviours:
 * repeated taps on the SAME sticker while one is in flight send once; a deliberate re-send
 * after completion works; different stickers are not blocked by each other.
 *
 * Uses [UnconfinedTestDispatcher] so the handler's fire-and-forget `scope.launch` runs
 * eagerly up to its first real suspension — making the in-flight window deterministic
 * without manual scheduler advancement.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StickerSendGuardTest {

    private fun savedSticker(uniqueId: Uuid = Uuid.random()) = SavedSticker(
        fileId = Uuid.random(),
        uniqueId = uniqueId,
        driveId = Uuid.random(),
        payloadKey = "stk",
        contentType = "image/png",
        keyHeader = KeyHeader.newRandom16(),
        previewThumbnail = null,
        payloadDescriptor = PayloadDescriptor(key = "stk", contentType = "image/png"),
        createdAt = 0L,
    )

    private fun handler(
        scope: CoroutineScope,
        resolve: suspend (SavedSticker) -> String?,
        onSend: () -> Unit,
    ) = StickerHandler(
        scope = scope,
        messagesUiState = MutableStateFlow(MessageListUiState()),
        sendEvent = {},
        addMessageWithFiles = { _, _, _ -> onSend() },
        resolveStickerForSend = resolve,
        saveStickerBytes = { _, _, _ -> null },
        deleteSticker = { false },
        getPayloadBytes = { _, _, _ -> null },
        savedStickers = { emptyList() },
        awaitDriveGranted = {},
    )

    private fun send(h: StickerHandler, sticker: SavedSticker) =
        h.handleSendSavedSticker(ConversationListUiAction.SendSavedSticker(Uuid.random(), sticker))

    @Test
    fun rapidTaps_sameSticker_inFlight_sendOnce() = runTest {
        var sends = 0
        val gate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val h = handler(scope, resolve = { gate.await(); "/tmp/s.png" }, onSend = { sends++ })
        val sticker = savedSticker()

        send(h, sticker)       // runs eagerly, suspends in resolve at the gate
        send(h, sticker)       // second tap while the first is in flight -> guarded
        gate.complete(Unit)    // first resolve completes -> send proceeds

        assertEquals(1, sends, "rapid taps on the same sticker while in flight should send once")
        scope.cancel()
    }

    @Test
    fun resend_afterCompletion_allowed() = runTest {
        var sends = 0
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val h = handler(scope, resolve = { "/tmp/s.png" }, onSend = { sends++ })
        val sticker = savedSticker()

        send(h, sticker)       // completes eagerly (no suspension)
        send(h, sticker)       // deliberate re-send after the first completed

        assertEquals(2, sends, "a deliberate re-send after completion should work")
        scope.cancel()
    }

    @Test
    fun differentStickers_notGuarded() = runTest {
        var sends = 0
        val gate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val h = handler(scope, resolve = { gate.await(); "/tmp/s.png" }, onSend = { sends++ })

        send(h, savedSticker())
        send(h, savedSticker()) // different sticker, also in flight
        gate.complete(Unit)

        assertEquals(2, sends, "different stickers should each send")
        scope.cancel()
    }
}
