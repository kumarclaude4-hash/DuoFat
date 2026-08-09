"use strict";

// Unit tests for the pure YouTube-search helpers backing /youtubeSearch
// (Watch Together). Run with: npm test   (inside server/) — Node's built-in
// test runner, same as pure.test.js.
//
// These cover the logic that index.js's /youtubeSearch handler delegates to, so
// the tests exercise the real code path rather than a reimplementation. The
// handler itself is thin glue (auth → validate → cache → rate limit → fetch);
// its collaborators (Firebase Admin, global fetch) are not stubbed here because
// booting them would require real credentials.

const test   = require("node:test");
const assert = require("node:assert/strict");
const pure   = require("./pure");

// ── Query validation ─────────────────────────────────────────────────────────

test("validateSearchQuery accepts a normal query and trims/collapses whitespace", () => {
  assert.deepEqual(pure.validateSearchQuery("lofi beats"), { ok: true, query: "lofi beats" });
  // Leading/trailing whitespace and internal runs collapse to a single space so
  // padded variants share one cache entry.
  assert.deepEqual(pure.validateSearchQuery("  lofi   beats  "), { ok: true, query: "lofi beats" });
  assert.deepEqual(pure.validateSearchQuery("\tlofi\nbeats "), { ok: true, query: "lofi beats" });
});

test("validateSearchQuery rejects empty and too-short queries", () => {
  // A 1-char query costs a full 100 quota units for near-random results.
  for (const bad of ["", " ", "   ", "a", " x "]) {
    const r = pure.validateSearchQuery(bad);
    assert.equal(r.ok, false, `expected rejection for ${JSON.stringify(bad)}`);
    assert.match(r.error, /at least/);
  }
  // Exactly at the minimum is allowed (boundary).
  assert.equal(pure.validateSearchQuery("ab").ok, true);
});

test("validateSearchQuery rejects over-long queries at the boundary", () => {
  const atLimit = "a".repeat(pure.SEARCH_QUERY_MAX_LEN);
  const overLimit = "a".repeat(pure.SEARCH_QUERY_MAX_LEN + 1);
  assert.equal(pure.validateSearchQuery(atLimit).ok, true);
  const r = pure.validateSearchQuery(overLimit);
  assert.equal(r.ok, false);
  assert.match(r.error, /at most/);
});

test("validateSearchQuery rejects malformed (non-string) input without throwing", () => {
  for (const bad of [null, undefined, 42, {}, [], true, () => {}]) {
    const r = pure.validateSearchQuery(bad);
    assert.equal(r.ok, false, `expected rejection for ${String(bad)}`);
    assert.equal(r.error, "Query must be a string");
  }
});

test("validateSearchQuery strips control characters rather than forwarding them", () => {
  // A NUL or ESC in a query has no search meaning and must not reach the
  // outbound URL or the cache key.
  const r = pure.validateSearchQuery("lofi\u0000\u001b beats");
  assert.equal(r.ok, true);
  assert.equal(r.query, "lofi beats");
  assert.ok(!r.query.includes("\u0000"));
});

test("validateSearchQuery preserves non-Latin queries", () => {
  // 2 chars is the minimum precisely so short CJK queries stay usable.
  assert.deepEqual(pure.validateSearchQuery("音楽"), { ok: true, query: "音楽" });
  assert.deepEqual(pure.validateSearchQuery(" مرحبا "), { ok: true, query: "مرحبا" });
});

// ── maxResults clamping ──────────────────────────────────────────────────────

test("clampMaxResults defaults, clamps, and floors", () => {
  assert.equal(pure.clampMaxResults(undefined), pure.SEARCH_MAX_RESULTS_DEFAULT);
  assert.equal(pure.clampMaxResults(null),      pure.SEARCH_MAX_RESULTS_DEFAULT);
  assert.equal(pure.clampMaxResults("abc"),     pure.SEARCH_MAX_RESULTS_DEFAULT);
  assert.equal(pure.clampMaxResults(NaN),       pure.SEARCH_MAX_RESULTS_DEFAULT);
  assert.equal(pure.clampMaxResults(Infinity),  pure.SEARCH_MAX_RESULTS_DEFAULT);

  // Regression: Number(null) / Number("") / Number([]) are all 0, which would be
  // clamped to 1 and silently return a single result for a request that simply
  // omitted the field. These must fall back to the default instead.
  assert.equal(pure.clampMaxResults(""),    pure.SEARCH_MAX_RESULTS_DEFAULT);
  assert.equal(pure.clampMaxResults([]),    pure.SEARCH_MAX_RESULTS_DEFAULT);
  assert.equal(pure.clampMaxResults(true),  pure.SEARCH_MAX_RESULTS_DEFAULT);
  assert.equal(pure.clampMaxResults(false), pure.SEARCH_MAX_RESULTS_DEFAULT);
  assert.equal(pure.clampMaxResults({}),    pure.SEARCH_MAX_RESULTS_DEFAULT);

  assert.equal(pure.clampMaxResults(5), 5);
  assert.equal(pure.clampMaxResults("5"), 5); // numeric strings from JSON
  assert.equal(pure.clampMaxResults(7.9), 7); // floored

  // Clamped at both ends — a client cannot request 500 rows.
  assert.equal(pure.clampMaxResults(0), 1);
  assert.equal(pure.clampMaxResults(-10), 1);
  assert.equal(pure.clampMaxResults(500), pure.SEARCH_MAX_RESULTS_LIMIT);
});

// ── Cache key ────────────────────────────────────────────────────────────────

test("searchCacheKey collapses case so duplicate searches share one entry", () => {
  assert.equal(pure.searchCacheKey("Lofi Beats", 10), pure.searchCacheKey("lofi beats", 10));
  // maxResults is part of the key: a 5-row answer must not be served for a
  // 15-row request.
  assert.notEqual(pure.searchCacheKey("lofi", 5), pure.searchCacheKey("lofi", 15));
  // Distinct queries must not collide.
  assert.notEqual(pure.searchCacheKey("lofi", 10), pure.searchCacheKey("jazz", 10));
});

test("searchCacheKey separator prevents query/count collisions", () => {
  // Without a delimiter, ("a", 11) and ("a1", 1) would both stringify to "a11".
  assert.notEqual(pure.searchCacheKey("a", 11), pure.searchCacheKey("a1", 1));
});

// ── Outbound URL construction ────────────────────────────────────────────────

test("buildYouTubeSearchUrl targets the official API with quota-minimal params", () => {
  const url = new URL(pure.buildYouTubeSearchUrl({
    query: "lofi beats",
    maxResults: 10,
    apiKey: "TEST_KEY",
  }));

  // Official YouTube Data API v3 — not a scraper or unofficial mirror.
  assert.equal(url.origin, "https://www.googleapis.com");
  assert.equal(url.pathname, "/youtube/v3/search");

  assert.equal(url.searchParams.get("part"), "snippet");
  assert.equal(url.searchParams.get("q"), "lofi beats");
  assert.equal(url.searchParams.get("maxResults"), "10");
  // type=video excludes channels/playlists, which carry no videoId.
  assert.equal(url.searchParams.get("type"), "video");
  // Non-embeddable videos cannot play in the Watch Together IFrame player.
  assert.equal(url.searchParams.get("videoEmbeddable"), "true");
  assert.equal(url.searchParams.get("safeSearch"), "moderate");

  // fields= narrows the payload to exactly what the UI renders.
  const fields = url.searchParams.get("fields");
  assert.match(fields, /id\/videoId/);
  assert.match(fields, /channelTitle/);

  // Pagination is never requested — it would multiply the 100-unit cost.
  assert.equal(url.searchParams.get("pageToken"), null);
});

test("buildYouTubeSearchUrl percent-encodes the query and omits regionCode when unset", () => {
  const url = new URL(pure.buildYouTubeSearchUrl({
    query: "cats & dogs?=#",
    maxResults: 3,
    apiKey: "TEST_KEY",
  }));
  // The raw query must not break out into extra URL parameters.
  assert.equal(url.searchParams.get("query"), null);
  assert.equal(url.searchParams.get("q"), "cats & dogs?=#");
  assert.equal(url.searchParams.get("regionCode"), null);

  const withRegion = new URL(pure.buildYouTubeSearchUrl({
    query: "x y", maxResults: 3, apiKey: "TEST_KEY", regionCode: "US",
  }));
  assert.equal(withRegion.searchParams.get("regionCode"), "US");
});

// ── Response transformation ──────────────────────────────────────────────────

const SAMPLE = {
  // Extra top-level keys mimic what YouTube really returns; none may survive.
  kind: "youtube#searchListResponse",
  etag: "SOME_ETAG",
  nextPageToken: "CAoQAA",
  pageInfo: { totalResults: 1000000, resultsPerPage: 2 },
  items: [
    {
      kind: "youtube#searchResult",
      etag: "ITEM_ETAG",
      id: { kind: "youtube#video", videoId: "dQw4w9WgXcQ" },
      snippet: {
        publishedAt: "2009-10-25T06:57:33Z",
        channelId: "UCuAXFkgsw1L7xaCfnd5JJOw",
        title: "Rick Astley - Never Gonna Give You Up",
        description: "a description that the UI does not render",
        channelTitle: "Rick Astley",
        thumbnails: {
          default: { url: "https://i.ytimg.com/vi/dQw4w9WgXcQ/default.jpg" },
          medium:  { url: "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg" },
        },
      },
    },
  ],
};

test("transformYouTubeSearchResponse returns only the four UI fields", () => {
  const results = pure.transformYouTubeSearchResponse(SAMPLE);
  assert.equal(results.length, 1);
  assert.deepEqual(Object.keys(results[0]).sort(), ["channel", "thumbnail", "title", "videoId"]);
  assert.deepEqual(results[0], {
    videoId: "dQw4w9WgXcQ",
    title: "Rick Astley - Never Gonna Give You Up",
    channel: "Rick Astley",
    thumbnail: "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg",
  });
});

test("transformYouTubeSearchResponse drops upstream metadata (etag, tokens, description)", () => {
  // This is the allow-list property: nothing outside the four keys can leak,
  // including anything YouTube might add to the schema in future.
  const serialized = JSON.stringify(pure.transformYouTubeSearchResponse(SAMPLE));
  for (const leaked of ["etag", "SOME_ETAG", "nextPageToken", "CAoQAA", "description", "channelId", "publishedAt", "totalResults"]) {
    assert.ok(!serialized.includes(leaked), `response leaked ${leaked}`);
  }
});

test("transformYouTubeSearchResponse prefers medium thumbnail, falls back to default", () => {
  const noMedium = {
    items: [{
      id: { videoId: "dQw4w9WgXcQ" },
      snippet: {
        title: "t", channelTitle: "c",
        thumbnails: { default: { url: "https://i.ytimg.com/vi/x/default.jpg" } },
      },
    }],
  };
  assert.equal(
    pure.transformYouTubeSearchResponse(noMedium)[0].thumbnail,
    "https://i.ytimg.com/vi/x/default.jpg"
  );
});

test("transformYouTubeSearchResponse omits non-https thumbnails", () => {
  const cleartext = {
    items: [{
      id: { videoId: "dQw4w9WgXcQ" },
      snippet: {
        title: "t", channelTitle: "c",
        thumbnails: { medium: { url: "http://i.ytimg.com/vi/x/mqdefault.jpg" } },
      },
    }],
  };
  // Empty string, not the cleartext URL — the client must never be handed one.
  assert.equal(pure.transformYouTubeSearchResponse(cleartext)[0].thumbnail, "");
});

test("transformYouTubeSearchResponse handles empty results", () => {
  assert.deepEqual(pure.transformYouTubeSearchResponse({ items: [] }), []);
  assert.deepEqual(pure.transformYouTubeSearchResponse({ kind: "x", pageInfo: {} }), []);
});

test("transformYouTubeSearchResponse tolerates malformed bodies without throwing", () => {
  for (const bad of [null, undefined, {}, [], "string", 42, { items: null }, { items: "nope" }]) {
    assert.deepEqual(pure.transformYouTubeSearchResponse(bad), [], `failed on ${JSON.stringify(bad)}`);
  }
});

test("transformYouTubeSearchResponse drops items with an invalid or missing videoId", () => {
  const mixed = {
    items: [
      // Channel result: no videoId at all (would break the player on tap).
      { id: { kind: "youtube#channel", channelId: "UC123" }, snippet: { title: "A channel", channelTitle: "c" } },
      // Wrong length / bad alphabet.
      { id: { videoId: "short" },        snippet: { title: "t", channelTitle: "c" } },
      { id: { videoId: "waaaaytoolong" }, snippet: { title: "t", channelTitle: "c" } },
      { id: { videoId: "bad!@#$%^&*(" }, snippet: { title: "t", channelTitle: "c" } },
      { id: { videoId: 12345 },          snippet: { title: "t", channelTitle: "c" } },
      { id: null,                        snippet: { title: "t", channelTitle: "c" } },
      // Valid, and must survive alongside the junk.
      { id: { videoId: "dQw4w9WgXcQ" },  snippet: { title: "good", channelTitle: "c" } },
    ],
  };
  const results = pure.transformYouTubeSearchResponse(mixed);
  assert.equal(results.length, 1);
  assert.equal(results[0].videoId, "dQw4w9WgXcQ");
});

test("transformYouTubeSearchResponse drops items with no usable title", () => {
  const untitled = {
    items: [
      { id: { videoId: "dQw4w9WgXcQ" }, snippet: { title: "   ", channelTitle: "c" } },
      { id: { videoId: "dQw4w9WgXcR" }, snippet: { channelTitle: "c" } },
      { id: { videoId: "dQw4w9WgXcS" }, snippet: {} },
      { id: { videoId: "dQw4w9WgXcT" } },
    ],
  };
  assert.deepEqual(pure.transformYouTubeSearchResponse(untitled), []);
});

test("transformYouTubeSearchResponse defaults a missing channel to an empty string", () => {
  const noChannel = { items: [{ id: { videoId: "dQw4w9WgXcQ" }, snippet: { title: "t" } }] };
  const r = pure.transformYouTubeSearchResponse(noChannel);
  assert.equal(r.length, 1);
  assert.equal(r[0].channel, "");
  assert.equal(r[0].thumbnail, "");
});

// ── Credential containment ───────────────────────────────────────────────────

test("the transformed response never contains the API key", () => {
  // The key travels in the request URL. Prove that nothing derived from a
  // response body can carry it, even if YouTube echoed it back to us.
  const echoed = {
    etag: "key=SUPER_SECRET_KEY",
    items: [{
      id: { videoId: "dQw4w9WgXcQ" },
      snippet: { title: "t", channelTitle: "c", description: "key=SUPER_SECRET_KEY" },
    }],
  };
  const serialized = JSON.stringify(pure.transformYouTubeSearchResponse(echoed));
  assert.ok(!serialized.includes("SUPER_SECRET_KEY"));
});

test("redactApiKey removes the credential from log-bound strings", () => {
  const withKey = "request to https://www.googleapis.com/youtube/v3/search?q=x&key=SUPER_SECRET_KEY failed";
  const out = pure.redactApiKey(withKey);
  assert.ok(!out.includes("SUPER_SECRET_KEY"), "key survived redaction");
  assert.match(out, /key=\[REDACTED\]/);
  // Surrounding context is preserved so the log stays useful.
  assert.match(out, /googleapis\.com/);
});

test("redactApiKey handles JSON-ish and quoted forms, and non-strings", () => {
  assert.ok(!pure.redactApiKey('{"key":"SECRET123"}').includes("SECRET123"));
  assert.ok(!pure.redactApiKey("?key=SECRET123&part=snippet").includes("SECRET123"));
  // Other params must survive so the log remains diagnosable.
  assert.match(pure.redactApiKey("?key=SECRET123&part=snippet"), /part=snippet/);
  // Never throws on non-strings (error.message can be undefined).
  assert.equal(pure.redactApiKey(undefined), "");
  assert.equal(pure.redactApiKey(null), "");
  assert.equal(pure.redactApiKey(42), "");
});

// ── Upstream error mapping ───────────────────────────────────────────────────

test("mapYouTubeError converts quota exhaustion into a retryable 503", () => {
  // The Data API reports quota exhaustion as 403, which would otherwise be
  // shown to the user as an auth error.
  for (const upstream of [403, 429]) {
    const mapped = pure.mapYouTubeError(upstream);
    assert.equal(mapped.status, 503);
    assert.match(mapped.error, /temporarily unavailable/i);
  }
});

test("mapYouTubeError maps 400 to a client error and everything else to 502", () => {
  assert.equal(pure.mapYouTubeError(400).status, 400);
  for (const upstream of [500, 502, 503, 418, 0, undefined]) {
    assert.equal(pure.mapYouTubeError(upstream).status, 502);
  }
});

test("mapYouTubeError never echoes upstream detail to the client", () => {
  // Messages must be static strings — no interpolation of upstream bodies,
  // which can contain the request URL, project number, or reason codes.
  for (const upstream of [400, 403, 429, 500, 502]) {
    const { error } = pure.mapYouTubeError(upstream);
    assert.equal(typeof error, "string");
    assert.ok(!/key|quota|project|googleapis|http/i.test(error), `leaky message: ${error}`);
  }
});
