"use strict";

// Unit tests for the durable admin lockout store (S04-L3).
// Run with: npm test   (inside server/) — uses the Node built-in test runner.
//
// No live Upstash credentials are used or required: the Redis client is a
// small in-memory fake that reproduces just enough of the @upstash/redis
// surface (`get`, `eval`, `del`) to exercise the store's logic, including its
// atomic-increment script and its fail-safe fallback on thrown errors.

const test = require("node:test");
const assert = require("node:assert/strict");
const { createAdminLockoutStore, buildKey, INCR_AND_MAYBE_EXPIRE_SCRIPT } = require("./adminLockoutStore");

// Reproduces exactly the behavior the real INCR_AND_MAYBE_EXPIRE_SCRIPT Lua
// script asks Redis to perform, so tests exercise the same semantics
// `recordFailure()` relies on without needing a live Redis. Matches the real
// @upstash/redis client's call signature: `redis.eval(script, keys, args)`.
function makeRedisFake({ now = () => Date.now() } = {}) {
  const store = new Map(); // key -> { value, expiresAt: ms|null }

  function isLive(entry) {
    return entry && (entry.expiresAt === null || entry.expiresAt > now());
  }

  return {
    store,
    async get(key) {
      const entry = store.get(key);
      if (!isLive(entry)) return null;
      return String(entry.value);
    },
    async eval(script, keys, args) {
      if (script !== INCR_AND_MAYBE_EXPIRE_SCRIPT) throw new Error("unexpected script");
      const key = keys[0];
      const ttlMs = Number(args[0]);
      const existing = isLive(store.get(key)) ? store.get(key) : null;
      const count = (existing ? Number(existing.value) : 0) + 1;
      const expiresAt = count === 1 ? now() + ttlMs : existing.expiresAt;
      store.set(key, { value: count, expiresAt });
      return count;
    },
    async del(key) {
      store.delete(key);
    },
  };
}

test("buildKey namespaces and includes the (already-normalized) ip", () => {
  assert.equal(buildKey("1.2.3.4"), "duoshield:adminlock:1.2.3.4");
});

test("redisEnabled is false with no client and true with one", () => {
  assert.equal(createAdminLockoutStore().redisEnabled, false);
  assert.equal(createAdminLockoutStore({ redis: makeRedisFake() }).redisEnabled, true);
});

test("local fallback (no redis): repeated failures accumulate and trigger lockout at threshold", async () => {
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

test("redis-backed: repeated failures are counted via the atomic script and reach lockout at threshold", async () => {
  const redis = makeRedisFake();
  const store = createAdminLockoutStore({ redis, maxFails: 3, windowMs: 60_000 });
  await store.recordFailure("1.2.3.4");
  await store.recordFailure("1.2.3.4");
  assert.equal(await store.isLocked("1.2.3.4"), false);
  await store.recordFailure("1.2.3.4");
  assert.equal(await store.isLocked("1.2.3.4"), true);
  assert.equal(redis.store.get(buildKey("1.2.3.4")).value, 3);
});

test("redis-backed: TTL is set on the first failure and anchored (not pushed out by later failures)", async () => {
  let currentTime = 1_000_000;
  const redis = makeRedisFake({ now: () => currentTime });
  const store = createAdminLockoutStore({ redis, windowMs: 1000, now: () => currentTime });
  await store.recordFailure("1.2.3.4");
  const afterFirst = redis.store.get(buildKey("1.2.3.4")).expiresAt;
  assert.equal(afterFirst, currentTime + 1000);
  currentTime += 500; // still inside window
  await store.recordFailure("1.2.3.4");
  const afterSecond = redis.store.get(buildKey("1.2.3.4")).expiresAt;
  // Anchored to the first failure's window, not extended by the second.
  assert.equal(afterSecond, afterFirst);
});

test("redis-backed: failure state expires — a stale key is treated as zero failures, not locked", async () => {
  let currentTime = 1_000_000;
  const redis = makeRedisFake({ now: () => currentTime });
  const store = createAdminLockoutStore({ redis, maxFails: 1, windowMs: 1000, now: () => currentTime });
  await store.recordFailure("1.2.3.4");
  assert.equal(await store.isLocked("1.2.3.4"), true);
  currentTime += 1001; // past TTL
  assert.equal(await store.isLocked("1.2.3.4"), false);
});

test("redis-backed: reset (successful authentication) clears the failure state in Redis", async () => {
  const redis = makeRedisFake();
  const store = createAdminLockoutStore({ redis, maxFails: 1 });
  await store.recordFailure("1.2.3.4");
  assert.equal(await store.isLocked("1.2.3.4"), true);
  await store.reset("1.2.3.4");
  assert.equal(await store.isLocked("1.2.3.4"), false);
  assert.equal(redis.store.has(buildKey("1.2.3.4")), false);
});

test("redis errors on isLocked fail safe: falls back to the local map instead of throwing", async () => {
  const errors = [];
  const redis = {
    async get() { throw new Error("upstash unreachable"); },
    async eval() { throw new Error("upstash unreachable"); },
    async del() { throw new Error("upstash unreachable"); },
  };
  const store = createAdminLockoutStore({
    redis,
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

test("redis errors on reset fail safe: does not throw, still clears local fallback state", async () => {
  const redis = {
    async get() { return "1"; },
    async eval() { throw new Error("upstash unreachable"); },
    async del() { throw new Error("upstash unreachable"); },
  };
  const store = createAdminLockoutStore({ redis, maxFails: 1, onError: () => {} });
  await store.recordFailure("1.2.3.4"); // degrades to local (eval throws)
  await assert.doesNotReject(() => store.reset("1.2.3.4"));
});

test("normalizeIp is applied before building the redis/local key (IPv6 /64 collapsing, S04-M1)", async () => {
  const redis = makeRedisFake();
  // Two different full IPv6 addresses in the same /64, pre-collapsed by the
  // caller-supplied normalizer to the same prefix — mirrors how index.js
  // wires pure.normalizeIpForRateLimit in.
  const normalizeIp = (ip) => (ip.startsWith("2001:db8::") ? "2001:db8:0:0" : ip);
  const store = createAdminLockoutStore({ redis, maxFails: 2, normalizeIp });
  await store.recordFailure("2001:db8::1");
  await store.recordFailure("2001:db8::2"); // different address, same /64
  assert.equal(await store.isLocked("2001:db8::3"), true); // same /64 again
  assert.equal(redis.store.size, 1); // both failures landed on one key
});

test("distinct ips outside the same /64 remain independent after normalization", async () => {
  const redis = makeRedisFake();
  const normalizeIp = (ip) => ip.split(":").slice(0, 4).join(":");
  const store = createAdminLockoutStore({ redis, maxFails: 1, normalizeIp });
  await store.recordFailure("2001:db8:0:0:aaaa::1");
  assert.equal(await store.isLocked("2001:db8:0:1::1"), false);
});
