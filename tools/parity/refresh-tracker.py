#!/usr/bin/env python3
"""Refresh the TRACKER.md task-board rows from the beads store (the writer).

TRACKER.md is the human digest; beads (`.beads/`, prefix `ta`) is the machine layer.
This rewrites only the Status / Claimed by / Evidence / Notes cells of task-board rows
whose ID exists in the store. Narrative sections (RESUME HERE, Blockers, Decision log,
Session log) stay hand-written.

    python3 tools/parity/refresh-tracker.py          # rewrite docs/parity/TRACKER.md
    python3 tools/parity/refresh-tracker.py --check  # exit 1 if the digest is stale
"""
import json
import re
import subprocess
import sys
from pathlib import Path

TRACKER = Path(__file__).resolve().parents[2] / "docs/parity/TRACKER.md"
STATUS = {
    "open": "TODO",
    "in_progress": "IN-PROGRESS",
    "blocked": "BLOCKED",
    "review": "DONE (review)",
    "done": "DONE",
    "verified": "VERIFIED",
    "dropped": "DROPPED",
    "deferred": "BLOCKED (deferred)",
}
SHA = re.compile(r"\b(?:sha|SHA)\s+([0-9a-f]{7,12})\b")


def cell(text: str, limit: int) -> str:
    text = " ".join(text.split()).replace("|", "/")
    return text if len(text) <= limit else text[: limit - 1] + "…"


def evidence(notes: str) -> str:
    idx = notes.rfind("EVIDENCE:")
    if idx < 0:
        return ""
    shas = []
    for m in SHA.finditer(notes[idx:]):
        if m.group(1) not in shas:
            shas.append(m.group(1))
    head = ", ".join(f"`{s}`" for s in shas[:3])
    return (head + " · " if head else "") + "`bd show`"


def last_note(notes: str) -> str:
    parts = [p for p in re.split(r"\n+", notes.strip()) if p.strip()]
    return parts[-1] if parts else ""


def main() -> int:
    issues = json.loads(
        subprocess.run(["bd", "list", "--all", "--json"], check=True, capture_output=True, text=True).stdout
    )
    by_id = {i["id"]: i for i in issues}
    src = TRACKER.read_text()
    out = []
    for line in src.split("\n"):
        m = re.match(r"^\| ([TS]\d+\.\d+) \|", line)
        cols = [c.strip() for c in line.strip().strip("|").split("|")]
        if m and m.group(1) in by_id and len(cols) == 6:
            i = by_id[m.group(1)]
            notes = i.get("notes") or ""
            status = STATUS.get(i["status"], i["status"])
            who = i.get("assignee") or ""
            when = (i.get("started_at") or "")[:16].replace("T", " ")
            claimed = f"{who} @ {when}" if who and when else who
            note = last_note(notes)
            if i["status"] == "blocked" and "BLOCKED:" in notes:
                note = notes[notes.rfind("BLOCKED:"):]
            if i["status"] == "done" or i["status"] == "verified":
                note = ""  # evidence lives in the bead; keep the board scannable
            cols[2] = status
            cols[3] = cell(claimed, 40)
            cols[4] = evidence(notes)
            cols[5] = cell(note, 140)
            line = "| " + " | ".join(cols) + " |"
        out.append(line)
    new = "\n".join(out)
    if "--check" in sys.argv:
        return 0 if new == src else 1
    TRACKER.write_text(new)
    return 0


if __name__ == "__main__":
    sys.exit(main())
