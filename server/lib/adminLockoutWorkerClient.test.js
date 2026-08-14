"use strict";

// Unit tests for the admin lockout Worker HTTP client (server/lib/adminLockoutWorkerClient.js).
// Run with: npm test   (inside server/) — uses the Node built-in test runner.
//
// No live Worker/Cloudflare deployment is used or required: `fetchImpl` is
// injected as a fake matching the global `fetch` signature, so these tests
// exercise only this module's request construction, timeout handling, and
// response/error parsing — not the Durable Object's actual logic (that's
// covered in worker/src/adminLockoutDurableObject.test.js and, in-process,
// by the CONCURRENCY tests in adminLockoutStore.test.js).

const test = require("node:test");
const assert = require("node:assert/strict");
const { createAdminLockoutWorkerClient, AdminLockoutWorkerError } = require("./adminLockoutWorkerClient");

function jsonResponse(body, { status = 200 } = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

test("createAdminLockoutWorkerClient requires workerUrl and workerSecret", () => {
  assert.throws(() => createAdminLockoutWorkerClient({ workerUrl: "https://x.workers.dev" }));
  assert.throws(() => createAdminLockoutWorkerClient({ workerSecret: "s" }));
  assert.doesNotThrow(() => createAdminLockoutWorkerClient({ workerUrl: "https://x.workers.dev", workerSecret: "s" }));
});

test("recordFailure POSTs to <workerUrl>/adminLockout with the Bearer secret and JSON body", async () => {
  let capturedUrl, capturedInit;
  const fetchImpl = async (url, init) => {
    capturedUrl = url;
    capturedInit = init;
    return jsonResponse({ count: 1, locked: false });
  };
  const client = createAdminLockoutWorkerClient({
    workerUrl: "https://duoshield-storage.example.workers.dev",
    workerSecret: "top-secret-value",
    fetchImpl,
  });
  await client.recordFailure("duoshield:adminlock:1.2.3.4", { windowMs: 900000, maxFails: 10 });

  assert.equal(capturedUrl, "https://duoshield-storage.example.workers.dev/adminLockout");
  assert.equal(capturedInit.method, "POST");
  assert.equal(capturedInit.headers.Authorization, "Bearer top-secret-value");
  assert.equal(capturedInit.headers["Content-Type"], "application/json");
  const body = JSON.parse(capturedInit.body);
  assert.equal(body.action, "record");
  assert.equal(body.key, "duoshield:adminlock:1.2.3.4");
  assert.equal(body.windowMs, 900000);
  assert.equal(body.maxFails, 10);
});

test("a trailing slash on workerUrl does not produce a double slash in the endpoint", async () => {
  let capturedUrl;
  const fetchImpl = async (url) => {
    capturedUrl = url;
    return jsonResponse({ count: 0, locked: false });
  };
  const client = createAdminLockoutWorkerClient({
    workerUrl: "https://x.workers.dev/",
    workerSecret: "s",
    fetchImpl,
  });
  await client.getStatus("k", {});
  assert.equal(capturedUrl, "https://x.workers.dev/adminLockout");
});

test("getStatus resolves { count, locked } from a well-formed response", async () => {
  const fetchImpl = async () => jsonResponse({ count: 4, locked: false });
  const client = createAdminLockoutWorkerClient({ workerUrl: "https://x.workers.dev", workerSecret: "s", fetchImpl });
  const result = await client.getStatus("k", { windowMs: 1000, maxFails: 10 });
  assert.deepEqual(result, { count: 4, locked: false });
});

test("reset resolves without a return value on a well-formed 2xx response", async () => {
  const fetchImpl = async () => jsonResponse({ ok: true });
  const client = createAdminLockoutWorkerClient({ workerUrl: "https://x.workers.dev", workerSecret: "s", fetchImpl });
  await assert.doesNotReject(() => client.reset("k"));
});

test("a non-2xx response throws AdminLockoutWorkerError carrying the status", async () => {
  const fetchImpl = async () => jsonResponse({ error: "Unauthorized" }, { status: 401 });
  const client = createAdminLockoutWorkerClient({ workerUrl: "https://x.workers.dev", workerSecret: "wrong", fetchImpl });
  await assert.rejects(
    () => client.recordFailure("k", {}),
    (err) => {
      assert.ok(err instanceof AdminLockoutWorkerError);
      assert.equal(err.status, 401);
      assert.match(err.message, /Unauthorized/);
      return true;
    }
  );
});

test("a non-2xx response with a plain-text (non-JSON) body still produces a safe, truncated error message", async () => {
  const fetchImpl = async () => new Response("Internal Server Error", { status: 500 });
  const client = createAdminLockoutWorkerClient({ workerUrl: "https://x.workers.dev", workerSecret: "s", fetchImpl });
  await assert.rejects(
    () => client.recordFailure("k", {}),
    (err) => {
      assert.equal(err.status, 500);
      assert.match(err.message, /Internal Server Error/);
      return true;
    }
  );
});

test("a very long non-JSON error body is truncated rather than fully echoed", async () => {
  const longBody = "x".repeat(5000);
  const fetchImpl = async () => new Response(longBody, { status: 502 });
  const client = createAdminLockoutWorkerClient({ workerUrl: "https://x.workers.dev", workerSecret: "s", fetchImpl });
  await assert.rejects(
    () => client.recordFailure("k", {}),
    (err) => {
      assert.ok(err.message.length < 500, `expected a truncated message, got ${err.message.length} chars`);
      return true;
    }
  );
});

test("a malformed (non-JSON) 2xx body throws AdminLockoutWorkerError rather than crashing", async () => {
  const fetchImpl = async () => new Response("not json{{{", { status: 200 });
  const client = createAdminLockoutWorkerClient({ workerUrl: "https://x.workers.dev", workerSecret: "s", fetchImpl });
  await assert.rejects(() => client.getStatus("k", {}), AdminLockoutWorkerError);
});

test("a 2xx response with valid JSON but missing count/locked fields throws rather than returning undefined-shaped data", async () => {
  const fetchImpl = async () => jsonResponse({ ok: true }); // no count/locked
  const client = createAdminLockoutWorkerClient({ workerUrl: "https://x.workers.dev", workerSecret: "s", fetchImpl });
  await assert.rejects(() => client.recordFailure("k", {}), AdminLockoutWorkerError);
});

test("a 2xx response with count/locked of the wrong type throws rather than propagating bad data", async () => {
  const fetchImpl = async () => jsonResponse({ count: "3", locked: "false" }); // wrong types
  const client = createAdminLockoutWorkerClient({ workerUrl: "https://x.workers.dev", workerSecret: "s", fetchImpl });
  await assert.rejects(() => client.getStatus("k", {}), AdminLockoutWorkerError);
});

test("a network-level fetch rejection is wrapped in AdminLockoutWorkerError, not propagated raw", async () => {
  const fetchImpl = async () => { throw new Error("getaddrinfo ENOTFOUND"); };
  const client = createAdminLockoutWorkerClient({ workerUrl: "https://x.workers.dev", workerSecret: "s", fetchImpl });
  await assert.rejects(
    () => client.recordFailure("k", {}),
    (err) => {
      assert.ok(err instanceof AdminLockoutWorkerError);
      assert.match(err.message, /ENOTFOUND/);
      return true;
    }
  );
});

test("a request that exceeds timeoutMs is aborted and throws a timeout-specific AdminLockoutWorkerError", async () => {
  const fetchImpl = (url, { signal }) =>
    new Promise((resolve, reject) => {
      signal.addEventListener("abort", () => {
        const err = new Error("aborted");
        err.name = "AbortError";
        reject(err);
      });
      // Never resolves on its own within the test's timeout window.
    });
  const client = createAdminLockoutWorkerClient({
    workerUrl: "https://x.workers.dev",
    workerSecret: "s",
    timeoutMs: 20,
    fetchImpl,
  });
  await assert.rejects(
    () => client.recordFailure("k", {}),
    (err) => {
      assert.ok(err instanceof AdminLockoutWorkerError);
      assert.match(err.message, /timed out/);
      return true;
    }
  );
});

test("the workerSecret never appears in a thrown error's message", async () => {
  const secret = "sk_live_super_secret_do_not_leak_12345";
  const fetchImpl = async () => jsonResponse({ error: "Unauthorized" }, { status: 401 });
  const client = createAdminLockoutWorkerClient({ workerUrl: "https://x.workers.dev", workerSecret: secret, fetchImpl });
  try {
    await client.recordFailure("k", {});
    assert.fail("expected recordFailure to throw");
  } catch (err) {
    assert.doesNotMatch(err.message, new RegExp(secret));
    assert.doesNotMatch(JSON.stringify(err), new RegExp(secret));
  }
});

test("the workerSecret never appears in a thrown error's message even when the fetch itself throws", async () => {
  const secret = "sk_live_another_secret_67890";
  const fetchImpl = async () => { throw new Error("connection refused"); };
  const client = createAdminLockoutWorkerClient({ workerUrl: "https://x.workers.dev", workerSecret: secret, fetchImpl });
  try {
    await client.getStatus("k", {});
    assert.fail("expected getStatus to throw");
  } catch (err) {
    assert.doesNotMatch(err.message, new RegExp(secret));
  }
});
