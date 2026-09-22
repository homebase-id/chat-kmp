package id.homebase.core.ui.screens.card

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import co.touchlab.kermit.Logger
import id.homebase.api.coroutines.supervisedScope
import id.homebase.api.util.truncateToCodePoints
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal val CARD_LOADED_TIMEOUT = 10.seconds

internal enum class CardPageHost(val param: String) { APP("app"), FRAME("frame") }

internal fun cardOrigin(odinId: String): String = "https://$odinId"

internal fun cardPageUrl(odinId: String, host: CardPageHost = CardPageHost.APP): String =
    "${cardOrigin(odinId)}/card?host=${host.param}"

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

expect fun createCardHost(odinId: String): CardHost

@Composable
expect fun CardHostView(host: CardHost, modifier: Modifier = Modifier)

internal object CardLog {
    private const val TAG = "CardHost"

    fun info(message: String) = Logger.i(tag = TAG) { message }

    fun error(message: String) = Logger.e(tag = TAG) { message }
}

internal abstract class CardHostBase(protected val pageUrl: String) : CardHost {
    private val _events = MutableSharedFlow<CardEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<CardEvent> = _events.asSharedFlow()

    private val _isLoaded = MutableStateFlow(false)
    override val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    protected val scope = supervisedScope("card-host", Dispatchers.Main)

    private var lastPayload: CardPayload? = null
    private var mainFrameSettled = false
    private var loadedTimeout: Job? = null

    protected abstract fun loadUrl(url: String)
    protected abstract fun send(command: CardCommand)
    protected abstract fun release()

    protected fun loadPage() {
        _isLoaded.value = false
        mainFrameSettled = false
        loadedTimeout?.cancel()
        loadUrl(pageUrl)
    }

    override fun render(payload: CardPayload) {
        lastPayload = payload
        if (_isLoaded.value) send(CardCommand.Render(payload))
    }

    override fun exportPng() {
        if (_isLoaded.value) send(CardCommand.ExportPng) else onPageError("exportPng before the page loaded")
    }

    override fun dispose() {
        if (!scope.isActive) return
        scope.cancel()
        _isLoaded.value = false
        release()
    }

    // A server without the /card route serves its public site there, which never posts `loaded`.
    internal fun onMainFrameFinished() {
        if (mainFrameSettled) return
        mainFrameSettled = true
        if (_isLoaded.value) return
        loadedTimeout = scope.launch {
            delay(CARD_LOADED_TIMEOUT)
            onPageError("no loaded event $CARD_LOADED_TIMEOUT after $pageUrl finished loading", unsupported = true)
        }
    }

    internal fun onMainFrameFailed(message: String, unsupported: Boolean) {
        if (mainFrameSettled) return
        mainFrameSettled = true
        onPageError(message, unsupported)
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

    internal fun onPageError(message: String, unsupported: Boolean = false) {
        CardLog.error(message)
        _events.tryEmit(CardEvent.Error(message, unsupported))
    }

    private fun onLoaded() {
        CardLog.info("loaded")
        loadedTimeout?.cancel()
        _isLoaded.value = true
        // Covers a render queued before load and a page that reloaded under a rendered card.
        lastPayload?.let { send(CardCommand.Render(it)) }
    }

    private fun decodedSize(base64: String): Int =
        base64.length / 4 * 3 - base64.takeLast(2).count { it == '=' }
}
