# Creating GitHub Issues for chat-kmp

This is the playbook Claude follows when the user asks it to file a bug or feature
issue against the **`homebase-id/chat-kmp`** repository. The user describes the
problem in chat; Claude turns it into a well-structured, plan-ready GitHub issue and
creates it with the `gh` CLI.

The goal: every issue body should read like a **`/plan` prompt** — enough context,
evidence, and scope that a Claude agent (or a human) could open the repo and produce a
reasonable implementation plan without a back-and-forth.

---

## Quick reference

- **Repo:** `homebase-id/chat-kmp`
- **Tool:** `gh issue create`
- **Issue author (current gh login):** `Seifert69`
- **Assignable users:** `2002Bishwajeet`, `acarlsen`, `odindevops`, `sebbarg`,
  `Seifert69`, `stef-coenen`, `toddmitchell`, `xcarpentier`
- **Labels in use:** `bug`, `enhancement`, `documentation`, `question`,
  `help wanted`, `good first issue`, `duplicate`, `invalid`, `wontfix`

---

## The process (what Claude does each time)

1. **Read this file first.** It defines the body format, labels, and assignee rules.
2. **Classify** the request: bug, enhancement, or other. Pick the matching label(s)
   and body template below.
3. **Draft the body** as a `/plan`-ready prompt (see template). Fill every section
   that applies; omit sections that genuinely don't (don't leave empty headings).
4. **Resolve the assignee.** If the user did **not** say who to assign, **ask** —
   never guess or leave it unassigned silently. (See "Assignees".)
5. **Handle attachments.** If the user attached files, process them per the
   "Attachments" section before creating the issue.
6. **Confirm the title + labels + assignee** in chat (one line), then create the
   issue with `gh`.
7. **Report back** the issue URL that `gh` prints.

### Updating an issue after new information (corrections)

When the user clarifies, corrects, or adds evidence to an **already-filed** issue,
**prefer editing the main issue description in place** (`gh issue edit <n> --body-file`)
over appending a comment. The top description must always read as the single, current
source of truth — a `/plan` agent (or a human) should get the correct, consolidated
picture from the body alone, without scrolling a trail of "correction:" comments.

- **Fold corrections into the relevant sections** (reproduction, root cause, evidence,
  acceptance) and update the **title** if the framing changed.
- If earlier correction **comments** you posted are now fully folded into the body,
  **delete them** to keep the thread clean
  (`gh api -X DELETE repos/homebase-id/chat-kmp/issues/comments/<comment_id>`).
- Reserve **comments** for genuinely additive discussion that isn't a correction to the
  spec (e.g. a question back to the assignee, a cross-link, a status note) — not for
  restating what the issue now is.
- Rewriting the whole body is cheap: fetch it (`gh issue view <n> --json body --jq .body`),
  edit, and re-`--body-file`. Do that rather than stacking deltas.

---

## Assignees

> **If the user forgets to say who to assign an issue to, ask before creating it.**

Phrase it simply, e.g. *"Who should I assign this to?"* and offer the known
assignable users. Do not invent usernames; only use names from the assignable list
above (refresh it with `gh api repos/homebase-id/chat-kmp/assignees --jq '.[].login'`
if someone new joined).

Pass the chosen user with `--assignee <login>`. Multiple assignees are allowed
(`--assignee a --assignee b`).

---

## Attachments

When the user attaches files to the chat, include them in the issue.

- **Text-ish files** — stack traces, `homebase.log` excerpts, `adb logcat` dumps,
  JSON descriptors, crash tombstones, diffs:
  **embed inline** in the issue body inside a fenced code block, under a
  `<details>` block if long. This is the most reliable path and keeps the evidence
  with the issue.

  ```
  <details><summary>homebase.log (relevant excerpt)</summary>

  ```text
  ...paste...
  ```
  </details>
  ```

- **Images / screenshots / binary files:**
  GitHub's drag-and-drop attachment CDN (`user-attachments`) is **not** reachable
  from the `gh` CLI. But **`homebase-id/chat-kmp` is a public repo**, so a committed
  file's `raw.githubusercontent.com` URL **renders inline** in the issue body with no
  auth. The working method:

  1. Copy the attachment into a worktree on a dedicated long-lived **`issue-assets`**
     branch (keeps `main` and feature branches clean), under
     `docs/issue-assets/<short-issue-slug>/<filename>`:
     ```bash
     # create the branch from main the first time, or check out the existing one
     git worktree add -B issue-assets /tmp/issue-assets-wt origin/main   # first time
     # (if it already exists on origin: git worktree add /tmp/issue-assets-wt issue-assets)
     cp <uploaded-file> /tmp/issue-assets-wt/docs/issue-assets/<slug>/<filename>
     cd /tmp/issue-assets-wt && git add -A && git commit -m "issue-assets: <slug> ..." && git push -u origin issue-assets
     git worktree remove /tmp/issue-assets-wt   # clean up; return to the original branch
     ```
  2. Embed it in the issue body via the raw URL (verify it returns HTTP 200 with
     `curl -so /dev/null -w '%{http_code}'` before creating the issue):
     ```
     ![description](https://raw.githubusercontent.com/homebase-id/chat-kmp/issue-assets/docs/issue-assets/<slug>/<filename>)
     ```

  Pushing to the `issue-assets` branch is authorized by the standing "upload any files
  I attach" instruction — it never touches `main` or the working feature branch. If
  the repo ever goes private, this stops rendering (raw URLs would then need auth); in
  that case fall back to telling the user to drag the image into the issue on
  github.com.

If you're unsure whether an attachment is text or binary, prefer inline embedding for
anything human-readable.

---

## Body template — Bug

Use label `bug` (add others as relevant). Title: short, specific, imperative or
symptom-first — e.g. *"Live location map freezes UI when conversation has 0 items"*.

```markdown
## Summary
<One or two sentences: what's broken and the user-visible impact.>

## Environment
- Platform(s): <Android / iOS / Desktop (macOS|Windows|Linux) / Web>
- Build: <debug|release>, version / commit if known
- Device / OS: <e.g. Pixel 7, Android 14 — or "all platforms">

## Steps to reproduce
1. …
2. …
3. …

## Expected vs actual
- **Expected:** …
- **Actual:** …

## Evidence
<Stack trace, ANR dump, logcat/homebase.log excerpt, screenshot path, profiler
sample. Per CLAUDE.md's "Debugging & root cause" rule, an issue is stronger with
concrete evidence than with a guessed cause. If no evidence is captured yet, name the
instrumentation that would capture it.>

## Suspected area (optional)
<Module + file(s) you suspect, e.g. homebase-chat ConversationContent.kt. Don't
assert a root cause you haven't proven — frame as a lead.>

## Scope for the plan
<What a fix should and shouldn't touch. Constraints (KMP targets affected, must not
regress X). This is the part that makes it a good `/plan` prompt.>
```

## Body template — Enhancement / Feature

Use label `enhancement`. Title: capability-framed — e.g. *"Add tap-to-share live
location duration picker"*.

```markdown
## Problem / motivation
<What can't the user do today, or what's awkward? Why does it matter?>

## Proposed behaviour
<Describe the desired UX/flow. Reference existing patterns in the app where
relevant — e.g. "like the Event composer in ADDING_TYPED_MESSAGE_KIND.md".>

## Affected modules / targets
<homebase-api / common / chat / core; which platforms.>

## Design / constraints
<Material 3 requirements, string-resource rules, descriptor size budgets, etc. —
call out the CLAUDE.md rules the implementer must respect.>

## Acceptance criteria
- [ ] …
- [ ] …

## Scope for the plan
<Boundaries: what's in, what's explicitly out of scope for this issue.>
```

---

## Creating the issue with `gh`

Write the body to a temp file (avoids shell-quoting pain with multi-line markdown),
then:

```bash
gh issue create \
  --repo homebase-id/chat-kmp \
  --title "<title>" \
  --label bug \
  --assignee <login> \
  --body-file /tmp/issue-body.md
```

Notes:
- Use `--body-file`, not `--body`, for anything longer than one line.
- Multiple labels: repeat `--label`. Multiple assignees: repeat `--assignee`.
- `gh` prints the new issue URL on success — relay it to the user.
- To verify afterward: `gh issue view <number> --repo homebase-id/chat-kmp`.

---

## Conventions / house style

- **Title:** specific and searchable. Lead with the symptom for bugs, the capability
  for features. No trailing period.
- **One issue per problem.** Don't bundle unrelated bugs.
- **Correct in place, not in comments.** When new info arrives for a filed issue, edit
  the body (and title) so it stays the single source of truth; delete correction
  comments once folded in. See "Updating an issue after new information".
- **Plan-ready over terse.** Err toward more context. The "Scope for the plan"
  section is what separates a filed ticket from a `/plan` prompt.
- **Evidence over guesses** (see CLAUDE.md). Prefer a stack trace to a hunch; if the
  cause is unproven, label it a lead, not a conclusion.
- **Respect repo rules** when describing fixes: Material 3, `stringResource()` for all
  user-facing text, RTL padding, no duplicated envelope fields in descriptors, etc.
- Don't create labels or milestones on the fly; if a new label seems needed, ask the
  user first.
