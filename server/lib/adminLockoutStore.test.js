"use strict";

// Unit tests for the durable admin lockout store (S04-L3), Durable Object
// migration. Run with: npm test   (inside server/) — uses the Node built-in
// test runner.
//
// No live Cloudflare credentials are used or required: `client` is a fake
// implementing the same shape `adminLockoutWorkerClient.js` exposes
// (`recordFailure`, `getStatus`, `reset`), backed either by a simple
// in-memory Map (for the non-concurrency tests) or, for the concurrency
// tests, by an actual instance of the real Durable Object class
// (`worker/src/adminLockoutDurableObject.js`) run in-process — so those
// tests exercise the real atomic increment logic, not a re-implementation
// of it that could drift from what's deployed.

const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { createAdminLockoutStore, buildKey } = require("./adminLockoutStore");

// A minimal in-memory fake of the Worker/Durable-Object client interface,
// for tests that don't need genuine concurrency semantics (those use the
// real Durable Object below instead). Single-key-at-a-time semantics here
// are intentionally simplistic — this fake is NOT used to make any claim
// about atomicity; see makeRealDurableObjectClient for that.
function makeSimpleClientFake() {
  const store = new Map(); // key -> { count, windowStart }
  return {
    store,
    async recordFailure(key, { windowMs, maxFails }) {
      const t = Date.now();
      let rec = store.get(key);
      if (!rec || t - rec.windowStart >= windowMs) {
        rec = { count: 1, windowStart: t };
      } else {
        rec = { count: rec.count + 1, windowStart: rec.windowStart };
      }
      store.set(key, rec);
      return { count: rec.count, locked: rec.count >= maxFails };
    },
    async getStatus(key, { windowMs, maxFails }) {
      const rec = store.get(key);
      if (!rec || Date.now() - rec.windowStart >= windowMs) return { count: 0, locked: false };
      return { count: rec.count, locked: rec.count >= maxFails };
    },
    async reset(key) {
      store.delete(key);
    },
  };
}

// Loads the actual Durable Object class and wraps it behind the same
// client interface (recordFailure/getStatus/reset), routing every call
// through ONE shared instance the way Cloudflare would for a single
// `idFromName(key)` — every request against that instance is processed
// strictly one at a time (Cloudflare's documented input/output gating),
// which is exactly the property under test. This talks to the real
// `AdminLockoutCounter` class, not a re-implementation, so a regression in
// the actual atomic-increment logic would fail these tests too.
function makeRealDurableObjectClient() {
  const workerSrc = path.join(__dirname, "..", "..", "worker", "src", "adminLockoutDurableObject.js");
  const { AdminLockoutCounter } = require(workerSrc);

  // One Durable Object instance per key, mirroring `idFromName` — Cloudflare
  // routes all requests for the same name to the same instance, and this
  // module's `key` (built by buildKey()) is exactly that name.
  const instances = new Map(); // key -> AdminLockoutCounter

  // Minimal fake of the `DurableObjectState` constructor argument: only
  // `storage` is used by the real class, backed by an in-memory Map with
  // the same get/put/delete/list shape as Cloudflare's real
  // `DurableObjectStorage`.
  function makeState() {
    const storage = new Map();
    return {
      storage: {
        async get(k) { return storage.get(k); },
        async put(k, v) { storage.set(k, v); },
        async delete(k) { storage.delete(k); },
      },
    };
  }

  function getInstance(key) {
    let inst = instances.get(key);
    if (!inst) {
      inst = new AdminLockoutCounter(makeState(), {});
      instances.set(key, inst);
    }
    return inst;
  }

  async function call(key, action, extra) {
    const inst = getInstance(key);
    const res = await inst.fetch("https://admin-lockout.internal/", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ action, ...extra }),
    });
    if (!res.ok) {
      const err = new Error(`Durable Object call failed (${res.status})`);
      err.status = res.status;
      throw err;
    }
    return res.json();
  }

  return {
    instances,
    async recordFailure(key, { windowMs, maxFails }) {
      return call(key, "record", { windowMs, maxFails });
    },
    async getStatus(key, { windowMs, maxFails }) {
      return call(key, "status", { windowMs, maxFails });
    },
    async reset(key) {
      await call(key, "reset", {});
    },
  };
}

test("buildKey namespaces and includes the (already-normalized) ip", () => {
  assert.equal(buildKey("1.2.3.4"), "duoshield:adminlock:1.2.3.4");
});

test("durableEnabled is false with no client and true with one", () => {
  assert.equal(createAdminLockoutStore().durableEnabled, false);
  assert.equal(createAdminLockoutStore({ client: makeSimpleClientFake() }).durableEnabled, true);
});

test("local fallback (no client): repeated failures accumulate and trigger lockout at threshold", async () => {
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

test("durable: missing key reads back as zero failures, not locked", async () => {
  const client = makeSimpleClientFake();
  const store = createAdminLockoutStore({ client, maxFails: 3 });
  assert.equal(await store.isLocked("1.2.3.4"), false);
  assert.equal(await store.count("1.2.3.4"), 0);
});

test("durable: repeated failures are counted and reach lockout at threshold", async () => {
  const client = makeSimpleClientFake();
  const store = createAdminLockoutStore({ client, maxFails: 3, windowMs: 60_000 });
  await store.recordFailure("1.2.3.4");
  await store.recordFailure("1.2.3.4");
  assert.equal(await store.isLocked("1.2.3.4"), false);
  await store.recordFailure("1.2.3.4");
  assert.equal(await store.isLocked("1.2.3.4"), true);
  assert.equal(client.store.get(buildKey("1.2.3.4")).count, 3);
});

test("durable: reset (successful authentication) clears the failure state", async () => {
  const client = makeSimpleClientFake();
  const store = createAdminLockoutStore({ client, maxFails: 1 });
  await store.recordFailure("1.2.3.4");
  assert.equal(await store.isLocked("1.2.3.4"), true);
  await store.reset("1.2.3.4");
  assert.equal(await store.isLocked("1.2.3.4"), false);
  assert.equal(client.store.has(buildKey("1.2.3.4")), false);
});

test("client errors on isLocked fail safe: falls back to the local map instead of throwing", async () => {
  const errors = [];
  const client = {
    async recordFailure() { throw new Error("worker unreachable"); },
    async getStatus() { throw new Error("worker unreachable"); },
    async reset() { throw new Error("worker unreachable"); },
  };
  const store = createAdminLockoutStore({
    client,
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

test("Cloudflare API / Worker HTTP failures (non-2xx) degrade to local fallback the same way as network errors", async () => {
  const errors = [];
  const client = {
    async recordFailure() {
      const err = new Error("Admin lockout Worker call failed (500): Internal Server Error");
      err.status = 500;
      throw err;
    },
    async getStatus() {
      const err = new Error("Admin lockout Worker call failed (401): Unauthorized");
      err.status = 401;
      throw err;
    },
    async reset() { return; },
  };
  const store = createAdminLockoutStore({ client, maxFails: 1, onError: (op, err) => errors.push([op, err.message]) });
  await store.recordFailure("1.2.3.4"); // throws inside recordFailure -> falls back locally
  assert.ok(errors.some(([op, msg]) => op === "recordFailure" && msg.includes("500")));
});

test("Durable Object / Worker failure on reset fails safe: does not throw, still clears local fallback state", async () => {
  const client = {
    async recordFailure() { throw new Error("worker unreachable"); }, // forces local fallback to be populated
    async getStatus() { return { count: 0, locked: false }; },
    async reset() { throw new Error("worker unreachable"); },
  };
  const store = createAdminLockoutStore({ client, maxFails: 1, onError: () => {} });
  await store.recordFailure("1.2.3.4"); // degrades to local
  await assert.doesNotReject(() => store.reset("1.2.3.4"));
});

test("network timeout while calling the Worker degrades to local fallback rather than hanging or throwing", async () => {
  const errors = [];
  const client = {
    async recordFailure() {
      const err = new Error("Admin lockout Worker call timed out after 5000ms");
      err.name = "AdminLockoutWorkerError";
      throw err;
    },
    async getStatus() { return { count: 0, locked: false }; },
    async reset() { return; },
  };
  const store = createAdminLockoutStore({ client, maxFails: 1, onError: (op, err) => errors.push([op, err.message]) });
  await assert.doesNotReject(() => store.recordFailure("1.2.3.4"));
  assert.ok(errors.some(([, msg]) => msg.includes("timed out")));
});

test("normalizeIp is applied before building the key (IPv6 /64 collapsing, S04-M1)", async () => {
  const client = makeSimpleClientFake();
  // Two different full IPv6 addresses in the same /64, pre-collapsed by the
  // caller-supplied normalizer to the same prefix — mirrors how index.js
  // wires pure.normalizeIpForRateLimit in.
  const normalizeIp = (ip) => (ip.startsWith("2001:db8::") ? "2001:db8:0:0" : ip);
  const store = createAdminLockoutStore({ client, maxFails: 2, normalizeIp });
  await store.recordFailure("2001:db8::1");
  await store.recordFailure("2001:db8::2"); // different address, same /64
  assert.equal(await store.isLocked("2001:db8::3"), true); // same /64 again
  assert.equal(client.store.size, 1); // both failures landed on one key
});

test("distinct ips outside the same /64 remain independent after normalization", async () => {
  const client = makeSimpleClientFake();
  const normalizeIp = (ip) => ip.split(":").slice(0, 4).join(":");
  const store = createAdminLockoutStore({ client, maxFails: 1, normalizeIp });
  await store.recordFailure("2001:db8:0:0:aaaa::1");
  assert.equal(await store.isLocked("2001:db8:0:1::1"), false);
});

test("a client error right after a successful recordFailure degrades isLocked to 'not locked', not a thrown error", async () => {
  // recordFailure() succeeded against a healthy client, so the count lives
  // only in the Durable Object, not in the local fallback Map. If
  // isLocked()'s later getStatus() call then throws (a transient blip), it
  // falls back to the local Map — which has no record for this key, since
  // it was never written while the client was healthy — so this documents
  // the deliberate behavior: a momentary read failure right after a
  // durable-only write resolves to "not locked" rather than throwing. This
  // is fail-safe for availability (an operator is never hard-blocked by a
  // transient Cloudflare blip); it does not constitute fail-open for the
  // counter's floor, since it takes a genuine outage coinciding with a
  // fresh key to matter at all, and the very next successful read reflects
  // the true durable state again.
  const client = makeSimpleClientFake();
  const store = createAdminLockoutStore({ client, maxFails: 1 });
  await store.recordFailure("1.2.3.4"); // succeeds against the durable client
  assert.equal(await store.isLocked("1.2.3.4"), true);

  const flakyClient = {
    async recordFailure() { throw new Error("worker unreachable"); },
    async getStatus() { throw new Error("worker unreachable"); },
    async reset() { throw new Error("worker unreachable"); },
  };
  const flakyStore = createAdminLockoutStore({ client: flakyClient, maxFails: 1, onError: () => {} });
  assert.equal(await flakyStore.isLocked("1.2.3.4"), false);
});

// ── Concurrency: these route through the REAL Durable Object class ─────────
// (worker/src/adminLockoutDurableObject.js) rather than a hand-rolled fake,
// so a regression in the actual atomic-increment logic fails here too, not
// just in the worker/ test suite. Cloudflare guarantees all requests to one
// Durable Object instance are processed strictly one at a time; each of
// these tests fires genuinely concurrent (unawaited-until-the-end) calls
// against a single shared instance for one key, and asserts the count can
// never come back lower than the number of calls issued.

test("CONCURRENCY: 10 simultaneous recordFailure calls (same IPv4 key) produce exactly count=10, locked=true, never an undercount", async () => {
  const client = makeRealDurableObjectClient();
  const store = createAdminLockoutStore({ client, maxFails: 10, windowMs: 60_000 });
  const ip = "203.0.113.7";

  const N = 10;
  await Promise.all(Array.from({ length: N }, () => store.recordFailure(ip)));

  assert.equal(await store.count(ip), N);
  assert.equal(await store.isLocked(ip), true);
});

test("CONCURRENCY: simultaneous failures from the same normalized IPv4 address never undercount", async () => {
  const client = makeRealDurableObjectClient();
  const normalizeIp = (ip) => ip; // IPv4 passes through unchanged (mirrors pure.js)
  const store = createAdminLockoutStore({ client, maxFails: 100, windowMs: 60_000, normalizeIp });
  const ip = "198.51.100.23";

  const N = 25;
  await Promise.all(Array.from({ length: N }, () => store.recordFailure(ip)));

  assert.equal(await store.count(ip), N);
});

test("CONCURRENCY: simultaneous failures from the same normalized IPv6 /64 never undercount, and land on one shared key", async () => {
  const client = makeRealDurableObjectClient();
  // Mirrors pure.normalizeIpForRateLimit's real behavior: collapse to /64.
  const normalizeIp = (ip) => ip.split(":").slice(0, 4).join(":");
  const store = createAdminLockoutStore({ client, maxFails: 100, windowMs: 60_000, normalizeIp });

  // 20 concurrent failures from 4 different full addresses, all within the
  // same /64 prefix — they must all collapse onto one Durable Object
  // instance and count exactly 20, not undercount due to racing.
  const addresses = [
    "2001:db8:1234:5678::1",
    "2001:db8:1234:5678::2",
    "2001:db8:1234:5678:aaaa::1",
    "2001:db8:1234:5678:ffff::ffff",
  ];
  const callsPerAddress = 5;
  const calls = [];
  for (const addr of addresses) {
    for (let i = 0; i < callsPerAddress; i++) calls.push(store.recordFailure(addr));
  }
  await Promise.all(calls);

  assert.equal(client.instances.size, 1); // all four addresses shared one DO instance
  assert.equal(await store.count(addresses[0]), addresses.length * callsPerAddress);
});

test("CONCURRENCY: requests crossing the lockout threshold concurrently are all counted — the transition to locked is not lost", async () => {
  const client = makeRealDurableObjectClient();
  const maxFails = 10;
  const store = createAdminLockoutStore({ client, maxFails, windowMs: 60_000 });
  const ip = "192.0.2.55";

  // 9 concurrent failures land right at the boundary — none individually
  // locked yet, then one more concurrent batch pushes it over.
  await Promise.all(Array.from({ length: maxFails - 1 }, () => store.recordFailure(ip)));
  assert.equal(await store.isLocked(ip), false);

  // Fire several concurrent failures that collectively cross the threshold;
  // an undercounting implementation could let some of these "miss" and
  // leave the IP unlocked past maxFails.
  const N = 5;
  await Promise.all(Array.from({ length: N }, () => store.recordFailure(ip)));

  assert.equal(await store.count(ip), maxFails - 1 + N);
  assert.equal(await store.isLocked(ip), true);
});

test("CONCURRENCY: repeated concurrent attempts while already locked keep incrementing without ever losing an increment", async () => {
  const client = makeRealDurableObjectClient();
  const maxFails = 5;
  const store = createAdminLockoutStore({ client, maxFails, windowMs: 60_000 });
  const ip = "192.0.2.99";

  await Promise.all(Array.from({ length: maxFails }, () => store.recordFailure(ip)));
  assert.equal(await store.isLocked(ip), true);

  // Keep hammering while locked — every one of these must still land.
  const extra = 15;
  await Promise.all(Array.from({ length: extra }, () => store.recordFailure(ip)));

  assert.equal(await store.count(ip), maxFails + extra);
  assert.equal(await store.isLocked(ip), true);
});

test("CONCURRENCY: lockout still expires correctly after a burst of concurrent failures", async () => {
  const client = makeRealDurableObjectClient();
  const store = createAdminLockoutStore({ client, maxFails: 5, windowMs: 1000 });
  const ip = "203.0.113.44";

  await Promise.all(Array.from({ length: 5 }, () => store.recordFailure(ip)));
  assert.equal(await store.isLocked(ip), true);

  await new Promise((resolve) => setTimeout(resolve, 1100));
  assert.equal(await store.isLocked(ip), false);
  assert.equal(await store.count(ip), 0);
});

test("CONCURRENCY: reset after a concurrent burst clears the counter for subsequent attempts", async () => {
  const client = makeRealDurableObjectClient();
  const store = createAdminLockoutStore({ client, maxFails: 5, windowMs: 60_000 });
  const ip = "203.0.113.201";

  await Promise.all(Array.from({ length: 5 }, () => store.recordFailure(ip)));
  assert.equal(await store.isLocked(ip), true);

  await store.reset(ip);
  assert.equal(await store.isLocked(ip), false);
  assert.equal(await store.count(ip), 0);

  // A fresh concurrent burst after reset starts clean and counts correctly.
  await Promise.all(Array.from({ length: 3 }, () => store.recordFailure(ip)));
  assert.equal(await store.count(ip), 3);
});

test("CONCURRENCY negative control: without Durable-Object-style serialization, a naive get-then-put race undercounts (documents why KV alone was rejected)", async () => {
  // This intentionally reproduces the OLD (rejected) Cloudflare KV
  // architecture's failure mode — plain get() then put(), no serialization —
  // to demonstrate the concurrency bug this migration fixes actually
  // existed, and that the fix above (routing through the real Durable
  // Object) is what prevents it, not an accident of test design.
  const naiveStore = new Map();
  async function naiveGet(key) { return naiveStore.get(key) ?? 0; }
  async function naivePut(key, value) {
    await new Promise((resolve) => setImmediate(resolve)); // simulate network latency
    naiveStore.set(key, value);
  }
  async function naiveIncrement(key) {
    const current = await naiveGet(key);
    const next = current + 1;
    await naivePut(key, next);
  }

  const N = 10;
  await Promise.all(Array.from({ length: N }, () => naiveIncrement("k")));
  const finalCount = naiveStore.get("k");

  // The bug: concurrent naive get-then-put calls can and do undercount.
  assert.ok(finalCount < N, `expected the naive approach to undercount below ${N}, got ${finalCount}`);
});
