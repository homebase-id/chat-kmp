# Investigation: "Who can locate me" collapses to a single member

**Status:** MOSTLY SOLVED — the device log has been captured and analyzed, and the affected
list has been **re-identified**: it is **"Who you can locate"** (`whoICanLocate`, the
`iCanLocate` app-data flag), NOT "Who can locate me" as previously clarified. The log shows
the collapsed list directly (2 members at screen-open) *and* shows it healing member-by-member
as peers' designation messages arrive. Root-cause mechanism identified (missing `iCanLocate`
flags + a one-shot, silently-failing backfill); WHY the flags went missing is the one open
question. See **Evidence from the device log** below. Earlier theories about the circle-backed
"Who can locate me" list are retained further down as history.

## Symptom (user report, verbatim)

> "Did you change something around emergency contacts with the circle work? All my emergency
> contacts disappeared (who I can see) except Frodo ?!"

Clarified with the reporter:
- ~~The affected list is **"Who can locate me"** (not "Who you can locate").~~
  **CORRECTED 2026-07-23: the affected list is "Who you can locate"** — the log evidence
  (see below) matches that list, and only that list, collapsing.
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

## Evidence from the device log (captured 2026-07-22 evening, analyzed 2026-07-23)

`homebase.log` from the affected user's Android device (identity `michael.seifert.page`),
covering 2026-07-22T23:45Z → 2026-07-23T03:45Z (≈18:45–22:45 local — right after the
investigation notes were written, i.e. when the user came back online).

### 0. CORRECTION: the affected list is "Who you can locate" — and the log shows the collapse

The reporter re-clarified (2026-07-23): the list that collapsed is **"Who you can locate"**
(`whoICanLocate` — contacts carrying this app's `iCanLocate` app-data flag), not the
circle-backed "Who can locate me". With that lens the log is conclusive:

**The collapsed list is directly visible.** When the Location screen's locatable section was
expanded at `03:43:56`, `verifyLocatablePass` preflighted exactly **two** members:
`frodo.baggins.demo.rocks` and `gabriel.silberberg.dk`. That IS the collapsed
"Who you can locate" list at that moment — matching "all disappeared except Frodo"
(gabriel's flag having plausibly been recovered between the report and this log).

**The list heals itself, member-by-member, during the log.** Two designation status messages
were processed while the log ran, each ending in the `iCanLocate` flag being (re)written:

```
03:44:40  POST /contacts/sync/shelly.silberberg.dk        (ensure/refresh contact)
03:44:48  verifyTemporalAccess shelly → hasAccess=true
03:44:48  PUT  /contacts/6f3fce13-…/app-data              (iCanLocate SET)
03:45:04  POST /contacts/sync/leela.silberberg.dk
03:45:07  verifyTemporalAccess leela  → hasAccess=true
03:45:07  PUT  /contacts/359dbf2d-…/app-data              (iCanLocate SET)
```

The next verify pass (`03:45:13`) covers **four** members — frodo, gabriel, shelly, leela.
The list grew 2 → 4 inside 90 seconds. So the peers' grants were never revoked (their
temporal access verifies `hasAccess=true` instantly); the client-side *flags* were missing
and are being re-derived as designation messages (re-)arrive.

**Why it stayed collapsed — the backfill backstop can't fire on this device.**
`iCanLocate` has exactly two set-paths:

1. The live WS status-message path (`EmergencyContactReceiveService.onDesignated`) — misses
   anything that arrives during cold sync or a dropped event, and each message is *consumed*
   (soft-deleted) after apply, so it can't re-fire later.
2. The backstop: `EmergencyContactReconciler.start()` — runs **once** per post-auth,
   fire-and-forget (`scope.launch { runCatching { reconcileAll() } }`), preflights each
   unflagged contact over the network, and **silently no-ops on any failure**. It never
   retries and logs nothing on failure.

This device's connectivity is exactly the environment where (2) dies silently: the log opens
with ~4 minutes of DNS failures resolving the user's *own* identity host, a single socket
abort at `03:44:27` killed all 57 queued `getConnectionStatus` calls at once, and
`MainThreadWatchdog` recorded 23–280 s freezes (Doze). No `reconcileAll` sweep appears
anywhere in the 4-hour window (it would show as a mass `TemporalRead` fan-out) — the app
never cold-started in-window, so the one-shot backstop was never re-armed.

**What's still open: why the flags went missing in the first place.** The split that made
`iCanLocate` the sole source of this list shipped 2026-06-24 (`bf6a16a4f`); the reporter says
the list was fine until the recent circle work (~Jul 14–15, `70e00bb9f`/`4495d8b88`). So the
flags existed and were *lost*, or the contacts were re-created without them. Candidate wipe
vectors, none provable from this log:

- A contact-record rewrite that drops this app's app-data slot — e.g. the server-side
  `POST /contacts/sync/{odinId}` enrich, a Contact Book add-on write path
  (ContactsProvider divergence notes), or a delete+recreate (uniqueId is stable
  `md5(odinId)`, so a recreated row simply has no app-data).
- A spurious `onRevoked` (explicit clear) — implausible for many peers at once, and each
  revoke would need a message from that peer.
- Note `chatAppData()` swallows deserialization failures (`getOrNull`) — an unparseable blob
  reads as *unflagged* and drops the row silently. Worth a log line.

Side observation: frodo — the one contact that "survived" — is the only member whose
`verifyTemporalAccess` **never returns** (4 POSTs across the window, zero responses; demo
identity likely unreachable), so his row would render Loading→Unreachable.

### Client write-path audit (2026-07-23): NO ContactService bypass — but one local hole found

Audited every contact-write path for the "direct drive upload wipes server-managed `appData`"
hypothesis. Result: **the client never bypasses the V2 ContactService.** `fileType=100` /
`CONTACT_FILE_TYPE` appears in exactly one place outside the provider — the QueryBatch *read*
filter (`ContactRepository.kt:121`). There is no `uploadFile` targeting the ContactDrive.

| Write path | Route | appData safe? |
|---|---|---|
| Create/edit (`saveContactDraft`/`saveContactEdit` → `ContactRepository.save` → `ContactsProvider.saveContact`) | `POST /api/v2/contacts`, `PUT /api/v2/contacts/{uniqueId}` | Server: yes — content built fresh, `appData=null` → omitted (`explicitNulls=false`) → merge leaves slot alone. **Client memory: NO — see below** |
| Enrich (`ContactRepository.sync`) | `POST /api/v2/contacts/sync/{odinId}` | server-side behavior still the open question |
| Avatar (`setContactImage`) | `PUT /api/v2/contacts/{id}/image` | untouched |
| iCanLocate flag (`set/clearICanLocate`) | `PUT/DELETE /api/v2/contacts/{id}/app-data` | is the appData write |
| Overlay/extras (`ContactOverrideStore.save`) | `PUT /api/v2/contacts/{id}/app-ext-data` (bulk tier) | separate tier, inline map untouched |
| Delete (`ContactRepository.delete`) | `DELETE /api/v2/contacts/{id}` | file gone; a later sync **recreates with empty appData** (uniqueId stable = silent) |
| Bulk | none exists (no import/migration/mass re-save) | — |

**uniqueId derivation is correct**: `Md5.toGuidId` hashes its input verbatim (md5 over UTF-8;
the KDoc's "input is lowercased" sentence is stale — the code does not lowercase). All contact
call sites pass `OdinId.domainName`, which `AsciiDomainName` normalizes to exactly the server's
form: `lowercase()`, and the validator *rejects* trailing dots, whitespace, and non-ASCII — so
a mismatched hash cannot be produced silently (bad input throws instead).

**The local hole (`ContactRepository.kt:255-256`)**: after a successful save, the optimistic
upsert rebuilds the in-memory row from the *client-built* content — it preserves
`existingImage` but **not the existing row's `content.appData`** — so editing a contact makes
them instantly vanish from "Who you can locate" until the authoritative file syncs back.
Transient and memory-only (server keeps the slot), but on this reporter's connectivity
"transient" can be hours. Fix: carry the previous row's `appData` into the optimistic content.

Note this hole does NOT explain the log's recovery trace: after `contacts/sync` the app had to
*re-derive and re-write* the flag (verify → `PUT app-data`), meaning the server-side record
genuinely lacked it — so the persistent wipe is still server-side (sync/enrich clobber,
delete+recreate, or a non-KMP client writing contact files directly, e.g. odin-js).

### Superseded first-pass analysis (done while the symptom was attributed to "Who can locate me")

The findings below were made against the circle-backed list. They remain true and useful as
context — chiefly: the outgoing Emergency circle itself is intact, so nothing is wrong
server-side with the user's own grants.

### 1. The decisive count: 6, every time → server-side removal RULED OUT

The `ConnectionService circles` line appears **16 times** across the 4-hour window and every
single one reads `8b5383a5927246f8a666f4f3fcb7392b(Emergency Location Access)=6`. The members
exist server-side, the fetch succeeds, and the client state (`_circles.value`) held all 6
throughout. No `addToCircle`/`removeFromCircle` calls and no `ConnectionChanged` /
`CircleDefinitionChanged` events appear anywhere in the window — membership was untouched.
Per the decision fork below: **client-side display, not a mutation bug.**

### 2. "Frodo" is NOT one of the 6 confirmed members — he's pending

At `03:44:27` (the one Location-screen visit in the window) `findPendingMembers` fanned out
and all its `getConnectionStatus` calls failed, each logging
`ConnectionService: getConnectionStatus failed for <id> ... while finding pending members of
8b5383a5-...`. Two decisive facts fall out of that failure list:

- **"Frodo" is in the candidate list.** Candidates are *connected identities that are NOT
  already real members* — so he was not among the 6 confirmed members at 03:44:27.
- **The real-member exclusion demonstrably applied**: 57 candidates ≈ 64 connected − the 6
  real members (circles had loaded at 03:42:22, well before the fan-out). This was not the
  cold-start empty-`realMembers` path.

So the reported symptom inverts the original hypothesis: the six **confirmed** members are the
contacts that "disappeared" from the UI, and the one survivor was a **pending** deposit. For
the UI to show only Frodo, the state must have been `whoCanLocateMeLoaded == true`,
`whoCanLocateMe == []`, `whoCanLocateMePending == [Frodo]` — i.e. a *loaded* circle state with
**zero** emergency members, while a pending check for Frodo had *succeeded*.

The confirmed-path client code was re-audited against this: `membersOf` (case-insensitive id
match, id verified identical to the constant) → `resolveByOdinId` (exact-key map lookup;
fallback preserves the odinId) → `distinctBy { it.odinId }` (OdinId equality = 128-bit
SHA-256-derived hash of the normalized domain) cannot turn 6 distinct members into 0 or 1.
Given every *logged* fetch carries 6 members, the only states that produce "only Frodo" are:

- **(a) an earlier, unlogged fetch returned the emergency circle empty/without members** —
  a transient server-side state during the circle work that has since healed, or
- **(b) a client render/state bug not yet identified** — only tenable if the symptom is
  *still visible now*, which the log data says it shouldn't be.

Distinguishing (a) from (b) is exactly the "is it still broken now?" question below.

### 3. Confirmed real (adjacent) bug: fan-out failure reads as "nobody pending"

All 57 status calls died within **135 ms** of each other with `NetworkException: Software
caused connection abort` — one dropped socket aborted the entire queued fan-out. In
`findPendingMembers`, a failed call yields `null` → `takeIf == true` is false → the identity
is treated as **not pending**. So a single network hiccup silently empties the pending list
(`whoCanLocateMePending = []`), making pending members vanish until the next successful check.
This device's network was exactly that hostile: DNS resolution of its *own* identity host
failed for minutes (23:45–23:49), sockets aborted mid-flight, and `MainThreadWatchdog`
recorded 23–280 s process freezes (likely Doze). This is a genuine disappearance bug for
*pending* members — it just isn't sufficient to explain "only the pending member survived".

Related asymmetry worth fixing while here: `connections` hydrate from cache on cold start,
`circles` do not — an offline cold start has a populated connection map but a permanently
not-loaded circle state (spinner / empty confirmed list until first successful fetch).

### 4. Side observations (separate issues, noted for later)

- Watchdog stall dumps repeatedly caught `LocationViewModel.resolveContact` /
  `OdinId.<init>` and `LocationUiState.equals` (O(n) list equals inside
  `StateFlowImpl.updateState`) on the UI thread. The watchdog self-reports as starved
  (OS-level freeze suspected), so these are *where the process happened to be*, not proven
  culprits — but `resolveContact`'s linear scan of `contactRepository.contacts` per share row
  on Main is real and cheap to fix.

### Next steps

1. **Recovery for the user (no code needed):** a fresh app cold-start on a good network
   re-runs `reconcileAll`, which will preflight every unflagged identity contact and restore
   each flag where `hasAccess=true`. Confirm with the reporter that the list repopulates.
2. **Fix the backstop's failure mode:** `reconcileAll` is one-shot, fire-and-forget, and
   silently swallows failures. Re-run it when connectivity returns
   (`AuthConnectionCoordinator.isOnline` transition, like #998's verify re-pass), and log the
   pass outcome (contacts checked / flags set / failures) so this is diagnosable next time.
3. **Find the wipe vector** (the remaining root-cause question): audit what rewrote the
   contact records around the circle-work update — does the server-side
   `POST /contacts/sync/{odinId}` enrich preserve per-app app-data slots? Do Contact Book
   add-on writes? Does any path delete+recreate contacts (stable uniqueId, fresh file, no
   app-data)? Cross-check one affected contact's file history on the owner console.
4. Log a warning when `chatAppData()` fails to deserialize instead of silently reading as
   unflagged.
5. (Carried over, still worth fixing:) the "Who can locate me" pending fan-out treats a
   failed `getConnectionStatus` as "not pending", silently emptying the pending list on any
   network hiccup — keep the previous pending list or surface a check-failed state.

## Leading hypothesis (SUPERSEDED — kept for history; inverted by the log evidence above)

**Frodo was the one *confirmed* circle member; the others were *pending*, and the pending path
regressed** (or simply requires expanding the section, or its live `/connections/status` read is
coming back empty). That would drop every pending member and leave exactly the one confirmed
member — matching "all gone except Frodo."

This is a hypothesis, **not confirmed.** The competing possibility is that the members were
genuinely **removed server-side** (a client mutation bug), in which case the confirmed list itself
is down to one.

## Decisive next step (DONE — answered by the device log: count = 6, UI showed 1 → display path)

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
