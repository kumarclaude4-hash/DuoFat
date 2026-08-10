package com.duoshield.app.call.watch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The YouTube search UI state machine for {@link WatchTogetherActivity}.
 *
 * <p>Pure Java — no Android imports — so every transition is unit testable on the JVM, the
 * same reason {@link WatchTogetherState} has no Android dependencies. The Activity owns one
 * instance, mutates it only from the main thread, and renders whatever {@link #phase} says.
 *
 * <p><strong>Why a token instead of a "cancel" flag.</strong> Searches are dispatched to a
 * background executor and can complete out of order: a slow request for "lofi" can land
 * after a fast request for "lofi beats". Every dispatch takes a monotonically increasing
 * token, and a response is only applied when {@link #isCurrent(long)} still holds. That
 * makes stale responses inert rather than requiring the in-flight request to be killable —
 * which an {@code HttpURLConnection} on a shared executor is not, cheaply.
 *
 * <p><strong>Why the query bounds live here.</strong> They mirror the server's
 * {@code validateSearchQuery} (min 2, max 100 characters after whitespace collapsing) so a
 * query the server would reject with a 400 is never sent at all. The server remains the
 * enforcement point — this is purely about not burning a round trip, and not spending 100
 * units of a shared 10,000/day YouTube quota on a query that cannot be answered usefully.
 */
public final class YouTubeSearchState {

    /** Mirrors the server's {@code SEARCH_QUERY_MIN_LEN}. */
    public static final int MIN_QUERY_LEN = 2;

    /** Mirrors the server's {@code SEARCH_QUERY_MAX_LEN}. */
    public static final int MAX_QUERY_LEN = 100;

    /**
     * How long the user must stop typing before an automatic search fires.
     *
     * <p>450 ms is long enough that typing "lofi beats" costs one search rather than ten.
     * Each avoided search is 100 units of a shared daily quota, so this is a cost control
     * as much as a UX one. Pressing the button or the IME action bypasses it entirely.
     */
    public static final long DEBOUNCE_MS = 450L;

    /** How many results to request. Server clamps to [1, 15]. */
    public static final int PAGE_SIZE = 12;

    /** What the search area should be showing. */
    public enum Phase {
        /** Nothing typed, or the panel is closed. Search UI hidden. */
        IDLE,
        /** Something typed but shorter than {@link #MIN_QUERY_LEN}. Hint shown, no request. */
        TOO_SHORT,
        /** A request is in flight. Spinner shown. */
        LOADING,
        /** At least one result. List shown. */
        RESULTS,
        /** Request succeeded with zero matches. "No results" shown. */
        EMPTY,
        /** Request failed. {@link #message} shown. */
        ERROR
    }

    private Phase phase = Phase.IDLE;
    private String message = "";
    private List<YouTubeSearchResult> results = Collections.emptyList();

    /** The query whose response is currently displayed (RESULTS/EMPTY) or in flight (LOADING). */
    private String activeQuery = "";

    /** Monotonic dispatch counter. 0 means "nothing has ever been dispatched". */
    private long token = 0L;

    // ── Query normalisation / validation (static, no instance state) ───────────

    /**
     * Collapses the query the same way the server does, so the client-side cache key and
     * the server-side cache key agree: control characters become spaces, whitespace runs
     * collapse to one space, then trim.
     *
     * <p>Without matching normalisation, {@code "lofi  beats"} and {@code "lofi beats"}
     * would be two client cache entries for one server cache entry — a wasted round trip
     * that looks like a cache miss for no reason.
     */
    public static String normalizeQuery(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        boolean pendingSpace = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            // C0/C1 control characters count as whitespace rather than being rejected: a
            // stray newline from a paste should not cost the user an error.
            boolean isSpace = c <= 0x1f || (c >= 0x7f && c <= 0x9f) || Character.isWhitespace(c);
            if (isSpace) {
                if (sb.length() > 0) pendingSpace = true;
            } else {
                if (pendingSpace) {
                    sb.append(' ');
                    pendingSpace = false;
                }
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** True when a normalised query is inside the bounds the server will accept. */
    public static boolean isSearchable(String normalized) {
        if (normalized == null) return false;
        int len = normalized.length();
        return len >= MIN_QUERY_LEN && len <= MAX_QUERY_LEN;
    }

    /**
     * Trims an over-long query to {@link #MAX_QUERY_LEN} so a long paste searches its first
     * 100 characters instead of returning a 400. Anything shorter is returned unchanged.
     */
    public static String clampQuery(String normalized) {
        if (normalized == null) return "";
        return normalized.length() > MAX_QUERY_LEN
                ? normalized.substring(0, MAX_QUERY_LEN)
                : normalized;
    }

    // ── Read-only accessors ───────────────────────────────────────────────────

    public Phase phase() {
        return phase;
    }

    /** User-facing text for {@link Phase#ERROR}; {@code ""} otherwise. */
    public String message() {
        return message;
    }

    /** Never {@code null}; empty unless {@link #phase} is {@link Phase#RESULTS}. */
    public List<YouTubeSearchResult> results() {
        return results;
    }

    public String activeQuery() {
        return activeQuery;
    }

    public long currentToken() {
        return token;
    }

    /** True when the search area (list/spinner/message) should be visible at all. */
    public boolean isPanelVisible() {
        return phase != Phase.IDLE;
    }

    public boolean isLoading() {
        return phase == Phase.LOADING;
    }

    // ── Transitions ───────────────────────────────────────────────────────────

    /**
     * Decides whether a normalised query is worth dispatching.
     *
     * <p>Returns {@code false} for an unsearchable query, and for a repeat of the query
     * already in flight or already displayed — the second case is what stops a debounce
     * timer that fires after the user pressed the button from spending a second 100-unit
     * search on an answer already on screen. A previous ERROR is always retryable, so the
     * same query after a failure does dispatch.
     */
    public boolean shouldDispatch(String normalized) {
        if (!isSearchable(normalized)) return false;
        boolean sameQuery = normalized.equals(activeQuery);
        if (!sameQuery) return true;
        return phase != Phase.LOADING && phase != Phase.RESULTS && phase != Phase.EMPTY;
    }

    /**
     * Marks a query as in flight and returns its token. The caller must pass that token
     * back to {@link #onResults} / {@link #onError} so a stale response can be discarded.
     */
    public long beginSearch(String normalized) {
        phase = Phase.LOADING;
        message = "";
        results = Collections.emptyList();
        activeQuery = normalized == null ? "" : normalized;
        return ++token;
    }

    /** True when {@code responseToken} belongs to the most recent dispatch. */
    public boolean isCurrent(long responseToken) {
        return responseToken == token && token != 0L;
    }

    /**
     * Applies a successful response. Ignored (returns {@code false}) when the token is
     * stale, so a late response for an abandoned query cannot overwrite the screen.
     */
    public boolean onResults(long responseToken, List<YouTubeSearchResult> incoming) {
        if (!isCurrent(responseToken)) return false;
        List<YouTubeSearchResult> safe =
                incoming == null ? Collections.<YouTubeSearchResult>emptyList()
                                 : new ArrayList<>(incoming);
        results = Collections.unmodifiableList(safe);
        // Zero results is a successful answer, not a failure — it gets its own phase so the
        // UI can say "no matches" rather than showing a scary error.
        phase = safe.isEmpty() ? Phase.EMPTY : Phase.RESULTS;
        message = "";
        return true;
    }

    /** Applies a failure. Ignored (returns {@code false}) when the token is stale. */
    public boolean onError(long responseToken, String userMessage) {
        if (!isCurrent(responseToken)) return false;
        results = Collections.emptyList();
        phase = Phase.ERROR;
        message = (userMessage == null || userMessage.trim().isEmpty())
                ? "Search failed. Try again."
                : userMessage.trim();
        return true;
    }

    /**
     * Reflects a query too short to search: no request, no error, just a hint.
     *
     * <p>Bumps the token so a response for the longer query the user just backspaced away
     * from cannot land and repopulate the list underneath them.
     */
    public void markTooShort() {
        phase = Phase.TOO_SHORT;
        message = "";
        results = Collections.emptyList();
        activeQuery = "";
        token++;
    }

    /**
     * Returns to {@link Phase#IDLE} and hides the panel — used when the field is cleared, a
     * result is selected, or the user dismisses search.
     *
     * <p>Also bumps the token, so an in-flight response cannot re-open the panel after the
     * user has moved on (e.g. tapped a result and started playback).
     */
    public void reset() {
        phase = Phase.IDLE;
        message = "";
        results = Collections.emptyList();
        activeQuery = "";
        token++;
    }

    /**
     * Maps the current phase onto the video id of a result at {@code position}, or
     * {@code null} when that position is not a live, selectable result.
     *
     * <p>This is the one function that turns a tap into the single value the existing Watch
     * Together session needs. Bounds- and phase-checked here rather than in the Activity so
     * a stale click on a list that has just been replaced cannot start the wrong video.
     */
    public String videoIdAt(int position) {
        if (phase != Phase.RESULTS) return null;
        if (position < 0 || position >= results.size()) return null;
        YouTubeSearchResult r = results.get(position);
        return (r == null || !YouTubeUrlParser.isValidVideoId(r.videoId)) ? null : r.videoId;
    }
}
