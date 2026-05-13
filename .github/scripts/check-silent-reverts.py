#!/usr/bin/env python3
"""
Flag PR-removed code that was added by another commit on the base branch
within the lookback window — i.e. silent reverts of recently-merged work.

Background
----------
Stash-pop / merge / rebase resolutions on a long-lived branch can silently
re-delete lines that landed on main while the branch was sleeping. PR review
catches this poorly because GitHub collapses big-file diffs by default and
the regressed hunks read like cosmetic refactors at a glance. PR #478
(dice-battle merge) silently undid five recently-landed fixes from PRs
#472/#473/#474/c0f0cb23 this way.

Algorithm
---------
1. Diff the PR's head against the merge-base with the target branch.
2. Extract each *contiguous* block of removed lines that's at least
   MIN_BLOCK_SIZE non-blank, non-trivial lines long.
3. Walk every non-merge commit on the target branch within the LOOKBACK_DAYS
   window. For each commit, extract its added blocks for files the PR
   removed lines from.
4. If a PR-removed block matches an added block from a recent commit (>= 70%
   line overlap), flag it.

Output
------
Writes a human-readable report to stdout AND to $GITHUB_STEP_SUMMARY (if
set). Exits 0 regardless of findings — this is informational so reviewers
can verify, not a hard gate. False-positive cost would be high otherwise:
some PRs legitimately revert recent commits.

Env
---
BASE_REF        target-branch ref to compare against.   default: origin/main
HEAD_REF        PR head ref.                            default: HEAD
LOOKBACK_DAYS   how far back to look for "recent" commits on BASE_REF.
                                                        default: 30
MIN_BLOCK_SIZE  smallest contiguous removed block (in non-blank lines) the
                check considers — filters out coincidental "}" matches.
                                                        default: 3
MIN_OVERLAP     fraction of removed-block lines that must also appear in the
                recent commit's added block.            default: 0.7
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
from collections import defaultdict
from typing import Iterable

BASE_REF = os.environ.get("BASE_REF", "origin/main")
HEAD_REF = os.environ.get("HEAD_REF", "HEAD")
LOOKBACK_DAYS = int(os.environ.get("LOOKBACK_DAYS", "30"))
MIN_BLOCK_SIZE = int(os.environ.get("MIN_BLOCK_SIZE", "3"))
MIN_OVERLAP = float(os.environ.get("MIN_OVERLAP", "0.7"))

# Lines too generic to count even if they match — closing braces, blank,
# common boilerplate. Stripped before comparison.
TRIVIAL = re.compile(r"^[\s{}();,]*$")


def run(cmd: list[str]) -> str:
    # Force UTF-8 with replacement so Windows runners (cp1252 default) and
    # diffs containing non-Latin1 bytes (emoji in source, UTF-8 docstrings,
    # etc.) don't crash the parser.
    result = subprocess.run(
        cmd, capture_output=True, check=True,
        encoding="utf-8", errors="replace",
    )
    return result.stdout or ""


def parse_diff_blocks(diff_text: str) -> dict[str, list[tuple[list[str], list[str]]]]:
    """Parse `git diff` output into per-file lists of (removed, added) blocks.

    A block is a contiguous run of - or + lines within one hunk. Files map
    to a list of (minus_lines, plus_lines) pairs, one per block.
    """
    out: dict[str, list[tuple[list[str], list[str]]]] = defaultdict(list)
    file: str | None = None
    minus: list[str] = []
    plus: list[str] = []

    def flush() -> None:
        nonlocal minus, plus
        if file and (minus or plus):
            out[file].append((minus, plus))
        minus, plus = [], []

    for line in diff_text.splitlines():
        if line.startswith("diff --git "):
            flush()
            file = None
        elif line.startswith("+++ b/"):
            file = line[6:]
        elif line.startswith("+++ /dev/null"):
            file = None  # deletion — caller doesn't care for our use case
        elif line.startswith("@@"):
            flush()
        elif line.startswith("---"):
            continue
        elif line.startswith("-"):
            minus.append(line[1:])
        elif line.startswith("+"):
            plus.append(line[1:])
        else:
            flush()
    flush()
    return out


def normalize(lines: Iterable[str]) -> list[str]:
    """Strip whitespace; drop blank and trivial-only lines."""
    out = []
    for line in lines:
        stripped = line.strip()
        if stripped and not TRIVIAL.match(stripped):
            out.append(stripped)
    return out


def collect_pr_removed(merge_base: str) -> dict[str, list[list[str]]]:
    """Per-file list of normalized removed-blocks of size >= MIN_BLOCK_SIZE."""
    diff = run(["git", "diff", "--unified=0", "--no-color", f"{merge_base}...{HEAD_REF}"])
    blocks_by_file: dict[str, list[list[str]]] = defaultdict(list)
    for file, file_blocks in parse_diff_blocks(diff).items():
        for minus, _plus in file_blocks:
            norm = normalize(minus)
            if len(norm) >= MIN_BLOCK_SIZE:
                blocks_by_file[file].append(norm)
    return blocks_by_file


def commit_added_blocks(commit: str, file: str) -> list[list[str]]:
    diff = run(["git", "show", "--unified=0", "--no-color", commit, "--", file])
    out: list[list[str]] = []
    for _file, file_blocks in parse_diff_blocks(diff).items():
        for _minus, plus in file_blocks:
            norm = normalize(plus)
            if len(norm) >= MIN_BLOCK_SIZE:
                out.append(norm)
    return out


def commit_files(commit: str) -> set[str]:
    raw = run(["git", "show", "--name-only", "--pretty=format:", commit])
    return {f for f in raw.split() if f}


def commit_meta(commit: str) -> tuple[str, str, str]:
    raw = run(["git", "log", "-1", "--pretty=format:%h%x09%an%x09%s", commit])
    short, author, subject = raw.split("\t", 2)
    return short, author, subject


def main() -> int:
    try:
        merge_base = run(["git", "merge-base", BASE_REF, HEAD_REF]).strip()
    except subprocess.CalledProcessError as e:
        print(f"::error ::Could not compute merge-base of {BASE_REF}..{HEAD_REF}: {e.stderr}")
        return 0

    pr_removed = collect_pr_removed(merge_base)
    if not pr_removed:
        print(f"No multi-line removals (>= {MIN_BLOCK_SIZE} substantive lines) in this PR.")
        return 0

    recent_raw = run([
        "git", "log", "--no-merges",
        f"--since={LOOKBACK_DAYS} days ago",
        "--pretty=format:%H",
        BASE_REF,
    ])
    recent_commits = [c for c in recent_raw.split() if c]

    findings: list[tuple[str, str, list[str], int, int]] = []  # commit, file, block, overlap, total
    for commit in recent_commits:
        files_in_commit = commit_files(commit) & pr_removed.keys()
        if not files_in_commit:
            continue
        for file in files_in_commit:
            added_blocks = commit_added_blocks(commit, file)
            if not added_blocks:
                continue
            for pr_block in pr_removed[file]:
                pr_set = set(pr_block)
                for added_block in added_blocks:
                    overlap = pr_set & set(added_block)
                    if (len(overlap) >= MIN_BLOCK_SIZE
                            and len(overlap) >= MIN_OVERLAP * len(pr_block)):
                        findings.append((commit, file, pr_block, len(overlap), len(pr_block)))
                        break

    if not findings:
        print(f"No silent-revert candidates against the last {LOOKBACK_DAYS} days of {BASE_REF}.")
        return 0

    by_commit: dict[str, list[tuple[str, int, int]]] = defaultdict(list)
    for commit, file, _block, overlap, total in findings:
        by_commit[commit].append((file, overlap, total))

    lines = []
    lines.append(f"## Possible silent reverts ({len(by_commit)} commit(s))")
    lines.append("")
    lines.append(
        f"This PR removes lines that were added by commits on `{BASE_REF}` "
        f"within the last {LOOKBACK_DAYS} days. Verify each removal is "
        f"intentional — if not, the PR may be silently reverting recently-merged "
        f"work (the same regression class as PR #478)."
    )
    lines.append("")
    for commit in by_commit:
        short, author, subject = commit_meta(commit)
        files = by_commit[commit]
        lines.append(f"### `{short}` — {subject}")
        lines.append(f"Author: **{author}**")
        lines.append("")
        for file, overlap, total in files:
            lines.append(f"- `{file}` — {overlap}/{total} lines re-removed")
        lines.append("")
        files_arg = " ".join(f for f, _, _ in files)
        lines.append(f"Verify: `git show {short} -- {files_arg}`")
        lines.append("")

    report = "\n".join(lines)
    print(report)
    print()
    for commit in by_commit:
        short, _author, subject = commit_meta(commit)
        for file, overlap, total in by_commit[commit]:
            print(f"::warning file={file}::Possible silent revert: removes "
                  f"{overlap}/{total} lines added by {short} ({subject})")

    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as f:
            f.write(report + "\n")

    return 0


if __name__ == "__main__":
    sys.exit(main())
