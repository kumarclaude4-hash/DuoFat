package com.duoshield.app.util;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.duoshield.app.BuildConfig;
import com.duoshield.app.call.watch.YouTubeSearchParser;
import com.duoshield.app.call.watch.YouTubeSearchResult;
import com.duoshield.app.call.watch.YouTubeSearchState;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Searches YouTube for the Watch Together picker by calling the DuoShield push server's
 * authenticated {@code POST /youtubeSearch} endpoint.
 *
 * <p><strong>There is no YouTube API key in this class, in this module, or anywhere in the
 * APK.</strong> The credential lives only in the push server's environment. The client sends
 * a query string and a Firebase ID token; it receives at most
 * {@code {videoId, title, channel, thumbnail}} rows. This is the entire reason the extra hop
 * exists — a key shipped in an APK is extractable no matter how it is stored or obfuscated,
 * and YouTube quota is a shared exhaustible resource, so a leaked key breaks search for every
 * user until it is rotated.
 *
 * <p>Modelled directly on {@link LinkPreviewFetcher}: same authenticated-POST-to-our-own-server
 * shape, same {@code getIdTokenSync} pattern, same bounded in-memory cache, same
 * callbacks-on-the-main-thread contract. Deliberately no new HTTP dependency.
 *
 * <p><strong>Why a client-side cache on top of the server's.</strong> The server caches for
 * 10 minutes, so a repeat query costs no YouTube quota — but it still costs a round trip, a
 * token fetch, and a visible spinner. Caching locally makes re-opening a previous query
 * instant and keeps the request off the wire entirely, which also means the per-user 6/min
 * rate limit is not consumed by navigation.
 */
public final class YouTubeSearchClient {

    /** Endpoint path on the push server. */
    private static final String PATH = "/youtubeSearch";

    private static final int CONNECT_TIMEOUT_MS = 8_000;

    /**
     * Read timeout slightly above the server's own 8 s upstream abort, so a server that is
     * about to answer with a clean 504 gets the chance to, instead of the client giving up
     * first and reporting a less specific network failure.
     */
    private static final int READ_TIMEOUT_MS = 12_000;

    /** Mirrors the server's cache TTL — no point holding a staler answer than it would. */
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    /** Small on purpose: a call-scoped picker, not a browsing history. */
    private static final int MAX_CACHE = 24;

    // ── Callback contract ─────────────────────────────────────────────────────

    public interface Callback {
        /**
         * A successful search. {@code results} is never {@code null} and may be empty, which
         * means "no matches" rather than "something went wrong".
         */
        void onResults(List<YouTubeSearchResult> results, boolean cached);

        /**
         * A failed search. {@code status} is an HTTP status, or
         * {@link YouTubeSearchParser#STATUS_NETWORK_FAILURE} /
         * {@link YouTubeSearchParser#STATUS_NOT_CONFIGURED} for the two non-HTTP cases.
         * {@code message} is already user-facing.
         */
        void onError(int status, String message);
    }

    private static final class Entry {
        final List<YouTubeSearchResult> results;
        final long expiresAtRealtime;

        Entry(List<YouTubeSearchResult> results, long expiresAtRealtime) {
            this.results = results;
            this.expiresAtRealtime = expiresAtRealtime;
        }
    }

    /**
     * Insertion-ordered so the oldest entry is the first to evict.
     *
     * <p>Guarded by {@code synchronized} rather than being a {@code ConcurrentHashMap}:
     * get-then-evict-then-put must be atomic as a group or the size cap can be exceeded, and
     * a {@code LinkedHashMap} is not thread-safe for iteration during modification anyway.
     */
    private static final Map<String, Entry> cache = new LinkedHashMap<>();

    /**
     * Single thread on purpose. Searches are user-initiated and sequential; a pool would let
     * an abandoned request race a newer one to the callback. Ordering is still not guaranteed
     * at the UI layer — {@link YouTubeSearchState}'s token check is the real defence — but one
     * thread keeps the wire traffic honest and bounds concurrent token fetches to one.
     */
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private YouTubeSearchClient() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Runs a search. The query must already be normalised via
     * {@link YouTubeSearchState#normalizeQuery} and within bounds — the caller owns that check
     * so a too-short query never reaches the network.
     *
     * <p>The callback always fires on the main thread, exactly once.
     */
    public static void search(String normalizedQuery, int maxResults, final Callback callback) {
        if (callback == null) return;

        final String query = normalizedQuery == null ? "" : normalizedQuery;
        if (!YouTubeSearchState.isSearchable(query)) {
            // Should not happen — the Activity gates on this — but never silently no-op.
            callback.onError(400, YouTubeSearchParser.messageForStatus(400, null));
            return;
        }

        final String base = BuildConfig.PUSH_SERVER_URL == null ? "" : BuildConfig.PUSH_SERVER_URL.trim();
        if (base.isEmpty()) {
            // A debug build with no PUSH_SERVER_URL. Fail with a distinct status so the UI can
            // say "paste a link instead" rather than blaming the network.
            callback.onError(YouTubeSearchParser.STATUS_NOT_CONFIGURED,
                    YouTubeSearchParser.messageForStatus(YouTubeSearchParser.STATUS_NOT_CONFIGURED, null));
            return;
        }

        final String key = cacheKey(query, maxResults);
        List<YouTubeSearchResult> hit = cacheGet(key);
        if (hit != null) {
            callback.onResults(hit, true);
            return;
        }

        executor.execute(new Runnable() {
            @Override
            public void run() {
                final Response response = execute(base, query, maxResults);
                if (response.ok) {
                    cachePut(key, response.results);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onResults(response.results, response.cached);
                        }
                    });
                } else {
                    // Failures are never cached: a 429 or a dropped connection must not become
                    // a sticky "no results" for the next ten minutes.
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(response.status, response.message);
                        }
                    });
                }
            }
        });
    }

    /** Call on sign-out, app wipe, or when a Watch Together session ends. */
    public static void clearCache() {
        synchronized (cache) {
            cache.clear();
        }
    }

    // ── Cache ─────────────────────────────────────────────────────────────────

    /**
     * Mirrors the server's key shape: lowercased query, NUL separator, then {@code maxResults}.
     * The NUL stops {@code ("a", 11)} colliding with {@code ("a1", 1)}, and including
     * {@code maxResults} stops a 5-row answer being served to a 15-row request.
     */
    private static String cacheKey(String query, int maxResults) {
        return query.toLowerCase() + '\u0000' + maxResults;
    }

    private static List<YouTubeSearchResult> cacheGet(String key) {
        synchronized (cache) {
            Entry e = cache.get(key);
            if (e == null) return null;
            // elapsedRealtime, not currentTimeMillis: a user changing the device clock must
            // not make a cached answer immortal or instantly stale.
            if (SystemClock.elapsedRealtime() > e.expiresAtRealtime) {
                cache.remove(key);
                return null;
            }
            return e.results;
        }
    }

    private static void cachePut(String key, List<YouTubeSearchResult> results) {
        synchronized (cache) {
            if (cache.size() >= MAX_CACHE) {
                java.util.Iterator<String> it = cache.keySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            cache.put(key, new Entry(results, SystemClock.elapsedRealtime() + CACHE_TTL_MS));
        }
    }

    // ── Networking ────────────────────────────────────────────────────────────

    private static final class Response {
        boolean ok;
        boolean cached;
        int status;
        String message;
        List<YouTubeSearchResult> results = Collections.emptyList();

        static Response success(List<YouTubeSearchResult> results, boolean cached) {
            Response r = new Response();
            r.ok = true;
            r.status = 200;
            r.cached = cached;
            r.results = results;
            return r;
        }

        static Response failure(int status, String body) {
            Response r = new Response();
            r.ok = false;
            r.status = status;
            r.message = YouTubeSearchParser.messageForStatus(status, body);
            return r;
        }
    }

    /** Runs on the executor thread. Never throws. */
    private static Response execute(String base, String query, int maxResults) {
        HttpURLConnection conn = null;
        try {
            String idToken = getIdTokenSync();
            if (idToken == null) {
                // No signed-in user or the token refresh failed. Same remedy either way.
                return Response.failure(401, null);
            }

            URL endpoint = new URL(base + PATH);
            conn = (HttpURLConnection) endpoint.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + idToken);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            JSONObject body = new JSONObject();
            body.put("q", query);
            body.put("maxResults", maxResults);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes("UTF-8"));
            }

            int status = conn.getResponseCode();
            String responseBody = readBody(conn, status);

            if (status == HttpURLConnection.HTTP_OK) {
                return Response.success(
                        YouTubeSearchParser.parseResults(responseBody),
                        YouTubeSearchParser.wasCached(responseBody));
            }
            return Response.failure(status, responseBody);
        } catch (Exception e) {
            // Any transport-level problem: DNS, TLS, timeout, airplane mode, malformed URL.
            //
            // The exception is deliberately NOT logged. It embeds the request URL, and while
            // this endpoint's URL carries no credential, the query string it describes is the
            // user's search term — and in a privacy tool, what someone searched for is
            // sensitive. The server applies the same rule to its own logs.
            return Response.failure(YouTubeSearchParser.STATUS_NETWORK_FAILURE, null);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Reads the response body, from the error stream for non-2xx.
     *
     * <p>{@code HttpURLConnection.getInputStream()} throws on a 4xx/5xx, which would otherwise
     * discard the server's own error message and turn every failure into a generic one.
     */
    private static String readBody(HttpURLConnection conn, int status) {
        InputStream in = null;
        try {
            in = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Synchronously retrieves the current user's Firebase ID token.
     *
     * <p>Same lock/notify pattern as {@link LinkPreviewFetcher#getIdTokenSync} — safe on a
     * background thread, and never called from the main thread.
     */
    private static String getIdTokenSync() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return null;

        final String[] holder = {null};
        final Object lock = new Object();
        user.getIdToken(false)
            .addOnSuccessListener(r -> {
                synchronized (lock) { holder[0] = r.getToken(); lock.notifyAll(); }
            })
            .addOnFailureListener(e -> {
                synchronized (lock) { lock.notifyAll(); }
            });
        synchronized (lock) {
            if (holder[0] == null) {
                try { lock.wait(8_000); } catch (InterruptedException ignored) {}
            }
        }
        return holder[0];
    }
}
