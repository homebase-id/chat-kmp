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
- You **review** them: confirming the connection is the review. Optionally, you add
  them to one or more circles at the same time.
  → Confirming is the moment they become a "real" connection in your network.
- The circles you choose determine **what they can see** of your profile and what
  special access they get.
- **Circles are optional.** You can confirm someone without adding them to any
  circle: they become **Connected** — you can chat, but they see only your public
  profile. This is a perfectly normal outcome of a review, not a dead end.
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
| —          | **Connected**                          | Reviewed & confirmed, in no circles; sees public info only, chat works |
| —          | **Any of my circles**                  | New easy default in visibility picker  |

## 4. Proposed Changes by Screen

### A. Contacts List

- Keep **Contacts | Circles** tabs.
- Replace the three filters (All / Unvetted / Vetted) with horizontal chips:
  - **All**
  - **New** (with count badge when there are introductions to review)
  - Your actual circles as filter chips (Friends, Family, Beer Drinking Buddies, etc.)
- Contact rows communicate one of **three states**:
  - **New** — introduced, not yet reviewed. Gets a prominent
    **"Review & Add to circles"** action.
  - **Connected** — reviewed and confirmed, in no circles. No pills, no badge (or a
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

**Circle selection is optional.** Deselecting everything is a legitimate choice —
the primary button adapts to make the outcome explicit:

- ≥ 1 circle selected → **"Confirm & Add to Circles"**
- nothing selected → **"Confirm connection"** with helper text: *"They'll see your
  public profile only. You can add them to circles anytime."*

Declining circles does **not** block chatting — the connection is what enables chat;
circles only govern extra profile visibility and permissions.

This makes the consequence of confirming very clear: "You are choosing what this person
can see."

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

**Phase 2**

- Update field visibility picker in Edit Profile to Public / My circles + Select
  circles dialog
- Improve Circles tab with cards and explainer

**Phase 3**

- Add per-circle override capability for fields and photos

## 8. Open Questions for Discussion

1. ~~Should "Any of my circles" be the default selection when someone opens the Select
   circles dialog, or should nothing be pre-selected?~~ **Largely resolved** by the
   adaptive confirm button: pre-selecting "Any of my circles" is safe because
   deselecting everything is an explicit, labeled path ("Confirm connection" —
   public only). Remaining detail: does the *field visibility* dialog also default
   to "Any of my circles"? (Proposed: yes.)
2. Do we want to show a small visibility pill next to each field in the main Edit
   Profile view (e.g. "Public" or "3 circles")?
3. Should we allow users to create circles directly from the confirm connection flow,
   or only from the Circles tab?
4. Should Connected (no circles) contacts show a subtle neutral indicator, or no
   indicator at all? (The circle badge is reserved for circle membership either way.)

---

This proposal keeps the experience simple for everyday use while unlocking the powerful
per-circle customization we want. It directly addresses the feedback that "Vetted" is
confusing.
