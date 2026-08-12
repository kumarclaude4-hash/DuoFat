// Regression tests for S3-11 (S03-H3, S03-L4, S08-I3).
//
// These are structural/behavioral tests against the actual exported handler
// and helper functions in src/index.js — run with plain `node --test`, no
// wrangler/Miniflare required. R2/KV/B2 are stubbed with minimal in-memory
// fakes; aws4fetch's AwsClient.fetch is not stubbed because these tests never
// exercise a cold-tier (B2) path.

import test from 'node:test';
import assert from 'node:assert/strict';
import { timingSafeEqual as nodeTimingSafeEqual } from 'node:crypto';
import worker from './index.js';

// `crypto.subtle.timingSafeEqual` is a Cloudflare Workers-runtime extension to
// the Web Crypto API — it exists in the real Workers runtime that src/index.js
// actually deploys to, but plain Node's `crypto.subtle` does not implement it
// (confirmed on Node 24). This is a test-environment gap, not a bug in the
// implementation, and not a security weakness to paper over: the shim below
// delegates to Node's own constant-time `crypto.timingSafeEqual` (the same
// primitive the server side of this repo already uses for its own
// timing-safe comparisons — see server/lib/pure.js) so the comparison under
// test is still genuinely constant-time, just running under Node instead of
// workerd.
if (typeof globalThis.crypto?.subtle?.timingSafeEqual !== 'function') {
  globalThis.crypto.subtle.timingSafeEqual = async (a, b) => {
    const bufA = Buffer.from(a);
    const bufB = Buffer.from(b);
    if (bufA.length !== bufB.length) return false;
    return nodeTimingSafeEqual(bufA, bufB);
  };
}

const MEDIA_TOKEN_SECRET = 'test-media-token-secret';

async function hmacSha256(secret, message) {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    'raw', enc.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'],
  );
  return new Uint8Array(await crypto.subtle.sign('HMAC', key, enc.encode(message)));
}

function bytesToB64url(bytes) {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function mintToken(op, key, holder, ttlMs = 60_000) {
  const expiresAt = Date.now() + ttlMs;
  const payload    = `v1|${op}|${expiresAt}|${holder}|${key}`;
  const sig         = await hmacSha256(MEDIA_TOKEN_SECRET, payload);
  return `v1.${op}.${expiresAt}.${holder}.${bytesToB64url(sig)}`;
}

// ─── Minimal fakes ─────────────────────────────────────────────────────────
function makeKv() {
  const store = new Map();
  return {
    store,
    async get(key) { return store.has(key) ? store.get(key) : null; },
    async put(key, value) { store.set(key, String(value)); },
  };
}

function makeHotBucket() {
  const objects = new Map(); // key -> { body, size, customMetadata, httpMetadata, httpEtag }
  return {
    objects,
    async put(key, body, opts) {
      const buf = body instanceof ArrayBuffer ? body : await new Response(body).arrayBuffer();
      objects.set(key, {
        size: buf.byteLength,
        customMetadata: opts?.customMetadata ?? {},
        httpMetadata: opts?.httpMetadata ?? {},
        httpEtag: `"etag-${key}"`,
      });
    },
    async head(key) {
      const o = objects.get(key);
      if (!o) return null;
      return { size: o.size, customMetadata: o.customMetadata, httpMetadata: o.httpMetadata, httpEtag: o.httpEtag };
    },
    async get(key) {
      const o = objects.get(key);
      if (!o) return null;
      return { ...o, body: new Uint8Array(o.size), arrayBuffer: async () => new ArrayBuffer(o.size) };
    },
    async delete(key) { objects.delete(key); },
  };
}

function makeEnv(overrides = {}) {
  return {
    MEDIA_TOKEN_SECRET,
    RATE_KV: makeKv(),
    HOT_BUCKET: makeHotBucket(),
    MAX_FILE_SIZE: '524288000',
    // getB2Client() constructs an AwsClient unconditionally on every DELETE
    // that finds the object still in R2 (the migration race-guard fires a
    // best-effort alongside B2 delete — see index.js). aws4fetch's AwsClient
    // constructor throws synchronously if accessKeyId/secretAccessKey are
    // missing, so these dummy values are required for R2-path DELETE tests
    // to even reach the fetch() call, whose network failure is already
    // swallowed by the `.catch(() => {})` in the implementation. Real
    // deployments always set these via `wrangler secret put` (see README).
    B2_ACCESS_KEY_ID: 'test-b2-key-id',
    B2_SECRET_ACCESS_KEY: 'test-b2-secret',
    B2_REGION: 'eu-central-003',
    B2_ENDPOINT: 'https://b2.invalid.example',
    B2_BUCKET: 'test-bucket',
    ...overrides,
  };
}

const ctx = { waitUntil: async (p) => { await p; } };

// ─── S03-L4: rejections must carry CORS headers ───────────────────────────
test('S03-L4: a 400 (invalid key format) rejection carries the CORS headers, not just success responses', async () => {
  const env = makeEnv({ CORS_ALLOWED_ORIGINS: 'https://app.example.com' });
  const req = new Request('https://worker.example/bad key with spaces', {
    method: 'GET',
    headers: { Origin: 'https://app.example.com' },
  });
  const res = await worker.fetch(req, env, ctx);
  assert.equal(res.status, 400);
  assert.equal(
    res.headers.get('Access-Control-Allow-Origin'),
    'https://app.example.com',
    'a rejection response must carry the same CORS headers as a success response, or a browser client cannot even read the error body'
  );
});

test('S03-L4: a 401 (missing capability token) rejection also carries CORS headers', async () => {
  const env = makeEnv({ CORS_ALLOWED_ORIGINS: 'https://app.example.com' });
  const req = new Request(`https://worker.example/media/${'a'.repeat(20)}/file.jpg`, {
    method: 'GET',
    headers: { Origin: 'https://app.example.com' },
  });
  const res = await worker.fetch(req, env, ctx);
  assert.equal(res.status, 401);
  assert.equal(res.headers.get('Access-Control-Allow-Origin'), 'https://app.example.com');
});

// ─── S08-I3: no wildcard ACAO; only explicit allow-listed origins ─────────
test('S08-I3: Access-Control-Allow-Origin is never "*"', async () => {
  const env = makeEnv({ CORS_ALLOWED_ORIGINS: 'https://app.example.com' });
  const req = new Request('https://worker.example/health', {
    headers: { Origin: 'https://evil.example.com' },
  });
  const res = await worker.fetch(req, env, ctx);
  assert.notEqual(res.headers.get('Access-Control-Allow-Origin'), '*');
  assert.equal(
    res.headers.get('Access-Control-Allow-Origin'),
    null,
    'an origin not on the allow list must not be reflected back'
  );
});

test('S08-I3: an allow-listed Origin is reflected back with Vary: Origin, never "*"', async () => {
  const env = makeEnv({ CORS_ALLOWED_ORIGINS: 'https://app.example.com,https://other.example.com' });
  const req = new Request('https://worker.example/health', {
    headers: { Origin: 'https://other.example.com' },
  });
  const res = await worker.fetch(req, env, ctx);
  assert.equal(res.headers.get('Access-Control-Allow-Origin'), 'https://other.example.com');
  assert.equal(res.headers.get('Vary'), 'Origin');
});

test('S08-I3: with no CORS_ALLOWED_ORIGINS configured, ACAO is omitted entirely (not defaulted to "*")', async () => {
  const env = makeEnv(); // no CORS_ALLOWED_ORIGINS
  const req = new Request('https://worker.example/health', {
    headers: { Origin: 'https://anything.example.com' },
  });
  const res = await worker.fetch(req, env, ctx);
  assert.equal(res.headers.get('Access-Control-Allow-Origin'), null);
});

// ─── S03-H3: per-user storage quota ────────────────────────────────────────
const CHAT_ID = 'a'.repeat(20); // satisfies KEY_FORMAT's 16-80 char id segment

test('S03-H3: a single holder is rejected with 507 once their per-user quota is exhausted, even though the global R2 cap is nowhere close', async () => {
  const env = makeEnv({ MAX_USER_BYTES: '1000' }); // tiny quota to make the test fast
  const holder = 'user-abc';

  // First upload of 600 bytes succeeds (under the 1000-byte quota).
  const key1 = `media/${CHAT_ID}/one.jpg`;
  const token1 = await mintToken('write', key1, holder);
  const body1 = new Uint8Array(600);
  const put1 = await worker.fetch(new Request(`https://worker.example/${key1}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token1}`, 'Content-Length': '600' },
    body: body1,
  }), env, ctx);
  assert.equal(put1.status, 200, await put1.clone().text());

  // Second upload of 600 more bytes from the SAME holder must be rejected —
  // 600 + 600 > 1000 — even though MAX_R2_BYTES (9.5 GB) is untouched.
  const key2 = `media/${CHAT_ID}/two.jpg`;
  const token2 = await mintToken('write', key2, holder);
  const body2 = new Uint8Array(600);
  const put2 = await worker.fetch(new Request(`https://worker.example/${key2}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token2}`, 'Content-Length': '600' },
    body: body2,
  }), env, ctx);
  assert.equal(put2.status, 507, await put2.clone().text());
  const payload = await put2.json();
  assert.match(payload.error, /per-user storage quota/i);
});

test('S03-H3: a different holder is unaffected by another holder\'s exhausted quota', async () => {
  const env = makeEnv({ MAX_USER_BYTES: '1000' });

  const keyA = `media/${CHAT_ID}/a.jpg`;
  const tokenA = await mintToken('write', keyA, 'user-a');
  const putA = await worker.fetch(new Request(`https://worker.example/${keyA}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${tokenA}`, 'Content-Length': '900' },
    body: new Uint8Array(900),
  }), env, ctx);
  assert.equal(putA.status, 200, await putA.clone().text());

  const keyB = `media/${CHAT_ID}/b.jpg`;
  const tokenB = await mintToken('write', keyB, 'user-b');
  const putB = await worker.fetch(new Request(`https://worker.example/${keyB}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${tokenB}`, 'Content-Length': '900' },
    body: new Uint8Array(900),
  }), env, ctx);
  assert.equal(putB.status, 200, 'user-b has their own independent 1000-byte quota, unaffected by user-a nearly exhausting theirs');
});

test('S03-H3: deleting an object credits the per-user quota back to the uploader', async () => {
  const env = makeEnv({ MAX_USER_BYTES: '1000' });
  const holder = 'user-recycle';

  const key1 = `media/${CHAT_ID}/one.jpg`;
  const token1 = await mintToken('write', key1, holder);
  const put1 = await worker.fetch(new Request(`https://worker.example/${key1}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token1}`, 'Content-Length': '900' },
    body: new Uint8Array(900),
  }), env, ctx);
  assert.equal(put1.status, 200, await put1.clone().text());

  // Delete it — the quota should be freed.
  const delToken = await mintToken('delete', key1, holder);
  const del = await worker.fetch(new Request(`https://worker.example/${key1}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${delToken}` },
  }), env, ctx);
  assert.equal(del.status, 200, await del.clone().text());

  // A fresh 900-byte upload from the same holder should now succeed again.
  const key2 = `media/${CHAT_ID}/two.jpg`;
  const token2 = await mintToken('write', key2, holder);
  const put2 = await worker.fetch(new Request(`https://worker.example/${key2}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token2}`, 'Content-Length': '900' },
    body: new Uint8Array(900),
  }), env, ctx);
  assert.equal(put2.status, 200, 'quota freed by the DELETE must let the same holder upload again');
});

test('S03-H3: a missing/unknowable Content-Length cannot be used to smuggle bytes past the per-user quota', async () => {
  const env = makeEnv({ MAX_USER_BYTES: '1000' });
  const holder = 'user-liar';

  // Spend most of the quota honestly first: 900 of the 1000-byte budget.
  const key1 = `media/${CHAT_ID}/honest.jpg`;
  const token1 = await mintToken('write', key1, holder);
  const put1 = await worker.fetch(new Request(`https://worker.example/${key1}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token1}`, 'Content-Length': '900' },
    body: new Uint8Array(900),
  }), env, ctx);
  assert.equal(put1.status, 200, await put1.clone().text());

  // Now upload another 900-byte body via a stream, whose length cannot be
  // known up front — Node's fetch neither preserves a caller-supplied
  // Content-Length alongside a stream body nor computes one itself, so
  // `declaredBytes` resolves to 0 via safeInt's fallback, exactly like a
  // client that omits the header entirely. The pre-check only sees
  // 900 (already used) + 0 (declared) = 900 <= 1000, so it trivially
  // passes — even though the real total would be 1800, well over quota.
  // The real 900-byte body is stored by R2 regardless of what was
  // declared, so only the post-upload re-check against the true HEAD size
  // can catch this and undo the write.
  const key2 = `media/${CHAT_ID}/lie.jpg`;
  const token2 = await mintToken('write', key2, holder);
  const stream = new ReadableStream({
    start(controller) {
      controller.enqueue(new Uint8Array(900));
      controller.close();
    },
  });
  const put2 = await worker.fetch(new Request(`https://worker.example/${key2}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token2}` },
    body: stream,
    duplex: 'half',
  }), env, ctx);
  assert.equal(put2.status, 507, await put2.clone().text());

  // The object must not be left behind in R2 after the rejection.
  const headAfterReject = await env.HOT_BUCKET.head(key2);
  assert.equal(headAfterReject, null, 'a rejected upload must not leave the object stored in R2');

  // Quota must not have been credited for the rejected upload either — the
  // holder must still be sitting at exactly 900/1000, unaffected by the
  // rejected write, and unable to fit another 900-byte upload.
  const key3 = `media/${CHAT_ID}/two.jpg`;
  const token3 = await mintToken('write', key3, holder);
  const put3 = await worker.fetch(new Request(`https://worker.example/${key3}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token3}`, 'Content-Length': '900' },
    body: new Uint8Array(900),
  }), env, ctx);
  assert.equal(put3.status, 507, 'the rejected/deleted upload must not have consumed any of the quota — the holder is still at 900/1000, not 1800/1000');
});

// ─── S03-M2: tokens must be uploader-bound, not just scope-bound ───────────
// A capability token only proves the caller participates in the chat/group
// that owns the key (verifyMediaToken has no concept of "whose media this
// is") — every participant of a conversation can mint a valid write/delete
// token for any key inside it, including one another participant uploaded.
// These tests assert the Worker itself closes that gap at the object layer.

test('S03-M2: a different chat participant with a valid write token cannot overwrite media they did not upload', async () => {
  const env = makeEnv();
  const uploader = 'user-alice';
  const attacker = 'user-bob'; // also a participant of the same chat — has a legitimately-minted token

  const key = `media/${CHAT_ID}/photo.jpg`;
  const putToken1 = await mintToken('write', key, uploader);
  const put1 = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${putToken1}`, 'Content-Length': '10' },
    body: new Uint8Array(10),
  }), env, ctx);
  assert.equal(put1.status, 200, await put1.clone().text());

  // Bob's token is entirely genuine — correctly signed, correct op, correct
  // key, unexpired — it is only bound to the wrong holder for this object.
  const putToken2 = await mintToken('write', key, attacker);
  const put2 = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${putToken2}`, 'Content-Length': '20' },
    body: new Uint8Array(20),
  }), env, ctx);
  assert.equal(put2.status, 403, await put2.clone().text());

  // The original bytes must be untouched — no swap occurred.
  const head = await env.HOT_BUCKET.head(key);
  assert.equal(head.size, 10, 'the object must be unchanged after the rejected overwrite attempt');
});

test('S03-M2: the original uploader may still overwrite their own object', async () => {
  const env = makeEnv();
  const uploader = 'user-alice';
  const key = `media/${CHAT_ID}/photo.jpg`;

  const putToken1 = await mintToken('write', key, uploader);
  await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${putToken1}`, 'Content-Length': '10' },
    body: new Uint8Array(10),
  }), env, ctx);

  const putToken2 = await mintToken('write', key, uploader);
  const put2 = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${putToken2}`, 'Content-Length': '20' },
    body: new Uint8Array(20),
  }), env, ctx);
  assert.equal(put2.status, 200, await put2.clone().text());

  const head = await env.HOT_BUCKET.head(key);
  assert.equal(head.size, 20, 'the same uploader must be able to overwrite their own object');
});

test('S03-M2: a brand-new key (no existing object) may be written by any holder with a valid scope-bound token', async () => {
  const env = makeEnv();
  const key = `media/${CHAT_ID}/brand-new.jpg`;
  const token = await mintToken('write', key, 'user-anyone');
  const put = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token}`, 'Content-Length': '5' },
    body: new Uint8Array(5),
  }), env, ctx);
  // Nothing exists yet at this key, so there is no prior uploader to protect —
  // the ownership check must only ever block overwriting SOMEONE ELSE's
  // existing object, never the initial upload itself.
  assert.equal(put.status, 200, await put.clone().text());
});

test('S03-M2: a different chat participant with a valid delete token cannot delete R2-tier media they did not upload', async () => {
  const env = makeEnv();
  const uploader = 'user-alice';
  const attacker = 'user-bob';
  const key = `media/${CHAT_ID}/voicemsg.m4a`;

  const putToken = await mintToken('write', key, uploader);
  await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${putToken}`, 'Content-Length': '10' },
    body: new Uint8Array(10),
  }), env, ctx);

  const deleteToken = await mintToken('delete', key, attacker);
  const del = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${deleteToken}` },
  }), env, ctx);
  assert.equal(del.status, 403, await del.clone().text());

  // The object must still exist — the delete must never have happened.
  const head = await env.HOT_BUCKET.head(key);
  assert.notEqual(head, null, 'the object must still exist after the rejected delete attempt');
});

test('S03-M2: the original uploader may still delete their own R2-tier object', async () => {
  const env = makeEnv();
  const uploader = 'user-alice';
  const key = `media/${CHAT_ID}/voicemsg.m4a`;

  const putToken = await mintToken('write', key, uploader);
  await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${putToken}`, 'Content-Length': '10' },
    body: new Uint8Array(10),
  }), env, ctx);

  const deleteToken = await mintToken('delete', key, uploader);
  const del = await worker.fetch(new Request(`https://worker.example/${key}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${deleteToken}` },
  }), env, ctx);
  assert.equal(del.status, 200, await del.clone().text());
  assert.equal(await env.HOT_BUCKET.head(key), null, 'the uploader\'s own delete must actually remove the object');
});

test('S03-M2: a different chat participant with a valid delete token cannot delete B2 (cold-tier) media they did not upload', async () => {
  const env = makeEnv();
  const uploader = 'user-alice';
  const attacker = 'user-bob';
  const key = `media/${CHAT_ID}/coldfile.jpg`;

  // Simulate an object that has already tiered to B2: nothing in R2 (the
  // Worker's cold-tier branch is only reached when `HOT_BUCKET.head()` misses),
  // and stub the global `fetch` that aws4fetch's AwsClient wraps, so a HEAD on
  // the B2 URL returns the migrated object's carried-over uploader tag
  // (`x-amz-meta-uploader` — see the `scheduled()` migration handler, which
  // copies this from R2's customMetadata.uploader specifically for this check).
  const realFetch = globalThis.fetch;
  let deleteCalled = false;
  globalThis.fetch = async (input) => {
    const req = input instanceof Request ? input : new Request(input);
    if (req.method === 'HEAD') {
      return new Response(null, { status: 200, headers: { 'x-amz-meta-uploader': uploader } });
    }
    if (req.method === 'DELETE') {
      deleteCalled = true;
      return new Response(null, { status: 204 });
    }
    return realFetch(input);
  };

  try {
    const deleteToken = await mintToken('delete', key, attacker);
    const del = await worker.fetch(new Request(`https://worker.example/${key}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${deleteToken}` },
    }), env, ctx);
    assert.equal(del.status, 403, await del.clone().text());
    assert.equal(deleteCalled, false, 'the B2 DELETE must never be issued once ownership fails the HEAD check');
  } finally {
    globalThis.fetch = realFetch;
  }
});

test('S03-M2: the original uploader may still delete their own B2 (cold-tier) object', async () => {
  const env = makeEnv();
  const uploader = 'user-alice';
  const key = `media/${CHAT_ID}/coldfile.jpg`;

  const realFetch = globalThis.fetch;
  let deleteCalled = false;
  globalThis.fetch = async (input) => {
    const req = input instanceof Request ? input : new Request(input);
    if (req.method === 'HEAD') {
      return new Response(null, { status: 200, headers: { 'x-amz-meta-uploader': uploader } });
    }
    if (req.method === 'DELETE') {
      deleteCalled = true;
      return new Response(null, { status: 204 });
    }
    return realFetch(input);
  };

  try {
    const deleteToken = await mintToken('delete', key, uploader);
    const del = await worker.fetch(new Request(`https://worker.example/${key}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${deleteToken}` },
    }), env, ctx);
    assert.equal(del.status, 200, await del.clone().text());
    assert.equal(deleteCalled, true, 'the uploader\'s own delete must actually reach the B2 DELETE call');
  } finally {
    globalThis.fetch = realFetch;
  }
});

test('S03-M2: a B2 (cold-tier) object with no carried-over uploader tag (pre-S3-11 migration) may still be deleted by any holder with a valid scope-bound token', async () => {
  const env = makeEnv();
  const key = `media/${CHAT_ID}/legacycoldfile.jpg`;

  const realFetch = globalThis.fetch;
  let deleteCalled = false;
  globalThis.fetch = async (input) => {
    const req = input instanceof Request ? input : new Request(input);
    if (req.method === 'HEAD') {
      // No x-amz-meta-uploader header at all — this object predates the
      // migration fix that started carrying the uploader tag to B2.
      return new Response(null, { status: 200, headers: {} });
    }
    if (req.method === 'DELETE') {
      deleteCalled = true;
      return new Response(null, { status: 204 });
    }
    return realFetch(input);
  };

  try {
    const deleteToken = await mintToken('delete', key, 'user-anyone');
    const del = await worker.fetch(new Request(`https://worker.example/${key}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${deleteToken}` },
    }), env, ctx);
    assert.equal(del.status, 200, await del.clone().text());
    assert.equal(deleteCalled, true, 'an untagged legacy object must not be permanently undeletable');
  } finally {
    globalThis.fetch = realFetch;
  }
});
