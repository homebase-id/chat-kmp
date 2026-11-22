# Code review

The second of a PR's two passes (CLAUDE.md, "Simplify, then code review"), after the simplify pass's commit.
Start it with exactly the prompt in CLAUDE.md, and read this before it runs.

## What it checks

Correctness first: what the diff gets wrong, with a concrete failure. Then the diff against this repo's rules,
each in CLAUDE.md under the section named, and reported like a bug when broken:

- **"Debugging & root cause":** a fix with no evidence of the cause, or one of the symptom patches listed there.
- **"Platform-specific code":** new platform code (in `androidMain`, `nativeMain`, `appleMain`, `jvmMain`,
  `wasmJsMain` and the like) that isn't behind one common contract whose KDoc states the behaviour all four
  platforms must match; an `actual` copied from another platform; platform behaviour tested only by
  `jvmTest` or a fake.
- **"User uploads", THUMBNAILS.md and UPLOADS.md:** code that goes against them, a second builder, detector,
  name rule or sealing step, or a new entry on an `ArchitectureTest` allowlist.
- **"Comments", "UI & Design Quality", "Compose & Flow gotchas", "Strings & Unicode" and "Phone numbers &
  email addresses in the UI".**
- **"Opening a PR":** one problem per PR, and the PR facts block.
- **Tests:** what a changed or removed test checked is still checked somewhere.

## What it may fix

The same as the simplify pass: SIMPLIFY.md, "What it may fix". A finding outside that scope is listed and asked
about, not fixed.

## Rounds

After each round, decide whether its fixes need a review of their own, and record it: `Review-Again: yes` or
`no`, with the reason in the commit body. The next round reviews the whole branch again, with the same prompt,
not only the fixes. Stop at the first round that says no.

Another round is needed when:

- **the round reported its cap** (`Review-Found` equals `Review-Max`): it ran out of room, not out of findings;
- **a fix went deep:** it changed what a flow does, its ordering or concurrency, a stored or sent format, a
  permission or security check, or error handling a caller relies on;
- **the fixes were large next to the change:** the round's `Code-Lines`, added plus removed, is more than about a
  quarter of the branch's (`scripts/pass-stats.py <base>...HEAD`);
- **a fix landed where no test reaches**, so only a reader would see what it broke.

Otherwise one round is enough: say which of these didn't apply. A round that fixes none of its findings, because
each is kept on purpose with its reason, is `Review-Again: no` even at its cap: the next round would only report
them again.

New feature work after both passes means running both again, on the new work. Merges of main and the passes'
own fixes don't count.

## The commit

Each round ends in one commit, even when it fixes nothing (`git commit --allow-empty`).

```
REVIEW: <what the review fixed, or "nothing to fix">

<each finding fixed, and each one skipped with why>

Review-Round: <1, 2, ...>
Review-Level: <the level it ran at>
Review-Found: <findings reported>
Review-Max: <the most findings it could report>
Review-Fixed: <how many were fixed>
Review-Again: <yes or no: whether the fixes need another round, and why in the body>
Code-Lines: <scripts/pass-stats.py>
```

- **`Found`** is every finding the round reported, a repeat of one an earlier round kept included, since the cap
  counts those too; the body marks the repeats. **`Fixed`** is how many were fixed. Every other one is in the body
  with its reason: out of scope, a design change to file, a false positive, kept on purpose.
- **`Code-Lines`** is measured, never counted by hand: stage the round's fixes, run `scripts/pass-stats.py`, paste
  its line, and commit with a plain `git commit` (no `-a`, no paths), so the commit holds exactly what was measured.
  `scripts/pass-stats.py HEAD` gives the same line afterwards.
- **The PR description** repeats each round's counts, with what was fixed and what was skipped.
