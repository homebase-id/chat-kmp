# Handoff: clipboard image paste — status & remaining gap

**Updated:** 2026-06-06. **Branch:** `clipboard-paste-ios-and-gif-fidelity` (PR #667).

## What ships now
- **No "Paste" button anywhere.** The attachment-sheet Paste item (`onPasteClick`)
  is removed on all platforms.
- **Paste = the keyboard shortcut, everywhere it can fire.** The `⌘/Ctrl+V`
  handler in `MessageInputBar` (both the expanded and compact fields) was gated to
  `isDesktopOrWeb()`; the V-paste case is now widened to run on any platform with a
  **hardware keyboard** — desktop, web, **and iOS/iPad**. It reads the clipboard via
  `getImageFromClipboard()` (per-target actual; the iOS/native one reads
  `UIPasteboard`, preserving GIF bytes) and dispatches `AttachClipboardImage`.
- **Enter-to-send** stays desktop/web only (mobile uses the send button).
- **Android** keeps its keyboard rich-content paste via `Modifier.contentReceiver`.

## The remaining gap (deferred)
**A touch iPhone with no hardware keyboard still can't paste an image.** Compose's
iOS text field is Skia-rendered (not a native `UITextView`), so it can't intercept
the OS **touch "Paste" menu** (long-press → Paste) for images — only text. There is
no public Compose hook for it.

### Options to close it (pick in a future pass)
1. **Inline "Paste image" chip (recommended).** Show a small contextual chip just
   above the input whenever the clipboard holds an image — use
   `UIPasteboard.hasImages` to decide visibility (it does **not** trigger the iOS
   paste-privacy banner; the banner only appears when bytes are actually read on
   tap). Tap → `getImageFromClipboard()` → `AttachClipboardImage`. Achievable
   without native interop, iMessage-style.
2. **Native `UITextView` interop.** True in-field paste from the touch menu, but a
   large, risky iOS-only rewrite that breaks the shared `RichTextEditor` toolbar.
   Not recommended.

## Pointers
- `⌘V` handlers + paste hook: `homebase-chat/.../widget/MessageInputBar.kt`
  (`MessageTextFieldExpanded` / `MessageTextFieldCompact`, `onPreviewKeyEvent`).
- Attach action: `onPasteImage` → `ConversationListUiAction.AttachClipboardImage`
  → `AttachmentHandler.handleAttachClipboardImage`.
- Clipboard read (expect + actuals incl. iOS `UIPasteboard`):
  `homebase-common/.../clipboard/ClipboardImageReader*`.
