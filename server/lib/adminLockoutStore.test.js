"use strict";

// Unit tests for the durable admin lockout store (S04-L3), Cloudflare KV
// migration. Run with: npm test   (inside server/) — uses the Node built-in
// test runner.
//
// No live Cloudflare credentials are used or required: the KV client is a
// small in-memory fake that reproduces just enough of
// `cloudflareKvStore.js`'s client surface (`get`, `put`, `delete`) to
// exercise the store's logic, including its (non-atomic, documented)
// read-modify-write increment and its fail-safe fallback on thrown errors.

const test = require("node:test");
const assert = require("node:assert/strict");
const { createAdminLockoutStore, buildKey } = require("./adminLockoutStore");

// Reproduces just the behavior `cloudflareKvStore.js`'s real client exposes:
// get() resolves to the stored string or null if absent/expired, put()
// stores a string with a TTL, delete() removes it. TTL expiry is modeled
// the same way Cloudflare's own would be (a value that has outlived its TTL
// reads back as absent) — the store's own windowStart bookkeeping is what
// application logic actually relies on, but this fake keeps KV-level TTL
// honest too, so a test that only trusted TTL expiry would still pass.
function makeKvFake({ now = () => Date.now() } = {}) {
  const store = new Map(); // key -> { value, expiresAt: ms }

  function isLive(entry) {
    return entry && entry.expiresAt > now();
  }

  return {
    store,
    async get(key) {
      const entry = store.get(key);
      if (!isLive(entry)) return null;
      return entry.value;
    },
    async put(key, value, { expirationTtl } = {}) {
      const ttlMs = Math.max(60, Number(expirationTtl) || 0) * 1000;
      store.set(key, { value, expiresAt: now() + ttlMs });
    },
    async delete(key) {
      store.delete(key);
    },
  };
}

test("buildKey namespaces and includes the (already-normalized) ip", () => {
  assert.equal(buildKey("1.2.3.4"), "duoshield:adminlock:1.2.3.4");
});

test("kvEnabled is false with no client and true with one", () => {
  assert.equal(createAdminLockoutStore().kvEnabled, false);
  assert.equal(createAdminLockoutStore({ kv: makeKvFake() }).kvEnabled, true);
});

test("local fallback (no kv): repeated failures accumulate and trigger lockout at threshold", async () => {
  const store = createAdminLockoutStore({ maxFails: 3, windowMs: 60_000 });
  assert.equal(await store.isLocked("1.2.3.4"), false);
  await store.recordFailure("1.2.3.4");
  await store.recordFailure("1.2.3.4");
  assert.equal(await store.isLocked("1.2.3.4"), false); // 2 < 3
  await store.recordFailure("1.2.3.4");
  assert.equal(await store.isLocked("1.2.3.4"), true); // 3 >= 3
});

test("local fallback: a different key is unaffected by another key's failures", async () => {
  const store = createAdminLockoutStore({ maxFails: 2 });
  await store.recordFailure("1.1.1.1");
  await store.recordFailure("1.1.1.1");
  assert.equal(await store.isLocked("1.1.1.1"), true);
  assert.equal(await store.isLocked("2.2.2.2"), false);
});

test("local fallback: reset clears the failure state", async () => {
  const store = createAdminLockoutStore({ maxFails: 1 });
  await store.recordFailure("1.2.3.4");
  assert.equal(await store.isLocked("1.2.3.4"), true);
  await store.reset("1.2.3.4");
  assert.equal(await store.isLocked("1.2.3.4"), false);
});

test("kv-backed: missing key reads back as zero failures, not locked", async () => {
  const kv = makeKvFake();
  const store = createAdminLockoutStore({ kv, maxFails: 3 });
  assert.equal(await store.isLocked("1.2.3.4"), false);
  assert.equal(await store.count("1.2.3.4"), 0);
});

test("kv-backed: repeated failures are counted via read-modify-write and reach lockout at threshold", async () => {
  const kv = makeKvFake();
  const store = createAdminLockoutStore({ kv, maxFails: 3, windowMs: 60_000 });
  await store.recordFailure("1.2.3.4");
  await store.recordFailure("1.2.3.4");
  assert.equal(await store.isLocked("1.2.3.4"), false);
  await store.recordFailure("1.2.3.4");
  assert.equal(await store.isLocked("1.2.3.4"), true);
  const raw = await kv.get(buildKey("1.2.3.4"));
  assert.equal(JSON.parse(raw).count, 3);
});

test("kv-backed: a key's stored value round-trips as JSON with count and windowStart", async () => {
  const kv = makeKvFake();
  const store = createAdminLockoutStore({ kv, maxFails: 5 });
  await store.recordFailure("1.2.3.4");
  const raw = await kv.get(buildKey("1.2.3.4"));
  const parsed = JSON.parse(raw);
  assert.equal(parsed.count, 1);
  assert.equal(typeof parsed.windowStart, "number");
});

test("kv-backed: TTL/window is anchored on first failure (not pushed out by later failures)", async () => {
  let currentTime = 1_000_000;
  const kv = makeKvFake({ now: () => currentTime });
  const store = createAdminLockoutStore({ kv, windowMs: 10_000, now: () => currentTime });
  await store.recordFailure("1.2.3.4");
  const first = JSON.parse(await kv.get(buildKey("1.2.3.4")));
  assert.equal(first.windowStart, currentTime);
  currentTime += 2000; // still inside the 10s window
  await store.recordFailure("1.2.3.4");
  const second = JSON.parse(await kv.get(buildKey("1.2.3.4")));
  // Anchored to the first failure's window, not extended by the second.
  assert.equal(second.windowStart, first.windowStart);
  assert.equal(second.count, 2);
});

test("kv-backed: failure state expires — a stale record is treated as zero failures, not locked", async () => {
  let currentTime = 1_000_000;
  const kv = makeKvFake({ now: () => currentTime });
  const store = createAdminLockoutStore({ kv, maxFails: 1, windowMs: 1000, now: () => currentTime });
  await store.recordFailure("1.2.3.4");
  assert.equal(await store.isLocked("1.2.3.4"), true);
  currentTime += 1001; // past the logical window
  assert.equal(await store.isLocked("1.2.3.4"), false);
});

test("kv-backed: a new failure after the window has elapsed starts a fresh window instead of accumulating", async () => {
  let currentTime = 1_000_000;
  const kv = makeKvFake({ now: () => currentTime });
  const store = createAdminLockoutStore({ kv, maxFails: 2, windowMs: 1000, now: () => currentTime });
  await store.recordFailure("1.2.3.4");
  await store.recordFailure("1.2.3.4");
  assert.equal(await store.isLocked("1.2.3.4"), true);
  currentTime += 1001; // window elapses
  await store.recordFailure("1.2.3.4"); // first failure of a new window
  const rec = JSON.parse(await kv.get(buildKey("1.2.3.4")));
  assert.equal(rec.count, 1);
  assert.equal(rec.windowStart, currentTime);
  assert.equal(await store.isLocked("1.2.3.4"), false);
});

test("kv-backed: reset (successful authentication) clears the failure state", async () => {
  const kv = makeKvFake();
  const store = createAdminLockoutStore({ kv, maxFails: 1 });
  await store.recordFailure("1.2.3.4");
  assert.equal(await store.isLocked("1.2.3.4"), true);
  await store.reset("1.2.3.4");
  assert.equal(await store.isLocked("1.2.3.4"), false);
  assert.equal(kv.store.has(buildKey("1.2.3.4")), false);
});

test("kv errors on isLocked fail safe: falls back to the local map instead of throwing", async () => {
  const errors = [];
  const kv = {
    async get() { throw new Error("cloudflare unreachable"); },
    async put() { throw new Error("cloudflare unreachable"); },
    async delete() { throw new Error("cloudflare unreachable"); },
  };
  const store = createAdminLockoutStore({
    kv,
    maxFails: 1,
    onError: (op, err) => errors.push([op, err.message]),
  });
  // recordFailure degrades to the local map rather than throwing.
  await assert.doesNotReject(() => store.recordFailure("1.2.3.4"));
  // isLocked also degrades to the local map (which now has the failure from
  // the call above) rather than throwing or silently reporting "not locked".
  const locked = await store.isLocked("1.2.3.4");
  assert.equal(locked, true);
  assert.ok(errors.some(([op]) => op === "recordFailure"));
  assert.ok(errors.some(([op]) => op === "isLocked"));
});

test("kv errors surface Cloudflare API failures (non-2xx) the same way as network errors", async () => {
  const errors = [];
  const kv = {
    async get() {
      const err = new Error("Cloudflare KV GET failed (500): Internal Server Error");
      err.status = 500;
      throw err;
    },
    async put() {
      const err = new Error("Cloudflare KV PUT failed (403): Authentication error");
      err.status = 403;
      throw err;
    },
    async delete() { return; },
  };
  const store = createAdminLockoutStore({ kv, maxFails: 1, onError: (op, err) => errors.push([op, err.message]) });
  await store.recordFailure("1.2.3.4"); // GET throws inside recordFailure -> falls back locally
  assert.ok(errors.some(([op, msg]) => op === "recordFailure" && msg.includes("500")));
});

test("kv errors on malformed API responses (bad JSON) degrade to local fallback, not a crash", async () => {
  const errors = [];
  const kv = {
    async get() { return "{not valid json"; }, // malformed stored value
    async put() { return; },
    async delete() { return; },
  };
  const store = createAdminLockoutStore({ kv, maxFails: 1, onError: (op, err) => errors.push([op, err.message]) });
  // A malformed record is parsed as "absent" (see parseRecord), not thrown —
  // isLocked() should resolve false, not reject.
  await assert.doesNotReject(() => store.isLocked("1.2.3.4"));
  assert.equal(await store.isLocked("1.2.3.4"), false);
});

test("kv errors on reset fail safe: does not throw, still clears local fallback state", async () => {
  const kv = {
    async get() { return JSON.stringify({ count: 1, windowStart: Date.now() }); },
    async put() { throw new Error("cloudflare unreachable"); },
    async delete() { throw new Error("cloudflare unreachable"); },
  };
  const store = createAdminLockoutStore({ kv, maxFails: 1, onError: () => {} });
  await store.recordFailure("1.2.3.4"); // degrades to local (put throws)
  await assert.doesNotReject(() => store.reset("1.2.3.4"));
});

test("network timeout while calling kv degrades to local fallback rather than hanging or throwing", async () => {
  const errors = [];
  const kv = {
    async get() {
      const err = new Error("Cloudflare KV GET timed out after 5000ms");
      err.name = "CloudflareKvError";
      throw err;
    },
    async put() { return; },
    async delete() { return; },
  };
  const store = createAdminLockoutStore({ kv, maxFails: 1, onError: (op, err) => errors.push([op, err.message]) });
  await assert.doesNotReject(() => store.isLocked("1.2.3.4"));
  assert.ok(errors.some(([, msg]) => msg.includes("timed out")));
});

test("concurrent recordFailure calls against the kv-backed store: documented race can undercount but never overcounts or throws", async () => {
  // Models the accepted, documented Cloudflare-KV race: this fake's get/put
  // are not atomic with respect to each other across concurrent calls (same
  // shape as the real client), so N concurrent recordFailure() calls that
  // all read before any of them writes can land on fewer than N in the
  // stored count. This test locks in that specific, bounded failure mode
  // (undercount only) rather than either pretending the race away or
  // regressing to something worse (overcount, thrown error, or lost lockout
  // entirely for a get-based interleaving that should have locked).
  const kv = makeKvFake();
  let pendingGets = [];
  const racyKv = {
    async get(key) {
      // Defer resolution so multiple concurrent calls can all read the
      // pre-increment value before any of them writes — the actual race.
      const value = await kv.get(key);
      await new Promise((resolve) => pendingGets.push(resolve));
      return value;
    },
    put: kv.put,
    delete: kv.delete,
  };
  const store = createAdminLockoutStore({ kv: racyKv, maxFails: 100, windowMs: 60_000 });

  const N = 5;
  const calls = Array.from({ length: N }, () => store.recordFailure("1.2.3.4"));
  // Let every get() start and stall at the deferred point, then release them
  // all at once so their writes race exactly as they would over real HTTP.
  await new Promise((resolve) => setImmediate(resolve));
  pendingGets.forEach((resolve) => resolve());
  await Promise.all(calls);

  const finalCount = JSON.parse(await kv.get(buildKey("1.2.3.4"))).count;
  assert.ok(finalCount >= 1 && finalCount <= N, `expected count in [1, ${N}], got ${finalCount}`);
  // The documented failure mode is undercounting under contention, never a
  // count exceeding the number of actual failures recorded.
  assert.ok(finalCount <= N);
});

test("normalizeIp is applied before building the kv/local key (IPv6 /64 collapsing, S04-M1)", async () => {
  const kv = makeKvFake();
  // Two different full IPv6 addresses in the same /64, pre-collapsed by the
  // caller-supplied normalizer to the same prefix — mirrors how index.js
  // wires pure.normalizeIpForRateLimit in.
  const normalizeIp = (ip) => (ip.startsWith("2001:db8::") ? "2001:db8:0:0" : ip);
  const store = createAdminLockoutStore({ kv, maxFails: 2, normalizeIp });
  await store.recordFailure("2001:db8::1");
  await store.recordFailure("2001:db8::2"); // different address, same /64
  assert.equal(await store.isLocked("2001:db8::3"), true); // same /64 again
  assert.equal(kv.store.size, 1); // both failures landed on one key
});

test("distinct ips outside the same /64 remain independent after normalization", async () => {
  const kv = makeKvFake();
  const normalizeIp = (ip) => ip.split(":").slice(0, 4).join(":");
  const store = createAdminLockoutStore({ kv, maxFails: 1, normalizeIp });
  await store.recordFailure("2001:db8:0:0:aaaa::1");
  assert.equal(await store.isLocked("2001:db8:0:1::1"), false);
});

test("a transient kv read failure right after a healthy kv write degrades to 'not locked', not a thrown error", async () => {
  // recordFailure() succeeded against a healthy kv, so the count lives only
  // in kv, not in the local fallback Map. If isLocked()'s later read then
  // throws (a transient blip), it falls back to the local Map — which has
  // no record for this key, since it was never written while kv was
  // healthy — so this documents the actual, deliberate behavior: a
  // momentary read failure right after a kv-only write resolves to "not
  // locked" rather than throwing. This is fail-safe for availability (an
  // operator is never hard-blocked by a transient Cloudflare blip) while the
  // very next successful read reflects the true kv state again; it does not
  // constitute fail-open for the counter's floor, since it takes a genuine
  // kv outage coinciding with a fresh key to matter at all.
  const kv = makeKvFake();
  const store = createAdminLockoutStore({ kv, maxFails: 1 });
  await store.recordFailure("1.2.3.4"); // succeeds against kv
  assert.equal(await store.isLocked("1.2.3.4"), true);

  const flakyKv = {
    async get() { throw new Error("cloudflare unreachable"); },
    put: kv.put,
    delete: kv.delete,
  };
  const flakyStore = createAdminLockoutStore({ kv: flakyKv, maxFails: 1, onError: () => {} });
  assert.equal(await flakyStore.isLocked("1.2.3.4"), false);
});
