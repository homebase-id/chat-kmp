#!/usr/bin/env python3
"""Fails when a Kotlin interface that Swift implements is not fully listed in the ObjC export list.

homebase-core/objc-entry-points.txt decides which Kotlin functions and properties get ObjC bridges in the
ComposeApp header. A Swift call to a member missing from it fails the Swift compile, so that case needs no
check. The silent case is a Kotlin interface that Swift implements: Kotlin calls its members, and one missing
from the list is dropped from the protocol, so Swift still compiles and the call fails at runtime.

This finds every Swift type conformance to a top-level Kotlin interface of an exported module, and requires
each member of that interface (and of its Kotlin super-interfaces) to be listed by name. A ".*" wildcard on
such an interface is an error too: it also matches equals/hashCode/toString, which then become required
protocol methods that the Swift classes don't implement. A nested Kotlin interface (Swift name OuterInner)
is not recognised.

Run from the repository root: python3 scripts/check-objc-entry-points.py
"""
import re
import sys
from pathlib import Path

ENTRY_POINTS = Path("homebase-core/objc-entry-points.txt")
SWIFT_ROOT = Path("iosApp")
# The modules homebase-core's framework exports (export(...) in homebase-core/build.gradle.kts).
EXPORTED_MODULES = ["homebase-core", "homebase-api", "homebase-common", "image-editor-ui"]
# Source sets that never reach the iOS framework.
NON_IOS_SOURCE_SETS = {"androidMain", "jvmMain", "wasmJsMain", "jsMain", "desktopMain"}


def strip_comments_and_strings(code):
    """Blanks comments and string literals so a word in KDoc or a string is never read as a declaration."""
    return re.sub(
        r'//[^\n]*|/\*.*?\*/|"""(?:.|\n)*?"""|"(?:\\.|[^"\\\n])*"' + r"|'(?:\\.|[^'\\\n])'",
        lambda m: re.sub(r"[^\n]", " ", m.group(0)),
        code,
        flags=re.S,
    )


def matching_brace(code, open_index):
    depth = 0
    for i in range(open_index, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return i
    raise ValueError("unbalanced braces")


def top_level_only(body):
    """The body with everything inside nested braces blanked: default implementations, companion objects."""
    out, depth = [], 0
    for ch in body:
        if ch == "}":
            depth -= 1
        out.append(ch if depth == 0 or ch == "\n" else " ")
        if ch == "{":
            depth += 1
    return "".join(out)


def kotlin_interfaces():
    """Simple name -> list of (fq name, members as [(kind, name)], super-type simple names, file)."""
    found = {}
    for module in EXPORTED_MODULES:
        for path in sorted(Path(module, "src").glob("*/kotlin/**/*.kt")):
            source_set = path.relative_to(Path(module, "src")).parts[0]
            if source_set.endswith("Test") or source_set in NON_IOS_SOURCE_SETS:
                continue
            code = strip_comments_and_strings(path.read_text())
            package = re.search(r"^\s*package\s+([\w.]+)", code, re.M)
            prefix = package.group(1) + "." if package else ""
            # "interface" at the start of a line, after modifiers: top-level and nested both match here,
            # nested ones are dropped below by their indentation.
            for m in re.finditer(r"^((?:[ \t]*)(?:(?:public|internal|fun|expect|actual)\s+)*)interface\s+(\w+)([^{\n]*)", code, re.M):
                if m.group(1) != m.group(1).lstrip():
                    continue
                name, header = m.group(2), m.group(3)
                supers = []
                if ":" in header:
                    supers = [re.match(r"\s*([\w.]+)", s).group(1).split(".")[-1]
                              for s in header.split(":", 1)[1].split(",") if s.strip()]
                members = []
                brace = code.find("{", m.end())
                if brace != -1 and code[m.end():brace].strip() == "":
                    body = top_level_only(code[brace + 1:matching_brace(code, brace)])
                    members += [("function", n) for n in re.findall(
                        r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.?<>]+\.)?(\w+)\s*\(", body)]
                    members += [("property", n) for n in re.findall(
                        r"\b(?:val|var)\s+(?:<[^>]*>\s*)?(?:[\w.?<>]+\.)?(\w+)\s*[:=]", body)]
                found.setdefault(name, []).append((prefix + name, members, supers, path))
    return found


def swift_conformances():
    """Simple names of the protocols Swift types conform to -> the Swift files that do it."""
    found = {}
    for path in sorted(SWIFT_ROOT.rglob("*.swift")):
        code = strip_comments_and_strings(path.read_text())
        for m in re.finditer(r"\b(?:class|struct|enum|actor|extension)\s+[\w.]+\s*(?:<[^>{]*>)?\s*:\s*([^{]+)\{", code):
            for proto in re.split(r"\bwhere\b", m.group(1))[0].split(","):
                proto = proto.strip()
                if re.fullmatch(r"[\w.]+", proto):
                    found.setdefault(proto.split(".")[-1], set()).add(str(path))
    return found


def read_entries():
    named, wildcards = set(), set()
    for line in ENTRY_POINTS.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("//"):
            continue
        kind, _, name = line.partition(" ")
        name = name.strip()
        if name.endswith(".*"):
            wildcards.add(name[:-2])
        else:
            named.add((kind, name))
    return named, wildcards


def main():
    interfaces = kotlin_interfaces()
    named, wildcards = read_entries()
    errors = []

    def check(simple_name, why, seen):
        candidates = interfaces.get(simple_name, [])
        if len(candidates) > 1:
            errors.append(f"{simple_name}: more than one exported Kotlin interface has this name "
                          f"({', '.join(str(c[3]) for c in candidates)}); extend this check to tell them apart")
            return
        if not candidates or simple_name in seen:
            return
        seen.add(simple_name)
        fq, members, supers, path = candidates[0]
        if fq in wildcards:
            errors.append(f"{fq}: Swift implements it ({why}), so list its members by name, not with \".*\"")
        for kind, member in members:
            if (kind, f"{fq}.{member}") not in named and ("callable", f"{fq}.{member}") not in named:
                errors.append(f"{fq}.{member} ({path}): Swift implements {simple_name} ({why}), "
                              f"so add \"{kind} {fq}.{member}\" to {ENTRY_POINTS}")
        for s in supers:
            check(s, f"super-interface of {simple_name}", seen)

    conformances = swift_conformances()
    checked = set()
    for proto, files in sorted(conformances.items()):
        check(proto, ", ".join(sorted(files)), checked)

    if errors:
        print("ObjC export list is missing members of Kotlin interfaces that Swift implements:", file=sys.stderr)
        for e in errors:
            print(f"  {e}", file=sys.stderr)
        return 1
    print(f"{ENTRY_POINTS}: all members of the {len(checked)} Kotlin interfaces Swift implements are listed "
          f"({', '.join(sorted(checked))})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
