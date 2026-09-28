#!/usr/bin/env node
// T2.2 supplement to the vendored helper corpus: cases the S0.2 exporter never recorded, produced
// by calling the REAL web modules once and checked in as
// core/reducer/src/test/resources/helpers/supplement.json (read by HelperSupplementTest).
//
//   node --experimental-strip-types tools/parity/gen-helper-supplement.mjs <tether-worktree> > \
//     core/reducer/src/test/resources/helpers/supplement.json
//
// Covers: (1) `localeCompare` order over adversarial strings (default ICU collator, as the web's
// sorts use it), (2) collation-sensitive helper calls, (3) compactNumber ≥ 1e15 (grouped T range),
// (4) calls where `undefined` and `null` arguments diverge. Values use the corpus typed layer.
import path from "node:path";
import { pathToFileURL } from "node:url";

const root = path.resolve(process.argv[2] ?? ".");
const load = (rel) => import(pathToFileURL(path.join(root, rel)).href);

const enc = (v) => {
  if (v === undefined) return { $undefined: true };
  if (typeof v === "number") {
    if (Number.isNaN(v)) return { $number: "NaN" };
    if (v === Infinity) return { $number: "Infinity" };
    if (v === -Infinity) return { $number: "-Infinity" };
    if (Object.is(v, -0)) return { $number: "-0" };
    return v;
  }
  if (v === null || typeof v !== "object") return v;
  if (v instanceof Map) return { $map: [...v].map(([k, x]) => [enc(k), enc(x)]) };
  if (v instanceof Set) return { $set: [...v].map(enc) };
  if (v instanceof Date) return { $date: enc(v.getTime()) };
  if (Array.isArray(v)) return v.map(enc);
  const out = {};
  for (const key of Object.keys(v).sort()) if (v[key] !== undefined) out[key] = enc(v[key]);
  return out;
};

const cases = [];
const record = (table, fn, impl, args, note) => {
  const entry = { table, fn, args: args.map(enc), ...(note ? { note } : {}) };
  try {
    entry.result = enc(impl(...args));
  } catch (error) {
    entry.throws = { name: error.name, message: error.message };
  }
  cases.push(entry);
};

// ---- (1) collation ----
const strings = [
  "", " ", "-", "_", "a", "A", "b", "B", "z", "Z", "0", "1", "9", "10", "2",
  "gpt", "GPT", "gpt-4o", "gpt 4o", "gpt4o", "GPT-4o", "gpt-4.1", "gpt_4", "gpt.4", "gpt/4",
  "GPT 4o mini", "GPT-4o-mini", "GPT 5 mini", "GPT-5-mini", "GPT-5", "GPT5", "GPT-5.1", "GPT-5 Codex",
  "o3", "o3-mini", "o3 pro", "o3mini", "O3", "o3.mini",
  "é", "e", "E", "É", "é", "ê", "ë", "ae", "æ", "Æ", "ß", "ss",
  "co-op", "coop", "co op", "Co-op", "(x)", "[x]", "x!", "x?", "#1", "@a", "~", "a-b-c", "a b c", "abc",
  "claude-opus-5", "claude opus 5", "Claude-Opus-5", "claude-opus-4-8", "Opus 5", "opus 5", "Opus 4.8",
];
const sorted = [...strings].sort((a, b) => a.localeCompare(b));
const signs = strings.map((a) => strings.map((b) => Math.sign(a.localeCompare(b))));

// ---- (2) collation-sensitive helpers ----
const browser = await load("lib/model-browser-view.mjs");
const order = await load("lib/sidebar-order.mjs");
const row = (modelLabel, modelId = modelLabel.toLowerCase().replace(/\s+/g, "-")) => ({
  key: `oc:${modelId}`, entryKey: "oc", provider: "opencode", providerLabel: "OpenCode",
  modelId, modelLabel, description: undefined, isDefault: false,
});
const gptRows = ["gpt-4.1", "GPT 4o mini", "GPT-5", "gpt-4o", "GPT-4o-mini", "GPT5", "GPT-5.1", "GPT 5 mini", "GPT-5 Codex", "GPT-5-mini"]
  .map((label, index) => row(label, `m${index}`));
record("model-browser-view", "filterAndRankModelRows", browser.filterAndRankModelRows, [gptRows, "gpt"], "verifier repro: ties break by label localeCompare");
const oRows = ["o3-mini", "o3 pro", "o3mini", "O3", "o3.mini", "Opus 5", "opus 5"].map((label, index) => row(label, `x${index}`));
record("model-browser-view", "filterAndRankModelRows", browser.filterAndRankModelRows, [oRows, "o"]);
const entry = (key) => ({ key, createdAt: 100, updatedAt: 200 });
const tieKeys = ["co-op", "coop", "co op", "Co-op", "a-b", "ab", "a b", "A-b", "b", "é", "e"];
record("sidebar-order", "sortSidebarEntries", (entries, mode) => order.sortSidebarEntries(entries, mode), [tieKeys.map(entry), "created"], "all keys tie on createdAt/updatedAt → localeCompare");

// ---- (3) compactNumber past the T unit ----
const format = await load("lib/format.ts");
for (const v of [999_949_999_999_999, 1e15, 9.99995e15, 1e16, 1.23456e17, 1e18, 1e19, 1e20, 1e21, -1e16, 1234.5e15]) {
  record("format", "compactNumber", format.compactNumber, [v]);
}

// ---- (4) undefined vs null ----
const pending = await load("lib/pending-input.mjs");
const workspace = await load("lib/pending-workspace.mjs");
const deepseek = await load("lib/deepseek-peak.mjs");
const spawn = await load("lib/spawn-marker.mjs");
const panels = await load("lib/panel-widths.mjs");
const picker = await load("lib/model-picker.mjs");
const codex = await load("lib/codex-mode-presets.mjs");
const store = { records: [{ key: "a", kind: "send", sessionId: "s1", text: "t", sentAt: 0, tries: 0, firstQueuedAt: 1 }] };
for (const sessionId of [undefined, null]) record("pending-input", "dueRecords", pending.dueRecords, [store, sessionId]);
for (const options of [undefined, null]) record("pending-input", "describePending", pending.describePending, [store, options]);
const intent = workspace.beginIntent("/w", "r1", 1);
for (const options of [undefined, null]) record("pending-workspace", "describeIntent", workspace.describeIntent, [intent, options]);
for (const model of [undefined, null]) {
  record("deepseek-peak", "isDeepSeekApiSession", deepseek.isDeepSeekApiSession, [{ provider: "pi", model: "deepseek/deepseek-v4-pro" }, model]);
}
for (const options of [undefined, null]) record("spawn-marker", "formatSpawnMarker", spawn.formatSpawnMarker, [options]);
for (const env of [undefined, null]) record("panel-widths", "panelWidthBounds", panels.panelWidthBounds, ["rail", env]);
for (const env of [undefined, null]) record("panel-widths", "clampPanelWidth", panels.clampPanelWidth, ["rail", 300, env]);
for (const options of [undefined, null]) record("model-picker", "modelReading", picker.modelReading, [options]);
for (const session of [undefined, null, []]) record("codex-mode-presets", "codexModeForSession", codex.codexModeForSession, [session]);

// ---- (5) v130 (S13.1-C) removedQueueIds: accepted evidence for a queue item that left the queue ----
// Needs a tether tree >= v130 (66626a8); on an older tree these cases record the pre-v130 result.
const removedShapes = [
  undefined,
  null,
  [],
  ["q-gone"],
  ["q-gone", 7, null, { queueId: "q-obj" }, "q-also"],
  "q-gone",
  { 0: "q-gone" },
];
for (const removedQueueIds of removedShapes) {
  const projection = {
    turnsById: { t1: { idempotencyKey: "k-turn" } },
    queuedMessages: [{ queueId: "q-live" }],
    ...(removedQueueIds === undefined ? {} : { removedQueueIds }),
  };
  record("pending-input", "acceptedKeys", pending.acceptedKeys, [projection]);
}
const queueStore = {
  records: [
    { key: "q-gone", kind: "queue", sessionId: "s1", text: "withdrawn elsewhere", sentAt: 1, tries: 1, firstQueuedAt: 1 },
    { key: "q-other", kind: "queue", sessionId: "s1", text: "never accepted", sentAt: 1, tries: 1, firstQueuedAt: 1 },
    { key: "q-gone", kind: "queue", sessionId: "s2", text: "other session", sentAt: 1, tries: 1, firstQueuedAt: 1 },
  ],
};
for (const removedQueueIds of [undefined, ["q-gone"], ["q-gone", "q-other"]]) {
  const projection = { turnsById: {}, queuedMessages: [], ...(removedQueueIds === undefined ? {} : { removedQueueIds }) };
  record("pending-input", "reconcileWithSnapshot", pending.reconcileWithSnapshot, [queueStore, "s1", projection]);
}

process.stdout.write(JSON.stringify({
  generator: "tools/parity/gen-helper-supplement.mjs",
  node: process.version,
  icu: process.versions.icu,
  collator: new Intl.Collator().resolvedOptions(),
  collation: { strings, sorted, signs },
  cases,
}, null, 1) + "\n");
