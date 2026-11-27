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
  - Failure says what went wrong and what to do next, as section 3 sets out. Outcomes are one-time
    events on a `SharedFlow` or `Channel`, not `UiState`.
  - The screen never just stops spinning.
- **Leaving mid-work.** Work the user would expect to finish (a send, an upload, a share) runs in a
  scope that outlives the screen. Work that only feeds the screen is cancelled with it.

Example: `AlbumShareScreen`'s Share button and `AlbumShareViewModel.busy`.

## 2. When a recipient is pending

When an app (Photos, Filesync, or any add-on) adds someone to one of its circles, odin-core may only
*deposit* the grant. It becomes real the next time that person's identity contacts yours (their app
syncs, they send a message, they open something of yours). Until then, anything that needs them to
hold the grant can't be done yet: sharing with them, giving them access, adding them to a group,
letting them add or edit. The person is **pending**: added, but not reachable yet.

This applies to every operation that names a recipient, not just sharing.

- **Decide before building.** Before building an operation that names a recipient, decide whether a
  pending recipient is queued and retried, or told to try again later. Write the decision down next
  to the operation: queued or not, and if queued, where the entry is stored and which upkeep step
  retries it. If the decision isn't clear from the request or the code, stop and ask; don't pick
  one. Until it's written down, the feature uses the "Try again later" wording.
- **Detect it before writing.** A recipient is pending when `SelectiveShareCircles.enroll` reports
  them as deposited, or they're in the circle's pending list (`getPendingCircleMembers`) and not its
  members (`getCircleMembers`). Check before the operation's write. Don't find out by catching the
  server's refusal afterwards, and never show its error text ("isn't granted the drive Read…").
- **It isn't an error.** The operation goes ahead for everyone who can be reached, ends normally
  (the spinner stops), and says what happened to the pending ones. Never "Failed" or "Couldn't" for
  them. Never retry in a loop from the screen, and never keep the user waiting on the pending person.
- **Only say what's true.** The UI may say something will happen later only if the feature really
  makes it happen: the operation is stored durably (on the drive, or in storage that survives a
  restart) *and* a named upkeep step retries it until it succeeds or is cancelled. Without both,
  it doesn't say "later".
  - If the feature queues and retries it: "Sam will get this once they're next online. You don't
    need to do anything." Use the operation's own words: "…will be able to add photos once…",
    "…will join the group once…".
  - If it doesn't: "Sam can't get this until they're next online. Try again later." Don't imply it
    will happen by itself.
  - Either way, name the person and what hasn't happened for them yet.
- **Wherever recipients are listed** (shared with, group members, editors):
  - Queued: list them with a "Waiting for Sam" label and a clock icon. Tapping it explains: "Sam
    was added, but their Homebase hasn't been in touch since. This finishes automatically the next
    time it is, usually when they open the app."
  - Not queued: don't list them as if they had it, because nothing was done for them.
  - Removing them works as for anyone else, and deletes anything queued for them.
- **When queued work completes**, the upkeep finishes it with no action from the user. The label
  changes to their normal status the next time the list is shown, with no notification. After 7 days
  still pending, the label reads "Still waiting for Sam. Ask them to open Homebase."

Today no feature queues a pending recipient, so every one uses "Try again later".

## 3. When an operation fails

The user is told why it failed and what they can do. The server usually says why; the app never
throws that away.

- **Known causes are checked before writing, and said in plain words.** Where a failure can be
  foreseen (a permission the app's circle lacks, someone not connected, a size limit), check it
  first and stop with a message that says what's wrong and what fixes it, naming the setting or
  person: "Your Photos circle only gives view access, so albums can't be shared with comments and
  reactions. Update Photos' permissions in your Homebase settings." Don't detect known causes by
  matching the server's message text; it carries only a generic code and can change.
- **An unrecognised failure passes the server's reason on.** The message names the step that failed
  and quotes the server: "Couldn't share the album: <server's message>." A "Details" action shows
  the error code, status and correlation ID, so the user can send them on. Never replace a server's
  reason with a generic "Something went wrong."
- **No reason at all** (no network, timeout, a crash in the app's own code) says that: "Couldn't
  reach your Homebase. Check your connection and try again."
- **"Try again" only when trying again can help**: a network failure, a timeout, a server that's
  busy. A refusal that will happen again the same way (a permission, a rule, invalid input) says
  what to change instead.
- **The log keeps the whole error**, with the operation and its ids, at Warn. The message the user
  sees comes from the same exception, not a separate guess.
- **Each foreseeable failure gets its own string resource**, and when a new one is found in a log,
  it becomes a check plus a message. "Unrecognised" is the exception, not the default.
- **Never**: "Failed", "Error" or "Try again" on their own; a message that blames the user for
  something they can't change; a raw stack trace.

Example: sharing an album when the Photos circle grants Read only. Checked before writing and told
in plain words. Any other server refusal is quoted.
