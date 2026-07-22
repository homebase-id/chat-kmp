# Investigation: "Who can locate me" collapses to a single member

**Status:** open — root cause NOT yet confirmed. Blocked on a device log from the affected user
(user was offline when reported). Do **not** ship a fix until the decisive evidence below is
captured — an earlier "null odinId" theory was investigated and **falsified** (see Ruled out).

## Symptom (user report, verbatim)

> "Did you change something around emergency contacts with the circle work? All my emergency
> contacts disappeared (who I can see) except Frodo ?!"

Clarified with the reporter:
- The affected list is **"Who can locate me"** (not "Who you can locate").
- The disappeared contacts **all have Homebase IDs** — "BECAUSE THEY WERE IN THE EMERGENCY CIRCLE."
- One member (**"Frodo"**, redacted) survived; everyone else vanished.
- The reporter associates it with recent **circle work**.

## What the "Who can locate me" list actually is

`LocationViewModel` builds it from **two groups combined**:

```
whoCanLocateMe (confirmed) + whoCanLocateMePending (pending)
```

- **Confirmed members** — read directly from Emergency-circle membership:
  `connectionService.circles` → `CircleMembershipState.membersOf(EMERGENCY_LOCATION_CIRCLE_ID)`
  → `contactService.resolveByOdinId(...)`. Shown **always**.
  (`LocationViewModel.kt` ~127-148)
- **Pending members** — people you've granted the circle to but whose grant isn't confirmed yet.
  Computed **only when the section is expanded** (`if (action.expanded) checkWhoCanLocateMePending()`),
  via a **live per-contact `/connections/status` fan-out** that looks for the circle in each
  connection's `pendingCircleIds`. There is no bulk "list pending" endpoint.
  (introduced by commit `70e00bb9f`, see below)

Key constant: `EMERGENCY_LOCATION_CIRCLE_ID = "8b5383a5927246f8a666f4f3fcb7392b"`
(`homebase-common/.../config/AppConfig.kt:78`).

## Leading hypothesis

**Frodo was the one *confirmed* circle member; the others were *pending*, and the pending path
regressed** (or simply requires expanding the section, or its live `/connections/status` read is
coming back empty). That would drop every pending member and leave exactly the one confirmed
member — matching "all gone except Frodo."

This is a hypothesis, **not confirmed.** The competing possibility is that the members were
genuinely **removed server-side** (a client mutation bug), in which case the confirmed list itself
is down to one.

## Decisive next step (do this first, when the user is back online)

There is an **existing** log line that prints the real fetched member count per circle on every
`ConnectionService.refresh()`:

```
adb shell run-as id.homebase.feed.dev cat files/logs/homebase.log | grep "ConnectionService circles"
```

It prints e.g. `... Emergency Location Access=6 ...`. That count is decisive:

- **Count > 1 but UI shows 1** → members are present in the fetch; the client drops them at
  display time → **pending-path / display bug** (leading hypothesis). Fix is client-side in
  `LocationViewModel`'s who-can-locate-me build.
- **Count == 1** → members were genuinely removed server-side → hunt the **mutation** (the in-app
  remove flow, `ConnectionService.removeFromCircle` / the emergency-contact picker). Cross-check
  in a browser: `https://<identity>/owner/circles/8b5383a5927246f8a666f4f3fcb7392b`.

Also ask the reporter:
1. Were the disappeared contacts confirmed a while ago, or **recently added** (possibly never
   confirmed = pending)?
2. Does **expanding** the "Who can locate me" section bring any of them back?

## Ruled out (with evidence)

- **Null `odinId` collapse in `filterLocatable().distinctBy { it.content.odinId }`** — this is the
  *"Who you can locate"* list (the `iCanLocate` app-data flag), a **different list**. Not the one
  affected. And circle members necessarily have odinIds. Theory falsified by the reporter's
  clarification.
- **Client-side dedup collapse** in the who-can-locate-me build — `distinctBy { it.odinId }` over
  distinct odinIds keeps everyone; `resolveByOdinId` returns distinct models. No collapse.
- **Push handler rebuilding from a partial payload** — the 5002/5003 circle/connection push
  handler (`ConnectionService`, commit `829c14e91`) does a **full debounced `refresh()`**
  (`getCirclesWithMembers`), never a partial in-place update.
- **A member cap/limit or parse bug in the fetch** — `getCirclesWithMembers` just deserializes
  `/connections/circles/with-members` with no client-side member limit or filter
  (`ConnectionNetworkProvider.kt`).
- **`EmergencyCircleNotifier` wiping the circle** — it only **notifies peers** on a grant/revoke
  diff; it never calls `removeFromCircle`. Cannot remove your own circle members.
  (`homebase-core/.../location/EmergencyCircleNotifier.kt`)
- **In-app add/remove overwriting membership** — uses **incremental** `addToCircle`
  (`POST /connections/circles/add`) and `removeFromCircle` (`POST /connections/circles/revoke`),
  one odinId at a time — not a full member-list replace.

## Relevant code (entry points for the fix, once the cause is known)

- `homebase-core/.../ui/screens/location/LocationViewModel.kt`
  - `whoCanLocateMe` collector (~127-148): confirmed members from circle membership.
  - `whoCanLocateMePending` / `checkWhoCanLocateMePending()` (gated on `action.expanded`):
    pending members via live `/connections/status` fan-out.
  - `whoICanLocate` collector (~155-168): the OTHER list (iCanLocate flag) — not affected here.
- `homebase-chat/.../services/convo/contact/ConnectionService.kt`
  - `refresh()`: parallel `getConnected`/`getBlocked` + `getCirclesWithMembers`; the
    `"ConnectionService circles: ...=N"` log line lives here.
  - `CircleMembershipState.membersOf(circleId)` (~54-58).
- `homebase-api/.../client/connections/ConnectionNetworkProvider.kt`
  - `getCirclesWithMembers`, `addToCircle`, `removeFromCircle`.
- `homebase-common/.../config/AppConfig.kt:78` — `EMERGENCY_LOCATION_CIRCLE_ID`.

## Relevant recent commits ("the circle work")

- `70e00bb9f` feat(location): add/remove emergency contacts in-app instead of via owner-console
  browser — **introduced the pending fan-out + in-app remove**; prime area for the leading
  hypothesis.
- `4495d8b88` feat(contacts): generic circle-membership management, plus hardening from live testing.
- `6d40313ac` feat(contacts): show pending circles + drives on contact detail.
- `829c14e91` Handle connection & circle state-change push notifications (5002/5003).
- `bf6a16a4f` Location: emergency-contact directionality via circle membership + iCanLocate
  (split "who can locate me" = circle membership vs "who you can locate" = iCanLocate flag).

## Related issues

- #982 (locatable duplicates/self — the *other* list), #996 (contact list blanks on network drop),
  #961 (reconcile clears iCanLocate flag), #952 (same lists, blank avatars).
