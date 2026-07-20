import { AwsClient } from 'aws4fetch';

// ─── Tier thresholds ──────────────────────────────────────────────────────────
// Parsed from env vars so wrangler.jsonc is the single source of truth.
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
  // URL-encode each path segment but preserve slashes
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

// ─── Rate limiter ─────────────────────────────────────────────────────────────
async function checkRateLimit(request, env) {
  if (!env.RATE_KV) return null; // KV not configured — skip rate limiting
  const clientId  = request.headers.get('X-Client-ID') || 'anon';
  const minuteKey = `rl:${clientId}:${Math.floor(Date.now() / 60_000)}`;
  const count     = parseInt((await env.RATE_KV.get(minuteKey)) || '0');
  if (count >= rateLimit(env)) {
    return json({ error: 'Rate limit exceeded' }, 429);
  }
  await env.RATE_KV.put(minuteKey, String(count + 1), { expirationTtl: 120 });
  return null;
}

// ─── Main fetch handler ───────────────────────────────────────────────────────
export default {
  async fetch(request, env, ctx) {
    // CORS preflight
    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders() });
    }

    // Health check
    const url = new URL(request.url);
    if (url.pathname === '/' || url.pathname === '/health') {
      return json({ status: 'ok', service: 'duoshield-storage' });
    }

    // Rate limiting
    const rateLimited = await checkRateLimit(request, env);
    if (rateLimited) return rateLimited;

    // Object key is everything after the leading slash.
    // DuoShield paths look like: media/<chatId>/<uuid>.jpg
    //                             voice/<chatId>/<uuid>.m4a
    //                             avatars/<uid>/profile.jpg
    const key = decodeURIComponent(url.pathname.slice(1));
    if (!key) return json({ error: 'Missing file key' }, 400);

    // ── UPLOAD ────────────────────────────────────────────────────────────────
    if (request.method === 'PUT') {
      const contentLength = parseInt(request.headers.get('Content-Length') || '0');
      if (contentLength > maxFileSize(env)) {
        return json({ error: `File too large (max ${maxFileSize(env) / 1_048_576} MB)` }, 413);
      }
      const contentType = request.headers.get('Content-Type') || 'application/octet-stream';

      await env.HOT_BUCKET.put(key, request.body, {
        httpMetadata:    { contentType },
        // Store original upload time so the cron can compute age accurately.
        // R2 also exposes uploaded timestamp via object.uploaded, but custom
        // metadata is more portable if we ever need it on the B2 side too.
        customMetadata:  { uploadedAt: Date.now().toString() },
      });

      return json({ status: 'stored', key, tier: 'hot' });
    }

    // ── DOWNLOAD ──────────────────────────────────────────────────────────────
    if (request.method === 'GET') {
      // 1. Try R2 (hot tier) first
      const r2Object = await env.HOT_BUCKET.get(key);
      if (r2Object) {
        const headers = new Headers({
          'Content-Type':     r2Object.httpMetadata?.contentType || 'application/octet-stream',
          'Cache-Control':    'private, max-age=3600',
          'X-Storage-Tier':  'hot',
          ...corsHeaders(),
        });
        return new Response(r2Object.body, { headers });
      }

      // 2. Fall back to B2 (cold tier)
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

      if (b2Response.status === 404) {
        return json({ error: 'File not found', key }, 404);
      }
      return json({ error: 'B2 error', status: b2Response.status }, 502);
    }

    // ── DELETE ────────────────────────────────────────────────────────────────
    if (request.method === 'DELETE') {
      // Delete from R2 (fire-and-forget errors — object may already be in B2)
      await env.HOT_BUCKET.delete(key).catch(() => {});

      // Delete from B2 (404 = already gone = success)
      const b2 = getB2Client(env);
      try {
        const delResp = await b2.fetch(b2Url(env, key), { method: 'DELETE' });
        if (!delResp.ok && delResp.status !== 404) {
          console.warn(`B2 delete non-OK for ${key}: ${delResp.status}`);
        }
      } catch (err) {
        // Log but don't fail — R2 delete already succeeded
        console.error(`B2 delete error for ${key}: ${err.message}`);
      }

      return json({ status: 'deleted', key });
    }

    return json({ error: 'Method not allowed' }, 405);
  },

  // ─── Scheduled: daily hot→cold tiering + cold purge ───────────────────────
  // Runs at 02:00 UTC every day (see wrangler.jsonc triggers.crons).
  async scheduled(event, env, ctx) {
    const b2  = getB2Client(env);
    const now = Date.now();
    let moved = 0;
    let purged = 0;

    // ── Step 1: Move old R2 objects → B2 ─────────────────────────────────────
    let cursor;
    do {
      const list = await env.HOT_BUCKET.list({ cursor, include: ['httpMetadata', 'customMetadata'] });

      for (const obj of list.objects) {
        // Prefer our stored uploadedAt for accuracy; fall back to R2 uploaded timestamp.
        const uploadedAt = obj.customMetadata?.uploadedAt
          ? parseInt(obj.customMetadata.uploadedAt)
          : new Date(obj.uploaded).getTime();
        const ageMs = now - uploadedAt;

        if (ageMs < hotTierMs(env)) continue; // still hot

        // Read encrypted blob from R2
        const r2Obj = await env.HOT_BUCKET.get(obj.key);
        if (!r2Obj) continue; // already gone

        const contentType = obj.httpMetadata?.contentType || 'application/octet-stream';

        // Upload to B2 cold bucket (idempotent — overwrite is fine)
        const putResp = await b2.fetch(b2Url(env, obj.key), {
          method:  'PUT',
          body:    r2Obj.body,
          headers: {
            'Content-Type':   contentType,
            // Pass age metadata so the purge step can compute from original upload date
            'x-amz-meta-uploaded-at': uploadedAt.toString(),
          },
        });

        if (putResp.ok) {
          // Remove from R2 to free hot-tier storage quota
          await env.HOT_BUCKET.delete(obj.key);
          moved++;
        } else {
          console.error(`Failed to tier ${obj.key} to B2: ${putResp.status}`);
        }
      }

      cursor = list.truncated ? list.cursor : null;
    } while (cursor);

    // ── Step 2: Purge very old B2 objects (age > COLD_TIER_DAYS) ─────────────
    let continuationToken;
    do {
      const listUrl = new URL(`${env.B2_ENDPOINT}/${env.B2_BUCKET}`);
      listUrl.searchParams.set('list-type', '2');
      listUrl.searchParams.set('fetch-owner', 'false');
      if (continuationToken) {
        listUrl.searchParams.set('continuation-token', continuationToken);
      }

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

      for (const { key, lastModified, uploadedAt } of keys) {
        // Use our stored metadata if present, else fall back to LastModified
        const refTime = uploadedAt
          ? parseInt(uploadedAt)
          : new Date(lastModified).getTime();
        if (now - refTime > coldTierMs(env)) {
          await b2.fetch(b2Url(env, key), { method: 'DELETE' }).catch(() => {});
          purged++;
        }
      }
    } while (continuationToken);

    console.log(`Tiering complete: moved ${moved} objects to B2, purged ${purged} from B2.`);
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
    const block       = match[1];
    const keyMatch    = block.match(/<Key>([^<]+)<\/Key>/);
    const dateMatch   = block.match(/<LastModified>([^<]+)<\/LastModified>/);
    // Our custom metadata comes back as x-amz-meta-uploaded-at in ListObjects
    const metaMatch   = block.match(/<x-amz-meta-uploaded-at>([^<]+)<\/x-amz-meta-uploaded-at>/);
    if (keyMatch && dateMatch) {
      keys.push({
        key:          keyMatch[1],
        lastModified: dateMatch[1],
        uploadedAt:   metaMatch ? metaMatch[1] : null,
      });
    }
  }

  return { keys, nextToken };
}
