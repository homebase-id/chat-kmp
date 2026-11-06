package id.homebase.core.ui.screens.filesync

import androidx.compose.ui.input.pointer.PointerEvent

/**
 * Whether [this] is the press of the mouse's back (side) button, for the app to handle itself.
 * Desktop only: Android already turns that button into system Back, a browser keeps it for its own Back,
 * and iOS has no such button, so elsewhere it's always false.
 */
internal expect fun PointerEvent.isMouseBackPress(): Boolean
