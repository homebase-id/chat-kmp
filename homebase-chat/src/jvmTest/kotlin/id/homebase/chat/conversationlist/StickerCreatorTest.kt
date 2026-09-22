@file:OptIn(ExperimentalUuidApi::class, ExperimentalCoroutinesApi::class)

package id.homebase.chat.conversationlist

import id.homebase.resources.MR
import id.homebase.resources.chat_sticker_save_failed
import id.homebase.resources.chat_sticker_saved
import id.homebase.resources.chat_sticker_send_failed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** The test scope's own dispatcher, so StickerCreator's withContext(workDispatcher) stays on the
 *  test scheduler and advanceUntilIdle drives it deterministically. */
private fun CoroutineScope.testDispatcher(): CoroutineDispatcher =
    coroutineContext[ContinuationInterceptor] as CoroutineDispatcher

private class Rec {
    val infos = mutableListOf<StringResource>()
    val saved = mutableListOf<Pair<ByteArray, String>>()
    val sent = mutableListOf<Triple<Uuid, ByteArray, String>>()
    var driveAwaits = 0
}

private fun creator(
    scope: CoroutineScope, rec: Rec,
    isTransparent: (ByteArray) -> Boolean,
    bgSupported: () -> Boolean = { true },
    cutOut: suspend (ByteArray) -> ByteArray? = { byteArrayOf(7) },
    crop: suspend (ByteArray) -> ByteArray = { it }, // identity by default; bg-removal branch crops before outline
    outline: suspend (ByteArray) -> ByteArray = { it + 9 },
    saveResult: Uuid? = Uuid.random(),
    normalize: suspend (ByteArray, String) -> Pair<ByteArray, String> = { b, ct -> b to ct },
    send: suspend (Uuid, ByteArray, String) -> Unit = { cid, b, ct -> rec.sent += Triple(cid, b, ct) },
) = StickerCreator(
    scope = scope,
    saveSticker = { b, ct -> rec.saved += b to ct; saveResult },
    sendSticker = send,
    sendInfo = { rec.infos += it },
    awaitDriveGranted = { rec.driveAwaits++ },
    isTransparent = isTransparent,
    bgRemovalSupported = bgSupported,
    cutOut = cutOut,
    cropToSubject = crop,
    addOutline = outline,
    normalize = normalize,
    workDispatcher = scope.testDispatcher(),
)

class StickerCreatorTest {
    private val convo = Uuid.random()

    @Test fun opaque_with_subject_offers_cutout_default() = runTest {
        val rec = Rec()
        val c = creator(this, rec, isTransparent = { false }, cutOut = { byteArrayOf(1) }, outline = { byteArrayOf(2) })
        c.create(byteArrayOf(0), "image/jpeg", convo); advanceUntilIdle()
        val s = c.state.value
        assertTrue(s is StickerCreateState.Choose)
        val cut = s.variants.first { it.kind == StickerVariant.CutOut }
        assertTrue(cut.bytes.contentEquals(byteArrayOf(2)))
        assertEquals(StickerVariant.CutOut, s.selected)
    }

    @Test fun opaque_with_subject_crops_before_outline() = runTest {
        // bg-removal branch must run cropToSubject on the mask BEFORE addWhiteOutline, so the
        // outline radius / 512 cap are sized to the cropped subject, not the full frame.
        val rec = Rec(); var cropInput: ByteArray? = null; var outlineInput: ByteArray? = null
        val c = creator(
            this, rec, isTransparent = { false },
            cutOut = { byteArrayOf(1) },
            crop = { cropInput = it; byteArrayOf(2) },
            outline = { outlineInput = it; byteArrayOf(3) },
        )
        c.create(byteArrayOf(0), "image/jpeg", convo); advanceUntilIdle()
        val s = c.state.value as StickerCreateState.Choose
        assertTrue(cropInput!!.contentEquals(byteArrayOf(1)), "crop receives the raw mask from cutOut")
        assertTrue(outlineInput!!.contentEquals(byteArrayOf(2)), "outline receives the cropped bytes, not the raw mask")
        assertTrue(s.variants.first { it.kind == StickerVariant.CutOut }.bytes.contentEquals(byteArrayOf(3)))
    }

    @Test fun transparent_source_skips_removal_outlines_source() = runTest {
        val rec = Rec(); var removeCalled = false
        val c = creator(this, rec, isTransparent = { true }, cutOut = { removeCalled = true; byteArrayOf(1) }, outline = { it + 2 })
        c.create(byteArrayOf(5), "image/png", convo); advanceUntilIdle()
        val s = c.state.value as StickerCreateState.Choose
        assertTrue(!removeCalled)
        assertTrue(s.variants.first { it.kind == StickerVariant.CutOut }.bytes.contentEquals(byteArrayOf(5, 2)))
    }

    @Test fun opaque_no_subject_only_original() = runTest {
        val rec = Rec()
        val c = creator(this, rec, isTransparent = { false }, cutOut = { null })
        c.create(byteArrayOf(0), "image/jpeg", convo); advanceUntilIdle()
        val s = c.state.value as StickerCreateState.Choose
        assertTrue(s.variants.none { it.kind == StickerVariant.CutOut })
        assertEquals(StickerVariant.Original, s.selected)
    }

    @Test fun animated_source_only_original_without_probing() = runTest {
        val rec = Rec(); var probed = false
        val c = creator(this, rec, isTransparent = { probed = true; true })
        // 2x1, two-frame GIF.
        val gif = java.util.Base64.getDecoder().decode(
            "R0lGODlhAgABAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAAAgABAAAIBQABAAgIACH5" +
                "BAAKAAAALAAAAAACAAEAgQAA/wAAAAAAAAAAAAgFAAEACAgAOw=="
        )
        c.create(gif, "image/gif", convo); advanceUntilIdle()
        val s = c.state.value as StickerCreateState.Choose
        assertEquals(listOf(StickerVariant.Original), s.variants.map { it.kind })
        assertTrue(!probed)
    }

    @Test fun send_normalizes_and_sends_once_without_saving_or_awaiting_the_drive() = runTest {
        val rec = Rec()
        val gif = byteArrayOf(0x47, 0x49, 0x46)
        val normalized = byteArrayOf(0x47, 0x49)
        val c = creator(this, rec, isTransparent = { false },
            normalize = { b, ct -> if (b.contentEquals(gif)) normalized to ct else b to ct })
        c.send(convo, gif, "image/gif")
        advanceUntilIdle()
        val sent = rec.sent.single()
        assertEquals(convo, sent.first); assertTrue(sent.second.contentEquals(normalized)); assertEquals("image/gif", sent.third)
        assertTrue(rec.saved.isEmpty()); assertEquals(0, rec.driveAwaits); assertTrue(rec.infos.isEmpty())
    }

    @Test fun send_only_failure_reports_send_failed() = runTest {
        val rec = Rec()
        val c = creator(this, rec, isTransparent = { false }, send = { _, _, _ -> throw RuntimeException("send boom") })
        c.send(convo, byteArrayOf(0x47, 0x49, 0x46), "image/gif")
        advanceUntilIdle()
        assertTrue(rec.saved.isEmpty())
        assertEquals(MR.string.chat_sticker_send_failed, rec.infos.single())
    }

    @Test fun unsupported_only_original_no_cutout_call() = runTest {
        val rec = Rec(); var cutCalled = false
        val c = creator(this, rec, isTransparent = { false }, bgSupported = { false }, cutOut = { cutCalled = true; byteArrayOf(1) })
        c.create(byteArrayOf(0), "image/jpeg", convo); advanceUntilIdle()
        val s = c.state.value as StickerCreateState.Choose
        assertTrue(s.variants.none { it.kind == StickerVariant.CutOut }); assertTrue(!cutCalled)
    }

    @Test fun confirm_cutout_saves_and_sends_png_then_clears() = runTest {
        val rec = Rec()
        val c = creator(this, rec, isTransparent = { false }, cutOut = { byteArrayOf(1) }, outline = { byteArrayOf(2) })
        c.create(byteArrayOf(0), "image/jpeg", convo); advanceUntilIdle()
        c.confirm(); advanceUntilIdle()
        assertEquals(1, rec.saved.size)
        assertTrue(rec.saved[0].first.contentEquals(byteArrayOf(2)))
        assertEquals("image/png", rec.saved[0].second)
        assertEquals(1, rec.sent.size)
        assertEquals(convo, rec.sent[0].first)
        assertTrue(rec.sent[0].second.contentEquals(byteArrayOf(2)))
        assertEquals(MR.string.chat_sticker_saved, rec.infos.single())
        assertEquals(1, rec.driveAwaits)
        assertNull(c.state.value)
    }

    @Test fun select_original_then_confirm_saves_and_sends_original() = runTest {
        val rec = Rec()
        val c = creator(this, rec, isTransparent = { false }, cutOut = { byteArrayOf(1) }, outline = { byteArrayOf(2) })
        c.create(byteArrayOf(0, 0), "image/jpeg", convo); advanceUntilIdle()
        c.selectVariant(StickerVariant.Original); c.confirm(); advanceUntilIdle()
        assertTrue(rec.saved[0].first.contentEquals(byteArrayOf(0, 0)))
        assertEquals("image/jpeg", rec.saved[0].second)
        assertTrue(rec.sent[0].second.contentEquals(byteArrayOf(0, 0)))
    }

    @Test fun dismiss_neither_saves_nor_sends() = runTest {
        val rec = Rec()
        val c = creator(this, rec, isTransparent = { false }, cutOut = { byteArrayOf(1) })
        c.create(byteArrayOf(0), "image/jpeg", convo); advanceUntilIdle()
        c.dismiss(); advanceUntilIdle()
        assertEquals(0, rec.saved.size); assertEquals(0, rec.sent.size); assertNull(c.state.value)
    }

    @Test fun cancel_during_processing_clears_state_and_never_saves() = runTest {
        // Suspend runCreate in the heavy phase: cutOut never returns, so the flow parks in
        // Processing (spinner showing). Dismissing must cancel cleanly — clear the state and
        // save/send nothing — rather than leak the job or strand the spinner.
        val rec = Rec()
        val gate = CompletableDeferred<Unit>()
        val c = creator(
            this, rec, isTransparent = { false },
            cutOut = { gate.await(); byteArrayOf(1) },
            outline = { byteArrayOf(2) },
        )
        c.create(byteArrayOf(0), "image/jpeg", convo); advanceUntilIdle()
        assertTrue(c.state.value is StickerCreateState.Processing)
        c.dismiss(); advanceUntilIdle()
        assertNull(c.state.value)
        assertEquals(0, rec.saved.size); assertEquals(0, rec.sent.size)
    }

    @Test fun save_failure_reports_failed() = runTest {
        val rec = Rec()
        val c = creator(this, rec, isTransparent = { false }, cutOut = { byteArrayOf(1) }, outline = { byteArrayOf(2) }, saveResult = null)
        c.create(byteArrayOf(0), "image/jpeg", convo); advanceUntilIdle()
        c.confirm(); advanceUntilIdle()
        assertEquals(MR.string.chat_sticker_save_failed, rec.infos.single())
    }

    @Test fun confirm_normalizes_bytes_before_save_and_send() = runTest {
        val rec = Rec()
        val heic = byteArrayOf(0x68, 0x65) // pretend-HEIC
        val jpeg = byteArrayOf(0x6A, 0x70)
        val c = creator(this, rec, isTransparent = { false }, cutOut = { byteArrayOf(1) }, outline = { byteArrayOf(2) },
            normalize = { b, ct -> if (b.contentEquals(heic)) jpeg to "image/jpeg" else b to ct })
        c.create(heic, "image/heic", convo); advanceUntilIdle()
        c.selectVariant(StickerVariant.Original); c.confirm(); advanceUntilIdle()
        // both save and send receive the normalized JPEG bytes + content type (not raw HEIC)
        assertTrue(rec.saved[0].first.contentEquals(jpeg)); assertEquals("image/jpeg", rec.saved[0].second)
        assertTrue(rec.sent[0].second.contentEquals(jpeg)); assertEquals("image/jpeg", rec.sent[0].third)
    }

    @Test fun send_failure_reports_send_failed() = runTest {
        val rec = Rec()
        val c = StickerCreator(
            scope = this,
            saveSticker = { b, ct -> rec.saved += b to ct; Uuid.random() },
            sendSticker = { _, _, _ -> throw RuntimeException("send boom") },
            sendInfo = { rec.infos += it },
            awaitDriveGranted = {},
            isTransparent = { false }, bgRemovalSupported = { true },
            cutOut = { byteArrayOf(1) }, cropToSubject = { it }, addOutline = { byteArrayOf(2) },
            normalize = { b, ct -> b to ct },
            workDispatcher = testDispatcher(),
        )
        c.create(byteArrayOf(0), "image/jpeg", convo); advanceUntilIdle()
        c.confirm(); advanceUntilIdle()
        assertEquals(MR.string.chat_sticker_send_failed, rec.infos.single())
    }
}
