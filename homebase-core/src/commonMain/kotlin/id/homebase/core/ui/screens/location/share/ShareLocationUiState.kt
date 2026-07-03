package id.homebase.core.ui.screens.location.share

import org.jetbrains.compose.resources.StringResource

/**
 * State for the full-screen share-location screen (#966): a pannable map with a fixed center pin
 * whose address resolves as the user pans, plus static-send and live-share controls.
 */
data class ShareLocationUiState(
    val showMapTiles: Boolean = true,
    /**
     * Unit-space `[minX,minY,maxX,maxY]` the map fits on entry (a zero-span point bbox — the
     * map widens it to a city-block zoom). List (not DoubleArray) so data-class equality works.
     * Null until a position source exists; the map shows the world view meanwhile.
     */
    val initialBbox: List<Double>? = null,
    /** Bumped to re-fit the map to [initialBbox] (e.g. a late first GPS fix refines the seed). */
    val initialBboxKey: Int = 0,
    /** The panned-to coordinates under the center pin — what a static send will share. */
    val pinLat: Double? = null,
    val pinLon: Double? = null,
    /** Resolved address for the pin ("" until the first resolve). */
    val address: String = "",
    val isResolvingAddress: Boolean = false,
    val comment: String = "",
    val isSending: Boolean = false,
    val isAcquiringFix: Boolean = false,
    val showEnableLocationDialog: Boolean = false,
    /** One-shot camera move (GPS re-center); the screen applies it and calls recenterConsumed(). */
    val recenterTarget: RecenterTarget? = null,
)

/** Unit-space camera target; [seq] distinguishes consecutive re-centers to the same spot. */
data class RecenterTarget(val unitX: Double, val unitY: Double, val seq: Int)

sealed interface ShareLocationUiEvent {
    /** The location message went out — the screen pops back to the conversation. */
    data object MessageSent : ShareLocationUiEvent

    data class ShowError(val res: StringResource) : ShareLocationUiEvent
}
