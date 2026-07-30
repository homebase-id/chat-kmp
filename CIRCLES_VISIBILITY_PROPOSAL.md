# Proposal: Simplifying Circles, Connection Confirmation & Profile Visibility

**Prepared for:** Team discussion
**Date:** July 11, 2026
**Status:** Draft for feedback

**Goal:** Make circles intuitive, remove confusion around "Vetted", give users clear
control over what they share, and support advanced per-circle customization (e.g. a
special photo for one circle) without adding complexity for normal use.

## 1. Current Problems

- "Vetted" is widely misunderstood as some kind of official approval or verification.
- The blue check currently means "connected", but we want it to mean **"I have reviewed
  this person and added them to one or more of my circles"**.
- The profile editor uses a simple Public / Vetted toggle, which doesn't scale to
  per-circle customization (e.g. a fun photo only for "Beer Drinking Buddies").
- The confirm connection flow is functional but still carries old terminology and
  doesn't clearly explain the consequence of adding someone to circles.
- The Circles tab is mostly a flat list and doesn't strongly help users understand the
  value of circles.
- At the ACL level, "Vetted" doesn't even enforce its promise: vetted profile
  fields are secured to the `connected` security group, which the server grants
  to **every** connection — unreviewed auto-connections included (section 8).

## 2. Proposed Mental Model (Simple & Consistent)

**Circles = My groups for sharing more and granting permissions.**

- Anyone can see your **Public** information.
- When someone is introduced to you, they start as a **New** 👋 connection:
  connected at the protocol level (you can chat), but not yet reviewed.
- **Every connection starts as New until reviewed** — introduced,
  auto-connected, and plain direct connections alike (today's "Unvetted" bucket
  maps 1:1 onto New). Being connected alone never moves a contact; the only path
  out of New is completing the review.
- You **review** them. A review is **triage, not endorsement** — it **records** that
  you looked and what you decided. It has four honest outcomes: add them to
  circles, keep them chat-only, disconnect, or block.
- The circles you might choose determine **what they can see** of your profile and what
  special access they may get.
- **Circles are optional.** Keeping someone chat-only makes them a **Chat** 💬
  connection — you can chat, they see only your public profile, and nothing
  about the relationship changed: the connection was exactly as real before you
  reviewed it. The review only records your decision.
- **Chat is a grant too.** Chatting requires write access to your chat drive —
  and New connections receive it the moment the connection forms, via the chat
  app's auto-connect default circle (section 8; without this, bootstrapping a
  chat network would be impractical). So
  New → Chat is promoted by the **review stamp**, not by any grant changing:
  "💬 Chat only" records your decision and may change nothing server-side. It
  also means an unreviewed stranger can already message you — New 👋 is the UI
  acknowledging exactly that.
- **"Chat" is the display name for the default connection state in the Chat
  app.** The state's definition is functional: reviewed, holding only the default
  connection grants, with **no read access beyond public**. Internally this is a
  **deposit-only** (no-read) connection — they can put things into your drives,
  they see nothing extra. In *this* app chat is the default capability that
  matters, so "Chat" is the honest UI label; a different app would name the same
  state by *its* default capability. The state machinery is shared — the label is
  per-app presentation.
- There is **no special "Vetted" system circle**. Being a **Circle** ⭕ connection
  implies reviewed and connected — but the converse doesn't hold: a contact can be
  reviewed and connected while being in no circles at all.
- Removing someone from their last circle drops them back to **Chat** — not to
  "New", as if you'd never reviewed them. This is possible due to the recording of
  the prior review, stored on the contact record itself.

**Blue check / circle badge** on contacts = This person is in one or more of my
circles. Chat contacts carry no circle badge (a subtle 💬 indicator at most — open
question 4); the badge is evidence of access granted, not the only evidence of
review.

### Following is orthogonal — not a fourth state

The three states classify **inbound access**: what they get of mine. Following is
**outbound consumption**: what I take from them. A follow grants the followed
person nothing, so it is not a rung on the ladder — it combines with any state,
or with no connection at all:

- **Public feed: no connection needed.** Follow-only identities can exist (like
  subscribing to a blog). If surfaced in the contact book they get a follow
  indicator (e.g. 📡), never a state pill — they have no connection state.
- **Encrypted feed: connection required.** Secured posts are encrypted to *their*
  circles, so receiving them requires being connected and in whichever of their
  circles they put me (typically an **audience** circle — see the next subsection). The follow is still just my subscription switch; how much
  it delivers is their call — the mirror image of this proposal: my circles govern
  what they see of me, their circles govern what I see of their feed.
- Consequence: disconnecting someone downgrades an existing follow to public
  posts only — their grants vanish with the connection, the subscription itself
  may remain.
- The "Follow their feed" toggle in the review modal is therefore a convenience
  for an orthogonal action offered at a natural moment — not a "connection
  default" that belongs to a tier.
- **Followers/following are not contacts.** No contact record and no
  connection-table linkage is required or implied — they remain in their own
  store, exactly as today. Caching a followed channel's public profile stays the
  feed app's concern, in the feed app's tables.

### Kinds of circles — personal, audience, service

Circles are the single grant primitive, but they serve distinct relationship
kinds:

- **Personal circles** — intimacy plus visibility/permissions: Friends, Family,
  Beer Drinking Buddies — and Emergency Location Access, which is app-owned yet
  among the most intimate circles you have (the people you live with, your
  parents when you're a child). User-created circles are personal by default.
- **Audience circles** — pure capability, no intimacy claim: **Subscribers** is
  just a circle whose grant is read access to the feed drive — that *is* the
  encrypted-feed subscription. Membership means "customer", not "confidant".
- **Service circles** — vendor and institution relationships: the hotel or
  airline that writes purchase history into your Receipts drive, the bank that
  uploads statements for your archive, a tax accountant. **Write-only in
  practice** — they deposit into your drives and see nothing of you. Neither
  intimate nor an audience, and often not even an individual.
A circle is, formally, **a named list of people plus grants defined by its
owning app**. The membership *list* is referenceable by any app (moments
distributing to Beer Drinking Buddies uses the circle purely as a set of
people); the *grants* belong to the owner. Friends, Family, Beer Drinking
Buddies are owned by the **profile app** — their grants are profile-attribute
reads, and that is where users create them. Emergency Location Access is the
same species one app over: the location app's PERSONAL circle.

App **default circles** (auto-connect and verified-connect enrollment —
section 8) are not a fourth relationship kind and carry no special designation:
their rendering keys off `Enrollment`. They never show as pills and never affect
states; they surface in the Circles tab as a visibly-distinct group (member
list, owner toggle, read-only grants).

Every circle carries a **`PERSONAL | AUDIENCE | SERVICE` designation** (an enum,
not a boolean — and the spare room earned its keep within a week: service circles
were discovered during review of this very proposal), set by the owning app when
it registers the circle. This rides the in-progress backend work where circles and
drives belong to an app; note the designation is **per-circle, not per-app** — the
location app owns a personal circle while the feed app owns an audience one.

**In this app, contact states derive from personal circles only.** The
derivation is a presentation rule scoped to the Homebase Chat KMP app (profile,
contacts, feed, chat, moments — maybe someday photos), not a protocol rule: each
app surfaces the circle kinds it owns and cares about. A service circle (bank →
Receipts drive) doesn't merely "not count" here — it doesn't **appear** in this
app's UI at all, neither as a state nor as a pill; displaying it is the owning
app's concern. Audience or service membership never awards ⭕ — your bank must
not render as a Circle contact. If an audience member needs a label anywhere in
this app (feed's Subscribers management), it's the circle's own name, which
claims nothing socially. This is also the strongest
concrete argument in open question 6 against option B: a paid subscriber you've
never met must not read as "Trusted 🛡️".

Audience approval is its own lightweight path: it does **not** stamp
`connectionReviewedAt` (approving subscriber #4032 is not a personal review), and
audience requests do **not** count toward the New 👋 badge — a popular feed must
not make the contact book scream "5,000 people to review".

A person can be both — your friend who also subscribes. Personal wins for
display; their detail view lists the union of grants.

## 3. Terminology

**Principle: "connection" is the constant noun, never a state name.** It always
means the wire — the key exchange and chat channel that "Disconnect" severs
(connection request, new connection, disconnect). The three contact states are
one-word modifiers on top of it. This is what un-overloads the word: today
"connection" is doing triple duty as the wire, the pre-review state, and an
implied endorsement (the LinkedIn sense).

### The three states — two candidate name sets

Both sets name the **same three states**; the team picks one:

| State (definition)                     | Emoji     | Option A | Option B |
|----------------------------------------|-----------|----------|----------|
| connected, not yet reviewed            | 👋        | New      | New      |
| reviewed, nothing granted — chat only  | 💬 / 🤝  | Chat     | Known    |
| reviewed, in ≥ 1 circle                | ⭕ / 🛡️  | Circle   | Trusted  |

- **Option A — capability ladder (New / Chat / Circle).** Names each state by what
  the contact *gets*: chat only, or circle access. Transparent, no endorsement
  implied, and the tier names double as filter chips (New | Chat | Circles).
  Review-form button (adaptive label): **⭕ Add to circles / 💬 Chat only**.
- **Option B — trust ladder (New / Known / Trusted).** Warmer and more human — it
  names the relationship rather than the mechanics. The risk sits in **Trusted**:
  it is a judgment word whose content is invisible — the same failure mode as
  "Vetted" (trusted to do *what*?) — and it hides circles from the state name
  right where circles are the payoff. Review-form button (adaptive label):
  **🛡️ Trust & add to circles / 🤝 Keep as known**.

### Emoji / icon per state

Shorthand for docs, chips, and marketing; in-app these map to Material icons and
tinted pills (e.g. `waving_hand`, `chat_bubble`, `workspaces` / `shield`):

| State            | Recommended     | Alternates | Avoid |
|------------------|-----------------|------------|-------|
| New              | 👋              | ✨ 📬      | 🆕 — a Latin "NEW" box, doesn't localize |
| Chat / Known     | 💬 (A) · 🤝 (B) | 🗨️        | |
| Circle / Trusted | ⭕ (A) · 🛡️ (B) | 🔵 👥      | ✅ — reads "verified", the exact Vetted misread |

👋 says "introduced, say hello / decide"; 💬 is the literal capability; ⭕ is the
literal circle. For option B, 🤝 reads "we've met" and 🛡️ carries trust without
the officialdom of a checkmark.

**Rendering rule (in-app):** the three state indicators are **monochrome vector
icons tinted in Homebase blue** (Material `waving_hand`, `chat_bubble`, a plain
circle outline) shown in a **fixed trailing slot** on the right of each contact
row — emoji glyphs can't be recolored (color fonts ignore text color), so the
states are icons, not characters. **User-chosen circle emoji** (next subsection
of section 4) render as normal **full-color emoji** on the row's second line.
The two are therefore different visual species — a user assigning 💬 to a circle
causes no ambiguity, so nothing needs reserving in the emoji picker. The emoji
forms above remain the shorthand for docs and marketing.

> The rest of this document uses **option A's names as working terms** (New, Chat,
> Circle). Substitute Known/Trusted if option B wins — the underlying states are
> identical.

### Other renames

| Current          | Recommended                          | Notes                                 |
|------------------|--------------------------------------|---------------------------------------|
| Vetted           | My circles / Visible to my circles   | Primary replacement                   |
| Unvetted         | New                                  | Maps 1:1 (see section 8)              |
| Blue check       | Circle badge / circle pills          | Reserved for circle membership        |
| Confirm (button) | Demoted to a verb: "confirming" = completing the review | The buttons name destinations instead |
| —                | **Any of my circles**                | New easy default in visibility picker |
| —                | **Personal circle**                  | Counts toward contact states; user-created circles default to it |
| —                | **Audience circle**                  | Pure capability grant (e.g. Subscribers); never affects contact states |
| —                | **Service circle**                   | Vendor/institution grants (e.g. bank → Receipts drive); write-only in practice; surfaced by its owning app, invisible in this app |
| —                | **App default circle**               | Auto-/verified-connect enrollment (section 8); renders via the state slot and review toggles, never a pill; visible in the Circles tab as a distinct group |

## 4. Proposed Changes by Screen

### A. Contacts List

- Keep **Contacts | Circles** tabs.
- Replace the three filters (All / Unvetted / Vetted) with horizontal chips:
  - **All**
  - **New** (with count badge when there are introductions to review)
  - Your actual circles as filter chips (Friends, Family, Beer Drinking Buddies, etc.)
- Contact rows communicate one of **three states** via the **monochrome state
  icon in a fixed trailing (right) slot**, tinted in Homebase blue (rendering
  rule, section 3):
  - **New** 👋 — connected (introduced, auto-connected, or direct) but not yet
    reviewed. Gets a prominent **"Review"** action.
  - **Chat** 💬 — reviewed, kept chat-only. Second line empty. Sees public info
    only.
  - **Circle** ⭕ — the row's **second line** shows the circles themselves:
    - circles with a user-assigned emoji show it as a normal **full-color emoji**
      (🧑‍🧑‍🧒‍🧒 📍 🍻 🤝) — compact, personal, instantly readable to its owner;
    - circles without one fall back to a small name pill, optionally shortened
      **"Hebrew style"** (vowel-dropped): Family → `fmly`, Buddies → `bdds`.
      The full name always appears in roomy contexts (circle cards, the review
      modal, tooltips) and is always the accessibility label.
- Tapping a contact shows their public profile + clear call-to-action to review the contact (so it's no longer new).
- The contact book lists **personal** contacts only. Audience-circle members
  (e.g. feed subscribers) never appear in this list — they're managed in the app
  that owns the circle (see section 8 for why this is also a storage necessity).

**Result:** The list immediately communicates "who I've reviewed, and what access
I've granted them".

### B. Edit Profile + Field Visibility

**Main Edit Profile screen**

- Keep **Public** section as-is.
- Rename the second section from "Vetted" to **"Visible to my circles"**.
- Subtext: "Additional details shown to anyone you've added to your circles."

**When editing any field** (Birthday, photo, email, status, etc.):

Replace the current **Public / Vetted** segmented control with:

**Public** | **Circles**

- **Public** → visible to everyone (current behavior).
- **Circles** → opens the **Select circles** dialog (see below).

**Select circles dialog**

- Header: "Visible to my circles"
- Top prominent option:
  **☑ Any of my circles**
  *Visible to anyone in at least one of your circles.*
- Below: Pick a circle.
- User can select "Any of my circles" (default )**or** pick one or more specific circles.

These are likely personal circles, probably not audience or service circles —
the visibility picker should list personal circles by default.

This single pattern supports both simple use and the advanced "special beer drinking
buddies photo" case.

### C. Connection Review Flow

Combine the explanatory text and circle selection into **one clean modal**:

> **Review connection with biggus.dickus.demo.rocks**
>
> You were introduced by samwise.gamgee.demo.rocks.
> Add Biggus Dickus to one or more circles if you want them to see more of your profile.
>
> - List of your circles with toggles/checkboxes
> - Special permission circles (Emergency Location Access) stay visually distinct
> - Per-app default toggles (each app's verified-connect defaults, e.g. feed
>   distribution — collapsed to a summary row by default) + "Follow their feed"
>
> **[ ⭕ Add to circles ]**  ← one big review button; label + emoji adapt to the
> selection: with ≥ 1 circle selected it reads **⭕ Add to circles**, with none it
> reads **💬 Chat only**
>
> 👋 Keep as new · Disconnect · Block

**One button, two destinations — the button stamps the review.** The label + emoji
always name the destination state the tap will produce, not a judgment ("confirm"
survives only as the verb for completing a review):

- **⭕ Add to circles** (≥ 1 circle selected) — applies the selected circles *and*
  the checked per-app defaults. Each toggle is an app's `VERIFIED_CONNECT`
  default circle or a per-connection setting — concretely: feed distribution,
  accepting introductions they relay, shard-recovery participation (the list the
  code inventory produced, section 8). These are **visible toggles in the
  modal**, not hidden side effects — hidden side effects are how "Vetted" got
  confusing in the first place. Presentation is **suite-aware**: apps of the
  suite the user is in collapse to one summary row ("Homebase apps ✓ — Chat,
  Feed, Moments"), expanded on tap; a genuinely separate app (a receipts
  vendor's) always gets its own visible row, and the app whose flow the request
  arrived through is the prominent one.
- **💬 Chat only** (no circles selected) — no circles granted. For the contact
  you'll talk to but don't want to endorse: the landlord, the seller, the
  introduction you're lukewarm about. Deselecting the last circle also flips the
  default toggles off (they stay visible, so the user can re-enable any of them
  deliberately). Helper text: *"They'll see your public profile only. You can add
  them to circles anytime."*

Either way the tap completes the review (stamps `connectionReviewedAt`, section 8)
and moves the contact out of New — into **Circle** ⭕ or **Chat** 💬, exactly as
the label promised.

**👋 Keep as new** replaces "Cancel": it dismisses the review without stamping
anything — the contact stays New, exactly as the label says. With that, every
labeled exit from the review names the state it leaves the contact in
(⭕ / 💬 / 👋); even the escape hatch is honest about its destination. Scrim-tap
and the back gesture keep plain cancel behavior.

The review flow is **personal triage only** — audience requests (e.g. feed
subscriptions) are approved in the owning app and never enter this flow.

**Disconnect / Block** stay available as tertiary actions (overflow menu or footer
link) — a review that can only end in approval isn't a review.

The adaptive single button avoids the earlier two-button design's weakness (two
buttons that differ only in preset checkbox states) while keeping both destinations
explicit: the label change *is* the feedback that deselecting circles changed the
outcome.

Declining circles does **not** block chatting — the connection is what enables chat;
circles only govern extra profile visibility and permissions.

This makes the consequence of the review very clear: "You are choosing what this
person can see and what happens by default."

### D. Circles Tab

- Add a short, friendly explainer at the top (collapsible after first view):
  "Circles let you organize contacts and control what you share with them. Add people
  to circles to share extra details and grant permissions."
- Turn the list into **cards** with:
  - Circle icon/name
  - Member count + small avatar preview
  - One-line description of what the circle shares
- Circles can optionally carry a **user-chosen emoji** (picker in create/edit —
  reuse the reaction picker). It shows on the card and everywhere the circle
  appears compactly: contact rows' second line, filter chips, the review modal's
  checkbox list (emoji + name there).
- Keep special circles (Emergency Location Access) visually distinct.
- "New connections" appears as a top item with a count.
- Big **+ Create Circle** button at the bottom.

## 5. Advanced Per-Circle Customization (Beer Drinking Buddies Photo)

When a user adds or edits a field/photo in the **"Visible to my circles"** section,
they get the choice:

- Share with **Any of my circles** (default)
- Or **Choose specific circles**

This allows exactly the use case described: a fun profile photo that only appears for
the "Beer Drinking Buddies" circle, while the rest of your circles see a different (or
no) photo.

Most users will never need this and can just use "Any of my circles". Power users get
the flexibility.

## 6. Benefits of This Approach

- Removes all confusion around the word "Vetted".
- Makes the meaning of the blue check / circle indicator obvious.
- Gives users a **simple default path** ("Any of my circles") while supporting advanced
  per-circle control.
- The connection review flow now clearly explains the privacy consequence.
- The mental model is consistent across Contacts, Profile, and Connection flows.
- Scales well as users create more custom circles.
- Scales cleanly in the other direction too: personal contacts and audience
  relationships (a feed with a million subscribers) live on separate axes and
  separate storage tiers — neither pollutes the other.

## 7. Suggested Phasing

**Phase 1 (Quick win)**

- Rename "Vetted" → "My circles" / "Visible to my circles" everywhere; pick the
  state-name set (section 3) and its emoji/icon triple
- Update blue check meaning and contact list indicators
- Improve connection review modal with "Any of my circles" option
- Stamp `connectionReviewedAt` in the contact's localAppData when the review
  completes (see section 8) — required as soon as a chat-only review outcome
  is possible, so the Chat state survives across the user's devices
- Coordinate with the in-progress app-owned circles backend so the
  `PERSONAL | AUDIENCE | SERVICE` circle designation **and the optional
  per-circle `emoji` field** land in that schema now (section 8). The enrollment
  model (`Enrollment`, `AutoConnectDefaults`, deposit-only invariant, owner
  toggle) is specified in odin-core's `docs/drive-addressing.md` (PR #1589) —
  retrofitting any of it after circles ship is far costlier

**Phase 2**

- Update field visibility picker in Edit Profile to Public / My circles + Select
  circles dialog
- Improve Circles tab with cards and explainer
- Optional per-circle emoji: picker in create/edit, full-color emoji in contact
  rows / filter chips / review modal, vowel-dropped pill fallback

**Phase 3**

- Add per-circle override capability for fields and photos

## 8. Implementation Notes

### Recording the review

The "reviewed" fact needs explicit, synced storage — it cannot be derived.

**Why it can't be derived.** Today the blue check comes from the server-computed
`vetted` flag: connected AND member of the "Confirmed Connections" system circle
(`RedactedIdentityConnectionRegistration.vetted`, issue #919). This proposal abolishes
that system circle. And a **Chat** contact has, by definition, zero circle
grants — so there is nothing left to infer "reviewed" from. Without explicit storage,
Chat and New would be indistinguishable.

**Where it lives.** Record it in the contact file's **localAppData JSON**
(`fileMetadata.localAppData.content`) on the contacts drive — the same pattern
conversations already use (`ConversationLocalAppDataJson` with `lastReadTime` etc.).
A new `ContactLocalAppDataJson`:

```kotlin
@Serializable
data class ContactLocalAppDataJson(
    /**
     * Stamped when the user completes the connection review — via the
     * adaptive review button (Add to circles / Chat only) — whether or
     * not any circles were selected. Null = never reviewed.
     */
    val connectionReviewedAt: UnixTimeUtc? = null,
)
```

localAppData is stored on the owner's server-side file header and syncs to **all of
the owner's clients/devices**, but is **never transferred to the peer** — which is
exactly the right privacy boundary: "I have reviewed you" is my private state.

**Deriving the three contact-list states.** Put explicitly: **all connections are
New until reviewed**. Today's "Unvetted" bucket — connected but unconfirmed, whether
auto-connected, introduced, or a plain direct connection — maps 1:1 onto New. The
only promotion out of New is completing the review dialog via the adaptive review
button (which stamps `connectionReviewedAt`); the legacy carve-outs below are the
sole exceptions.

| State      | Condition                                                    |
|------------|--------------------------------------------------------------|
| New 👋     | connected, `connectionReviewedAt == null`, no circle grants  |
| Chat 💬    | `connectionReviewedAt != null`, no circle grants             |
| Circle ⭕  | ≥ 1 circle grant (implies reviewed)                          |

**Migration / legacy:**

- Existing contacts with `vetted == true` (Confirmed Connections membership): treat as
  reviewed on read, and backfill `connectionReviewedAt` opportunistically.
- Contacts with circle grants but no stamp (e.g. circles granted from another surface):
  treat as reviewed — membership is itself evidence of review.
- Removing someone from their last circle requires **no localAppData change**:
  `connectionReviewedAt` persists, so they land in Chat, not New.

### New connections already hold chat write

Chatting requires write access to the owner's chat drive, and that grant is
typically issued the moment a connection forms — a deliberate bootstrapping
feature (without it, building a chat network would be impractical). Consequence:
the New → Chat transition usually changes **no grants at all** — it is purely the
`connectionReviewedAt` stamp. This also suggests a candidate answer to open
question 5: confirming may grant literally nothing beyond the selected circles,
making the review a pure client-side record.

### The `PERSONAL | AUDIENCE | SERVICE` designation

Rides the in-progress backend work where circles and drives belong to an app: the
circle registration record carries the designation, set by the owning app, with
user-created circles minted under the profile app and defaulting to `PERSONAL`. An enum, not a boolean — history
(the Confirmed Connections system circle) said new circle kinds would appear, and
`SERVICE` was discovered during review of this very proposal, before the schema
even shipped. The **shared** piece is the designation on the circle record —
every client can filter consistently. Which kinds an app *surfaces* is that
app's choice: this app's contact book derives states exclusively from `PERSONAL`
circles and shows `AUDIENCE` only inside feed's subscriber management; `SERVICE`
circles are invisible here and belong to whichever app owns them.

The designation never participates in ACL evaluation — that role was considered
and rejected (see *The `connected` ACL tier* below); it remains presentation and
filtering only.

**Why personal circles are the ones that count:** the state ladder measures
**read access — what they can see of you** ("you are choosing what this person
can see"). Write grants let people *give* you things (chat messages, receipts,
statements); read grants let people *see* you; only the second is intimacy. A
bank with receipts-write is deposit-only and sits in the Chat state without
contradiction. Note the **designation is the normative rule** — explicit and
auditable; the read/see framing is the rationale. We deliberately do *not*
derive states from grant plumbing directly, so a permissions change can never
silently reclassify a contact.

### Per-circle emoji

An optional `emoji: String?` on the same circle registration record — one backend
ask together with the designation enum. Owning apps may preset it for their
circles (📡 for Subscribers). Implementation cautions:

- User emoji are often **multi-codepoint ZWJ sequences** (🧑‍🧑‍🧒‍🧒) — store and
  render the full string, never substring it (the Strings & Unicode rules apply).
- Desktop JVM emoji fonts can lag the newest sequences — the name-pill fallback
  doubles as the can't-render fallback.
- The vowel-dropping abbreviation is **Latin-script-specific**; for other scripts
  fall back to codepoint truncation (`truncateToCodePoints`). The full circle
  name is always the `contentDescription` — screen readers pronounce ZWJ
  sequences unpredictably, so the emoji is never the semantic label.

### System circles — inventory and disposition

Exactly two system circles exist today (client constants in `AppConfig.kt`;
grants in odin-core's `CircleConstants.cs`). The code inventory showed their
drive-grant bundles are **identical** — Write|React on the chat/lists/moments/
mail/feed drives. Confirmed adds only: ShardRecovery write, the
`AllowIntroductions` key, feed-distribution eligibility, optional
ReadWhoIFollow/ReadConnections keys, and the right to be granted further circles
(the 3010 lockout).

| System circle | Today's role | Disposition |
|---|---|---|
| **Confirmed Connections** (`bb2683fa…`) | Membership = the server-computed `vetted` flag (#919); the target of "confirm" | **Dissolves** into per-app `VERIFIED_CONNECT` default circles plus explicit review-dialog toggles (enrollment model below) |
| **Auto Connections** (`9e22b429…`) | Where auto-connected identities land; the carrier of their baseline grants — how "New connections already hold chat write" is implemented. Shown today in the Circles tab renamed "Unvetted" | **Dissolves** into per-app `AUTO_CONNECT` default circles. The "New" filter chip replaces its user-facing role |

**The enrollment model.** Backend spec: `docs/drive-addressing.md` on odin-core
PR #1589 — the single source of truth for the schema; this doc describes only
client behavior. Each app declares default circles with an `Enrollment` marker:

- `AUTO_CONNECT` circles enroll on auto-connection with no owner action — gated
  by a standing per-app toggle in the owner console (the app declares, the owner
  disposes). Bound by the **deposit-only invariant**: write/react grants only,
  no read beyond public, no permission keys.
- `VERIFIED_CONNECT` circles enroll when the owner completes the connection
  review — the per-app toggles in section 4C's modal. These may carry read
  grants: **the review is the key ceremony**, the moment read-bearing grants can
  be minted.

Consequence for the ladder: New 👋 and Chat 💬 hold zero read keys **by
construction** — "the states measure read access" upgrades from rationale to
enforced property. Default circles carry no special designation — their
rendering keys off `Enrollment`: never a pill, never a state, visible in the
Circles tab as a distinct group. Clients get to delete their hardcoded GUID
knowledge (today's `circleSortRank()` pinning and the "Unvetted" display
rename).

Naming bonus: if the chat app names its auto-connect circle **"Chat-only"**, the
💬 state is literally that circle's membership made visible — the state icon,
the review button's "💬 Chat only" label, and the circle's own member list
("who can message me?") collapse into one concept. New 👋 and Chat 💬 are both
members; the review stamp is what separates them. Removing someone from the
circle is a mute softer than blocking.

One coupling to know: a `VERIFIED_CONNECT` circle keeps whatever designation its
app chose. If an app designates its verified default `PERSONAL` (e.g. feed
distribution as a personal grant), its review toggle *is* a circle selection —
enrolling it yields ⭕, and the adaptive button must count it.

Migration items:

1. ~~Grant inventory~~ — **done** (the delta list above); each Confirmed extra
   becomes an explicit review toggle (feed distribution, introductions) or a
   deliberate grant (shard recovery).
2. **Profile-field ACLs** — today's "Vetted" fields are ACL'd to the bare
   `connected` security group, not to the Confirmed circle. They must be
   re-secured as app-maintained personal-circle ACLs (next subsection).
3. ~~Baseline carrier for direct connections~~ — **answered** by the inventory:
   both origins route through the system circles (`CircleNetworkUtils`); under
   enrollment the question dissolves — a manual accept goes through the review,
   an auto-accept uses the enabled `AUTO_CONNECT` set.

### The `connected` ACL tier — retire it

What the code inventory established (odin-core `DriveAclAuthorizationService`):
`Connected` and `AutoConnected` ACLs are evaluated as **one case**, and no caller
is ever stamped `autoconnected` — a `connected` ACL admits **every** connection,
unreviewed auto-connections included. The tier never enforced what its label
promised (hence the bullet in section 1), and `autoconnected` as an ACL value is
accidental semantics: it silently disables feed distribution and mis-buckets
result priority.

Decision:

- **Retire both as ACL targets.** No user-facing surface offers "connected" as
  an audience again; `autoconnected` is deleted outright.
- The literal string `connected` survives on the wire **only as the carrier of
  circle-scoped ACLs** (`requiredSecurityGroup: connected` + `circleIdList` is
  how every circle ACL is already encoded) — retiring the *bare* form needs no
  wire change and no file migration.
- **The perimeter is untouched.** `Caller.IsConnected` checks (inbox push, peer
  websocket, transit) test the wire, not a read audience.
- **"Any of my circles" becomes an app-maintained enumerated ACL.** Setting a
  field to "Any of my circles" writes the concrete list of personal circles this
  app manages and tags the attribute (in app data) as meaning-any; creating or
  deleting a circle reconciles every tagged attribute. Fail-closed: if
  reconciliation lags, a new circle temporarily *doesn't* see the field —
  instead of a stranger temporarily seeing it.
- Legacy mapping is behavior-identical: existing bare-`connected` files read as
  "member of any `AUTO_CONNECT` circle" — the same admit set the evaluator
  produces today.

**Rejected alternative — recorded so it isn't reinvented:** a
designation-qualified tier (`connected(PERSONAL)`: "any connection in at least
one PERSONAL circle", evaluated dynamically). Attractive because it never goes
stale; rejected because it is **ambient authority resting on a distributed
judgment** — any app that ever registers a mis-designated circle silently widens
every ACL referencing the qualifier. A global predicate cannot be built on
per-app semantics. The enumerated-and-reconciled form keeps the semantics inside
the app that owns them, and fails closed.

### Audience circles at scale

Audience members must **never materialize as local contact records** — a feed
with a million subscribers cannot sync a million contact files to a phone. They
exist server-side as connection registrations plus audience-circle membership,
and the owning app browses them with **paged server queries** (member count, a
search box, pages of ~50 — which is also where a creator actually thinks about
subscribers).

The contact book's completeness promise is therefore **logical, not physical**:
everything personal syncs locally and works offline; everything else is
answerable on demand via a server-backed "All connections" search ("does
alice.demo.rocks hold anything of mine?" — paged, shows the union of personal and
audience grants). Block and disconnect work from that search result or from the
member list — no local record needed.

Boundary crossing: when an audience member becomes personal (you review them and
add them to Friends), **that** is the moment a contact file is created and starts
syncing — the promotion is also a storage-tier transition. The reverse applies on
removal, or the address book slowly accretes ex-subscribers.

## 9. Open Questions for Discussion

1. ~~Should "Any of my circles" be the default selection when someone opens the Select
   circles dialog, or should nothing be pre-selected?~~ **Largely resolved** by the
   explicit secondary path: pre-selecting "Any of my circles" is safe because
   declining is explicit — the review button relabels to "💬 Chat only".
   Remaining detail: does the *field visibility* dialog also default to "Any of my
   circles"? (Proposed: yes.)
2. Do we want to show a small visibility pill next to each field in the main Edit
   Profile view (e.g. "Public" or "3 circles")?
3. ~~Should we allow users to create circles directly from the review flow?~~
   **Resolved: yes, from both** the profile editor's visibility picker and the
   review dialog — inline creation mints the circle under the profile app; the
   Circles tab remains the management home (members, emoji, description).
4. Should Chat (no circles) contacts show a subtle 💬 indicator, or no
   indicator at all? (The circle badge is reserved for circle membership either way.)
5. ~~What does confirming actually grant server-side today?~~ **Resolved by the
   code inventory** (section 8): ShardRecovery write, `AllowIntroductions`,
   feed-distribution eligibility, optional ReadWhoIFollow/ReadConnections, and
   lifting the auto-connected circle lockout (3010). Each becomes an explicit
   review toggle or a deliberate grant; the defaults strip shows the per-app
   verified-connect defaults.
6. **Which state-name set wins — New / Chat / Circle or New / Known / Trusted?**
   And with it, the emoji/icon triple (👋 💬 ⭕ vs 👋 🤝 🛡️). See section 3 for
   the trade-offs. The audience case (section 2) is a concrete strike against
   option B: a paid subscriber you've never met must not read as "Trusted".
7. Where does the server-backed **"All connections" audit view** live — inside
   the contact book (a search mode / separate tab) or under settings/security?
   It must show every connected identity with the union of its grants (personal
   and audience), paged from the server (section 8).
8. ~~Baseline carrier for direct connections?~~ **Resolved** — both origins route
   through the system circles today (`CircleNetworkUtils`); under enrollment the
   question dissolves: a manual accept goes through the review, an auto-accept
   uses the enabled `AUTO_CONNECT` set (section 8).
9. ~~Confirm the `connected` vs `autoconnected` evaluation order and plan the ACL
   sweep.~~ **Resolved** — the evaluator folds both into one case and never
   stamps callers `autoconnected`; legacy bare-`connected` maps
   behavior-identically to "member of any `AUTO_CONNECT` circle" (section 8).

---

This proposal keeps the experience simple for everyday use while unlocking the powerful
per-circle customization we want. It directly addresses the feedback that "Vetted" is
confusing.
