#!/usr/bin/env python3
"""Style-literal checker for EV Sync UI spec v1.

Default: inspect only added lines (staged + unstaged diff) and untracked Java
files, so the ~487 legacy literals do not block daily work (spec section 6).
--all: report every Java file, always exit 0 (migration progress only).
"""
import argparse
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "apk" / "src" / "com" / "application" / "watch" / "classschedule"
ALLOW = {"Ui.java", "WatchAppearance.java"}
RULES = (
    ("hardcoded-color",
     re.compile(r"0x[0-9A-Fa-f]{8}\b|Color\.parseColor\("),
     "Ui.ACCENT / Ui.CARD / Ui.ON_ACCENT ..."),
    ("hardcoded-text-size",
     re.compile(r"\.setTextSize\(\s*(?:[^,()]+,\s*)?\d+(?:\.\d+)?f?\s*\)"),
     "Ui.SP_* with COMPLEX_UNIT_SP"),
    ("hardcoded-corner",
     re.compile(r"\.setCornerRadius\(\s*\d+|Ui\.round\([^,]+,\s*\d+"),
     "Ui.R_CARD / R_CTRL / R_BLOCK"),
)


def run(*args):
    return subprocess.run(args, cwd=ROOT, text=True,
                          capture_output=True).stdout


def added_lines():
    """Yield (path, lineno, text) for added lines in staged + unstaged diff."""
    for extra in (("--cached",), ()):
        path, lineno = None, 0
        for line in run("git", "diff", "-U0", *extra).splitlines():
            if line.startswith("diff --git"):
                path, lineno = None, 0
            elif line.startswith("+++ b/"):
                path = line[6:]
            elif line.startswith("@@"):
                m = re.search(r"\+(\d+)", line)
                lineno = int(m.group(1)) - 1 if m else 0
            elif line.startswith("+") and not line.startswith("+++"):
                lineno += 1
                if path:
                    yield path, lineno, line[1:]
            elif not line.startswith("-") and path:
                lineno += 1


def untracked_java():
    for rel in run("git", "ls-files", "--others", "--exclude-standard").splitlines():
        p = ROOT / rel
        if p.suffix == ".java" and SRC in p.parents:
            text = p.read_text(encoding="utf-8", errors="replace")
            for i, line in enumerate(text.splitlines(), 1):
                yield rel, i, line


def all_java():
    for p in sorted(SRC.glob("*.java")):
        rel = str(p.relative_to(ROOT))
        text = p.read_text(encoding="utf-8", errors="replace")
        for i, line in enumerate(text.splitlines(), 1):
            yield rel, i, line


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--all", action="store_true",
                    help="full report of legacy literals, never fails")
    args = ap.parse_args()
    items = all_java() if args.all else list(added_lines()) + list(untracked_java())
    found, checked, files = [], 0, set()
    for rel, lineno, text in items:
        if not rel.endswith(".java") or pathlib.Path(rel).name in ALLOW:
            continue
        if "/classschedule/" not in rel.replace("\\", "/"):
            continue
        checked += 1
        files.add(rel)
        stripped = text.strip()
        if stripped.startswith("//") or stripped.startswith("*"):
            continue
        for name, rx, hint in RULES:
            m = rx.search(text)
            if not m:
                continue
            if name == "hardcoded-color" and m.group(0).upper() == "0X00000000":
                continue  # fully transparent: no token exists yet
            found.append((rel, lineno, name, hint, stripped[:100]))
            break
    for rel, lineno, name, hint, src in found:
        print("[ui-spec] %s:%d  %s  -> %s\n          %s" %
              (pathlib.Path(rel).name, lineno, name, hint, src))
    mode = "full report" if args.all else "added lines"
    if found:
        print("[ui-spec] %d violation(s) / %d line(s) in %d file(s) (%s). "
              "See docs/EV同步器UI设计规范-v1.md §6." %
              (len(found), checked, len(files), mode))
        return 0 if args.all else 1
    print("[ui-spec] OK: %d line(s) checked, 0 violation(s) (%s)." %
          (checked, mode))
    return 0


if __name__ == "__main__":
    sys.exit(main())
