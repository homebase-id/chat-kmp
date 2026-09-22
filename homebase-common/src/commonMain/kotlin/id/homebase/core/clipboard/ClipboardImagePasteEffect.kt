package id.homebase.core.clipboard

import androidx.compose.runtime.Composable

/**
 * Delivers images the user pastes with the keyboard, on platforms whose clipboard can only be
 * read from inside a paste event.
 *
 * Web is the whole reason this exists. `navigator.clipboard.read()` is user-mediated by spec, so
 * every browser — Safari, Firefox and Chrome alike — interposes its own Paste confirmation button
 * before handing the page clipboard contents. That is correct for an explicit "Paste image" menu
 * command and wrong for Cmd/Ctrl+V, where the keystroke IS the user's intent and a second
 * confirmation reads as a bug. The DOM paste event carries the same bytes with no permission and
 * no browser UI, so web listens for that instead.
 *
 * Every other platform reads the clipboard directly in the key handler and supplies a no-op here.
 */
@Composable
expect fun ClipboardImagePasteEffect(enabled: Boolean, onImagePasted: (ByteArray) -> Unit)
