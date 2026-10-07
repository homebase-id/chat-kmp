@file:OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)

package id.homebase.core.ui.screens.webdrop

import id.homebase.core.ui.screens.webdrop.model.PickedDropFile
import id.homebase.core.ui.screens.webdrop.model.WebDropTtlChoice
import id.homebase.core.webdrop.WebDropProtocol
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/** Emits the real sender output (content JSON, ciphertext payloads, link key) for the web-drop-app cross-repo test. */
class WebDropGoldenFixtureTest {

    private val picked = listOf(PickedDropFile("/a.txt", "a.txt", "text/plain", 0))

    private suspend fun emit(name: String, viewOnly: Boolean) {
        val fs = StageFakeFileOps(mutableMapOf("/a.txt" to "hello golden".encodeToByteArray()))
        val built = buildWebDrop(
            fs, Uuid.random(), "frodo.example", picked, WebDropTtlChoice.BurnAfterOpen,
            null, WebDropProtocol.ThemeClean, viewOnly, nowMs = 1_700_000_000_000,
        )
        val content = built.dropRequest.metadata.appData.content!!
        val payloads = built.dropRequest.payloads.joinToString(",") { p ->
            "\"${p.key}\":\"${Base64.encode(fs.files[p.filePath]!!)}\""
        }
        val fragment = built.url.substringAfter('#')
        val json = "{\"keyB64Url\":\"$fragment\",\"content\":${quote(content)},\"payloads\":{$payloads}}"
        val dir = File(System.getProperty("webdrop.fixture.dir") ?: "build/webdrop-golden").apply { mkdirs() }
        File(dir, "$name.json").writeText(json)
        assertTrue(File(dir, "$name.json").length() > 0)
    }

    private fun quote(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\")
            else -> append(c)
        }
        append('"')
    }

    @Test fun emitViewOnly() = runTest { emit("viewonly", true) }
    @Test fun emitNormal() = runTest { emit("normal", false) }
}
