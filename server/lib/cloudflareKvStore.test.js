"use strict";

// Unit tests for the Cloudflare Workers KV REST client. Run with:
//   npm test   (inside server/) — uses the Node built-in test runner.
//
// No live Cloudflare credentials are used or required: `fetch` is injected
// as a fake that asserts on the request it was given and returns a
// scripted Response.

const test = require("node:test");
const assert = require("node:assert/strict");
const { createCloudflareKvClient, CloudflareKvError } = require("./cloudflareKvStore");

const ACCOUNT_ID = "acct123";
const API_TOKEN = "super-secret-token-do-not-log";
const NAMESPACE_ID = "ns456";

function makeClient({ fetchImpl, timeoutMs } = {}) {
  return createCloudflareKvClient({
    accountId: ACCOUNT_ID,
    apiToken: API_TOKEN,
    namespaceId: NAMESPACE_ID,
    timeoutMs,
    fetchImpl,
  });
}

test("createCloudflareKvClient requires accountId, apiToken, and namespaceId", () => {
  assert.throws(() => createCloudflareKvClient({ apiToken: "x", namespaceId: "y" }));
  assert.throws(() => createCloudflareKvClient({ accountId: "x", namespaceId: "y" }));
  assert.throws(() => createCloudflareKvClient({ accountId: "x", apiToken: "y" }));
});

test("get() sends the correct URL, method, and Authorization header", async () => {
  let capturedUrl;
  let capturedInit;
  const fetchImpl = async (url, init) => {
    capturedUrl = url;
    capturedInit = init;
    return new Response("hello", { status: 200 });
  };
  const client = makeClient({ fetchImpl });
  const value = await client.get("my-key");
  assert.equal(value, "hello");
  assert.equal(capturedInit.method, "GET");
  assert.equal(capturedInit.headers.Authorization, `Bearer ${API_TOKEN}`);
  assert.match(
    String(capturedUrl),
    new RegExp(`^https://api\\.cloudflare\\.com/client/v4/accounts/${ACCOUNT_ID}/storage/kv/namespaces/${NAMESPACE_ID}/values/my-key$`)
  );
});

test("get() returns null for a 404 (key does not exist) without throwing", async () => {
  const fetchImpl = async () => new Response("not found", { status: 404 });
  const client = makeClient({ fetchImpl });
  assert.equal(await client.get("missing-key"), null);
});

test("get() throws CloudflareKvError distinguishing API failure from a missing key", async () => {
  const fetchImpl = async () =>
    new Response(JSON.stringify({ success: false, errors: [{ code: 10000, message: "Authentication error" }] }), {
      status: 403,
    });
  const client = makeClient({ fetchImpl });
  await assert.rejects(() => client.get("some-key"), (err) => {
    assert.ok(err instanceof CloudflareKvError);
    assert.equal(err.status, 403);
    assert.match(err.message, /Authentication error/);
    return true;
  });
});

test("get() handles a malformed (non-JSON) error body without throwing an unrelated error", async () => {
  const fetchImpl = async () => new Response("<html>Bad Gateway</html>", { status: 502 });
  const client = makeClient({ fetchImpl });
  await assert.rejects(() => client.get("some-key"), (err) => {
    assert.ok(err instanceof CloudflareKvError);
    assert.equal(err.status, 502);
    return true;
  });
});

test("put() sends the value as the body and clamps expirationTtl up to the 60s floor", async () => {
  let capturedUrl;
  let capturedBody;
  const fetchImpl = async (url, init) => {
    capturedUrl = url;
    capturedBody = init.body;
    return new Response(JSON.stringify({ success: true }), { status: 200 });
  };
  const client = makeClient({ fetchImpl });
  await client.put("my-key", "the-value", { expirationTtl: 5 }); // below the 60s floor
  assert.equal(capturedBody, "the-value");
  const url = new URL(String(capturedUrl));
  assert.equal(url.searchParams.get("expiration_ttl"), "60");
});

test("put() does not clamp expirationTtl that is already >= 60s", async () => {
  let capturedUrl;
  const fetchImpl = async (url) => {
    capturedUrl = url;
    return new Response(JSON.stringify({ success: true }), { status: 200 });
  };
  const client = makeClient({ fetchImpl });
  await client.put("my-key", "v", { expirationTtl: 900 });
  const url = new URL(String(capturedUrl));
  assert.equal(url.searchParams.get("expiration_ttl"), "900");
});

test("put() throws CloudflareKvError on a non-2xx status", async () => {
  const fetchImpl = async () =>
    new Response(JSON.stringify({ success: false, errors: [{ message: "Namespace not found" }] }), { status: 404 });
  const client = makeClient({ fetchImpl });
  await assert.rejects(() => client.put("k", "v", { expirationTtl: 60 }), CloudflareKvError);
});

test("put() throws CloudflareKvError on a 200 response with success:false (Cloudflare's own soft-failure shape)", async () => {
  const fetchImpl = async () => new Response(JSON.stringify({ success: false, errors: [{ message: "quota exceeded" }] }), { status: 200 });
  const client = makeClient({ fetchImpl });
  await assert.rejects(() => client.put("k", "v", { expirationTtl: 60 }), (err) => {
    assert.ok(err instanceof CloudflareKvError);
    assert.match(err.message, /quota exceeded/);
    return true;
  });
});

test("put() throws CloudflareKvError on a 200 response with a malformed (non-JSON) body", async () => {
  const fetchImpl = async () => new Response("not json at all", { status: 200 });
  const client = makeClient({ fetchImpl });
  await assert.rejects(() => client.put("k", "v", { expirationTtl: 60 }), CloudflareKvError);
});

test("delete() treats a 404 as success (idempotent, matches Redis DEL semantics)", async () => {
  const fetchImpl = async () => new Response("not found", { status: 404 });
  const client = makeClient({ fetchImpl });
  await assert.doesNotReject(() => client.delete("missing-key"));
});

test("delete() succeeds on a 200 with success:true", async () => {
  const fetchImpl = async () => new Response(JSON.stringify({ success: true }), { status: 200 });
  const client = makeClient({ fetchImpl });
  await assert.doesNotReject(() => client.delete("k"));
});

test("delete() throws CloudflareKvError on a non-2xx, non-404 status", async () => {
  const fetchImpl = async () => new Response(JSON.stringify({ success: false, errors: [] }), { status: 500 });
  const client = makeClient({ fetchImpl });
  await assert.rejects(() => client.delete("k"), CloudflareKvError);
});

test("network failure (fetch rejects) surfaces as CloudflareKvError, not a raw TypeError", async () => {
  const fetchImpl = async () => {
    throw new TypeError("fetch failed");
  };
  const client = makeClient({ fetchImpl });
  await assert.rejects(() => client.get("k"), (err) => {
    assert.ok(err instanceof CloudflareKvError);
    assert.match(err.message, /fetch failed/);
    return true;
  });
});

test("a request that never resolves is aborted at timeoutMs and surfaces as a CloudflareKvError", async () => {
  const fetchImpl = (url, init) =>
    new Promise((_resolve, reject) => {
      init.signal.addEventListener("abort", () => {
        const err = new Error("This operation was aborted");
        err.name = "AbortError";
        reject(err);
      });
    });
  const client = makeClient({ fetchImpl, timeoutMs: 20 });
  await assert.rejects(() => client.get("k"), (err) => {
    assert.ok(err instanceof CloudflareKvError);
    assert.match(err.message, /timed out/);
    return true;
  });
});

test("error messages never contain the API token", async () => {
  const fetchImpl = async () =>
    new Response(JSON.stringify({ success: false, errors: [{ message: "Authentication error" }] }), { status: 403 });
  const client = makeClient({ fetchImpl });
  try {
    await client.get("k");
    assert.fail("expected get() to throw");
  } catch (err) {
    assert.doesNotMatch(err.message, new RegExp(API_TOKEN));
    assert.doesNotMatch(JSON.stringify(err), new RegExp(API_TOKEN));
  }
});

test("get() truncates an oversized/unexpected error body rather than surfacing it in full", async () => {
  const hugeBody = "x".repeat(5000);
  const fetchImpl = async () => new Response(hugeBody, { status: 500 });
  const client = makeClient({ fetchImpl });
  await assert.rejects(() => client.get("k"), (err) => {
    assert.ok(err.message.length < 500);
    return true;
  });
});
