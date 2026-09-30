#!/usr/bin/env python3
"""T15.5: reduce a six-skin token export to the Studio-only shape.

The web retired every theme family but Studio (OVERVIEW_STUDIO_PLAN.md section 3,
"Native exports: emit only Studio light/dark tokens"). Its exporter now writes
`skinMap: {light: "studio", dark: "studio-dark"}` and the two Studio skins only.

The vendored corpus is still the PARITY_BASE export (six skins, family x mode
skinMap). Until T15.8 re-vendors from the exporter at the new base, this filter
turns that export into the new shape without touching a single Studio value:

  - drops every skin that is not in the Studio pair, and the `families` list;
  - rewrites `skinMap` to the mode -> skin map and drops each skin's `family`;
  - drops the top-level `chrome` block (the browser meta/manifest colours of the
    retired default skin; nothing native reads it, and each Studio skin keeps
    its own `chrome.graphite` / `chrome.bootThemeColor`).

Output is the exporter's stableStringify form (keys sorted, 2-space indent).
Idempotent: running it on an already Studio-only file rewrites it unchanged.

Usage: tools/parity/studio-only-tokens.py parity-corpus/tokens/design-tokens.json
"""
import json
import sys

STUDIO = {"light": "studio", "dark": "studio-dark"}


def norm(v):
    if isinstance(v, list):
        return [norm(x) for x in v]
    if isinstance(v, dict):
        return {k: norm(v[k]) for k in sorted(v)}
    return v


def main(path):
    with open(path, encoding="utf-8") as f:
        doc = json.load(f)
    skins = {}
    for mode, skin in STUDIO.items():
        s = dict(doc["skins"][skin])
        if s.get("mode") != mode:
            sys.exit(f"{skin}: mode {s.get('mode')!r} != {mode!r}")
        s.pop("family", None)
        skins[skin] = s
    doc["skins"] = skins
    doc["skinMap"] = dict(STUDIO)
    doc.pop("families", None)
    doc.pop("chrome", None)
    with open(path, "w", encoding="utf-8") as f:
        f.write(json.dumps(norm(doc), indent=2, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main(sys.argv[1])
