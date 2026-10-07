@file:OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)

package id.homebase.core.ui.screens.webdrop

import id.homebase.api.crypto.AesCbc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.ui.screens.webdrop.model.PickedDropFile
import id.homebase.core.ui.screens.webdrop.model.WebDropTtlChoice
import id.homebase.core.webdrop.WebDropDropContent
import id.homebase.core.webdrop.WebDropIntroContent
import id.homebase.core.webdrop.WebDropProtocol
import id.homebase.core.webdrop.WebDropReceiptContent
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class WebDropViewOnlyTest {

    private val driveId = Uuid.random()

    private fun fileOps() = StageFakeFileOps(
        mutableMapOf("/a.jpg" to ByteArray(40) { 1 }, "/b.pdf" to ByteArray(9) { 2 }),
    )

    private val picked = listOf(
        PickedDropFile("/a.jpg", "a.jpg", "image/jpeg", 0),
        PickedDropFile("/b.pdf", "b.pdf", "application/pdf", 0),
    )

    private suspend fun build(viewOnly: Boolean, intro: WebDropIntroContent? = null) = buildWebDrop(
        fileOps(), driveId, "frodo.example", picked, WebDropTtlChoice.BurnAfterOpen,
        intro, WebDropProtocol.ThemeClean, viewOnly, nowMs = 1_700_000_000_000,
    )

    private fun BuiltWebDropContent(built: BuiltWebDrop): WebDropDropContent =
        OdinSystemSerializer.deserialize(built.dropRequest.metadata.appData.content!!)

    private suspend fun receiptOf(built: BuiltWebDrop): WebDropReceiptContent {
        val header = built.receiptRequest.keyHeader
        val cipher = Base64.decode(built.receiptRequest.metadata.appData.content!!)
        val json = AesCbc.decrypt(cipher, header.aesKey, header.iv).decodeToString()
        return OdinSystemSerializer.deserialize(json)
    }

    @Test
    fun viewOnlyDropUploadsTheVmetaManifestAndDataKeysOnly() = runTest {
        val built = build(viewOnly = true)
        val content = BuiltWebDropContent(built)

        val keys = built.dropRequest.payloads.map { it.key }.toSet()
        assertEquals(setOf("wdr_vmeta", "wdr_dat1", "wdr_dat2"), keys)
        assertEquals(keys, content.ivs.keys)
        assertEquals(WebDropProtocol.ViewOnlyContentVersion, content.v)
        assertEquals(true, content.viewOnly)
        assertTrue(built.dropRequest.payloads.all { it.isPreEncrypted })

        val receipt = receiptOf(built)
        assertEquals(true, receipt.viewOnly)
        assertEquals(WebDropProtocol.ViewOnlyContentVersion, receipt.v)
        assertEquals(listOf("wdr_dat1", "wdr_dat2"), receipt.files.map { it.key })
    }

    @Test
    fun viewOnlyJsonCarriesTheFlagAndNoLegacyManifestKey() = runTest {
        val json = build(viewOnly = true).dropRequest.metadata.appData.content!!
        assertTrue("\"viewOnly\":true" in json, json)
        assertTrue("\"v\":2" in json, json)
        assertTrue("wdr_vmeta" in json, json)
        assertFalse("wdr_meta" in json, json)
    }

    @Test
    fun normalDropIsUnchanged() = runTest {
        val built = build(viewOnly = false, intro = WebDropIntroContent(recipientName = "Thomas"))
        val content = BuiltWebDropContent(built)

        assertEquals(setOf("wdr_meta", "wdr_dat1", "wdr_dat2"), built.dropRequest.payloads.map { it.key }.toSet())
        assertEquals(built.dropRequest.payloads.map { it.key }.toSet(), content.ivs.keys)
        assertEquals(1, content.v)
        assertNull(content.viewOnly)
        val json = built.dropRequest.metadata.appData.content!!
        assertFalse("viewOnly" in json, json)
        assertFalse("wdr_vmeta" in json, json)

        val receipt = receiptOf(built)
        assertNull(receipt.viewOnly)
        assertEquals(1, receipt.v)
    }

    @Test
    fun theComposerStateIsWhatCreateDropReceives() {
        val state = WebDropUiState(
            pickedFiles = picked, viewOnly = true, theme = WebDropProtocol.ThemeClean,
            recipientName = " ", conditions = setOf(WebDropProtocol.ConditionNoRetention),
        )
        val request = state.toCreateRequest()
        assertTrue(request.viewOnly)
        assertEquals(picked, request.files)
        assertEquals(listOf(WebDropProtocol.ConditionNoRetention), request.intro?.conditions)
        assertFalse(state.copy(viewOnly = false).toCreateRequest().viewOnly)
    }

    @Test
    fun dismissingTheComposerResetsViewOnlyButKeepsTheTheme() {
        val state = WebDropUiState(
            composeOpen = true, pickedFiles = picked, viewOnly = true,
            theme = WebDropProtocol.ThemeClean,
        )
        val after = state.afterComposeDismissed()
        assertFalse(after.viewOnly)
        assertFalse(after.composeOpen)
        assertEquals(WebDropProtocol.ThemeClean, after.theme)
    }
}
