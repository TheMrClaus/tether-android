#!/usr/bin/env node
// T6.4: expected outputs of the web's sub-agent run model, todo-bar progress and background-command
// labels, produced by calling the REAL web code once and checked in as
// feature/chat/src/test/resources/subagent-runs/expectations.json (read by SubagentRunConformanceTest).
// Inputs: every step of the vendored reducer corpus cases that carry runs, tasks, spawned runs,
// todos, plans or background commands (the fields these models read), plus synthetic edge cases.
//
//   node --experimental-strip-types tools/parity/gen-subagent-run-expectations.mjs <tether-tree> \
//     > feature/chat/src/test/resources/subagent-runs/expectations.json
//
// <tether-tree> holds components/ at PARITY_BASE (e.g. `git archive 7d65611 components | tar -x`),
// never the production checkout. subagent-run-model.mjs is imported as it is; the TSX helpers
// (statusLabel, runStatusText, harnessLabel, usageGapReason, runTabTitle; selectProgress;
// backgroundCommandStatusLabel / backgroundCommandFailed) are sliced out verbatim.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";

const root = path.resolve(process.argv[2] ?? ".");
const here = path.dirname(new URL(import.meta.url).pathname);
const repo = path.resolve(here, "../..");
const load = (file) => import(pathToFileURL(file).href);

const model = await load(path.join(root, "components/subagent-run-model.mjs"));

function slice(source, name) {
  const start = source.search(new RegExp(`^(export )?function ${name}\\b`, "m"));
  if (start < 0) throw new Error(`function ${name} not found`);
  const end = source.indexOf("\n}\n", start);
  return source.slice(start, end + 3).replace(/^export /, "");
}
function sliceConst(source, name) {
  const start = source.search(new RegExp(`^(export )?const ${name}\\b`, "m"));
  if (start < 0) throw new Error(`const ${name} not found`);
  const semi = source.indexOf(";\n", start);
  return source.slice(start, semi + 2).replace(/^export /, "");
}
const runsTsx = fs.readFileSync(path.join(root, "components/subagent-runs.tsx"), "utf8");
const todoTsx = fs.readFileSync(path.join(root, "components/todo-bar.tsx"), "utf8");
const viewTsx = fs.readFileSync(path.join(root, "components/chat-view.tsx"), "utf8");
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "t64-"));
const mod = path.join(tmp, "helpers.ts");
fs.writeFileSync(
  mod,
  [
    "type SubagentRun = any; type SubagentRunStatus = any; type SessionProjection = any; type ProgressView = any; type BackgroundCommandProjection = any;",
    sliceConst(runsTsx, "STATUS_TEXT"),
    sliceConst(runsTsx, "UNCONFIRMED_STATUS_TEXT"),
    slice(runsTsx, "statusLabel"),
    slice(runsTsx, "runStatusText"),
    slice(runsTsx, "harnessLabel"),
    slice(runsTsx, "usageGapReason"),
    slice(runsTsx, "runTabTitle"),
    slice(todoTsx, "selectProgress"),
    slice(viewTsx, "backgroundCommandStatusLabel"),
    slice(viewTsx, "backgroundCommandFailed"),
    "export { statusLabel, runStatusText, harnessLabel, usageGapReason, runTabTitle, selectProgress, backgroundCommandStatusLabel, backgroundCommandFailed };",
  ].join("\n\n"),
);
const web = await load(mod);
fs.rmSync(tmp, { recursive: true, force: true });

const clean = (v) => JSON.parse(JSON.stringify(v ?? null));

// Only what the models read.
function pick(full) {
  const turnsById = {};
  for (const [id, t] of Object.entries(full.turnsById ?? {})) {
    turnsById[id] = { status: t.status, blocks: t.blocks, blocksById: t.blocksById, plan: t.plan };
  }
  return {
    provider: full.provider,
    turnOrder: full.turnOrder,
    turnsById,
    backgroundTasks: full.backgroundTasks,
    spawnedRuns: full.spawnedRuns,
    backgroundCommands: full.backgroundCommands,
    todo: full.todo,
  };
}

const RELEVANT = /"(Agent|Task|task|mcp__tether__delegate|collaboration:spawnAgent|subagent_activity|read_only_task)"|spawned_run|task_started|todo_|plan_updated|background_command/;
const corpusDir = path.join(repo, "parity-corpus/reducer");
const states = [];
for (const file of fs.readdirSync(corpusDir).sort()) {
  if (!file.endsWith(".json")) continue;
  const text = fs.readFileSync(path.join(corpusDir, file), "utf8");
  if (!RELEVANT.test(text)) continue;
  const doc = JSON.parse(text);
  // Every distinct step up to 32 KiB, then at most 16 of them per case (evenly spaced, the last
  // one always kept) so the cap cases do not swamp the file.
  let previous = "";
  const kept = [];
  (doc.expectedProjectionAfterEachStep ?? []).forEach((full, i) => {
    if (!full?.turnsById) return;
    const state = pick(full);
    const key = JSON.stringify(state);
    if (key === previous || key.length > 32 * 1024) return;
    previous = key;
    kept.push({ source: `${file}#${i}`, state });
  });
  const MAX_PER_CASE = 16;
  const step = Math.max(1, Math.ceil(kept.length / MAX_PER_CASE));
  kept.forEach((entry, i) => { if (i % step === 0 || i === kept.length - 1) states.push(entry); });
}

// Synthetic states the corpus does not reach.
const tool = (blockId, name, input, extra = {}) => ({ blockId, kind: "tool", name, input, done: true, ...extra });
const thread = (entries) => ({ order: entries.map((e) => e.key), entries: Object.fromEntries(entries.map((e) => [e.key, e])) });
const turn = (blocks, status = "done") => ({ status, blocks: blocks.map((b) => b.blockId), blocksById: Object.fromEntries(blocks.map((b) => [b.blockId, b])) });
const synthetic = {
  // v117: background launches — no task (unconfirmed), a live task, a terminal task, a failed one.
  background: {
    provider: "claude",
    turnOrder: ["t1"],
    turnsById: {
      t1: turn([
        tool("bg-none", "Agent", { description: "Long survey", run_in_background: true }, { output: "launched" }),
        tool("bg-live", "Agent", { description: "Live one", run_in_background: true }, { output: "launched" }),
        tool("bg-done", "Agent", { description: "Done one", run_in_background: true }),
        tool("bg-failed", "Task", { subagent_type: "reviewer", run_in_background: true }),
        tool("fg", "Agent", { prompt: "   \nsecond line" }, { isError: true }),
        tool("fg-running", "Agent", { prompt: "x".repeat(80) }, { done: false, elapsedSeconds: 12.4 }),
      ]),
    },
    backgroundTasks: [
      { taskId: "a", toolUseId: "bg-live", status: "running", live: true },
      { taskId: "b", toolUseId: "bg-done", status: "completed", live: false },
      { taskId: "c", toolUseId: "bg-failed", status: "killed", live: false },
      { taskId: "d", toolUseId: null, status: "running", live: true },
    ],
  },
  // Issue #172: codex lifecycle-only threads, a spawnAgent claiming its thread (row before the launcher), interacted-only drop.
  codexThreads: {
    provider: "codex",
    turnOrder: ["t1", "t2"],
    turnsById: {
      t1: turn([
        tool("act0", "subagent_activity", { kind: "started", agentThreadId: "th-a", agentPath: "/root/reviewer" }, { output: { kind: "interacted", agentThreadId: "th-a" } }),
        tool("act1", "subagent_activity", { kind: "started", agentThreadId: " th-b ", agentPath: "/root/impeccable_finish_reviewer" }),
        tool("spawn", "collaboration:spawnAgent", { prompt: "Review the retry change\nthen report", receiverThreadIds: ["th-b"], reasoningEffort: "high" }, { output: { agentsStates: { "th-c": "running" } } }),
        tool("act2", "subagent_activity", { kind: "interacted", agentThreadId: "th-z" }),
        tool("act3", "subagent_activity", { kind: "completed", agentThreadId: "th-c", agentPath: "/root/helper" }),
      ]),
      t2: turn([tool("act4", "subagent_activity", { kind: "interrupted", agentThreadId: "th-d" }), tool("act5", "subagent_activity", { kind: "completed", agentThreadId: "th-a" })], "running"),
    },
  },
  // Delegates (object and JSON-string results), reasonix profiles, opencode task envelopes, nesting and inheritance.
  delegates: {
    provider: "claude",
    turnOrder: ["t1"],
    turnsById: {
      t1: turn([
        tool("del1", "mcp__tether__delegate", { prompt: "Review it", provider: "codex", mode: "review" }, { output: { childSessionId: "s2", text: "Looks fine.", outcome: "cancelled", cancelReason: "timeout", filesTouched: ["a.ts", 3, "b.ts"] } }),
        tool("del2", "mcp__tether__delegate", { prompt: "Build it" }, { output: JSON.stringify({ childSessionId: "s3", text: "Built.", outcome: "ok" }) }),
        tool("del3", "mcp__tether__delegate", { description: "Bad JSON" }, { output: "{not json" }),
        tool("parent", "Agent", { description: "Parent run" }, {
          isError: true,
          subagent: {
            ...thread([
              { key: "n1", kind: "tool", name: "Agent", input: { description: "Nested child" } },
              { key: "n2", kind: "message", text: "hi" },
              { key: "n3", kind: "tool", name: "Task", input: { subagent_type: "explore" }, done: true },
              { key: "n4", kind: "thinking", text: "" },
            ]),
            usage: { model: "claude-fable-5", inputTokens: 10, outputTokens: 5, cacheReadInputTokens: -1, cacheCreationInputTokens: 2.5 },
          },
        }),
      ]),
    },
  },
  reasonix: {
    provider: "reasonix",
    turnOrder: ["t1"],
    turnsById: { t1: turn([tool("r1", "task", { profile: "planner", prompt: "Plan" }), tool("r2", "read_only_task", { description: "  " }), tool("r3", "fleet", {})]) },
  },
  opencode: {
    provider: "opencode",
    turnOrder: ["t1"],
    turnsById: { t1: turn([tool("o1", "task", { description: "Survey", subagent_type: "general" }, { output: "<task id=\"x\">\n<task_result>\nTwo files.\n</task_result>\n</task>" }), tool("o2", "Agent", {})]) },
  },
  // Issue #173: spawned + discovered children, placed by parent turn; unknown parent at the end.
  spawned: {
    provider: "claude",
    turnOrder: ["t1", "t2"],
    turnsById: { t1: turn([tool("a1", "Agent", { description: "First" })]), t2: turn([tool("a2", "Agent", { description: "Second" })]) },
    spawnedRuns: [
      { runId: "sp1", origin: "spawned", provider: "codex", title: null, prompt: "Fix the flaky test\nplease", mode: "build", model: "gpt-5", logFile: "/tmp/sp1.log", nativeId: null, parentTurnId: "t2", toolId: "call-1", status: "running", exitCode: null, startedAt: 1000, endedAt: null, output: "compiling…", outputTruncated: false, media: [{ type: "media_ref", mediaKind: "image", mediaType: "image/png", url: "/api/tool-media/aa.png", source: "input", label: "shot.png" }, { type: "other" }] },
      { runId: "sp2", origin: "discovered", provider: "claude", title: "Linked run", prompt: null, mode: null, model: null, logFile: null, nativeId: "native-9", parentTurnId: null, toolId: null, status: "running", exitCode: null, startedAt: 0, endedAt: null, output: "", outputTruncated: false },
      { runId: "sp3", origin: "spawned", provider: "pi", title: null, prompt: null, mode: null, model: null, logFile: "/tmp/sp3.log", nativeId: null, parentTurnId: "t1", toolId: null, status: "stopped", exitCode: 143, startedAt: 2000, endedAt: 3500, output: "x", outputTruncated: true },
      { runId: "sp4", origin: "spawned", provider: "codex", status: "finished", exitCode: 0, startedAt: 5000, endedAt: 4000, parentTurnId: "gone" },
      { notARun: true },
    ],
  },
  // Todo bar: todo wins; Codex plan fallback (newest turn with steps); nothing.
  todo: {
    turnOrder: ["t1"],
    turnsById: { t1: { status: "done", blocks: [], blocksById: {}, plan: { steps: [{ step: "Old", status: "completed" }] } } },
    todo: { items: [{ content: "Write tests", activeForm: "Writing tests", status: "in_progress" }, { content: "Ship", activeForm: "", status: "pending" }, { content: "Plan", activeForm: "Planning", status: "completed" }], activeForm: "Writing tests", completed: 1, total: 3 },
  },
  plan: {
    turnOrder: ["t1", "t2", "t3"],
    turnsById: {
      t1: { status: "done", blocks: [], blocksById: {}, plan: { steps: [{ step: "Old", status: "completed" }] } },
      t2: { status: "done", blocks: [], blocksById: {}, plan: { steps: [{ step: "Read", status: "completed" }, { step: "Edit", status: "in_progress" }, { step: "Test", status: "pending" }] } },
      t3: { status: "running", blocks: [], blocksById: {}, plan: { steps: [] } },
    },
    todo: { items: [], activeForm: null, completed: 0, total: 0 },
  },
  planDone: {
    turnOrder: ["t1"],
    turnsById: { t1: { status: "done", blocks: [], blocksById: {}, plan: { steps: [{ step: "Read", status: "completed" }] } } },
  },
};
for (const [name, state] of Object.entries(synthetic)) states.push({ source: `synthetic#${name}`, state });

const commands = [
  { commandId: "c1", command: "npm test", status: "running", exitCode: null, signal: null },
  { commandId: "c2", command: "ls", status: "finished", exitCode: null, signal: null },
  { commandId: "c3", command: "ls", status: "finished", exitCode: 2, signal: null },
  { commandId: "c4", command: "sleep", status: "stopped", exitCode: null, signal: "SIGTERM" },
  { commandId: "c5", command: "boom", status: "error", exitCode: null, signal: "SIGKILL" },
  { commandId: "c6", command: "boom", status: "error", exitCode: null, signal: null },
  { commandId: "c7", command: "boom", status: "error", exitCode: 3, signal: "" },
  { commandId: "c8", command: "x", status: "interrupted", exitCode: null, signal: null },
];

const flatRun = (run) => ({ ...run, children: run.children.map((c) => c.runId) });

const out = {
  note: "Generated by tools/parity/gen-subagent-run-expectations.mjs from the web's own code at PARITY_BASE. Do not hand-edit.",
  states: states.map(({ source, state }) => {
    const runs = model.collectSubagentRuns(state);
    const byId = new Map(runs.map((r) => [r.runId, r]));
    return {
      source,
      state,
      runs: clean(runs.map(flatRun)),
      labels: runs.map((r) => ({ status: web.statusLabel(r), statusText: web.runStatusText(r), harness: web.harnessLabel(r), gap: web.usageGapReason(r), title: web.runTabTitle(r, byId) })),
      summary: clean(model.subagentRosterSummary(runs)),
      entries: runs.map((r) => [model.subagentRunEntries(r, false).map((e) => e.key), model.subagentRunEntries(r, true).map((e) => e.key)]),
      toolOwners: runs.flatMap((r) => Object.keys(r.thread?.entries ?? {})).concat(["nobody"]).map((id) => [id, model.runForToolId(runs, id)?.runId ?? null]),
      progress: clean(web.selectProgress(state)),
    };
  }),
  commands: commands.map((c) => ({ command: c, label: web.backgroundCommandStatusLabel(c), failed: web.backgroundCommandFailed(c) })),
};
process.stdout.write(`${JSON.stringify(out)}\n`);
