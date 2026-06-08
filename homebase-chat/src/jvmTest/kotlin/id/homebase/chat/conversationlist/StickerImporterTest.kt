@file:OptIn(ExperimentalUuidApi::class, ExperimentalCoroutinesApi::class)

package id.homebase.chat.conversationlist

import id.homebase.resources.MR
import id.homebase.resources.chat_sticker_import_no_subject
import id.homebase.resources.chat_sticker_import_not_transparent
import id.homebase.resources.chat_sticker_save_failed
import id.homebase.resources.chat_sticker_saved
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private class Recorder {
    val infos = mutableListOf<StringResource>()
    val saved = mutableListOf<Pair<ByteArray, String>>()
    var grantOrderOk = true
    var granted = false
}

private fun importer(
    scope: kotlinx.coroutines.CoroutineScope,
    rec: Recorder,
    isTransparent: (ByteArray) -> Boolean,
    bgSupported: () -> Boolean = { true },
    cutOut: suspend (ByteArray) -> ByteArray? = { ByteArray(2) },
    saveResult: Uuid? = Uuid.random(),
) = StickerImporter(
    scope = scope,
    saveSticker = { bytes, ct ->
        if (!rec.granted) rec.grantOrderOk = false
        rec.saved += bytes to ct
        saveResult
    },
    sendInfo = { rec.infos += it },
    awaitDriveGranted = { rec.granted = true },
    isTransparent = isTransparent,
    bgRemovalSupported = bgSupported,
    cutOut = cutOut,
)

class StickerImporterTest {

    @Test fun transparent_saves_directly_no_preview() = runTest {
        val rec = Recorder()
        val imp = importer(this, rec, isTransparent = { true })
        imp.import(byteArrayOf(1), "image/png")
        advanceUntilIdle()
        assertEquals(1, rec.saved.size)
        assertEquals(MR.string.chat_sticker_saved, rec.infos.single())
        assertNull(imp.preview.value)
        assertTrue(rec.grantOrderOk)
    }

    @Test fun opaque_supported_with_subject_goes_to_ready_without_saving() = runTest {
        val rec = Recorder()
        val imp = importer(this, rec, isTransparent = { false }, cutOut = { byteArrayOf(9) })
        imp.import(byteArrayOf(1), "image/jpeg")
        advanceUntilIdle()
        val ready = imp.preview.value
        assertTrue(ready is StickerImportPreview.Ready)
        assertEquals(0, rec.saved.size)
    }

    @Test fun opaque_ready_confirm_saves_cutout_and_clears() = runTest {
        val rec = Recorder()
        val imp = importer(this, rec, isTransparent = { false }, cutOut = { byteArrayOf(9) })
        imp.import(byteArrayOf(1), "image/jpeg")
        advanceUntilIdle()
        imp.confirm()
        advanceUntilIdle()
        assertEquals(1, rec.saved.size)
        assertEquals("image/png", rec.saved.single().second)
        assertNull(imp.preview.value)
        assertEquals(MR.string.chat_sticker_saved, rec.infos.single())
        assertTrue(rec.grantOrderOk)
    }

    @Test fun opaque_ready_cancel_does_not_save() = runTest {
        val rec = Recorder()
        val imp = importer(this, rec, isTransparent = { false }, cutOut = { byteArrayOf(9) })
        imp.import(byteArrayOf(1), "image/jpeg")
        advanceUntilIdle()
        imp.dismiss()
        advanceUntilIdle()
        assertEquals(0, rec.saved.size)
        assertNull(imp.preview.value)
    }

    @Test fun opaque_no_subject_reports_error_no_save() = runTest {
        val rec = Recorder()
        val imp = importer(this, rec, isTransparent = { false }, cutOut = { null })
        imp.import(byteArrayOf(1), "image/jpeg")
        advanceUntilIdle()
        assertEquals(0, rec.saved.size)
        assertNull(imp.preview.value)
        assertEquals(MR.string.chat_sticker_import_no_subject, rec.infos.single())
    }

    @Test fun opaque_unsupported_platform_reports_pick_transparent() = runTest {
        val rec = Recorder()
        var cutCalled = false
        val imp = importer(
            this, rec, isTransparent = { false }, bgSupported = { false },
            cutOut = { cutCalled = true; byteArrayOf(9) },
        )
        imp.import(byteArrayOf(1), "image/jpeg")
        advanceUntilIdle()
        assertEquals(0, rec.saved.size)
        assertNull(imp.preview.value)
        assertEquals(MR.string.chat_sticker_import_not_transparent, rec.infos.single())
        assertTrue(!cutCalled)
    }

    @Test fun cancel_mid_processing_cancels_and_never_saves() = runTest {
        val rec = Recorder()
        val gate = CompletableDeferred<ByteArray?>()
        val imp = importer(this, rec, isTransparent = { false }, cutOut = { gate.await() })
        imp.import(byteArrayOf(1), "image/jpeg")
        runCurrent()
        assertEquals(StickerImportPreview.Processing, imp.preview.value)
        imp.dismiss()
        advanceUntilIdle()
        assertNull(imp.preview.value)
        assertEquals(0, rec.saved.size)
    }

    @Test fun save_exception_soft_fails_and_clears() = runTest {
        val rec = Recorder()
        val imp = StickerImporter(
            scope = this,
            saveSticker = { _, _ -> throw RuntimeException("boom") },
            sendInfo = { rec.infos += it },
            awaitDriveGranted = { },
            isTransparent = { true },
            bgRemovalSupported = { true },
            cutOut = { byteArrayOf() },
        )
        imp.import(byteArrayOf(1), "image/png")
        advanceUntilIdle()
        assertEquals(MR.string.chat_sticker_save_failed, rec.infos.single())
        assertNull(imp.preview.value)
    }
}
