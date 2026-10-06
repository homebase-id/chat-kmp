package id.homebase.core.ui.screens.card

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import co.touchlab.kermit.Logger
import id.homebase.api.coroutines.supervisedScope
import id.homebase.api.util.truncateToCodePoints
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
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

// No saved design leaves the choice to the page, as the viewer falls back to the site default.
internal fun cardLinkUrl(odinId: String, design: String?): String =
    "${cardOrigin(odinId)}/card" + (design?.let { "?design=$it" } ?: "")

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
    fun probeEdges()
    fun requestPaint()

    // Callers keep the image (tiles, covers), so each call must return a new one, never a reused buffer.
    suspend fun snapshot(): ImageBitmap?
    fun dispose()
}

expect fun createCardHost(odinId: String): CardHost

/**
 * Shows [host]'s page. With a [layoutWidth] wider than the view, the page lays out at that width (keeping the view's
 * aspect ratio) and is drawn scaled down to fit, so a small preview reflows exactly like the full-size card.
 * Desktop lays out at the view's own size: wry exposes no zoom.
 */
@Composable
expect fun CardHostView(host: CardHost, modifier: Modifier = Modifier, layoutWidth: Dp? = null)

internal fun pageScale(viewWidth: Float, layoutWidth: Float): Float = (viewWidth / layoutWidth).coerceIn(MIN_PAGE_SCALE, 1f)

private const val MIN_PAGE_SCALE = 0.1f

/** Measures the content at [layoutWidth] and the view's aspect ratio, then draws it scaled into the view's bounds. */
internal fun Modifier.laidOutAt(layoutWidth: Dp?): Modifier =
    if (layoutWidth == null) this else clipToBounds().then(LaidOutAtElement(layoutWidth))

private data class LaidOutAtElement(val layoutWidth: Dp) : ModifierNodeElement<LaidOutAtNode>() {
    override fun create() = LaidOutAtNode(layoutWidth)

    override fun update(node: LaidOutAtNode) {
        if (node.layoutWidth == layoutWidth) return
        node.layoutWidth = layoutWidth
        node.invalidateMeasurement()
    }
}

private class LaidOutAtNode(var layoutWidth: Dp) : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val scale = pageScale(width.toFloat(), layoutWidth.toPx())
        val placeable = measurable.measure(Constraints.fixed((width / scale).roundToInt(), (height / scale).roundToInt()))
        return layout(width, height) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

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
    private var renderSent: TimeMark? = null
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
        if (_isLoaded.value) sendRender(payload)
    }

    private fun sendRender(payload: CardPayload) {
        renderSent = TimeSource.Monotonic.markNow()
        send(CardCommand.Render(payload))
    }

    override fun exportPng() {
        if (_isLoaded.value) send(CardCommand.ExportPng) else onPageError("exportPng before the page loaded")
    }

    override fun probeEdges() {
        if (_isLoaded.value) send(CardCommand.ProbeEdges)
    }

    override fun requestPaint() {
        if (_isLoaded.value) send(CardCommand.RequestPaint)
    }

    override suspend fun snapshot(): ImageBitmap? = null

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
            is CardEvent.Ready -> CardLog.info("ready layout=${event.layout} ${event.ms}ms, ${renderSent?.elapsedNow()?.inWholeMilliseconds}ms after the render was sent")
            CardEvent.Painted -> renderSent?.let {
                CardLog.info("painted ${it.elapsedNow().inWholeMilliseconds}ms after the render was sent")
                renderSent = null
            }
            is CardEvent.Png -> CardLog.info("png ${decodedSize(event.base64)}B ${event.width}x${event.height}")
            is CardEvent.Link -> CardLog.info("link ${event.href}")
            is CardEvent.Error -> CardLog.error(event.message)
            is CardEvent.Edges -> Unit
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
        lastPayload?.let(::sendRender)
    }

    private fun decodedSize(base64: String): Int =
        base64.length / 4 * 3 - base64.takeLast(2).count { it == '=' }
}
