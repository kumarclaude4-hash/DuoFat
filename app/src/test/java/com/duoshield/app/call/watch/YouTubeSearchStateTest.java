package com.duoshield.app.call.watch;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link YouTubeSearchState} — the search UI state machine.
 *
 * <p>The interesting behaviour is not "does LOADING become RESULTS" but the three things that
 * cost real money or produce visible bugs: duplicate dispatch suppression (each avoided
 * search is 100 units of a shared 10,000/day quota), stale-response rejection (out-of-order
 * completions overwriting the screen), and result → {@code videoId} mapping (the only value
 * handed to the existing Watch Together session).
 */
public class YouTubeSearchStateTest {

    private static final String ID  = "dQw4w9WgXcQ";
    private static final String ID2 = "9bZkp7q19f0";

    private YouTubeSearchState state;

    @Before
    public void setUp() {
        state = new YouTubeSearchState();
    }

    private static YouTubeSearchResult row(String id, String title) {
        return YouTubeSearchResult.of(id, title, "Chan", "https://t/" + id + ".jpg");
    }

    private static List<YouTubeSearchResult> rows(YouTubeSearchResult... rs) {
        return new ArrayList<>(Arrays.asList(rs));
    }

    // ── Query normalisation ───────────────────────────────────────────────────

    @Test
    public void normalizeCollapsesWhitespaceAndTrimsLikeTheServerDoes() {
        assertEquals("lofi beats", YouTubeSearchState.normalizeQuery("  lofi   beats  "));
        assertEquals("lofi beats", YouTubeSearchState.normalizeQuery("lofi\tbeats"));
        assertEquals("lofi beats", YouTubeSearchState.normalizeQuery("lofi\n\nbeats"));
        assertEquals("a b c", YouTubeSearchState.normalizeQuery(" a  b   c "));
        assertEquals("", YouTubeSearchState.normalizeQuery("   "));
        assertEquals("", YouTubeSearchState.normalizeQuery(null));
    }

    @Test
    public void normalizeStripsControlCharactersFromAPaste() {
        assertEquals("cat video", YouTubeSearchState.normalizeQuery("cat\u0000\u0001video"));
        assertEquals("cat video", YouTubeSearchState.normalizeQuery("\u007fcat\u009fvideo\u0002"));
    }

    @Test
    public void normalizePreservesNonLatinQueries() {
        // Justifies a minimum length of 2 rather than something larger: two CJK characters
        // are a perfectly meaningful search.
        assertEquals("音楽", YouTubeSearchState.normalizeQuery("  音楽 "));
        assertEquals("موسيقى", YouTubeSearchState.normalizeQuery("موسيقى"));
        assertTrue(YouTubeSearchState.isSearchable(YouTubeSearchState.normalizeQuery("音楽")));
    }

    @Test
    public void searchabilityMatchesTheServerBounds() {
        assertFalse(YouTubeSearchState.isSearchable(null));
        assertFalse(YouTubeSearchState.isSearchable(""));
        assertFalse("one character is below the server minimum", YouTubeSearchState.isSearchable("a"));
        assertTrue("two characters is the boundary", YouTubeSearchState.isSearchable("ab"));

        StringBuilder max = new StringBuilder();
        for (int i = 0; i < YouTubeSearchState.MAX_QUERY_LEN; i++) max.append('x');
        assertTrue(YouTubeSearchState.isSearchable(max.toString()));
        assertFalse(YouTubeSearchState.isSearchable(max.toString() + "x"));
    }

    @Test
    public void clampTrimsAnOverlongPasteInsteadOfFailingIt() {
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < YouTubeSearchState.MAX_QUERY_LEN + 50; i++) tooLong.append('y');

        String clamped = YouTubeSearchState.clampQuery(tooLong.toString());
        assertEquals(YouTubeSearchState.MAX_QUERY_LEN, clamped.length());
        assertTrue(YouTubeSearchState.isSearchable(clamped));

        assertEquals("short", YouTubeSearchState.clampQuery("short"));
        assertEquals("", YouTubeSearchState.clampQuery(null));
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    public void startsIdleWithNothingToShow() {
        assertEquals(YouTubeSearchState.Phase.IDLE, state.phase());
        assertTrue(state.results().isEmpty());
        assertEquals("", state.message());
        assertFalse(state.isPanelVisible());
        assertFalse(state.isLoading());
    }

    // ── Dispatch gating (quota protection) ────────────────────────────────────

    @Test
    public void doesNotDispatchAnUnsearchableQuery() {
        assertFalse(state.shouldDispatch(null));
        assertFalse(state.shouldDispatch(""));
        assertFalse(state.shouldDispatch("a"));
    }

    @Test
    public void doesNotRedispatchTheQueryAlreadyInFlight() {
        assertTrue(state.shouldDispatch("lofi"));
        state.beginSearch("lofi");
        assertFalse("a debounce firing after an explicit submit must not double-spend quota",
                state.shouldDispatch("lofi"));
        assertTrue("a different query still dispatches", state.shouldDispatch("lofi beats"));
    }

    @Test
    public void doesNotRedispatchTheQueryAlreadyDisplayed() {
        long t = state.beginSearch("lofi");
        state.onResults(t, rows(row(ID, "A")));
        assertEquals(YouTubeSearchState.Phase.RESULTS, state.phase());
        assertFalse(state.shouldDispatch("lofi"));
    }

    @Test
    public void doesNotRedispatchAQueryThatCameBackEmpty() {
        // "no matches for this typo" is a stable answer; re-asking costs another 100 units.
        long t = state.beginSearch("asdkjhasd");
        state.onResults(t, Collections.<YouTubeSearchResult>emptyList());
        assertEquals(YouTubeSearchState.Phase.EMPTY, state.phase());
        assertFalse(state.shouldDispatch("asdkjhasd"));
    }

    @Test
    public void redispatchesTheSameQueryAfterAnError() {
        long t = state.beginSearch("lofi");
        state.onError(t, "No connection. Check your network and try again.");
        assertEquals(YouTubeSearchState.Phase.ERROR, state.phase());
        assertTrue("retry after failure must be allowed", state.shouldDispatch("lofi"));
    }

    // ── Loading / results / empty ─────────────────────────────────────────────

    @Test
    public void beginSearchEntersLoadingAndClearsPreviousResults() {
        long t1 = state.beginSearch("first");
        state.onResults(t1, rows(row(ID, "A")));
        assertEquals(1, state.results().size());

        state.beginSearch("second");
        assertEquals(YouTubeSearchState.Phase.LOADING, state.phase());
        assertTrue("stale rows must not linger under the spinner", state.results().isEmpty());
        assertEquals("", state.message());
        assertTrue(state.isLoading());
        assertTrue(state.isPanelVisible());
        assertEquals("second", state.activeQuery());
    }

    @Test
    public void resultsTransitionToResultsPhase() {
        long t = state.beginSearch("lofi");
        assertTrue(state.onResults(t, rows(row(ID, "A"), row(ID2, "B"))));
        assertEquals(YouTubeSearchState.Phase.RESULTS, state.phase());
        assertEquals(2, state.results().size());
        assertFalse(state.isLoading());
        assertTrue(state.isPanelVisible());
    }

    @Test
    public void zeroResultsIsASuccessfulEmptyNotAnError() {
        long t = state.beginSearch("zzzzqqqq");
        assertTrue(state.onResults(t, Collections.<YouTubeSearchResult>emptyList()));
        assertEquals(YouTubeSearchState.Phase.EMPTY, state.phase());
        assertEquals("", state.message());
        assertTrue(state.results().isEmpty());
    }

    @Test
    public void nullResultListIsTreatedAsEmptyNotACrash() {
        long t = state.beginSearch("lofi");
        assertTrue(state.onResults(t, null));
        assertEquals(YouTubeSearchState.Phase.EMPTY, state.phase());
        assertTrue(state.results().isEmpty());
    }

    @Test
    public void resultsAreDefensivelyCopiedAndNotWritableByCallers() {
        List<YouTubeSearchResult> mutable = rows(row(ID, "A"));
        long t = state.beginSearch("lofi");
        state.onResults(t, mutable);

        mutable.clear(); // caller mutating its own list must not empty the state
        assertEquals(1, state.results().size());

        try {
            state.results().add(row(ID2, "B"));
            fail("exposed result list must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // exactly right
        }
    }

    // ── Error state ───────────────────────────────────────────────────────────

    @Test
    public void errorStoresTheMessageAndClearsResults() {
        long t = state.beginSearch("lofi");
        state.onResults(t, rows(row(ID, "A")));

        long t2 = state.beginSearch("lofi beats");
        assertTrue(state.onError(t2, "Too many searches — try again in a minute."));
        assertEquals(YouTubeSearchState.Phase.ERROR, state.phase());
        assertEquals("Too many searches — try again in a minute.", state.message());
        assertTrue(state.results().isEmpty());
    }

    @Test
    public void errorWithoutAMessageStillShowsSomething() {
        long t = state.beginSearch("lofi");
        state.onError(t, null);
        assertFalse(state.message().trim().isEmpty());

        long t2 = state.beginSearch("lofi 2");
        state.onError(t2, "   ");
        assertFalse(state.message().trim().isEmpty());
    }

    // ── Stale-response rejection ──────────────────────────────────────────────

    @Test
    public void aStaleSuccessCannotOverwriteANewerSearch() {
        long slow = state.beginSearch("lofi");
        long fast = state.beginSearch("lofi beats");
        assertNotEquals(slow, fast);

        assertTrue(state.onResults(fast, rows(row(ID2, "Newer"))));
        assertFalse("the abandoned request must be inert", state.onResults(slow, rows(row(ID, "Older"))));

        assertEquals(1, state.results().size());
        assertEquals("Newer", state.results().get(0).title);
        assertEquals("lofi beats", state.activeQuery());
    }

    @Test
    public void aStaleErrorCannotClobberAGoodNewerResult() {
        long slow = state.beginSearch("lofi");
        long fast = state.beginSearch("lofi beats");
        state.onResults(fast, rows(row(ID2, "Newer")));

        assertFalse(state.onError(slow, "Search timed out. Try again."));
        assertEquals(YouTubeSearchState.Phase.RESULTS, state.phase());
        assertEquals("", state.message());
    }

    @Test
    public void aResponseArrivingAfterResetIsIgnored() {
        long t = state.beginSearch("lofi");
        state.reset(); // user tapped a result / cleared the field
        assertFalse(state.onResults(t, rows(row(ID, "A"))));
        assertEquals("an in-flight response must not re-open the panel",
                YouTubeSearchState.Phase.IDLE, state.phase());
        assertFalse(state.isPanelVisible());
    }

    @Test
    public void aResponseArrivingAfterTheQueryGotTooShortIsIgnored() {
        long t = state.beginSearch("lofi");
        state.markTooShort(); // user backspaced down to one character
        assertFalse(state.onResults(t, rows(row(ID, "A"))));
        assertEquals(YouTubeSearchState.Phase.TOO_SHORT, state.phase());
        assertTrue(state.results().isEmpty());
    }

    @Test
    public void tokenZeroIsNeverCurrentSoNothingAppliesBeforeTheFirstDispatch() {
        assertFalse(state.isCurrent(0L));
        assertFalse(state.onResults(0L, rows(row(ID, "A"))));
        assertEquals(YouTubeSearchState.Phase.IDLE, state.phase());
    }

    @Test
    public void tokensAreStrictlyIncreasingAcrossEveryTransition() {
        long a = state.beginSearch("aa");
        state.markTooShort();
        long b = state.beginSearch("bb");
        state.reset();
        long c = state.beginSearch("cc");
        assertTrue(a < b);
        assertTrue(b < c);
        assertTrue(state.isCurrent(c));
        assertFalse(state.isCurrent(a));
        assertFalse(state.isCurrent(b));
    }

    // ── Panel visibility ──────────────────────────────────────────────────────

    @Test
    public void panelIsHiddenOnlyWhenIdle() {
        assertFalse(state.isPanelVisible());

        state.markTooShort();
        assertTrue(state.isPanelVisible());

        long t = state.beginSearch("lofi");
        assertTrue(state.isPanelVisible());

        state.onResults(t, rows(row(ID, "A")));
        assertTrue(state.isPanelVisible());

        state.reset();
        assertFalse(state.isPanelVisible());
    }

    @Test
    public void resetClearsEverything() {
        long t = state.beginSearch("lofi");
        state.onError(t, "boom");
        state.reset();
        assertEquals(YouTubeSearchState.Phase.IDLE, state.phase());
        assertEquals("", state.message());
        assertEquals("", state.activeQuery());
        assertTrue(state.results().isEmpty());
    }

    // ── Result → videoId mapping (the handoff to the existing player) ─────────

    @Test
    public void mapsATappedPositionToItsVideoId() {
        long t = state.beginSearch("lofi");
        state.onResults(t, rows(row(ID, "A"), row(ID2, "B")));
        assertEquals(ID, state.videoIdAt(0));
        assertEquals(ID2, state.videoIdAt(1));
    }

    @Test
    public void outOfRangePositionsMapToNull() {
        long t = state.beginSearch("lofi");
        state.onResults(t, rows(row(ID, "A")));
        assertNull(state.videoIdAt(-1));
        assertNull(state.videoIdAt(1));
        assertNull(state.videoIdAt(99));
    }

    @Test
    public void tapsAreIgnoredUnlessResultsAreActuallyOnScreen() {
        assertNull("IDLE", state.videoIdAt(0));

        state.markTooShort();
        assertNull("TOO_SHORT", state.videoIdAt(0));

        long t = state.beginSearch("lofi");
        assertNull("LOADING", state.videoIdAt(0));

        state.onResults(t, Collections.<YouTubeSearchResult>emptyList());
        assertNull("EMPTY", state.videoIdAt(0));

        long t2 = state.beginSearch("lofi 2");
        state.onError(t2, "boom");
        assertNull("ERROR", state.videoIdAt(0));
    }

    @Test
    public void aTapLandingAfterTheListWasReplacedCannotStartTheWrongVideo() {
        long t = state.beginSearch("lofi");
        state.onResults(t, rows(row(ID, "A"), row(ID2, "B")));

        // A new search starts; the RecyclerView's queued click for position 1 arrives now.
        state.beginSearch("something else");
        assertNull("phase is LOADING, so the stale tap is inert", state.videoIdAt(1));
    }

    @Test
    public void everyMappedVideoIdIsValidForThePlayer() {
        long t = state.beginSearch("lofi");
        state.onResults(t, rows(row(ID, "A"), row(ID2, "B")));
        for (int i = 0; i < state.results().size(); i++) {
            String vid = state.videoIdAt(i);
            assertNotNull(vid);
            assertTrue("videoIdAt must only ever return something the player accepts",
                    YouTubeUrlParser.isValidVideoId(vid));
        }
    }
}
