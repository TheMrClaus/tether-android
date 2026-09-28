#!/usr/bin/env node
// T6.3: expected outputs of the web's attention-card view logic, produced by calling the REAL web
// code once and checked in as feature/chat/src/test/resources/approvals/expectations.json (read by
// ApprovalModelConformanceTest). Inputs: every step of the vendored reducer corpus cases that carry
// approvals, questions, denials or sub-agent threads, plus synthetic edge cases.
//
//   node --experimental-strip-types tools/parity/gen-approval-expectations.mjs <tether-tree> \
//     > feature/chat/src/test/resources/approvals/expectations.json
//
// <tether-tree> holds components/ at PARITY_BASE (e.g. `git archive 7d65611 components | tar -x`),
// never the production checkout. denial-target-model.mjs is imported as it is; denialCopy /
// DENIAL_COPY / buildQuestionAnswers (TSX, not importable) are sliced out verbatim.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";

const root = path.resolve(process.argv[2] ?? ".");
const here = path.dirname(new URL(import.meta.url).pathname);
const repo = path.resolve(here, "../..");
const load = (file) => import(pathToFileURL(file).href);

const denial = await load(path.join(root, "components/denial-target-model.mjs"));

function slice(source, name) {
  const start = source.search(new RegExp(`^(export )?function ${name}\\b`, "m"));
  if (start < 0) throw new Error(`function ${name} not found`);
  const end = source.indexOf("\n}\n", start);
  return source.slice(start, end + 3).replace(/^export /, "");
}
function sliceConst(source, name) {
  const start = source.search(new RegExp(`^const ${name}\\b`, "m"));
  if (start < 0) throw new Error(`const ${name} not found`);
  return source.slice(start, source.indexOf("};\n", start) + 3);
}
const view = fs.readFileSync(path.join(root, "components/chat-view.tsx"), "utf8");
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "t63-"));
const mod = path.join(tmp, "helpers.ts");
fs.writeFileSync(
  mod,
  [
    "type PermissionDenialProjection = any; type QuestionPrompt = any;",
    sliceConst(view, "DENIAL_COPY"),
    slice(view, "denialCopy"),
    slice(view, "buildQuestionAnswers"),
    "export { DENIAL_COPY, denialCopy, buildQuestionAnswers };",
  ].join("\n\n"),
);
const web = await load(mod);
fs.rmSync(tmp, { recursive: true, force: true });

const clean = (v) => JSON.parse(JSON.stringify(v ?? null));

// Inputs ----------------------------------------------------------------------------------------
const corpusDir = path.join(repo, "parity-corpus/reducer");
const states = [];
for (const file of fs.readdirSync(corpusDir).sort()) {
  if (!file.endsWith(".json")) continue;
  const text = fs.readFileSync(path.join(corpusDir, file), "utf8");
  if (!/permission_denied|approval_request|question_request|question_answered|subagent_message/.test(text)) continue;
  const doc = JSON.parse(text);
  let previous = "";
  (doc.expectedProjectionAfterEachStep ?? []).forEach((full, i) => {
    if (!full?.turnsById) return;
    // Only what placement reads, and only the steps where it changed and there is a denial.
    const state = {
      turnOrder: full.turnOrder,
      turnsById: Object.fromEntries(Object.entries(full.turnsById).map(([id, t]) => [id, {
        blocks: t.blocks, blocksById: t.blocksById, permissionDenials: t.permissionDenials,
      }])),
      unattributedPermissionDenials: full.unattributedPermissionDenials,
    };
    const hasDenial = (state.unattributedPermissionDenials ?? []).length > 0 ||
      Object.values(state.turnsById).some((t) => (t.permissionDenials ?? []).length > 0);
    const key = JSON.stringify(state);
    if (!hasDenial || key === previous || key.length > 64 * 1024) return;
    previous = key;
    states.push({ source: `${file}#${i}`, state });
  });
}

// A state the corpus does not reach: a late denial of a sub-agent call (anchored to its parent),
// a repeated toolId across two turns, an unattributed duplicate of an attributed one, and a
// homeless one.
const tool = (blockId, name, input, extra = {}) => ({ blockId, kind: "tool", name, input, done: true, ...extra });
states.push({
  source: "synthetic#placement",
  state: {
    turnOrder: ["a", "b"],
    turnsById: {
      a: {
        blocks: ["m", "task", "bash"],
        blocksById: {
          m: { blockId: "m", kind: "message", text: "hi", done: true },
          task: tool("task", "Agent", { description: "Look" }, { subagent: { order: ["child"], entries: { child: { key: "child", kind: "tool", name: "Read", input: { file_path: "/w/secret" } } } } }),
          bash: tool("bash", "Bash", { command: "rm -rf /" }),
        },
        permissionDenials: [
          { toolId: "bash", name: "Bash", reason: "classifier" },
          { toolId: "gone", name: "Edit", reason: "unknown" },
        ],
      },
      b: {
        blocks: ["bash"],
        blocksById: { bash: tool("bash", "Bash", { command: "ls" }) },
        permissionDenials: [{ toolId: "bash", name: "Bash", reason: "rule", reasonCode: "rule" }],
      },
    },
    unattributedPermissionDenials: [
      { toolId: "child", name: "Read", reason: "working_dir", subagent: true },
      { toolId: "bash", name: "Bash", reason: "hook" },
      { toolId: "nowhere", name: "WebFetch", reason: "sandbox" },
    ],
  },
});

const denials = [
  { toolId: "t", name: "Bash", reason: "unknown" },
  { toolId: "t", name: "Bash", reason: "unknown", reasonCode: "subcommandResults" },
  { toolId: "t", name: "Bash", reason: "unknown", error: "Tool permission request failed: AbortError: Stream closed" },
  { toolId: "t", name: "Bash", reason: "unknown", error: "" },
  { toolId: "t", name: "Bash", reason: "hook", error: "ignored for a known reason" },
  ...Object.keys(web.DENIAL_COPY).map((reason) => ({ toolId: "t", name: "Bash", reason })),
];

const targets = [
  "   ",
  "a raw string input",
  "x".repeat(301),
  "é".repeat(300) + "tail",
  null,
  42,
  ["command"],
  {},
  { command: "  " , file_path: "src/a.ts" },
  { command: "npm test", file_path: "src/a.ts" },
  { file_path: "src/a.ts", path: "/b" },
  { path: "/b", url: "https://x" },
  { url: "https://x", pattern: "TODO" },
  { pattern: "TODO", prompt: "go" },
  { prompt: "go" },
  { command: 7, file_path: "src/a.ts" },
  { command: " \u00A0\uFEFF", url: "u" },
  { content: "only content" },
];

const prompts = [
  { question: "Which DB?", header: "Database", multiSelect: false, options: [{ label: "Postgres" }, { label: "SQLite" }] },
  { question: "Which environments?", header: "Env", multiSelect: true, options: [{ label: "staging" }, { label: "production" }] },
  { question: "Anything else?", header: "", multiSelect: false, options: [] },
];
const answerCases = [
  { picks: {}, other: {}, skipped: [] },
  { picks: { "Which DB?": ["Postgres"] }, other: {}, skipped: [] },
  { picks: { "Which DB?": ["Postgres"], "Which environments?": ["staging", "production"] }, other: { "Anything else?": "  ship it \n" }, skipped: [] },
  { picks: { "Which environments?": ["production"] }, other: { "Which environments?": "canary", "Which DB?": " " }, skipped: ["Which DB?"] },
  { picks: { "Which DB?": ["SQLite"] }, other: { "Which DB?": "with WAL", "Anything else?": "no" }, skipped: ["Which DB?", "Anything else?"] },
  { picks: { "Which DB?": [] }, other: { "Which environments?": "a", "Anything else?": "b" }, skipped: [] },
];

// Outputs --------------------------------------------------------------------------------------
const out = {
  note: "Generated by tools/parity/gen-approval-expectations.mjs from the web's own code at PARITY_BASE. Do not hand-edit.",
  states: states.map(({ source, state }) => {
    const turnDenials = [];
    for (const turnId of state.turnOrder ?? []) {
      const turn = state.turnsById?.[turnId];
      for (const d of turn?.permissionDenials ?? []) {
        const input = denial.deniedToolInput(turn, d.toolId);
        turnDenials.push({ turnId, toolId: d.toolId, input: clean(input), target: clean(denial.denialTarget(input)), copy: web.denialCopy(d) });
      }
    }
    const late = (state.unattributedPermissionDenials ?? []).map((d) => {
      const input = denial.lateDenialToolInput(state, d.toolId);
      return { toolId: d.toolId, input: clean(input), target: clean(denial.denialTarget(input)), copy: web.denialCopy(d) };
    });
    return { source, state, placement: clean(denial.placeDenials(state)), turnDenials, late };
  }),
  denialCopies: denials.map((d) => ({ denial: d, copy: web.denialCopy(d) })),
  denialTargets: targets.map((input) => ({ input, target: clean(denial.denialTarget(input)) })),
  questionAnswers: answerCases.map((c) => ({ prompts, ...c, result: clean(web.buildQuestionAnswers(prompts, c.picks, c.other, new Set(c.skipped))) })),
};
process.stdout.write(`${JSON.stringify(out)}\n`);
