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
- When someone is introduced to you, they start as a lightweight connection.
- **Every connection starts as "New" until reviewed** — introduced,
  auto-connected, and plain direct connections alike (today's "Unvetted" bucket
  maps 1:1 onto New). Being connected alone never moves a contact; the only path
  out of New is completing the review.
- You **review** them. A review is **triage, not endorsement** — it records that
  you looked and what you decided. It has four honest outcomes: add them to
  circles, keep them as a plain connection, disconnect, or block.
- The circles you choose determine **what they can see** of your profile and what
  special access they get.
- **Circles are optional.** Keeping someone as a plain connection makes them
  **Connected** — you can chat, they see only your public profile, and nothing
  about the relationship changed: the connection was exactly as real before you
  reviewed it. The review only records your decision.
- There is **no special "Vetted" system circle**. Being in any of your circles
  implies reviewed and connected — but the converse doesn't hold: a contact can be
  reviewed and connected while being in no circles at all.
- Removing someone from their last circle drops them back to **Connected** — not to
  "New", as if you'd never reviewed them.

**Blue check / circle badge** on contacts = This person is in one or more of my
circles. Connected-but-circleless contacts carry no badge (or a subtle neutral
check); the badge is evidence of access granted, not the only evidence of review.

## 3. Recommended Terminology Changes

| Current    | Recommended                            | Notes                                  |
|------------|----------------------------------------|----------------------------------------|
| Vetted     | My circles / Circle members            | Primary replacement                    |
| Unvetted   | New connections / Introduced           | Clearer that action is needed          |
| Blue check | Circle badge or "In circles" indicator | Shows membership                       |
| —          | **Connected**                          | Reviewed, kept as a plain connection; sees public info only, chat works |
| —          | **Any of my circles**                  | New easy default in visibility picker  |

## 4. Proposed Changes by Screen

### A. Contacts List

- Keep **Contacts | Circles** tabs.
- Replace the three filters (All / Unvetted / Vetted) with horizontal chips:
  - **All**
  - **New** (with count badge when there are introductions to review)
  - Your actual circles as filter chips (Friends, Family, Beer Drinking Buddies, etc.)
- Contact rows communicate one of **three states**:
  - **New** — connected (introduced, auto-connected, or direct) but not yet
    reviewed. Gets a prominent **"Review & Add to circles"** action.
  - **Connected** — reviewed, kept as a plain connection. No pills, no badge (or a
    subtle neutral check). Sees public info only.
  - **In circles** — small **colored circle pills/tags** (e.g. "Friends", "Family")
    instead of (or next to) the blue check.
- Tapping a contact shows their public profile + clear call-to-action to add them to
  circles.

**Result:** The list immediately communicates "who I've reviewed, and what access
I've granted them".

### B. Edit Profile + Field Visibility

**Main Edit Profile screen**

- Keep **Public** section as-is.
- Rename the second section from "Vetted" to **"Visible to my circles"**.
- Subtext: "Additional details shown to anyone you've added to your circles."

**When editing any field** (Birthday, photo, email, status, etc.):

Replace the current **Public / Vetted** segmented control with:

**Public** | **My circles**

- **Public** → visible to everyone (current behavior).
- **My circles** → opens the **Select circles** dialog (see below).

**Select circles dialog**

- Header: "Visible to my circles"
- Top prominent option:
  **☑ Any of my circles**
  *Visible to anyone in at least one of your circles.*
- Below: Checkbox list of all your circles.
- User can select "Any of my circles" **or** pick one or more specific circles.

This single pattern supports both simple use and the advanced "special beer drinking
buddies photo" case.

### C. Confirm Connection Flow

Combine the explanatory text and circle selection into **one clean modal**:

> **Confirm connection to biggus.dickus.demo.rocks**
>
> You were introduced by samwise.gamgee.demo.rocks.
> Add them to one or more circles so they can see more of your profile.
>
> - **Any of my circles** (prominent, pre-selected by default)
> - List of your circles with toggles/checkboxes
> - Special permission circles (Emergency Location Access) stay visually distinct
>
> **[ Confirm & Add to Circles ]**  (big primary button)
>
> Cancel

**Two completion paths — both stamp the review:**

- **Confirm** (primary) — applies the selected circles *and* the connection
  defaults: whatever confirming enables today beyond circle grants (e.g. follow
  their feed by default, accept introductions they relay, identity verification —
  see open question 5). These defaults are shown as **visible toggles in the
  modal**, not hidden side effects — hidden side effects are how "Vetted" got
  confusing in the first place.
- **Keep as connection** (secondary, quieter) — no circles, all defaults off. For
  the contact you'll talk to but don't want to endorse: the landlord, the seller,
  the introduction you're lukewarm about. Helper text: *"They'll see your public
  profile only. You can add them to circles anytime."*

Both buttons complete the review (stamp `connectionReviewedAt`, section 8) and move
the contact out of New — into **In circles** or **Connected** respectively.

**Disconnect / Block** stay available as tertiary actions (overflow menu or footer
link) — a review that can only end in approval isn't a review.

**Is the second button worth having?** Only if confirmation genuinely carries
defaults. Two buttons that differ merely in preset checkbox states would be silly —
the user can uncheck things themselves. If the answer to open question 5 is that
confirming grants nothing beyond the selected circles, collapse to a single neutral
**Done** button and let the visible toggles speak for themselves.

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
- The confirm connection flow now clearly explains the privacy consequence.
- The mental model is consistent across Contacts, Profile, and Connection flows.
- Scales well as users create more custom circles.

## 7. Suggested Phasing

**Phase 1 (Quick win)**

- Rename "Vetted" → "My circles" / "Visible to my circles" everywhere
- Update blue check meaning and contact list indicators
- Improve confirm connection modal with "Any of my circles" option
- Stamp `connectionReviewedAt` in the contact's localAppData when the confirm
  dialog completes (see section 8) — required as soon as confirming with zero
  circles is possible, so the Connected state survives across the user's devices

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
that system circle. And a **Connected** contact has, by definition, zero circle
grants — so there is nothing left to infer "reviewed" from. Without explicit storage,
Connected and New would be indistinguishable.

**Where it lives.** Record it in the contact file's **localAppData JSON**
(`fileMetadata.localAppData.content`) on the contacts drive — the same pattern
conversations already use (`ConversationLocalAppDataJson` with `lastReadTime` etc.).
A new `ContactLocalAppDataJson`:

```kotlin
@Serializable
data class ContactLocalAppDataJson(
    /**
     * Stamped when the user completes the connection review — via either
     * Confirm or Keep as connection — whether or not any circles were
     * selected. Null = never reviewed.
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
only promotion out of New is completing the review dialog via either button (which
stamps `connectionReviewedAt`); the legacy carve-outs below are the sole exceptions.

| State       | Condition                                                    |
|-------------|--------------------------------------------------------------|
| New         | connected, `connectionReviewedAt == null`, no circle grants  |
| Connected   | `connectionReviewedAt != null`, no circle grants             |
| In circles  | ≥ 1 circle grant (implies reviewed)                          |

**Migration / legacy:**

- Existing contacts with `vetted == true` (Confirmed Connections membership): treat as
  reviewed on read, and backfill `connectionReviewedAt` opportunistically.
- Contacts with circle grants but no stamp (e.g. circles granted from another surface):
  treat as reviewed — membership is itself evidence of review.
- Removing someone from their last circle requires **no localAppData change**:
  `connectionReviewedAt` persists, so they land in Connected, not New.

## 9. Open Questions for Discussion

1. ~~Should "Any of my circles" be the default selection when someone opens the Select
   circles dialog, or should nothing be pre-selected?~~ **Largely resolved** by the
   explicit secondary path: pre-selecting "Any of my circles" is safe because
   declining is its own labeled button ("Keep as connection" — public only).
   Remaining detail: does the *field visibility* dialog also default to "Any of my
   circles"? (Proposed: yes.)
2. Do we want to show a small visibility pill next to each field in the main Edit
   Profile view (e.g. "Public" or "3 circles")?
3. Should we allow users to create circles directly from the confirm connection flow,
   or only from the Circles tab?
4. Should Connected (no circles) contacts show a subtle neutral indicator, or no
   indicator at all? (The circle badge is reserved for circle membership either way.)
5. **What does confirming actually grant server-side today, beyond Confirmed
   Connections membership?** Candidates: identity verification
   (`hasVerificationHash`), accepting future introductions relayed by this person,
   following their feed by default. The two-button design in section 4C stands or
   falls with this answer — if confirming grants nothing beyond the selected
   circles, collapse Confirm / Keep as connection into a single neutral **Done**
   button with visible toggles.

---

This proposal keeps the experience simple for everyday use while unlocking the powerful
per-circle customization we want. It directly addresses the feedback that "Vetted" is
confusing.
