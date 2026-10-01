#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Cross-check emoji/symbol glyphs actually present in app-auth/admin_Dx23.html
against the mapper map (GLYPHS in inject_lucide_mapper.py). Reports glyphs
that are NOT covered, so they would still render as emoji.
No writes. Prints JSON report + human summary.
"""
import importlib.util, sys, json, collections, os

TARGET = "/Users/Banner/Documents/guomengtao/app-auth/admin_Dx23.html"
spec = importlib.util.spec_from_file_location(
    "ilm", "/Users/Banner/Documents/guomengtao/ev-schedule-android/tools/inject_lucide_mapper.py")
ilm = importlib.util.module_from_spec(spec); sys.modules["ilm"] = ilm; spec.loader.exec_module(ilm)

s = open(TARGET, encoding="utf-8").read()
covered = set(ilm.GLYPHS.keys())

# codepoint ranges treated as symbol/emoji icon candidates
ranges = [
    (0x2000, 0x206F),  # general punctuation-ish (rarely icons)
    (0x2190, 0x21FF),  # arrows
    (0x2300, 0x23FF),  # misc technical
    (0x2500, 0x25FF),  # box drawing + geometric shapes
    (0x2600, 0x26FF),  # misc symbols
    (0x2700, 0x27BF),  # dingbats
    (0x2B00, 0x2BFF),  # misc symbols and arrows
    (0xFE00, 0xFE0F),  # variation selectors (combining)
    (0x1F000, 0x1FAFF),# emoji
    (0x1FA70, 0x1FAFF),# extended-A (overlap safe)
]
def in_ranges(cp):
    return any(a <= cp <= b for a, b in ranges)

counter = collections.Counter()
for ch in s:
    if ch == '\uFE0F' or ch == '\uFE00':
        continue  # combining variation selector, grouped with base emoji
    if in_ranges(ord(ch)):
        counter[ch] += 1

print("file_len:", len(s), " total-symbol-glyph-hits:", sum(counter.values()),
      " covered-map-size:", len(covered))

missed = sorted((c, n) for c, n in counter.items() if c not in covered)
covered_present = sorted((c, n) for c, n in counter.items() if c in covered)

print("=== glyphs PRESENT in file but NOT in mapper map (MISSED) ===")
for c, n in missed:
    print("U+%04X %s x%d" % (ord(c), c, n))
print("missed-unique:", len(missed), " total occurrences:", sum(n for _, n in missed))
print()
print("=== glyphs in file that ARE covered ===")
print("covered-unique-present:", len(covered_present), " total occurrences:", sum(n for _, n in covered_present))