# Environment and resource policy

Read before claiming an engine, build, browser or GPU resource.
Installed by game-orchestration; retain all host and repo-specific grants.

## Environment rules

- **The shared dev instance is single-instance: wait, never kill.** Only the lead starts or stops a
  long-running shared process (the deployed dev/preview instance, a seeded local service, a
  database container). A lane may run its own builds and tests — that is why lanes get worktrees.
- **Host-heavy jobs are serialised — this box is shared with the game fleet, prod and the Hermes
  stack.** A full build or test gate runs as `~/bin/heavy-lock build -- <command>` (the same
  host-wide lock the game gates take), so two gates never compile at once. It waits (default
  1800 s, `HEAVY_LOCK_WAIT` overrides) and **exits 75 without running the command** on timeout: a
  75 is never a pass — report it and re-run.
- **Never point anything at production.** Mutating scripts, migrations and tests run against a
  disposable local/dev target; a deploy is the lead's, and it is **verified against the served
  artifact** (build id/hash plus a real request to the live URL), never against a build log alone.
  A repo's own deploy rules (its `CLAUDE.md` / runbook) win for that repo where they are stricter —
  including any "never touch this service from a lane" rule.
- **UI work is verified in a real browser** (Playwright CLI per the house convention) at the run
  doc's viewports — desktop **and** a phone width — with the capture attached to the evidence.
  "It builds" is not evidence that a screen works.
- **A shipped app must look designed, not default** (owner rule). Design work names its reference
  and its tokens; a piece that looks like a framework's untouched default does not pass its bar.
- **Secrets never touch git, a wiki, a memory file, a session summary or a bead.** New credentials
  are wired into the env/config of the services that need them, files stay `600`, and only
  placeholders go into docs (`.env.example`). Public-repo hygiene: block dotfile/VCS paths at the
  proxy, never let a build copy them into a static bundle.
- **Commit by explicit path; never `git add -A`/`git add .`/`commit -a` (the fleet gate denies
  them); no AI `Co-authored-by`
  trailers.** Never discard uncommitted work in a shared checkout — the tree belongs to every
  workstream in the repo. Push the gated sha explicitly and check `git log origin/main..<sha>` for
  another workstream's unpushed commits before you do.
- **Dependencies: latest stable, not minimal** when a bump is in scope; one bump per PR-shaped change
  with the gate re-run.
- Big binaries, screenshots, generated images and run artefacts stay **outside git**.
- **File upstream bugs** when the root cause is upstream rather than patching around it silently,
  and say in the PR which half is a local workaround.
- 3D is not in this fleet's scope: no Meshy, no Blender, no Unity, no rigs. Hand 3D work to Games.

---

Repo-specific rig (gates, deploy, captures, run docs): see this repo's own docs (`CLAUDE.md`,
`docs/`). Where a repo has no run doc, the rules above plus `docs/agents/gauntlet-core.md` are the
whole policy.
