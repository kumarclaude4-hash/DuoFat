"use strict";

// ── Pure, side-effect-free server helpers ─────────────────────────────────────
// Extracted from index.js so they can be unit-tested with the Node built-in test
// runner without booting the HTTP server, Firebase Admin, or the Firestore
// listeners. index.js requires these exact implementations, so the tests cover
// the real code path — not a copy that could drift.

const crypto = require("crypto");

// Human-readable push body for a message document, keyed by its `type`.
function notificationBody(data) {
  if (!data || typeof data !== "object") return "New encrypted message";
  if (data.type === "image") return "Sent a photo 🖼";
  if (data.type === "video") return "Sent a video 🎬";
  if (data.type === "voice") return "Sent a voice note 🎙";
  if (data.type === "contact") return "Shared a contact card 📇";
  return "New encrypted message";
}

// Constant-time token comparison so an attacker cannot learn the admin token
// byte-by-byte through response-timing differences. Returns false for any
// length mismatch (timingSafeEqual throws on unequal lengths).
function safeTokenEqual(a, b) {
  const bufA = Buffer.from(String(a));
  const bufB = Buffer.from(String(b));
  if (bufA.length !== bufB.length) return false;
  return crypto.timingSafeEqual(bufA, bufB);
}

/**
 * Constant-time comparison of two fixed-width hex digests.
 *
 * Deliberately distinct from safeTokenEqual above. That one is for opaque bearer
 * tokens and coerces with String(), which makes `undefined` compare equal to the
 * literal "undefined" and accepts non-string input without complaint. For
 * identity material the stored side may legitimately be absent or malformed, and
 * that state must read as "ownership not proven" rather than being coerced into
 * something comparable — see S07-H1, where a fail-open hash check issued tokens
 * for accounts whose stored hash was missing.
 *
 * Length is compared in the clear: a digest's width is not secret, and
 * crypto.timingSafeEqual throws rather than returning false on unequal lengths.
 * Buffer.from(x, "hex") stops at the first invalid character instead of throwing,
 * so the decoded length is re-checked — otherwise a malformed value could compare
 * equal to a prefix of the real digest.
 */
function timingSafeEqualHex(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  if (a.length !== b.length || a.length === 0) return false;
  if (a.length % 2 !== 0) return false;
  const bufA = Buffer.from(a, "hex");
  const bufB = Buffer.from(b, "hex");
  if (bufA.length !== a.length / 2 || bufB.length !== b.length / 2) return false;
  if (bufA.length !== bufB.length) return false;
  return crypto.timingSafeEqual(bufA, bufB);
}

// Whitelist for admin-supplied UIDs: printable, bounded length, and free of path
// separators / control characters that could be used to traverse Firestore
// document paths or smuggle control bytes.
function validAdminUid(uid) {
  return typeof uid === "string"
    && uid.length >= 1
    && uid.length <= 128
    && !/[\/\\\u0000-\u001f]/.test(uid);
}

// Parse a single cookie value out of a Cookie header string. Accepts either a
// raw header string or a request-like object exposing `headers.cookie`.
function getCookie(reqOrHeader, name) {
  const raw = typeof reqOrHeader === "string"
    ? reqOrHeader
    : (reqOrHeader && reqOrHeader.headers && reqOrHeader.headers.cookie) || "";
  for (const part of raw.split(";")) {
    const separator = part.indexOf("=");
    if (separator < 0) continue;
    const key = part.slice(0, separator).trim();
    if (key === name) return decodeURIComponent(part.slice(separator + 1).trim());
  }
  return "";
}

// SSRF guard: block loopback, RFC-1918 private ranges, link-local, and cloud
// metadata hostnames. Applied to the initial /linkPreview target AND to every
// redirect hop, so a public host cannot bounce the fetch to an internal address.
function isBlockedPreviewHost(hostname) {
  const host = String(hostname || "").toLowerCase();
  return /^(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|\[?::1\]?)/.test(host) ||
    host === "metadata.google.internal" ||
    host === "169.254.169.254" ||
    host.endsWith(".internal") ||
    host.endsWith(".local");
}

// S04-I3: /linkPreview labeled every result with the hostname of the
// ORIGINALLY SUBMITTED url, even though title/imageUrl are scraped from the
// FINAL hop of a (validated) redirect chain — so a link on trusted.example
// that 302s to attacker.example rendered a preview card labelled
// trusted.example carrying attacker.example's title and image. Fed
// `finalUrl` (already checked per-hop by fetchFollowingSafeRedirects, so
// this never surfaces an unvalidated host), this returns the domain that
// content actually came from. Falls back to `fallbackHostname` (the
// originally-submitted host) only if `finalUrl` fails to parse — belt and
// suspenders, since a value that reached here already round-tripped through
// `new URL()` in fetchFollowingSafeRedirects.
function previewDomainFromUrl(finalUrl, fallbackHostname) {
  try {
    return new URL(finalUrl).hostname.replace(/^www\./, "");
  } catch {
    return String(fallbackHostname || "").replace(/^www\./, "");
  }
}

// S04-M2: `/turnCredentials` minted 24-hour Cloudflare TURN relay credentials
// (`ttl: 86400`) — wildly longer than any call needs, and long enough that a
// bulk-minted, resold, or leaked credential (a plain bearer username/password
// that works from anywhere, bound to nothing) stays useful for a full day.
// This clamps whatever TTL the caller asks for into a hard floor/ceiling that
// holds regardless of a misconfigured env var, so a typo'd
// `TURN_CRED_TTL_SECONDS` can never resurrect the original 24h exposure. The
// default ceiling (3600s / 1h) intentionally matches the Android client's own
// refresh assumption — `TurnCredentialCache.TTL_MS` (app-side) already treats
// a cached credential as stale after 1h, so this does not change client
// behavior, only how long a stolen credential remains valid server-side.
function clampTurnCredentialTtlSeconds(requestedSeconds, minSeconds, maxSeconds) {
  const min = Number.isFinite(minSeconds) ? minSeconds : 60;
  const max = Number.isFinite(maxSeconds) ? maxSeconds : 3600;
  const requested = Number(requestedSeconds);
  const fallback = max; // unset/non-numeric env var -> ceiling, never the old 24h default
  const value = Number.isFinite(requested) ? requested : fallback;
  return Math.min(max, Math.max(min, value));
}

// ── Fixed-window rate-limit evaluation (pure) ─────────────────────────────────
// Given the caller's current record ({ count, windowStart } | undefined) and the
// clock, decide whether the request is allowed and return the record to persist.
// The caller owns storage (a Map); this only holds the windowing math so it can
// be tested deterministically.
function evaluateFixedWindow(rec, now, windowMs, max) {
  if (!rec || now - rec.windowStart >= windowMs) {
    return { allowed: true, record: { count: 1, windowStart: now } };
  }
  if (rec.count >= max) {
    return { allowed: false, record: rec };
  }
  return { allowed: true, record: { count: rec.count + 1, windowStart: rec.windowStart } };
}

// ── Stale-entry purge (pure) ──────────────────────────────────────────────────
// S02-L3: `mintCooldown` (one key per userId ever seen, value = last-mint
// timestamp) has no purge job, unlike every sibling limiter Map in index.js
// (`ipHits`, `waitlistIpHits`, `authRateLimits`), so on a long-lived Render
// instance it grows by one entry per distinct userId forever — unbounded
// memory growth is itself a DoS surface. This holds only the "which keys are
// stale" decision so it is testable against plain arrays/timestamps, with no
// Map or real clock involved; the caller (index.js) owns the actual Map and
// setInterval and does the `.delete()`.
//
// `entries` is any iterable of `[key, timestampMs]` pairs — a `Map` satisfies
// this directly via its default iterator, so callers can pass the live Map.
function collectStaleKeys(entries, now, ttlMs) {
  const stale = [];
  const cutoff = now - ttlMs;
  for (const [key, timestampMs] of entries) {
    if (timestampMs < cutoff) stale.push(key);
  }
  return stale;
}

// ── Client IP resolution with configurable proxy trust (pure) ────────────────
// S04-M3: `getClientIp()` unconditionally trusted the rightmost entry of
// X-Forwarded-For as proxy-appended, hardcoding "exactly one trusted hop"
// (Render's edge). That is correct for the current deployment topology but
// wrong — and silently insecure — for any other one: zero proxies (XFF absent
// or fully attacker-controlled) or more than one trusted hop (e.g. a CDN in
// front of Render) both need a different pick, and there was no way to
// configure it without editing code. `trustedHops` makes the pick explicit:
//   0         → ignore X-Forwarded-For entirely, always use the socket address.
//   N (>=1)   → trust that the terminating proxy appended exactly N hops of
//               its own, and pick the Nth-from-right entry (N=1 reproduces the
//               original "rightmost" behavior byte-for-byte).
// Malformed/insufficient entries fall back to the socket address rather than
// guessing, so a misconfigured hop count fails toward "less trust", not more.
function pickClientIp(forwardedHeader, remoteAddress, trustedHops) {
  const fallback = remoteAddress || "unknown";
  const hops = Number.isInteger(trustedHops) ? trustedHops : 1;
  if (hops <= 0 || !forwardedHeader) return fallback;

  const entries = String(forwardedHeader).split(",").map((s) => s.trim()).filter(Boolean);
  if (entries.length < hops) return fallback;

  const picked = entries[entries.length - hops];
  return picked || fallback;
}

// ── IPv6 /64-aware rate-limit key normalization (pure) ────────────────────────
// S04-M1: every IP-keyed limiter (waitlistIpHits, waitlistPollHits, ipHits,
// adminIpFails) used the raw client IP string as its Map key. A residential
// ISP delegates a whole /64 (2^64 addresses) to a single customer, and an
// attacker on that customer's own connection can rotate the last 64 bits of
// their address per request (many OSes do this automatically for privacy —
// "privacy extensions" / RFC 4941) at zero cost, so keying by the full 128-bit
// address makes the limiter's actual granularity "one bucket per request" for
// any IPv6 attacker — the limit is defeated entirely, not just weakened.
// IPv4 has no such delegated-block problem (a /32 *is* the single address)
// and is returned unchanged. IPv4-mapped IPv6 addresses (::ffff:a.b.c.d, used
// by some dual-stack proxies) are unwrapped to their IPv4 form for the same
// reason. Malformed input is returned unchanged rather than guessed at, so a
// parse failure fails toward "no worse than pre-fix", not toward silently
// merging unrelated clients into one bucket.
function normalizeIpForRateLimit(ip) {
  if (typeof ip !== "string" || ip.length === 0) return ip;

  // Strip an IPv6 zone index (fe80::1%eth0) and enclosing brackets ([::1]),
  // both of which can appear on remoteAddress/XFF values but are irrelevant
  // to which /64 an address belongs to.
  let addr = ip.split("%")[0];
  if (addr.startsWith("[") && addr.endsWith("]")) addr = addr.slice(1, -1);

  if (!addr.includes(":")) return ip; // IPv4, or already-unparseable — leave as-is

  const mapped = addr.match(/^::ffff:(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})$/i);
  if (mapped) return mapped[1];

  // Expand "::" (at most one occurrence in a valid address) into explicit
  // zero groups so the /64 prefix (first 4 of 8 groups) is unambiguous
  // regardless of where the shorthand run of zeros falls.
  const halves = addr.split("::");
  if (halves.length > 2) return ip; // malformed (multiple "::") — don't guess

  let groups;
  if (halves.length === 2) {
    const left  = halves[0] ? halves[0].split(":") : [];
    const right = halves[1] ? halves[1].split(":") : [];
    const missing = 8 - left.length - right.length;
    if (missing < 0) return ip; // malformed — too many groups already
    groups = [...left, ...Array(missing).fill("0"), ...right];
  } else {
    groups = addr.split(":");
    if (groups.length !== 8) return ip; // malformed — not a full address
  }

  return groups.slice(0, 4).join(":");
}

// ── YouTube search: query validation (pure) ───────────────────────────────────
// Watch Together lets a user search YouTube through the server so the API key
// never ships in the APK. Every input constraint below exists to bound YouTube
// Data API quota, which is the scarce resource: a search.list call costs 100
// quota units against a 10,000/day free allowance, i.e. only ~100 searches per
// day for the WHOLE deployment. Validation therefore runs before any outbound
// call, and anything rejected here costs zero quota.

// A 1-character query returns near-random results and still costs a full 100
// units, so it is never worth spending. 2 is the shortest useful CJK query.
const SEARCH_QUERY_MIN_LEN = 2;
// YouTube itself truncates well before this; a longer string is either abuse or
// a paste accident. Bounded length also keeps the cache key small.
const SEARCH_QUERY_MAX_LEN = 100;

const SEARCH_MAX_RESULTS_DEFAULT = 10;
// Hard ceiling. maxResults does NOT change the 100-unit cost of a search.list
// call, so this bounds response size and client rendering work, not quota.
const SEARCH_MAX_RESULTS_LIMIT = 15;

/**
 * Validates and normalises a raw search query.
 *
 * Returns `{ ok: true, query }` with the collapsed/trimmed query, or
 * `{ ok: false, error }` with a client-safe reason.
 *
 * Control characters are stripped rather than rejected: they carry no search
 * meaning, and a stray \n from a paste should not cost the user an error.
 */
function validateSearchQuery(raw) {
  if (typeof raw !== "string") return { ok: false, error: "Query must be a string" };
  // Strip C0/C1 control characters, then collapse all whitespace runs to one
  // space so "  cat   video " and "cat video" share one cache entry.
  const cleaned = raw
    .replace(/[\u0000-\u001f\u007f-\u009f]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  if (cleaned.length < SEARCH_QUERY_MIN_LEN) {
    return { ok: false, error: `Query must be at least ${SEARCH_QUERY_MIN_LEN} characters` };
  }
  if (cleaned.length > SEARCH_QUERY_MAX_LEN) {
    return { ok: false, error: `Query must be at most ${SEARCH_QUERY_MAX_LEN} characters` };
  }
  return { ok: true, query: cleaned };
}

/**
 * Clamps a client-supplied maxResults into [1, SEARCH_MAX_RESULTS_LIMIT].
 * Non-numeric / absent input falls back to the default rather than erroring —
 * the field is optional in the request body.
 */
function clampMaxResults(raw) {
  // Only a number or a numeric string is a meaningful count. Everything else is
  // "not specified" and takes the default.
  //
  // This is checked BEFORE Number() because JS coercion is actively misleading
  // here: Number(null), Number(""), Number([]) and Number(false) are all 0,
  // which would then be clamped to 1 and silently return a single result for a
  // request that merely omitted the field.
  const isNumeric =
    typeof raw === "number" ||
    (typeof raw === "string" && raw.trim() !== "" && Number.isFinite(Number(raw)));
  if (!isNumeric) return SEARCH_MAX_RESULTS_DEFAULT;
  const n = Number(raw);
  if (!Number.isFinite(n)) return SEARCH_MAX_RESULTS_DEFAULT;
  const floored = Math.floor(n);
  if (floored < 1) return 1;
  if (floored > SEARCH_MAX_RESULTS_LIMIT) return SEARCH_MAX_RESULTS_LIMIT;
  return floored;
}

/**
 * Cache key for a (query, maxResults) pair. Case- and accent-insensitive on the
 * query so "Lofi Beats" and "lofi beats" hit the same entry and cost one call
 * instead of two.
 */
function searchCacheKey(query, maxResults) {
  return `${String(query).toLowerCase()}\u0000${maxResults}`;
}

/**
 * Builds the YouTube Data API v3 search.list URL.
 *
 * Deliberate choices, all quota- or safety-motivated:
 * - `part=snippet` only — `id` comes back regardless; adding parts costs more.
 * - `fields=` narrows the payload to exactly what the Android UI renders, so
 *   YouTube does not send (and we cannot accidentally forward) anything else.
 * - `type=video` excludes channels/playlists, which have no videoId and would
 *   break the Watch Together player.
 * - `videoEmbeddable=true` excludes videos the IFrame player legally cannot
 *   play — showing them would produce a result that silently fails on tap.
 * - `safeSearch=moderate` is YouTube's own filter; costs nothing extra.
 *
 * The API key is passed as a query parameter because that is the only auth
 * mechanism the Data API accepts for public data. It is supplied by the caller
 * from server env and MUST NOT be logged or returned — see redactApiKey.
 */
function buildYouTubeSearchUrl({ query, maxResults, apiKey, regionCode }) {
  const url = new URL("https://www.googleapis.com/youtube/v3/search");
  url.searchParams.set("part", "snippet");
  url.searchParams.set(
    "fields",
    "items(id/videoId,snippet(title,channelTitle,thumbnails(medium/url,default/url)))"
  );
  url.searchParams.set("q", query);
  url.searchParams.set("type", "video");
  url.searchParams.set("videoEmbeddable", "true");
  url.searchParams.set("safeSearch", "moderate");
  url.searchParams.set("maxResults", String(maxResults));
  // No pageToken is ever sent: pagination would multiply a 100-unit cost by the
  // number of pages a user idly scrolls through. One page per query, by design.
  if (regionCode) url.searchParams.set("regionCode", regionCode);
  url.searchParams.set("key", apiKey);
  return url.toString();
}

// A YouTube video id is exactly 11 chars from the URL-safe base64 alphabet.
// Mirrors YouTubeUrlParser.isValidVideoId on the Android side — the client
// re-validates, but sending a malformed id is a bug worth catching here too.
const YOUTUBE_VIDEO_ID = /^[A-Za-z0-9_-]{11}$/;

/**
 * Projects a raw YouTube search.list body down to the minimal DuoShield shape.
 *
 * This is an allow-list transform, not a filter: a new field appearing in the
 * upstream response can never reach the client, because only these four keys
 * are ever copied out. That property is what keeps the response free of quota
 * metadata, etag/pageToken values, and anything else YouTube may add.
 *
 * Items without a valid 11-char videoId or without a title are dropped rather
 * than forwarded with empty strings, so the client never renders a dead row.
 */
function transformYouTubeSearchResponse(body) {
  const items = body && Array.isArray(body.items) ? body.items : [];
  const results = [];
  for (const item of items) {
    const videoId = item && item.id && typeof item.id.videoId === "string" ? item.id.videoId : "";
    if (!YOUTUBE_VIDEO_ID.test(videoId)) continue;

    const snippet = (item && item.snippet) || {};
    const title = typeof snippet.title === "string" ? snippet.title.trim() : "";
    if (!title) continue;

    const thumbs = snippet.thumbnails || {};
    const medium = thumbs.medium && typeof thumbs.medium.url === "string" ? thumbs.medium.url : "";
    const fallback =
      thumbs.default && typeof thumbs.default.url === "string" ? thumbs.default.url : "";
    const thumbnail = medium || fallback;

    results.push({
      videoId,
      title,
      channel: typeof snippet.channelTitle === "string" ? snippet.channelTitle.trim() : "",
      // Only https thumbnails are forwarded; anything else is omitted so the
      // client never attempts a cleartext image load.
      thumbnail: thumbnail.startsWith("https://") ? thumbnail : "",
    });
  }
  return results;
}

/**
 * Removes any `key=...` value from a string before it reaches a log sink.
 *
 * The YouTube API key travels in the request URL, and Node's fetch/URL errors
 * routinely embed the full URL in `err.message`. Logging that verbatim would
 * write the credential into Render's persistent logs — the exact leak this
 * whole server-side design exists to prevent. Applied to every YouTube-related
 * log line, and covers `key=` in both query-string and JSON-ish contexts.
 */
function redactApiKey(text) {
  if (typeof text !== "string") return "";
  return text.replace(/([?&"']key["']?\s*[=:]\s*["']?)[^&"'\s,}]+/gi, "$1[REDACTED]");
}

/**
 * Maps a YouTube Data API failure onto a client-safe {status, error} pair.
 *
 * Upstream error bodies are never forwarded: they can contain the request URL
 * (and therefore the key), the project number, and internal reason codes. The
 * client only needs to know whether retrying could help.
 *
 * 403 is the interesting case — for the Data API it almost always means the
 * daily quota is exhausted rather than "forbidden", so it is surfaced as 503
 * with a retry-later message instead of a misleading auth error.
 */
function mapYouTubeError(upstreamStatus) {
  if (upstreamStatus === 403 || upstreamStatus === 429) {
    return { status: 503, error: "Search is temporarily unavailable. Try again later." };
  }
  if (upstreamStatus === 400) {
    return { status: 400, error: "Invalid search query" };
  }
  return { status: 502, error: "Search failed. Try again." };
}

// ── Backblaze B2 (S3-compatible) SigV4 presign ────────────────────────────────
// The signing key derivation and canonical-request construction, parameterised so
// they are deterministic and testable (index.js supplies env credentials and the
// current time). Returns null when credentials are absent.
function b2HmacKey(appKey, dateStamp, region) {
  const kDate    = crypto.createHmac("sha256", Buffer.from("AWS4" + appKey)).update(dateStamp).digest();
  const kRegion  = crypto.createHmac("sha256", kDate).update(region).digest();
  const kService = crypto.createHmac("sha256", kRegion).update("s3").digest();
  return crypto.createHmac("sha256", kService).update("aws4_request").digest();
}

function buildB2PresignUrl({ keyId, appKey, bucket, region, method, objectKey, contentType, ttlSeconds, now }) {
  if (!keyId || !appKey) return null;

  const host = "s3." + region + ".backblazeb2.com";
  const clock = now instanceof Date ? now : new Date();
  const ds = clock.toISOString().slice(0, 10).replace(/-/g, "");
  // yyyyMMddTHHmmssZ
  const az = clock.toISOString().replace(/[-:]/g, "").replace(/\.\d{3}/, "");
  const cs = ds + "/" + region + "/s3/aws4_request";
  const cred = keyId + "/" + cs;
  const sh = (method === "PUT" && contentType) ? "content-type;host" : "host";

  const qpRaw = [
    ["X-Amz-Algorithm",     "AWS4-HMAC-SHA256"],
    ["X-Amz-Credential",    cred],
    ["X-Amz-Date",          az],
    ["X-Amz-Expires",       String(ttlSeconds)],
    ["X-Amz-SignedHeaders", sh],
  ];
  const canonQs = qpRaw.slice().sort((a, b) => a[0].localeCompare(b[0]))
    .map(([k, v]) => encodeURIComponent(k) + "=" + encodeURIComponent(v))
    .join("&");

  const ch = (method === "PUT" && contentType)
    ? "content-type:" + contentType + "\nhost:" + host + "\n"
    : "host:" + host + "\n";

  const cr = [method, "/" + bucket + "/" + objectKey, canonQs, ch, sh, "UNSIGNED-PAYLOAD"].join("\n");
  const sts = ["AWS4-HMAC-SHA256", az, cs,
    crypto.createHash("sha256").update(cr).digest("hex")].join("\n");
  const sk = b2HmacKey(appKey, ds, region);
  const sig = crypto.createHmac("sha256", sk).update(sts).digest("hex");

  return "https://" + host + "/" + bucket + "/" + objectKey
    + "?" + canonQs + "&X-Amz-Signature=" + sig;
}

module.exports = {
  notificationBody,
  safeTokenEqual,
  timingSafeEqualHex,
  validAdminUid,
  getCookie,
  isBlockedPreviewHost,
  previewDomainFromUrl,
  clampTurnCredentialTtlSeconds,
  evaluateFixedWindow,
  collectStaleKeys,
  pickClientIp,
  normalizeIpForRateLimit,
  b2HmacKey,
  buildB2PresignUrl,
  // YouTube search (Watch Together)
  SEARCH_QUERY_MIN_LEN,
  SEARCH_QUERY_MAX_LEN,
  SEARCH_MAX_RESULTS_DEFAULT,
  SEARCH_MAX_RESULTS_LIMIT,
  validateSearchQuery,
  clampMaxResults,
  searchCacheKey,
  buildYouTubeSearchUrl,
  transformYouTubeSearchResponse,
  redactApiKey,
  mapYouTubeError,
};
