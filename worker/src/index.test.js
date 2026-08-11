// Regression tests for S3-11 (S03-H3, S03-L4, S08-I3).
//
// These are structural/behavioral tests against the actual exported handler
// and helper functions in src/index.js — run with plain `node --test`, no
// wrangler/Miniflare required. R2/KV/B2 are stubbed with minimal in-memory
// fakes; aws4fetch's AwsClient.fetch is not stubbed because these tests never
// exercise a cold-tier (B2) path.

import test from 'node:test';
import assert from 'node:assert/strict';
import worker from './index.js';

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
