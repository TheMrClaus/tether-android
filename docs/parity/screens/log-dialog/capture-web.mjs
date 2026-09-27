// T4.5 web reference for the Health & Event Log dialog. The S0.4 corpus has no log-dialog
// scenario, so this adds one to the S0.4 harness in memory (no tether file changes) and writes
// the 12 PNGs (6 skins x phone/tablet) OUTSIDE every repo. Fake engine, loopback, the harness's
// own ports and dirs; never the production service. From an agent session run it in its own scope:
//
//   S04_WT=<tether worktree on android-parity/S0.4> OUT=<dir> \
//     systemd-run --user --scope --quiet --unit=tether-parity-log-$(date +%s) \
//       --setenv=S04_WT="$S04_WT" --setenv=OUT="$OUT" node docs/parity/screens/log-dialog/capture-web.mjs
import { readFileSync } from "node:fs";

// Refuse to run anywhere but its own transient scope: never inside tether.service's cgroup.
const cgroup = readFileSync("/proc/self/cgroup", "utf8");
if (cgroup.includes("tether.service") || !/tether-parity-[^/\n]*\.scope/u.test(cgroup)) {
  console.error("capture-web: run me in my own scope (systemd-run --user --scope --unit=tether-parity-log-<n> ...), see the header. Current cgroup:\n" + cgroup);
  process.exit(2);
}

const WT = process.env.S04_WT;
const OUT = process.env.OUT;
if (!WT || !OUT) throw new Error("set S04_WT (the S0.4 worktree) and OUT (an output dir outside the repos)");
const { SCENARIOS } = await import(`${WT}/scripts/parity-seed.mjs`);
const { captureScreens } = await import(`${WT}/scripts/parity-screens.mjs`);
SCENARIOS.push({
  name: "log-dialog", phase: "main", path: "/?session={idle}", session: "idle",
  ui: "open the Health & event log from the topbar",
  open: async (page) => { await page.getByRole("button", { name: "Health & event log" }).first().click(); },
  ready: ".log-dialog[open] .log-meta",
});
const { shots, seconds } = await captureScreens({ outDir: OUT, only: ["log-dialog"] });
console.log(`log-dialog: ${shots.length} PNGs in ${seconds}s`);
