// Regenerates cases.json from the web's own messageStamp (web 19c56e25, lib/message-time.ts).
//   TZ=UTC LC_ALL=en_US.UTF-8 node gen.mjs <path to web clone>/lib/message-time.ts > cases.json
// Never hand-write the expected strings.
const { messageStamp } = await import(process.argv[2]);
const D = (s) => Date.parse(s);
const now = D("2026-10-18T12:00:00Z");
const inputs = [
  [D("2026-10-18T09:05:00Z"), now], [D("2026-10-18T00:00:00Z"), now], [D("2026-10-18T23:59:59.999Z"), now],
  [D("2026-10-17T23:59:00Z"), now], [D("2026-10-05T14:32:00Z"), now], [D("2026-01-01T00:00:00Z"), now],
  [D("2025-10-05T14:32:00Z"), now], [D("2025-12-31T23:47:00Z"), D("2026-01-01T00:10:00Z")],
  [D("2026-10-18T09:05:00.9Z"), now], [1_700_000_000_123.75, now], [0, now], [-86_400_000, now],
  [D("2026-03-09T09:05:00Z"), now], [D("2026-10-18T12:00:00Z"), now], [D("2026-10-19T08:00:00Z"), now],
  [null, now], [undefined, now], [Number.NaN, now], [Number.POSITIVE_INFINITY, now],
];
console.log(JSON.stringify(inputs.map(([ts, n]) => ({ ts: ts === undefined ? null : Number.isFinite(ts) ? ts : null, now: n, stamp: messageStamp(ts, n) })), null, 1));
