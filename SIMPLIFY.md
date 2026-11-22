# Simplify

The first of a PR's two passes (CLAUDE.md, "Simplify, then code review"), for a PR that changes real code. It
makes the changed code simpler without changing what it does, so the code review then reads the cleaned-up
diff. Start it with exactly the prompt in CLAUDE.md, and read this before it runs.

## What it looks for

The `/simplify` skill's four angles: reuse, simplification, efficiency and altitude. Not correctness bugs:
those are the review's.

In this repo, in particular:

- **A helper that already exists.** The one place for each job is named in CLAUDE.md: content types
  (`ContentTypes.kt`), file names (`safeFileName`), the upload builder ("User uploads"), user text
  (`truncateToCodePoints`, `initials`: "Strings & Unicode"), scroll indices ("Compose & Flow gotchas"). Reading
  and stripping metadata is `MediaMetadataReader` and `MediaMetadataStripper` (UPLOADS.md, section 3.4).
- **Platform code that could be shared.** One implementation where possible, in a shared source set
  (CLAUDE.md, "Platform-specific code"); never an `actual` copied from another platform.
- **Comments.** The diff's comments follow CLAUDE.md, "Comments": none by default, at most one terse why.
- **Work on a hot path.** The phones back up thousands of photos in short windows: an extra read, decode or
  encode per photo is worth a finding (THUMBNAILS.md, "The goal").

A finding that is a design change rather than a cleanup is filed, not added to the PR.

## What it may fix

A finding may be fixed on the branch when it is:

- in code the change adds or touches; or
- a cleanup anywhere that keeps behaviour the same: merging a near-copy, deleting dead code, calling a helper
  that already exists. Touching other parts of the code this way is welcome; or
- a change to tests anywhere, as long as no coverage is lost of how the system is still supposed to work: a
  test may be fixed, isolated, merged, moved or rewritten, but what it checked stays checked.

A fix that changes how a flow the change did not set out to change behaves is out of scope, however real the
finding (password reset, recovery, login, another app's sign-in). List it with the finding instead, and file
it if the person who asked for the work agrees. The test before fixing: name the flow whose behaviour the fix
changes. If the PR's description, or the task, doesn't name it, don't fix it: ask.

When new code seems to break a rule written for an older flow, ask whether the rule should cover the new code
before changing either.

## The commit

The pass ends in one commit, even when it changes nothing (`git commit --allow-empty`). A SIMPLIFY commit keeps
behaviour: a behaviour change in it is either a bug or something its body calls out.

```
SIMPLIFY: <what the cleanup did, or "nothing to change">

<each finding fixed, and each one skipped with why>

Simplify-Found: <findings, after merging the agents' duplicates>
Simplify-Fixed: <how many of them were fixed>
Code-Lines: <scripts/pass-stats.py>
```

- **`Found`** counts each distinct finding once, however many agents reported it. **`Fixed`** counts the ones
  fixed; every other one is in the body with its reason.
- **`Code-Lines`** is measured, never counted by hand: stage the fixes, run `scripts/pass-stats.py`, paste its
  line, and commit with a plain `git commit` (no `-a`, no paths), so the commit holds exactly what was measured.
  `scripts/pass-stats.py HEAD` gives the same line afterwards.
- **The PR description** repeats the counts, with what was fixed and what was skipped.
