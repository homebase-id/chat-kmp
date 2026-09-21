package id.homebase.core.ui.screens.card

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import co.touchlab.kermit.Logger
import id.homebase.api.coroutines.supervisedScope
import id.homebase.api.util.truncateToCodePoints
import id.homebase.resources.MR
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi

const val CARD_BASE_URL = "https://card.homebase.local/"
internal const val CARD_HTML_PATH = "files/card/card.html"

/**
 * One card page kept alive independently of any [CardHostView], so it can be created (and the page
 * loaded) before the card screen shows and reused across screens. Main thread only.
 */
interface CardHost {
    val events: SharedFlow<CardEvent>
    val isLoaded: StateFlow<Boolean>
    fun render(payload: CardPayload)
    fun exportPng()
    fun dispose()
}

expect fun createCardHost(): CardHost

@Composable
expect fun CardHostView(host: CardHost, modifier: Modifier = Modifier)

internal object CardLog {
    private const val TAG = "CardHost"

    fun info(message: String) = Logger.i(tag = TAG) { message }

    fun error(message: String, throwable: Throwable? = null) =
        Logger.e(tag = TAG, throwable = throwable) { message }
}

private val cardHtmlLock = Mutex()
private var cardHtml: String? = null

@OptIn(ExperimentalResourceApi::class)
private suspend fun readCardHtml(): String = cardHtmlLock.withLock {
    cardHtml ?: withContext(Dispatchers.Default) { MR.readBytes(CARD_HTML_PATH).decodeToString() }
        .also { cardHtml = it }
}

internal abstract class CardHostBase(
    private val readPage: suspend () -> String = ::readCardHtml,
) : CardHost {
    private val _events = MutableSharedFlow<CardEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<CardEvent> = _events.asSharedFlow()

    private val _isLoaded = MutableStateFlow(false)
    override val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    protected val scope = supervisedScope("card-host", Dispatchers.Main)

    private var lastPayload: CardPayload? = null

    protected abstract fun loadHtml(html: String)
    protected abstract fun evaluateNow(js: String)
    protected abstract fun release()

    protected fun loadPage() {
        _isLoaded.value = false
        scope.launch {
            try {
                loadHtml(readPage())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable, not Exception: a failed fetch on wasmJs is a JsException, which isn't an Exception.
                onPageError("loading $CARD_HTML_PATH failed: $e", e)
            }
        }
    }

    override fun render(payload: CardPayload) {
        lastPayload = payload
        if (_isLoaded.value) evaluateNow(renderScript(payload))
    }

    override fun exportPng() {
        if (_isLoaded.value) evaluateNow(EXPORT_PNG_SCRIPT) else onPageError("exportPng before the page loaded")
    }

    override fun dispose() {
        if (!scope.isActive) return
        scope.cancel()
        _isLoaded.value = false
        release()
    }

    internal fun onBridgeMessage(json: String) {
        val event = parseCardEvent(json)
        if (event == null) {
            onPageError("unreadable card event ${json.truncateToCodePoints(200)}")
            return
        }
        when (event) {
            CardEvent.Loaded -> onLoaded()
            is CardEvent.Ready -> CardLog.info("ready layout=${event.layout} ${event.ms}ms")
            is CardEvent.Png -> CardLog.info("png ${decodedSize(event.base64)}B ${event.width}x${event.height}")
            is CardEvent.Link -> CardLog.info("link ${event.href}")
            is CardEvent.Error -> CardLog.error(event.message)
        }
        _events.tryEmit(event)
    }

    internal fun onPageError(message: String, throwable: Throwable? = null) {
        CardLog.error(message, throwable)
        _events.tryEmit(CardEvent.Error(message))
    }

    private fun onLoaded() {
        CardLog.info("loaded")
        _isLoaded.value = true
        // Covers a render queued before load and a page that reloaded under a rendered card.
        lastPayload?.let { evaluateNow(renderScript(it)) }
    }

    private fun decodedSize(base64: String): Int =
        base64.length / 4 * 3 - base64.takeLast(2).count { it == '=' }
}
