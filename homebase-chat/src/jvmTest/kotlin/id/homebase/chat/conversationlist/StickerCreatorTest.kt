@file:OptIn(ExperimentalUuidApi::class, ExperimentalCoroutinesApi::class)

package id.homebase.chat.conversationlist

import id.homebase.resources.MR
import id.homebase.resources.chat_sticker_save_failed
import id.homebase.resources.chat_sticker_saved
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private class Rec {
    val infos = mutableListOf<StringResource>()
    val saved = mutableListOf<Pair<ByteArray, String>>()
    val sent = mutableListOf<Triple<Uuid, ByteArray, String>>()
}

private fun creator(
    scope: CoroutineScope, rec: Rec,
    isTransparent: (ByteArray) -> Boolean,
    bgSupported: () -> Boolean = { true },
    cutOut: suspend (ByteArray) -> ByteArray? = { byteArrayOf(7) },
    outline: suspend (ByteArray) -> ByteArray = { it + 9 },
    saveResult: Uuid? = Uuid.random(),
) = StickerCreator(
    scope = scope,
    saveSticker = { b, ct -> rec.saved += b to ct; saveResult },
    sendSticker = { cid, b, ct -> rec.sent += Triple(cid, b, ct) },
    sendInfo = { rec.infos += it },
    awaitDriveGranted = {},
    isTransparent = isTransparent,
    bgRemovalSupported = bgSupported,
    cutOut = cutOut,
    addOutline = outline,
)

class StickerCreatorTest {
    private val convo = Uuid.random()

    @Test fun opaque_with_subject_offers_cutout_default() = runTest {
        val rec = Rec()
        val c = creator(this, rec, isTransparent = { false }, cutOut = { byteArrayOf(1) }, outline = { byteArrayOf(2) })
        c.create(byteArrayOf(0), "image/jpeg", convo); advanceUntilIdle()
        val s = c.state.value
        assertTrue(s is StickerCreateState.Choose)
        assertEquals(StickerVariant.CutOut, s.selected)
        assertTrue(s.cutOutOutlined!!.contentEquals(byteArrayOf(2)))
    }

    @Test fun transparent_source_skips_removal_outlines_source() = runTest {
        val rec = Rec(); var removeCalled = false
        val c = creator(this, rec, isTransparent = { true }, cutOut = { removeCalled = true; byteArrayOf(1) }, outline = { it + 2 })
        c.create(byteArrayOf(5), "image/png", convo); advanceUntilIdle()
        val s = c.state.value as StickerCreateState.Choose
        assertTrue(!removeCalled)
        assertTrue(s.cutOutOutlined!!.contentEquals(byteArrayOf(5, 2)))
    }

    @Test fun opaque_no_subject_only_original() = runTest {
        val rec = Rec()
        val c = creator(this, rec, isTransparent = { false }, cutOut = { null })
        c.create(byteArrayOf(0), "image/jpeg", convo); advanceUntilIdle()
        val s = c.state.value as StickerCreateState.Choose
        assertNull(s.cutOutOutlined)
        assertEquals(StickerVariant.Original, s.selected)
    }

    @Test fun unsupported_only_original_no_cutout_call() = runTest {
        val rec = Rec(); var cutCalled = false
        val c = creator(this, rec, isTransparent = { false }, bgSupported = { false }, cutOut = { cutCalled = true; byteArrayOf(1) })
        c.create(byteArrayOf(0), "image/jpeg", convo); advanceUntilIdle()
        val s = c.state.value as StickerCreateState.Choose
        assertNull(s.cutOutOutlined); assertTrue(!cutCalled)
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

    @Test fun save_failure_reports_failed() = runTest {
        val rec = Rec()
        val c = creator(this, rec, isTransparent = { false }, cutOut = { byteArrayOf(1) }, outline = { byteArrayOf(2) }, saveResult = null)
        c.create(byteArrayOf(0), "image/jpeg", convo); advanceUntilIdle()
        c.confirm(); advanceUntilIdle()
        assertEquals(MR.string.chat_sticker_save_failed, rec.infos.single())
    }
}
