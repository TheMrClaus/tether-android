#!/usr/bin/env bash
# Resolves the curated public release notes for the Android release workflow and writes them to a file.
#
#   VERSION_NAME=1.5.7 [PUBLIC_NOTES='<markdown>'] [NOTES_ROOT=<repo root>] release-notes.sh <out-file>
#
# Source: PUBLIC_NOTES (the workflow's public_notes input) when it has text, otherwise the committed
# docs/releases/<VERSION_NAME>.md. There is no git-log fallback: missing or blank notes exit 1, before any
# signing or publishing step. VERSION_NAME must be plain x.y.z, which also keeps the file path inside
# docs/releases/. Inputs come in the environment, never on a command line.
set -euo pipefail

out="${1:?usage: release-notes.sh <out-file>}"
version="${VERSION_NAME:-}"
root="${NOTES_ROOT:-.}"

if ! [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "VERSION_NAME '$version' is not a plain x.y.z version." >&2; exit 1
fi

has_text() { grep -q '[^[:space:]]' <<< "$1"; }

if has_text "${PUBLIC_NOTES:-}"; then
  notes="$PUBLIC_NOTES"
  echo "Using the public_notes override." >&2
else
  file="$root/docs/releases/$version.md"
  if [ ! -f "$file" ]; then
    echo "No curated release notes: $file is missing. Commit docs/releases/$version.md or pass public_notes." >&2; exit 1
  fi
  notes="$(cat "$file")"
  if ! has_text "$notes"; then
    echo "Curated release notes $file are empty." >&2; exit 1
  fi
  echo "Using $file." >&2
fi

printf '%s\n' "$notes" > "$out"
