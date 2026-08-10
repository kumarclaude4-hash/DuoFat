package com.duoshield.app.call.watch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses responses from the DuoShield push server's {@code POST /youtubeSearch} endpoint.
 *
 * <p>Depends only on {@code org.json} (bundled with Android, and added as a real
 * {@code testImplementation} dependency so these functions are genuinely exercised on the
 * JVM rather than against the android.jar stub). No Android or Firebase imports, so the
 * whole class is unit testable — see {@code YouTubeSearchParserTest}.
 *
 * <p><strong>Everything here is deliberately total.</strong> No method throws and no method
 * returns {@code null} where a caller would have to null-check a list: a malformed body, a
 * truncated body, a plain-text body, and an HTML error page from an interposing proxy all
 * degrade to "no results" or a generic message. A search screen must never crash the
 * Watch Together Activity that hosts it, because that Activity is running mid-call.
 */
public final class YouTubeSearchParser {

    /** Success body keys, per the endpoint contract. */
    private static final String K_RESULTS   = "results";
    private static final String K_CACHED    = "cached";
    private static final String K_ERROR     = "error";
    private static final String K_VIDEO_ID  = "videoId";
    private static final String K_TITLE     = "title";
    private static final String K_CHANNEL   = "channel";
    private static final String K_THUMBNAIL = "thumbnail";

    /** Sentinel status used by the client for "the request never completed" (no HTTP status). */
    public static final int STATUS_NETWORK_FAILURE = 0;

    /** Sentinel status for "this build has no push server URL configured". */
    public static final int STATUS_NOT_CONFIGURED = -1;

    private YouTubeSearchParser() {}

    // ── Success body ──────────────────────────────────────────────────────────

    /**
     * Extracts the result rows from a 200 body.
     *
     * <p>Always returns a list, never {@code null}. Individual rows that fail
     * {@link YouTubeSearchResult#of} are dropped rather than repaired, so a partially
     * corrupt page still yields the usable rows instead of failing wholesale — the failure
     * mode a user actually wants when 9 of 10 results are fine.
     */
    public static List<YouTubeSearchResult> parseResults(String body) {
        JSONObject root = asJsonObject(body);
        if (root == null) return Collections.emptyList();

        JSONArray arr = root.optJSONArray(K_RESULTS);
        if (arr == null) return Collections.emptyList();

        List<YouTubeSearchResult> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue; // a string/number/null in the array — skip it
            YouTubeSearchResult r = YouTubeSearchResult.of(
                    item.optString(K_VIDEO_ID, ""),
                    item.optString(K_TITLE, ""),
                    item.optString(K_CHANNEL, ""),
                    item.optString(K_THUMBNAIL, ""));
            if (r != null) out.add(r);
        }
        return out;
    }

    /**
     * Reads the informational {@code cached} flag. Defaults to {@code false} when absent or
     * not a boolean — it only ever drives a debug log, never behaviour.
     */
    public static boolean wasCached(String body) {
        JSONObject root = asJsonObject(body);
        return root != null && root.optBoolean(K_CACHED, false);
    }

    // ── Error body ────────────────────────────────────────────────────────────

    /**
     * Pulls the server's {@code error} string out of a body, or {@code null} when there
     * isn't one.
     *
     * <p>The endpoint returns JSON for most failures but <strong>plain text</strong> for
     * {@code 413} (shared body-size guard) and {@code 500} (shared error helper, whose text
     * carries a log correlation id). Both land here as "not JSON" → {@code null}, which is
     * why {@link #messageForStatus} must always have a static fallback.
     */
    public static String extractServerError(String body) {
        JSONObject root = asJsonObject(body);
        if (root == null) return null;
        String err = root.optString(K_ERROR, "");
        return err.trim().isEmpty() ? null : err.trim();
    }

    /**
     * Maps an HTTP status (plus whatever body came with it) onto one user-facing sentence.
     *
     * <p><strong>Which statuses may echo the server's own message.</strong> Only the ones
     * whose messages are static, deliberately-worded strings on the server side
     * ({@code mapYouTubeError} and the validation gates), all of which are asserted by
     * server tests to contain no credential, quota, project, or upstream-URL fragments.
     * {@code 500}'s text embeds a log reference id and {@code 413}'s is a bare internal
     * string, so those two are replaced with generic copy instead of being shown.
     *
     * <p>This is the client half of the "never echo upstream detail" rule: even if a future
     * server change started leaking detail in a body, only these allow-listed statuses could
     * surface it, and the two plain-text statuses could not surface anything at all.
     */
    public static String messageForStatus(int status, String body) {
        String serverMessage = extractServerError(body);

        switch (status) {
            case STATUS_NOT_CONFIGURED:
                return "Search isn't available in this build. Paste a YouTube link instead.";
            case STATUS_NETWORK_FAILURE:
                return "No connection. Check your network and try again.";

            case 400:
                // Validation errors ("Query must be at least 2 characters", "Invalid search
                // query") are already phrased for a user, and the client pre-checks length,
                // so reaching here usually means YouTube itself rejected the terms.
                return serverMessage != null ? serverMessage : "That search didn't work. Try different words.";

            case 401:
                // The ID token was missing/expired/rejected. Not actionable as-is, so the
                // server's terse "Unauthorized" is replaced with something a user can act on.
                return "Couldn't verify your account. Sign out and back in, then try again.";

            case 413:
                // Plain text on the wire — never echoed.
                return "That search is too long. Try fewer words.";

            case 429:
                return serverMessage != null ? serverMessage : "Too many searches — try again in a minute.";

            case 502:
                return serverMessage != null ? serverMessage : "Search failed. Try again.";

            case 503:
                // Two distinct causes share this status: the key is unset on the server, or
                // the shared daily YouTube quota is exhausted. Both are "not your fault, try
                // later, paste a link meanwhile", and the server already words them that way.
                return serverMessage != null
                        ? serverMessage + " You can still paste a YouTube link."
                        : "Search is unavailable right now. You can still paste a YouTube link.";

            case 504:
                return serverMessage != null ? serverMessage : "Search timed out. Try again.";

            case 500:
                // Plain text carrying a log correlation id — never echoed.
                return "Something went wrong on our side. Try again.";

            default:
                if (status >= 500) return "Search is unavailable right now. Try again later.";
                return "Search failed. Try again.";
        }
    }

    /**
     * True when retrying the identical query could plausibly succeed. Drives whether the
     * error state offers a "Retry" affordance instead of just an explanation.
     */
    public static boolean isRetryable(int status) {
        switch (status) {
            case STATUS_NOT_CONFIGURED:
            case 400:
            case 413:
                return false; // nothing the user can change by tapping again
            default:
                return true;  // network, 401 (token refresh), 429, 5xx
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Parses a body into a {@link JSONObject}, or {@code null} for anything that is not a
     * JSON object — plain text, HTML, a bare JSON array, empty, or {@code null}.
     */
    private static JSONObject asJsonObject(String body) {
        if (body == null) return null;
        String trimmed = body.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '{') return null;
        try {
            return new JSONObject(trimmed);
        } catch (Exception e) {
            // org.json throws JSONException, but a truncated body can also surface as a
            // StringIndexOutOfBounds in some implementations. Catch broadly: there is no
            // recovery either way, and this must not propagate into a live call screen.
            return null;
        }
    }
}
