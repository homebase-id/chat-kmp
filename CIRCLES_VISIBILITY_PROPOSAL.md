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

## 4. Proposed Changes by Screen

### A. Contacts List

- Keep **Contacts | Circles** tabs.
- Replace the three filters (All / Unvetted / Vetted) with horizontal chips:
  - **All**
  - **New** (with count badge when there are introductions to review)
  - Your actual circles as filter chips (Friends, Family, Beer Drinking Buddies, etc.)
- Contact rows communicate one of **three states**:
  - **New** 👋 — connected (introduced, auto-connected, or direct) but not yet
    reviewed. Gets a prominent **"Review"** action.
  - **Chat** 💬 — reviewed, kept chat-only. No circle pills. Sees public info only.
  - **Circle** ⭕ — small **colored circle pills/tags** (e.g. "Friends", "Family")
    instead of (or next to) the blue check.
- Tapping a contact shows their public profile + clear call-to-action to review the contact (so it's no longer new).

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
> - Connection defaults as visible toggles (e.g. "Follow their feed")
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
  the connection defaults: whatever confirming enables today beyond circle grants
  (e.g. follow their feed by default, accept introductions they relay, identity
  verification — see open question 5). These defaults are shown as **visible
  toggles in the modal**, not hidden side effects — hidden side effects are how
  "Vetted" got confusing in the first place.
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

## 7. Suggested Phasing

**Phase 1 (Quick win)**

- Rename "Vetted" → "My circles" / "Visible to my circles" everywhere; pick the
  state-name set (section 3) and its emoji/icon triple
- Update blue check meaning and contact list indicators
- Improve connection review modal with "Any of my circles" option
- Stamp `connectionReviewedAt` in the contact's localAppData when the review
  completes (see section 8) — required as soon as a chat-only review outcome
  is possible, so the Chat state survives across the user's devices

**Phase 2**

- Update field visibility picker in Edit Profile to Public / My circles + Select
  circles dialog
- Improve Circles tab with cards and explainer

**Phase 3**

- Add per-circle override capability for fields and photos

## 8. Implementation Note: Recording the Review

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

## 9. Open Questions for Discussion

1. ~~Should "Any of my circles" be the default selection when someone opens the Select
   circles dialog, or should nothing be pre-selected?~~ **Largely resolved** by the
   explicit secondary path: pre-selecting "Any of my circles" is safe because
   declining is explicit — the review button relabels to "💬 Chat only".
   Remaining detail: does the *field visibility* dialog also default to "Any of my
   circles"? (Proposed: yes.)
2. Do we want to show a small visibility pill next to each field in the main Edit
   Profile view (e.g. "Public" or "3 circles")?
3. Should we allow users to create circles directly from the review flow,
   or only from the Circles tab?
4. Should Chat (no circles) contacts show a subtle 💬 indicator, or no
   indicator at all? (The circle badge is reserved for circle membership either way.)
5. **What does confirming actually grant server-side today, beyond Confirmed
   Connections membership?** Candidates: identity verification
   (`hasVerificationHash`), accepting future introductions relayed by this person,
   following their feed by default. This decides what the defaults strip in the
   review modal contains — if confirming grants nothing beyond the selected
   circles, the strip disappears entirely (the adaptive button is unaffected).
6. **Which state-name set wins — New / Chat / Circle or New / Known / Trusted?**
   And with it, the emoji/icon triple (👋 💬 ⭕ vs 👋 🤝 🛡️). See section 3 for
   the trade-offs.

---

This proposal keeps the experience simple for everyday use while unlocking the powerful
per-circle customization we want. It directly addresses the feedback that "Vetted" is
confusing.
