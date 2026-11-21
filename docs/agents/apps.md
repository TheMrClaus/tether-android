# Apps

App leads ask the owner at decision checkpoints, except on weekdays from 00:00 to 08:00 host local time: then follow **Night protocol** in `orchestration.md`, where `advisor` decides each open question in the owner's place (routing rule 11). Log provisional decisions and their overturn conditions, send the 08:00 report, finish in-flight work to its boundary, then resume ask-first. When the owner speaks, resume ask-first for that session. Spending beyond approval, destructive actions, legal or policy decisions and project stop-and-ask gates stay with the owner. The lead controls the shared dev instance, build gates and releases. Use the project's documented app commands and target viewports.

| When | What to do |
| --- | --- |
| Starting or resuming a run | Claim or take over the lead lock using **One live lead per run** in `orchestration.md`. Inspect current state and ready claims. Before the first dispatch read **Roster** and applicable routing rules in `orchestration-roles.md`. Prove runtime identity on the first real dispatch and at wave boundaries. |
| Planning a wave | Give every piece a requirement, instrument and acceptance threshold. Have `wave-check` challenge the plan before dispatch. Use `gauntlet-core.md` › What a wave is and Standing rules. Check claims in `orchestration.md` › Tracking. |
| Changing an API, schema or behavior | Assign `builder` for product logic and `scout` for light wiring or recon. Exercise the real client or data boundary and check a failing control. Use `run-operations.md` › Validation and acceptance. |
| Designing or reviewing a screen | Assign `design`. Use the designated image lane for raster generation and commit the recipe. Capture the running app at the named viewports. Compare against one reference. See routing rules 2 and 4 and `gauntlet-core.md` › Measurements. |
| Starting a shared job or preview | Read `orchestration-environment.md` before acquiring the slot. The lead controls the dev instance and verifies the served artifact on authorized deployment. |
| Stuck, or deciding at night | Before a third attempt at a twice-failed problem, and for every night decision, consult `advisor` with one question (routing rule 11). If the advised attempt also fails, bring it to the owner. |
| Judging, closing or handing over | Use `gauntlet-core.md` › Findings and What a wave is. At the third upheld rejection park the piece for the owner, including overnight. Use `checkpoint.md` before dispatch, handover and wave closure, and `run-operations.md` for validation, handover and usage. |

File names here refer to `docs/agents/`. A lane reads its assigned role and the sections its brief names. The binding details for cost, verification, tracking, policy changes and the Codex lead are indexed by heading in `orchestration.md`.
