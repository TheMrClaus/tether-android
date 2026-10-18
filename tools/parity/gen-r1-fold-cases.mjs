#!/usr/bin/env node
// ta-d3tj.10 (R1): fold cases for the reduceEvent hunks the vendored corpus does NOT exercise (task reopen / terminal
// dedupe, withdrawn queue ids, queued held / peer sender, spawned-run summary, pi-dialog replace, the session-error cap,
// idempotency and unstamped paths). Every expected projection is the web's own `reduce` at the tether tree given as
// argv[2] (19c56e25), in the corpus case format (ReducerR1FoldCasesTest reads them). Needs node >= 24 (type stripping).
//
//   node tools/parity/gen-r1-fold-cases.mjs <tether-tree> core/reducer/src/test/resources/r1-fold
import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

const [, , tree, outDir] = process.argv;
if (!tree || !outDir) throw new Error("usage: gen-r1-fold-cases.mjs <tether-tree> <out-dir>");
const { initialSessionState, reduce } = await import(pathToFileURL(path.resolve(tree, "engines/events.ts")).href);
const { canonicalJson } = await import(pathToFileURL(path.resolve(tree, "scripts/parity-corpus/canonical-json.mjs")).href);

const BASE = 1_790_000_000_000;
const INITIAL = { tetherSessionId: "s1", provider: "claude", cwd: "/workspace" };
// journal stamps exactly like the corpus (seq = 1-based position, ts = base + seq*1000) unless the event authors its own;
// `unstamped: true` leaves seq and ts off.
const stamp = (events) => events.map((e, i) => {
  const { unstamped, ...rest } = e;
  return unstamped ? rest : { seq: i + 1, ts: BASE + (i + 1) * 1000, ...rest };
});
const long = (n) => "x".repeat(n);

const cases = [];
const add = (name, description, events) => cases.push({ name, description, events: stamp(events) });

const turnStart = (turnId, extra = {}) => ({ type: "turn_started", turnId, idempotencyKey: `k-${turnId}`, ...extra });

add("r1-session-errors", "turn-less errors fold into sessionErrors (idempotent by seq, cause only when truthy, unstamped, cap), turn errors set errorAt first-wins, turn_end sets endedAt, turn_started clears lastError", [
  { type: "error", turnId: null, message: "boom", cause: "auth_expired" },
  { type: "error", turnId: null, message: "boom", seq: 1, ts: BASE + 1000 }, // same seq replayed: one entry
  { type: "error", turnId: null, message: "boom" }, // identical text, new seq: a second entry
  { type: "error", turnId: null, message: "no stamp", unstamped: true },
  { type: "error", turnId: null, message: "bad ts", ts: -5 },
  { type: "error", turnId: null, message: "fractional ts", ts: 1790000007000.5 },
  turnStart("t1"),
  { type: "error", turnId: "t1", message: "turn error one" },
  { type: "error", turnId: "t1", message: "turn error two" },
  { type: "turn_end", turnId: "t1", outcome: "error" },
  turnStart("t2"),
  { type: "error", turnId: "t2", message: "unstamped turn error", unstamped: true },
  { type: "turn_end", turnId: "t2", outcome: "error", unstamped: true },
  { type: "error", turnId: null, message: "after turns", cause: "usage_limit" },
  turnStart("t3"),
  { type: "turn_end", turnId: "t3", outcome: "ok", ts: 3.5 },
]);

add("r1-session-errors-cap", "the 21st turn-less error drops the oldest (slice(-20)), a replayed seq inside the window stays one", [
  ...Array.from({ length: 22 }, (_, i) => ({ type: "error", turnId: null, message: `e${i}` })),
  { type: "error", turnId: null, message: "e21", seq: 22, ts: BASE + 22_000 },
  { type: "error", turnId: null, message: "e0 again", seq: 3 },
]);

add("r1-task-reopen-and-dedupe", "task_started reopens a terminal record; a terminal task_completed ignores a conflicting status and only fills what is missing", [
  { type: "task_started", turnId: null, taskId: "k1", toolUseId: "tu1", taskType: "agent" },
  { type: "task_completed", turnId: null, taskId: "k1", status: "completed", summary: "first summary", outputFile: "/tmp/out1", totalTokens: 10 },
  { type: "task_completed", turnId: null, taskId: "k1", status: "failed", summary: "conflict" }, // ignored
  { type: "task_completed", turnId: null, taskId: "k1", status: "completed", summary: "second", toolUses: 3 }, // fills nothing but usage
  { type: "task_started", turnId: null, taskId: "k1", taskType: "agent" }, // reopen: summary/outputFile/usage dropped, toolUseId kept
  { type: "task_progress", turnId: null, taskId: "k1", status: "running", lastToolName: "Read" },
  { type: "task_completed", turnId: null, taskId: "k1", status: "stopped", summary: long(2500), outputFile: long(4200) },
  { type: "task_completed", turnId: null, taskId: "k1", status: "stopped", summary: "late", outputFile: "/late", toolUseId: "tu9", durationMs: 7 },
  { type: "task_started", turnId: null, taskId: "k2" },
  { type: "task_completed", turnId: null, taskId: "k2", status: "completed", summary: "", outputFile: "" },
  { type: "task_completed", turnId: null, taskId: "k2", status: "completed", summary: "filled later", toolUseId: "tu2" },
  { type: "task_started", turnId: null, taskId: "k3", toolUseId: "tu3" },
  { type: "task_completed", turnId: null, taskId: "k3", status: "completed" },
  { type: "task_started", turnId: null, taskId: "k3", toolUseId: "tu4" },
]);

add("r1-queue-held-peer-withdrawn", "queued held set/cleared/left, peer + handover sender fields (bounded), withdrawn vs flushed removal and the flushed-then-requeued id", [
  { type: "queued_message_added", turnId: null, queueId: "q1", text: "hello", origin: "operator" },
  { type: "queued_message_updated", turnId: null, queueId: "q1", text: "hello edited", held: true },
  { type: "queued_message_updated", turnId: null, queueId: "q1", text: "hello again" }, // held untouched
  { type: "queued_message_updated", turnId: null, queueId: "q1", text: "released", held: false },
  { type: "queued_message_updated", turnId: null, queueId: "q1", text: "string held", held: "yes" },
  { type: "queued_message_added", turnId: null, queueId: "q2", text: "from a peer", origin: "peer", noticeKind: "peer", fromSessionId: "sess-9", fromSessionName: "Peer nine" },
  { type: "queued_message_added", turnId: null, queueId: "q3", text: "handover", noticeKind: "handover", fromSessionId: "sess-1", fromSessionName: long(120) },
  { type: "queued_message_added", turnId: null, queueId: "q4", text: "no id", noticeKind: "peer", fromSessionName: "nameless" },
  { type: "queued_message_added", turnId: null, queueId: "q5", text: "other kind", noticeKind: "info", fromSessionId: "sess-2", fromSessionName: "ignored" },
  { type: "queued_message_added", turnId: null, queueId: "q6", text: "deferred", flushMode: "next-call", noticeKind: "peer", fromSessionId: "sess-3" },
  { type: "queued_message_removed", turnId: null, queueId: "q1", reason: "flushed" },
  { type: "queued_message_added", turnId: null, queueId: "q1", text: "requeued" },
  { type: "queued_message_removed", turnId: null, queueId: "q1", reason: "withdrawn" }, // leaves removedQueueIds, enters withdrawnQueueIds
  { type: "queued_message_removed", turnId: null, queueId: "q2" },
  { type: "queued_message_removed", turnId: null, queueId: "q3", reason: "withdrawn" },
  { type: "queued_message_removed", turnId: null, queueId: "q3", reason: "withdrawn" }, // gone: no-op
  ...Array.from({ length: 52 }, (_, i) => [
    { type: "queued_message_added", turnId: null, queueId: `w${i}`, text: `w${i}` },
    { type: "queued_message_removed", turnId: null, queueId: `w${i}`, reason: "withdrawn" },
  ]).flat(),
]);

add("r1-spawned-run-summary", "spawned_run_updated carries a bounded summary (counts, partial flag, final message + truncated flag) and a later update without a reading keeps it", [
  { type: "spawned_run_updated", turnId: null, runId: "r1", provider: "codex", origin: "spawned", status: "running", startedAt: BASE, title: "child" },
  { type: "spawned_run_updated", turnId: null, runId: "r1", provider: "codex", origin: "spawned", status: "finished", startedAt: BASE, endedAt: BASE + 5000, exitCode: 0, inputTokens: 100, outputTokens: 20, cachedInputTokens: 0, toolCalls: 4, toolCallsPartial: true, finalMessage: "all done", finalMessageTruncated: true },
  { type: "spawned_run_updated", turnId: null, runId: "r1", provider: "codex", origin: "spawned", status: "finished", startedAt: BASE, endedAt: BASE + 5000, exitCode: 0, nativeId: "native-1" }, // no reading: carried
  { type: "spawned_run_updated", turnId: null, runId: "r1", provider: "codex", origin: "spawned", status: "finished", startedAt: BASE, endedAt: BASE + 5000, exitCode: 0, nativeId: "native-1", totalTokens: 7, finalMessage: "second words" },
  { type: "spawned_run_updated", turnId: null, runId: "r2", provider: "codex", origin: "discovered", status: "running", inputTokens: -1, outputTokens: 1.5, totalTokens: 9007199254740993, toolCalls: "3", toolCallsPartial: true, finalMessage: "", finalMessageTruncated: true },
  { type: "spawned_run_updated", turnId: null, runId: "r3", provider: "codex", origin: "spawned", status: "running", toolCallsPartial: true, finalMessage: long(8200), finalMessageTruncated: true },
  { type: "spawned_run_updated", turnId: null, runId: "r3", provider: "codex", origin: "spawned", status: "finished", toolCalls: 2 }, // partial flag travels with the count
]);

add("r1-pi-dialog-notice-replace", "the pi-dialog-cancelled session notice is replaced in place by its latest instance; any other notice id stays first-wins", [
  { type: "provider_notice", turnId: null, noticeId: "pi-dialog-cancelled", level: "info", message: "1 dialog cancelled" },
  { type: "provider_notice", turnId: null, noticeId: "other", level: "warning", message: "other one" },
  { type: "provider_notice", turnId: null, noticeId: "pi-dialog-cancelled", level: "warning", message: "2 dialogs cancelled", code: "pi_dialog" },
  { type: "provider_notice", turnId: null, noticeId: "other", level: "error", message: "other two (ignored)" },
  turnStart("t1"),
  { type: "provider_notice", turnId: "t1", noticeId: "pi-dialog-cancelled", level: "info", message: "turn one" },
  { type: "provider_notice", turnId: "t1", noticeId: "pi-dialog-cancelled", level: "info", message: "turn two" },
]);

add("r1-user-message-from", "user_message_accepted carries a bounded peer `from` (already folded since v149; the corpus has no case)", [
  turnStart("t1"),
  { type: "user_message_accepted", turnId: "t1", text: "hi", from: { sessionId: "sess-5", name: "Peer five" } },
  turnStart("t2"),
  { type: "user_message_accepted", turnId: "t2", text: "no id", from: { name: "nobody" } },
]);

mkdirSync(outDir, { recursive: true });
for (const spec of cases) {
  const initial = { ...INITIAL };
  let state = initialSessionState(initial);
  const expectedInitialProjection = JSON.parse(canonicalJson(state));
  const inputEvents = JSON.parse(canonicalJson(spec.events));
  const steps = inputEvents.map((event) => {
    state = reduce(state, event);
    return JSON.parse(canonicalJson(state));
  });
  const file = {
    name: spec.name,
    description: spec.description,
    source: { kind: "synthetic", generator: "tools/parity/gen-r1-fold-cases.mjs", web: "19c56e25" },
    initial,
    expectedInitialProjection,
    inputEvents,
    projectionSampling: { rule: "every-step" },
    expectedProjectionAfterEachStep: steps,
  };
  writeFileSync(path.join(outDir, `${spec.name}.json`), canonicalJson(file) + "\n");
}
console.log(`wrote ${cases.length} cases to ${outDir}`);
