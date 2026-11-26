# UI behaviours

How screens behave while the user works with them: what they show, what they block, and what they say
when something ends. Which component to use for a value is [`SPECIAL_FIELDS.md`](SPECIAL_FIELDS.md).

## 1. Buttons that start slow work

A button whose work waits on the network, the disk or another app (a send, an upload, a share, a
sign-in) shows that it's working, every time.

- **While it runs**, a text button shows a small `CircularProgressIndicator` (18dp, 2dp stroke,
  `LocalContentColor.current`) before a label saying what's happening ("Sharing…", "Saving…",
  "Sending…"). The label is a string resource, never the idle label. An icon button has no room for a
  label: the spinner replaces the icon at the icon's size, and the `contentDescription` becomes the
  busy string.
- **The button keeps its width** when the label changes, so the layout doesn't jump.
- **Nothing can start it twice.** The button is disabled while it runs, and so are the controls that
  feed it: pickers, fields, toggles, segmented buttons.
- **The busy state lives in the ViewModel's `UiState`** (`busy`, or a flag per action), set before the
  work starts and cleared in a `finally`, never in `remember` state. It survives recomposition and
  clears on failure.
- **When the work ends**, the button goes back to its idle label, and the user can see the outcome:
  - Success shows itself: the result appears, or the screen closes, or, when neither shows it, a
    snackbar says so ("Shared with Sam.").
  - Failure says what went wrong and what to do next ("Couldn't change who sees this album. Try
    again."). Outcomes are one-time events on a `SharedFlow` or `Channel`, not `UiState`.
  - The screen never just stops spinning.
- **Leaving mid-work.** Work the user would expect to finish (a send, an upload, a share) runs in a
  scope that outlives the screen. Work that only feeds the screen is cancelled with it.

Example: `AlbumShareScreen`'s Share button and `AlbumShareViewModel.busy`.
