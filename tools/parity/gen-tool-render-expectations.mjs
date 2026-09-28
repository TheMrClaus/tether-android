#!/usr/bin/env node
// T6.2: expected outputs of the web's tool-card view models, produced by calling the REAL web code
// once and checked in as feature/chat/src/test/resources/tool-render/expectations.json (read by
// ToolRenderConformanceTest). Inputs: every tool block and turn of the vendored reducer corpus's
// final projections, plus synthetic edge cases.
//
//   node --experimental-strip-types tools/parity/gen-tool-render-expectations.mjs <tether-worktree> \
//     > feature/chat/src/test/resources/tool-render/expectations.json
//
// <tether-worktree> is a checkout at PARITY_BASE (components/ identical to 7d65611) — never the
// production checkout. codex-/opencode-rich-render-model.mjs are imported as they are; the pure
// helpers of components/chat-tool-render.tsx and the grouping helpers of components/chat-view.tsx
// (TSX, not importable) are sliced out verbatim into a temporary .ts module.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";

const root = path.resolve(process.argv[2] ?? ".");
const here = path.dirname(new URL(import.meta.url).pathname);
const repo = path.resolve(here, "../..");
const load = (file) => import(pathToFileURL(file).href);

const codex = await load(path.join(root, "components/codex-rich-render-model.mjs"));
const opencode = await load(path.join(root, "components/opencode-rich-render-model.mjs"));

/** The source text of a top-level `function name(` (optionally exported) up to its closing brace. */
function slice(source, name) {
  const start = source.search(new RegExp(`^(export )?function ${name}\\b`, "m"));
  if (start < 0) throw new Error(`function ${name} not found`);
  const end = source.indexOf("\n}\n", start);
  return source.slice(start, end + 3).replace(/^export /, "");
}
function sliceConst(source, name) {
  const start = source.search(new RegExp(`^const ${name}\\b`, "m"));
  if (start < 0) throw new Error(`const ${name} not found`);
  return source.slice(start, source.indexOf(";\n", start) + 2);
}
const render = fs.readFileSync(path.join(root, "components/chat-tool-render.tsx"), "utf8");
const view = fs.readFileSync(path.join(root, "components/chat-view.tsx"), "utf8");
const pieces = [
  ...["summarize", "asRecord", "asString", "toMediaItem", "isMediaBlock", "extractToolMedia", "stripToolMedia", "remainingToolText", "lineDiff"].map((n) => slice(render, n)),
  sliceConst(render, "MAX_DIFF_ROWS"),
  ...["toolCategory", "pluralize", "activitySummary", "isGroupableBlock", "groupHasRunning", "segmentBlocks"].map((n) => slice(view, n)),
];
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "t62-"));
const mod = path.join(tmp, "helpers.ts");
fs.writeFileSync(
  mod,
  [
    "type TurnBlock = any; type ToolMediaItem = any; type DiffRow = { t: string; text: string }; type ActivitySegment = any;",
    ...pieces,
    "export { summarize, extractToolMedia, stripToolMedia, remainingToolText, lineDiff, MAX_DIFF_ROWS, toolCategory, pluralize, activitySummary, isGroupableBlock, groupHasRunning, segmentBlocks };",
  ].join("\n\n"),
);
const web = await load(mod);
fs.rmSync(tmp, { recursive: true, force: true });

// Inputs ----------------------------------------------------------------------------------------
const corpusDir = path.join(repo, "parity-corpus/reducer");
const blocks = [];
const turns = [];
for (const file of fs.readdirSync(corpusDir).sort()) {
  if (!file.endsWith(".json")) continue;
  const doc = JSON.parse(fs.readFileSync(path.join(corpusDir, file), "utf8"));
  const steps = doc.expectedProjectionAfterEachStep ?? [];
  const last = steps[steps.length - 1];
  if (!last?.turnsById) continue;
  for (const [turnId, turn] of Object.entries(last.turnsById)) {
    const hasTool = (turn.blocks ?? []).some((id) => turn.blocksById?.[id]?.kind === "tool");
    // diff-chars-cap's half-megabyte diff exists to test the reducer's cap, not the renderer.
    if ((hasTool || turn.diff) && JSON.stringify(turn).length < 64 * 1024) turns.push({ source: `${file}#${turnId}`, turn });
    for (const blockId of turn.blocks ?? []) {
      const block = turn.blocksById?.[blockId];
      if (block?.kind === "tool") blocks.push({ source: `${file}#${turnId}/${blockId}`, block });
    }
  }
}
const png = "iVBORw0KGgo=";
const synthetic = [
  { name: "Write", input: { file_path: "src/v.ts", content: "a\nb\n" }, output: "ok", done: true },
  { name: "Edit", input: { file_path: "a.ts", old_string: "1\n2\n3\n4\n5\n6", new_string: "1\n2\n3\nX\n5\n6", replace_all: 1 }, done: true, isError: false },
  { name: "MultiEdit", input: { file_path: "a.ts", edits: [{ old_string: "a", new_string: "b" }, null, { old_string: 1 }] }, done: true },
  { name: "Bash", input: { command: "ls" }, output: [{ type: "text", text: "listing" }, { type: "media_ref", mediaKind: "image", mediaType: "image/png", url: `/api/tool-media/${"0".repeat(64)}.png`, bytes: 12 }], done: true },
  { name: "mcp__x__shot", input: {}, output: [{ type: "image", source: { type: "base64", media_type: "image/png", data: png } }], done: true },
  { name: "mcp__x__clip", input: {}, output: [{ type: "media_ref", mediaKind: "video", mediaType: "video/mp4", url: `/api/tool-media/${"1".repeat(64)}.mp4` }], done: true },
  { name: "bad_media", input: {}, output: [{ type: "media_ref", mediaKind: "audio", mediaType: "audio/mp3", url: "/x" }, { type: "image", source: { type: "url", media_type: "image/png", data: "x" } }, { type: "media_ref", mediaKind: "image", mediaType: "", url: "/y" }], done: true },
  { name: "command_execution", input: { command: "npm t", cwd: "/w" }, output: "streaming line\n", done: false },
  { name: "command_execution", input: {}, output: { exitCode: 1.5, durationMs: 1250, status: "failed", text: "x" }, done: true, isError: true },
  { name: "mcp:docs/search/deep", input: { arguments: [1, { a: null }] }, output: { result: { content: null, structuredContent: { hits: 2 } }, error: { message: "boom" }, durationMs: 9999 }, done: true, isError: true },
  { name: "mcp:", input: {}, output: "progress 1/3", done: false },
  { name: "collaboration:wait", input: { receiverThreadIds: ["a", 2, "b"], agentsStates: { "10": "done", "2": { s: 1 }, z: "run" } }, output: { agentsStates: null }, done: false },
  { name: "subagent_activity", input: { kind: "", agentPath: "" }, done: true },
  { name: "file_change", input: { changes: [{ path: "", diff: "x" }, { path: "a", kind: "", diff: "" }] }, output: "patch streaming", done: false },
  { name: "task", input: { description: "Survey", prompt: "List files", subagent_type: "general" }, output: '<task id="ses_1" state="completed">\n<task_result>\nTwo files.\r\n</task_result>\n</task>', done: true },
  { name: "task", input: {}, output: "<task_result>unterminated", done: true, isError: true },
  { name: "task", input: {}, done: false },
  { name: "AskUserQuestion", input: { questions: [] }, done: false },
  { name: "", input: null, done: true },
  { name: "TodoWrite", input: { todos: [] }, output: [], done: true },
  { name: "Glob", input: "raw string input", output: 42, done: true },
];
synthetic.forEach((b, i) => blocks.push({ source: `synthetic#${i}`, block: { blockId: `syn-${i}`, kind: "tool", ...b } }));

const diffs = [
  "diff --git a/src/a.ts b/src/b.ts\nindex 1..2 100644\n--- a/src/a.ts\n+++ b/src/b.ts\n@@ -1,2 +1,2 @@\n-old\n+new\n context\n\\ No newline at end of file\ndiff --git a/x b/y\r\n--- /dev/null\n+++ b/new file.txt\t2024-01-01\n@@ -0,0 +1 @@\n+hi\n",
  "@@ -1 +1 @@\n-export const config = { retries: 3 };\n+export const config = { retries: 5 };\n",
  "plain text\nno headers",
  "--- a/only-old\n",
  "",
];

// Outputs --------------------------------------------------------------------------------------
const clean = (v) => JSON.parse(JSON.stringify(v ?? null));
const out = {
  note: "Generated by tools/parity/gen-tool-render-expectations.mjs from the web's own code at PARITY_BASE. Do not hand-edit.",
  blocks: blocks.map(({ source, block }) => ({
    source,
    block,
    codexKind: codex.codexRichToolKind(block),
    opencodeKind: opencode.opencodeRichToolKind(block),
    command: clean(codex.commandView(block)),
    fileChange: clean(codex.fileChangeView(block)),
    mcp: clean(codex.mcpView(block)),
    collaboration: clean(codex.collaborationView(block)),
    subagent: clean(codex.subagentView(block)),
    task: clean(opencode.taskView(block)),
    summarizeInput: web.summarize(block.input),
    summarizeOutput: web.summarize(block.output),
    media: clean(web.extractToolMedia(block.output)),
    remainingText: web.remainingToolText(block.output),
    groupable: web.isGroupableBlock(block),
    category: web.toolCategory(block.name),
  })),
  turns: turns.map(({ source, turn }) => {
    const groups = web.segmentBlocks(turn.blocks ?? [], turn.blocksById ?? {});
    return {
      source,
      turn,
      hasInlineFileChangeDiffs: codex.hasInlineFileChangeDiffs(turn),
      segments: clean(groups),
      summaries: groups.filter((s) => s.type === "group").map((s) => web.activitySummary(s.blockIds.map((id) => turn.blocksById[id]))),
      running: groups.filter((s) => s.type === "group").map((s) => web.groupHasRunning(s.blockIds.map((id) => turn.blocksById[id]))),
      diff: turn.diff ? clean(codex.parseUnifiedDiff(turn.diff.unifiedDiff)) : null,
    };
  }),
  diffs: diffs.map((d) => ({ input: d, files: clean(codex.parseUnifiedDiff(d)) })),
  lineDiffs: [["a\nb\nc", "a\nB\nc"], ["", "x"], ["same", "same"], ["1\n2\n3\n4\n5\n6\n7", "1\n2\n3\n4\n5\n6\n7\n8"], ["x\ny", ""]].map(([o, n]) => ({ old: o, new: n, rows: web.lineDiff(o, n) })),
  taskResults: ["", "no envelope", "<task_result>a</task_result>", "<task_result>\r\nmulti\nline\r\n</task_result> tail <task_result>second</task_result>"].map((s) => ({ input: s, output: opencode.taskResultText(s) })),
  pluralize: [[1, "search"], [2, "search"], [3, "shell command"], [2, "class"], [2, "wish"], [0, "file read"]].map(([n, s]) => ({ count: n, singular: s, text: web.pluralize(n, s) })),
  summarizeLong: { input: "x".repeat(700), text: web.summarize("x".repeat(700)) },
};
process.stdout.write(`${JSON.stringify(out)}\n`);
