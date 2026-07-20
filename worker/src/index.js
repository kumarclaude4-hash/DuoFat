import { AwsClient } from 'aws4fetch';

// ─── Hard limits ──────────────────────────────────────────────────────────────
// R2 free tier = 10 GB/month (credit card on file — enforce at 90% = 9 GB)
// B2 free tier = 10 GB (no credit card risk — no cap imposed)
const MAX_R2_BYTES         = 9 * 1024 * 1024 * 1024; // 9 GB R2 cap (90% of free 10 GB)
const MAX_MONTHLY_REQUESTS = 90_000;                  // 90K Worker invocations / month

// ─── Tier thresholds ──────────────────────────────────────────────────────────
function hotTierMs(env)  { return parseInt(env.HOT_TIER_DAYS  || '30')  * 86_400_000; }
function coldTierMs(env) { return parseInt(env.COLD_TIER_DAYS || '180') * 86_400_000; }
function maxFileSize(env){ return parseInt(env.MAX_FILE_SIZE   || '10485760'); }
function rateLimit(env)  { return parseInt(env.RATE_LIMIT_PER_MIN || '120'); }

// ─── B2 (S3-compatible) client ────────────────────────────────────────────────
function getB2Client(env) {
  return new AwsClient({
    accessKeyId:     env.B2_ACCESS_KEY_ID,
    secretAccessKey: env.B2_SECRET_ACCESS_KEY,
    region:          env.B2_REGION,
    service:         's3',
  });
}

function b2Url(env, key) {
  const encoded = key.split('/').map(encodeURIComponent).join('/');
  return `${env.B2_ENDPOINT}/${env.B2_BUCKET}/${encoded}`;
}

// ─── Response helpers ─────────────────────────────────────────────────────────
function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function corsHeaders() {
  return {
    'Access-Control-Allow-Origin':  '*',
    'Access-Control-Allow-Methods': 'GET, PUT, DELETE, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, X-Client-ID',
  };
}

// ─── KV helpers ───────────────────────────────────────────────────────────────
function monthKey() {
  const d = new Date();
  return `global:req:${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
}

async function kvGet(env, key, fallback = '0') {
  if (!env.RATE_KV) return fallback;
  return (await env.RATE_KV.get(key)) ?? fallback;
}

async function kvSet(env, key, value, opts = {}) {
  if (!env.RATE_KV) return;
  await env.RATE_KV.put(key, String(value), opts);
}

// ─── Monthly request limit ────────────────────────────────────────────────────
async function checkMonthlyRequestLimit(env) {
  if (!env.RATE_KV) return null;
  const key   = monthKey();
  const count = parseInt(await kvGet(env, key));
  if (count >= MAX_MONTHLY_REQUESTS) {
    return json({
      error:  'Monthly request limit reached (90K). Resets on the 1st of next month.',
      count,
      limit:  MAX_MONTHLY_REQUESTS,
    }, 429);
  }
  // TTL: 35 days — outlives the month so we can audit after rollover
  await kvSet(env, key, count + 1, { expirationTtl: 35 * 86_400 });
  return null;
}

// ─── Storage tracking ─────────────────────────────────────────────────────────
async function getR2Bytes(env) {
  return parseInt(await kvGet(env, 'global:storage:r2'));
}

// delta can be negative (deletion) — floor at 0 to avoid underflow
async function adjustStorage(env, tier, deltaBytes) {
  if (!env.RATE_KV || deltaBytes === 0) return;
  const key  = `global:storage:${tier}`; // 'r2' or 'b2'
  const cur  = parseInt(await kvGet(env, key));
  await kvSet(env, key, Math.max(0, cur + deltaBytes));
}

// ─── Per-user rate limiter ────────────────────────────────────────────────────
async function checkPerUserRateLimit(request, env) {
  if (!env.RATE_KV) return null;
  const clientId  = request.headers.get('X-Client-ID') || 'anon';
  const minuteKey = `rl:${clientId}:${Math.floor(Date.now() / 60_000)}`;
  const count     = parseInt(await kvGet(env, minuteKey));
  if (count >= rateLimit(env)) {
    return json({ error: 'Per-user rate limit exceeded (120 req/min)' }, 429);
  }
  await kvSet(env, minuteKey, count + 1, { expirationTtl: 120 });
  return null;
}

// ─── Main fetch handler ───────────────────────────────────────────────────────
export default {
  async fetch(request, env, ctx) {
    // CORS preflight — does not count against request quota
    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders() });
    }

    const url = new URL(request.url);

    // ── Health check (does not count against quota) ───────────────────────────
    if (url.pathname === '/' || url.pathname === '/health') {
      return json({ status: 'ok', service: 'duoshield-storage' });
    }

    // ── Stats endpoint (admin) ────────────────────────────────────────────────
    if (url.pathname === '/stats') {
      const [r2Raw, b2Raw, reqRaw] = await Promise.all([
        kvGet(env, 'global:storage:r2'),
        kvGet(env, 'global:storage:b2'),
        kvGet(env, monthKey()),
      ]);
      const r2Bytes  = parseInt(r2Raw);
      const b2Bytes  = parseInt(b2Raw);
      const reqCount = parseInt(reqRaw);
      return json({
        r2: {
          used_bytes:      r2Bytes,
          limit_bytes:     MAX_R2_BYTES,
          used_pct:        parseFloat((r2Bytes / MAX_R2_BYTES * 100).toFixed(2)),
          remaining_bytes: Math.max(0, MAX_R2_BYTES - r2Bytes),
          note:            'Capped at 9 GB (90% of 10 GB free tier) — credit card on file',
        },
        b2: {
          used_bytes: b2Bytes,
          limit_bytes: 10 * 1024 * 1024 * 1024,
          used_pct:    parseFloat((b2Bytes / (10 * 1024 * 1024 * 1024) * 100).toFixed(2)),
          note:        'No hard cap — B2 10 GB free tier, no credit card risk',
        },
        requests: {
          this_month:      reqCount,
          limit_per_month: MAX_MONTHLY_REQUESTS,
          remaining:       Math.max(0, MAX_MONTHLY_REQUESTS - reqCount),
        },
      });
    }

    // ── Global monthly request gate ───────────────────────────────────────────
    const monthlyLimited = await checkMonthlyRequestLimit(env);
    if (monthlyLimited) return monthlyLimited;

    // ── Per-user rate limit ───────────────────────────────────────────────────
    const rateLimited = await checkPerUserRateLimit(request, env);
    if (rateLimited) return rateLimited;

    // Object key is everything after the leading slash.
    // DuoShield paths: media/<chatId>/<uuid>.jpg  |  voice/<chatId>/<uuid>.m4a
    const key = decodeURIComponent(url.pathname.slice(1));
    if (!key) return json({ error: 'Missing file key' }, 400);

    // ── UPLOAD ────────────────────────────────────────────────────────────────
    if (request.method === 'PUT') {
      const contentLength = parseInt(request.headers.get('Content-Length') || '0');

      // Per-file size check
      if (contentLength > maxFileSize(env)) {
        return json({ error: `File too large (max ${maxFileSize(env) / 1_048_576} MB)` }, 413);
      }

      // R2 cap: reject if this upload would push R2 past 9 GB (90% of free 10 GB tier)
      const r2Bytes = await getR2Bytes(env);
      if (r2Bytes + contentLength > MAX_R2_BYTES) {
        const remainingMB = ((MAX_R2_BYTES - r2Bytes) / 1_048_576).toFixed(1);
        return json({
          error:        'R2 storage limit reached (9 GB cap). File moved to cold tier or try again later.',
          r2_used_bytes:   r2Bytes,
          r2_limit_bytes:  MAX_R2_BYTES,
          remaining_mb: parseFloat(remainingMB),
        }, 507);
      }

      const contentType = request.headers.get('Content-Type') || 'application/octet-stream';

      await env.HOT_BUCKET.put(key, request.body, {
        httpMetadata:   { contentType },
        customMetadata: { uploadedAt: Date.now().toString() },
      });

      // Track storage: increment R2 counter
      ctx.waitUntil(adjustStorage(env, 'r2', contentLength));

      return json({ status: 'stored', key, tier: 'hot' });
    }

    // ── DOWNLOAD ──────────────────────────────────────────────────────────────
    if (request.method === 'GET') {
      // 1. Try R2 hot tier first
      const r2Object = await env.HOT_BUCKET.get(key);
      if (r2Object) {
        const headers = new Headers({
          'Content-Type':    r2Object.httpMetadata?.contentType || 'application/octet-stream',
          'Cache-Control':   'private, max-age=3600',
          'X-Storage-Tier': 'hot',
          ...corsHeaders(),
        });
        return new Response(r2Object.body, { headers });
      }

      // 2. Fall back to B2 cold tier
      const b2 = getB2Client(env);
      let b2Response;
      try {
        b2Response = await b2.fetch(b2Url(env, key));
      } catch (err) {
        return json({ error: 'B2 fetch failed', detail: err.message }, 502);
      }

      if (b2Response.ok) {
        const headers = new Headers(b2Response.headers);
        headers.set('X-Storage-Tier', 'cold');
        headers.set('Cache-Control', 'private, max-age=3600');
        Object.entries(corsHeaders()).forEach(([k, v]) => headers.set(k, v));
        return new Response(b2Response.body, { status: 200, headers });
      }

      if (b2Response.status === 404) return json({ error: 'File not found', key }, 404);
      return json({ error: 'B2 error', status: b2Response.status }, 502);
    }

    // ── DELETE ────────────────────────────────────────────────────────────────
    if (request.method === 'DELETE') {
      // Read R2 object size BEFORE deleting so we can decrement storage counter
      const r2Head = await env.HOT_BUCKET.head(key).catch(() => null);
      const r2Size = r2Head?.size ?? 0;

      await env.HOT_BUCKET.delete(key).catch(() => {});
      if (r2Size > 0) ctx.waitUntil(adjustStorage(env, 'r2', -r2Size));

      // Delete from B2 (404 = already gone = fine)
      const b2 = getB2Client(env);
      try {
        // HEAD B2 to get size before deleting
        const b2HeadResp = await b2.fetch(b2Url(env, key), { method: 'HEAD' });
        const b2Size = parseInt(b2HeadResp.headers.get('Content-Length') || '0');

        const delResp = await b2.fetch(b2Url(env, key), { method: 'DELETE' });
        if (!delResp.ok && delResp.status !== 404) {
          console.warn(`B2 delete non-OK for ${key}: ${delResp.status}`);
        } else if (b2Size > 0) {
          ctx.waitUntil(adjustStorage(env, 'b2', -b2Size));
        }
      } catch (err) {
        console.error(`B2 delete error for ${key}: ${err.message}`);
      }

      return json({ status: 'deleted', key });
    }

    return json({ error: 'Method not allowed' }, 405);
  },

  // ─── Scheduled: daily hot→cold tiering + cold purge + storage reconcile ───
  async scheduled(event, env, ctx) {
    const b2  = getB2Client(env);
    const now = Date.now();
    let moved  = 0;
    let purged = 0;
    let r2TotalBytes = 0; // will be the authoritative R2 figure after full scan
    let b2DeltaBytes = 0; // net change to B2 during this run

    // ── Step 1: Scan R2 — tier old objects to B2, count remaining bytes ───────
    let cursor;
    do {
      const list = await env.HOT_BUCKET.list({ cursor, include: ['httpMetadata', 'customMetadata'] });

      for (const obj of list.objects) {
        const uploadedAt = obj.customMetadata?.uploadedAt
          ? parseInt(obj.customMetadata.uploadedAt)
          : new Date(obj.uploaded).getTime();
        const ageMs = now - uploadedAt;

        if (ageMs < hotTierMs(env)) {
          // Still hot — count toward R2 total
          r2TotalBytes += obj.size ?? 0;
          continue;
        }

        // Past hot-tier threshold — move to B2
        const r2Obj = await env.HOT_BUCKET.get(obj.key);
        if (!r2Obj) continue;

        const contentType = obj.httpMetadata?.contentType || 'application/octet-stream';

        const putResp = await b2.fetch(b2Url(env, obj.key), {
          method:  'PUT',
          body:    r2Obj.body,
          headers: {
            'Content-Type':           contentType,
            'x-amz-meta-uploaded-at': uploadedAt.toString(),
          },
        });

        if (putResp.ok) {
          await env.HOT_BUCKET.delete(obj.key);
          // obj.size moved from R2 → B2
          b2DeltaBytes += obj.size ?? 0;
          moved++;
        } else {
          // Failed to tier — object stays in R2
          r2TotalBytes += obj.size ?? 0;
          console.error(`Failed to tier ${obj.key} to B2: ${putResp.status}`);
        }
      }

      cursor = list.truncated ? list.cursor : null;
    } while (cursor);

    // Reconcile R2 storage counter with the authoritative scan result
    await kvSet(env, 'global:storage:r2', r2TotalBytes);

    // ── Step 2: Purge very old B2 objects (age > COLD_TIER_DAYS) ─────────────
    let b2TotalBytes = parseInt(await kvGet(env, 'global:storage:b2'));
    let continuationToken;
    do {
      const listUrl = new URL(`${env.B2_ENDPOINT}/${env.B2_BUCKET}`);
      listUrl.searchParams.set('list-type', '2');
      listUrl.searchParams.set('fetch-owner', 'false');
      if (continuationToken) listUrl.searchParams.set('continuation-token', continuationToken);

      let listResp;
      try {
        listResp = await b2.fetch(listUrl.toString());
      } catch (err) {
        console.error('B2 list failed:', err.message);
        break;
      }
      if (!listResp.ok) break;

      const xmlText = await listResp.text();
      const { keys, nextToken } = parseListXml(xmlText);
      continuationToken = nextToken;

      for (const { key, lastModified, uploadedAt, size } of keys) {
        const refTime = uploadedAt
          ? parseInt(uploadedAt)
          : new Date(lastModified).getTime();

        if (now - refTime > coldTierMs(env)) {
          await b2.fetch(b2Url(env, key), { method: 'DELETE' }).catch(() => {});
          b2DeltaBytes -= size ?? 0;
          b2TotalBytes  = Math.max(0, b2TotalBytes - (size ?? 0));
          purged++;
        } else {
          // Count B2 objects that are still within retention
          // (b2TotalBytes accumulates from KV + deltas; we re-derive below)
        }
      }
    } while (continuationToken);

    // Apply net B2 delta (tiered in − purged out) and persist
    const newB2Total = Math.max(0, parseInt(await kvGet(env, 'global:storage:b2')) + b2DeltaBytes);
    await kvSet(env, 'global:storage:b2', newB2Total);

    const r2UsedPct = (r2TotalBytes / MAX_R2_BYTES * 100).toFixed(1);
    const b2UsedPct = (newB2Total / (10 * 1024 * 1024 * 1024) * 100).toFixed(1);

    console.log(
      `Tiering complete: moved=${moved} purged=${purged} | ` +
      `R2=${(r2TotalBytes / 1_048_576).toFixed(1)}MB (${r2UsedPct}% of 9GB cap) ` +
      `B2=${(newB2Total / 1_048_576).toFixed(1)}MB (${b2UsedPct}% of 10GB free)`
    );

    // Warn only on R2 — it has a credit card attached
    if (r2TotalBytes > MAX_R2_BYTES * 0.9) {
      console.warn(`⚠️ R2 at ${r2UsedPct}% of 9 GB cap — approaching limit! New uploads will be rejected.`);
    }
  },
};

// ─── Minimal XML parser for S3 ListObjectsV2 response ────────────────────────
function parseListXml(xml) {
  const keys      = [];
  let   nextToken = null;

  const tokenMatch = xml.match(/<NextContinuationToken>([^<]+)<\/NextContinuationToken>/);
  if (tokenMatch) nextToken = tokenMatch[1];

  const re = /<Contents>([\s\S]*?)<\/Contents>/g;
  let match;
  while ((match = re.exec(xml)) !== null) {
    const block     = match[1];
    const keyMatch  = block.match(/<Key>([^<]+)<\/Key>/);
    const dateMatch = block.match(/<LastModified>([^<]+)<\/LastModified>/);
    const sizeMatch = block.match(/<Size>(\d+)<\/Size>/);
    const metaMatch = block.match(/<x-amz-meta-uploaded-at>([^<]+)<\/x-amz-meta-uploaded-at>/);
    if (keyMatch && dateMatch) {
      keys.push({
        key:          keyMatch[1],
        lastModified: dateMatch[1],
        size:         sizeMatch ? parseInt(sizeMatch[1]) : 0,
        uploadedAt:   metaMatch ? metaMatch[1] : null,
      });
    }
  }

  return { keys, nextToken };
}
