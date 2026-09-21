package id.homebase.core.ui.screens.card

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class CardHostBaseTest {
    private val host = FakeCardHost()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        host.dispose()
        Dispatchers.resetMain()
    }

    @Test
    fun renderBeforeLoadIsQueuedUntilLoaded() {
        host.render(payload(CardDesign.BOARD))
        assertTrue(host.evaluated.isEmpty())

        host.onBridgeMessage("""{"type":"loaded"}""")

        assertTrue(host.isLoaded.value)
        assertEquals(1, host.evaluated.size)
        assertTrue(host.evaluated.single().startsWith("window.homebaseCard.render("))
    }

    @Test
    fun renderAfterLoadGoesStraightToThePage() {
        host.onBridgeMessage("""{"type":"loaded"}""")
        assertTrue(host.evaluated.isEmpty())

        host.render(payload(CardDesign.POSTER))

        assertEquals(1, host.evaluated.size)
    }

    @Test
    fun reloadedPageGetsTheLastCardAgain() {
        host.onBridgeMessage("""{"type":"loaded"}""")
        host.render(payload(CardDesign.POSTER))
        host.onBridgeMessage("""{"type":"ready","layout":"poster","ms":5}""")

        host.reload()
        assertFalse(host.isLoaded.value)
        host.onBridgeMessage("""{"type":"loaded"}""")

        assertEquals(2, host.evaluated.size)
        assertEquals(host.evaluated[0], host.evaluated[1])
    }

    @Test
    fun exportPngBeforeLoadIsAnErrorNotASilentNoOp() = runTest {
        val next = async(start = CoroutineStart.UNDISPATCHED) { host.events.first() }

        host.exportPng()

        assertIs<CardEvent.Error>(next.await())
        assertTrue(host.evaluated.isEmpty())
    }

    @Test
    fun pageEventsAreForwarded() = runTest {
        val next = async(start = CoroutineStart.UNDISPATCHED) { host.events.first() }

        host.onBridgeMessage("""{"type":"link","href":"https://a.b"}""")

        assertEquals(CardEvent.Link("https://a.b"), next.await())
    }

    @Test
    fun unreadableEventSurfacesAsError() = runTest {
        val next = async(start = CoroutineStart.UNDISPATCHED) { host.events.first() }

        host.onBridgeMessage("{not json")

        assertIs<CardEvent.Error>(next.await())
    }

    @Test
    fun aPageThatFailsToLoadSurfacesAsAnError() = runTest {
        val failing = FakeCardHost(readPage = { throw PageFetchFailure() })
        val next = async(start = CoroutineStart.UNDISPATCHED) { failing.events.first() }

        failing.load()

        val error = assertIs<CardEvent.Error>(next.await())
        assertTrue("fetch failed" in error.message, error.message)
        assertFalse(failing.isLoaded.value)
        failing.dispose()
    }

    @Test
    fun disposingMidLoadIsNotAnError() = runTest {
        val loading = FakeCardHost(readPage = { awaitCancellation() })
        val events = mutableListOf<CardEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { loading.events.collect { events += it } }

        loading.load()
        loading.dispose()

        assertTrue(events.isEmpty(), "$events")
    }

    private fun payload(design: String) = CardPayload(design = design, data = CardData(odinId = "frodo.dotyou.cloud"))

    // What wasmJs throws for a failed fetch: a JsException, which is a Throwable but not an Exception.
    private class PageFetchFailure : Throwable("fetch failed")

    private class FakeCardHost(readPage: suspend () -> String = { "<html></html>" }) : CardHostBase(readPage) {
        val evaluated = mutableListOf<String>()

        fun load() = loadPage()

        fun reload() = loadPage()

        override fun loadHtml(html: String) = Unit
        override fun evaluateNow(js: String) {
            evaluated += js
        }
        override fun release() = Unit
    }
}
