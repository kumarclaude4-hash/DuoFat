package com.duoshield.app.call.watch;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link YouTubeSearchParser} and {@link YouTubeSearchResult}.
 *
 * <p>Covers the response contract the push server guarantees, plus every way the body can be
 * wrong: malformed JSON, plain-text error bodies (the endpoint really does return those for
 * 413/500), missing keys, wrong types, and rows that must be dropped rather than rendered.
 *
 * <p>Runs on the JVM, matching {@code YouTubeUrlParserTest} and {@code WatchTogetherStateTest}.
 */
public class YouTubeSearchParserTest {

    private static final String ID  = "dQw4w9WgXcQ";
    private static final String ID2 = "9bZkp7q19f0";

    // ── Test-harness integrity ────────────────────────────────────────────────

    /**
     * Guards the whole file. android.jar stubs org.json, and this module sets
     * {@code returnDefaultValues true}, so if the real org.json is ever dropped from the test
     * classpath every JSON assertion below would pass vacuously against "" and null. This
     * test fails loudly in that case instead.
     */
    @Test
    public void orgJsonIsTheRealImplementationNotTheAndroidStub() throws Exception {
        JSONObject o = new JSONObject("{\"k\":\"v\",\"n\":3}");
        assertEquals("real org.json must be on the unit-test classpath", "v", o.optString("k", ""));
        assertEquals(3, o.optInt("n", 0));
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    public void parsesAWellFormedResponse() {
        String body = "{\"results\":["
                + "{\"videoId\":\"" + ID + "\",\"title\":\"Never Gonna Give You Up\","
                + "\"channel\":\"Rick Astley\",\"thumbnail\":\"https://i.ytimg.com/vi/" + ID + "/mqdefault.jpg\"},"
                + "{\"videoId\":\"" + ID2 + "\",\"title\":\"Gangnam Style\","
                + "\"channel\":\"officialpsy\",\"thumbnail\":\"https://i.ytimg.com/vi/" + ID2 + "/mqdefault.jpg\"}"
                + "],\"cached\":false}";

        List<YouTubeSearchResult> results = YouTubeSearchParser.parseResults(body);

        assertEquals(2, results.size());
        assertEquals(ID, results.get(0).videoId);
        assertEquals("Never Gonna Give You Up", results.get(0).title);
        assertEquals("Rick Astley", results.get(0).channel);
        assertTrue(results.get(0).hasThumbnail());
        assertEquals(ID2, results.get(1).videoId);
    }

    @Test
    public void readsTheCachedFlag() {
        assertTrue(YouTubeSearchParser.wasCached("{\"results\":[],\"cached\":true}"));
        assertFalse(YouTubeSearchParser.wasCached("{\"results\":[],\"cached\":false}"));
        assertFalse("absent flag defaults to false",
                YouTubeSearchParser.wasCached("{\"results\":[]}"));
        assertFalse(YouTubeSearchParser.wasCached("not json"));
    }

    // ── Empty results ─────────────────────────────────────────────────────────

    @Test
    public void emptyResultArrayYieldsAnEmptyList() {
        List<YouTubeSearchResult> results =
                YouTubeSearchParser.parseResults("{\"results\":[],\"cached\":false}");
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    public void missingResultsKeyYieldsAnEmptyListNotNull() {
        assertTrue(YouTubeSearchParser.parseResults("{\"cached\":false}").isEmpty());
    }

    @Test
    public void resultsOfTheWrongTypeYieldAnEmptyList() {
        assertTrue(YouTubeSearchParser.parseResults("{\"results\":\"nope\"}").isEmpty());
        assertTrue(YouTubeSearchParser.parseResults("{\"results\":42}").isEmpty());
        assertTrue(YouTubeSearchParser.parseResults("{\"results\":{}}").isEmpty());
        assertTrue(YouTubeSearchParser.parseResults("{\"results\":null}").isEmpty());
    }

    // ── Malformed bodies ──────────────────────────────────────────────────────

    @Test
    public void malformedBodiesNeverThrow() {
        String[] bodies = {
                null,
                "",
                "   ",
                "not json at all",
                "Request body too large",                 // real 413 body (plain text)
                "Server error (ref: abc123)",             // real 500 body (plain text)
                "<html><body>502 Bad Gateway</body></html>",
                "{",
                "{\"results\":[",
                "[]",                                     // bare array, not an object
                "[{\"videoId\":\"" + ID + "\"}]",
                "{\"results\":[{\"videoId\":}]}",
        };
        for (String body : bodies) {
            List<YouTubeSearchResult> results = YouTubeSearchParser.parseResults(body);
            assertNotNull("null returned for body: " + body, results);
            assertTrue("expected no results for body: " + body, results.isEmpty());
        }
    }

    @Test
    public void nonObjectEntriesInTheArrayAreSkippedNotFatal() {
        String body = "{\"results\":["
                + "\"a string\",42,null,"
                + "{\"videoId\":\"" + ID + "\",\"title\":\"Good\",\"channel\":\"C\",\"thumbnail\":\"\"}"
                + "]}";
        List<YouTubeSearchResult> results = YouTubeSearchParser.parseResults(body);
        assertEquals(1, results.size());
        assertEquals(ID, results.get(0).videoId);
    }

    // ── Row-level sanitisation (defence in depth over the server's own) ───────

    @Test
    public void rowsWithAnInvalidVideoIdAreDropped() {
        String body = "{\"results\":["
                + "{\"videoId\":\"tooshort\",\"title\":\"A\"},"                       // 8 chars
                + "{\"videoId\":\"waaaytoolongforanid\",\"title\":\"B\"},"             // >11
                + "{\"videoId\":\"bad!chars!!\",\"title\":\"C\"},"                     // illegal chars
                + "{\"videoId\":\"\",\"title\":\"D\"},"                                // empty
                + "{\"title\":\"E\"},"                                                 // absent
                + "{\"videoId\":\"" + ID + "\",\"title\":\"Keeper\"}"
                + "]}";
        List<YouTubeSearchResult> results = YouTubeSearchParser.parseResults(body);
        assertEquals(1, results.size());
        assertEquals("Keeper", results.get(0).title);
    }

    @Test
    public void rowsWithNoUsableTitleAreDropped() {
        String body = "{\"results\":["
                + "{\"videoId\":\"" + ID + "\",\"title\":\"\"},"
                + "{\"videoId\":\"" + ID2 + "\",\"title\":\"   \"},"
                + "{\"videoId\":\"abcdefghijk\"}"
                + "]}";
        assertTrue(YouTubeSearchParser.parseResults(body).isEmpty());
    }

    @Test
    public void missingChannelAndThumbnailBecomeEmptyStringsNotNull() {
        String body = "{\"results\":[{\"videoId\":\"" + ID + "\",\"title\":\"T\"}]}";
        List<YouTubeSearchResult> results = YouTubeSearchParser.parseResults(body);
        assertEquals(1, results.size());
        assertEquals("", results.get(0).channel);
        assertEquals("", results.get(0).thumbnail);
        assertFalse(results.get(0).hasThumbnail());
    }

    @Test
    public void cleartextThumbnailsAreStrippedRatherThanLoaded() {
        String body = "{\"results\":["
                + "{\"videoId\":\"" + ID + "\",\"title\":\"T\",\"thumbnail\":\"http://i.ytimg.com/x.jpg\"},"
                + "{\"videoId\":\"" + ID2 + "\",\"title\":\"U\",\"thumbnail\":\"//i.ytimg.com/y.jpg\"}"
                + "]}";
        List<YouTubeSearchResult> results = YouTubeSearchParser.parseResults(body);
        assertEquals(2, results.size());
        assertEquals("", results.get(0).thumbnail);
        assertEquals("", results.get(1).thumbnail);
    }

    @Test
    public void titleAndChannelAreTrimmed() {
        String body = "{\"results\":[{\"videoId\":\"" + ID
                + "\",\"title\":\"  Spaced Title  \",\"channel\":\"  Chan  \"}]}";
        YouTubeSearchResult r = YouTubeSearchParser.parseResults(body).get(0);
        assertEquals("Spaced Title", r.title);
        assertEquals("Chan", r.channel);
    }

    @Test
    public void unexpectedExtraFieldsAreIgnoredNotForwarded() {
        // If the server ever regressed and echoed something it shouldn't, the client copies
        // only the four known keys, so nothing else can reach the UI.
        String body = "{\"results\":[{\"videoId\":\"" + ID + "\",\"title\":\"T\","
                + "\"description\":\"long text\",\"etag\":\"xyz\",\"key\":\"SECRET\"}],"
                + "\"nextPageToken\":\"tok\"}";
        List<YouTubeSearchResult> results = YouTubeSearchParser.parseResults(body);
        assertEquals(1, results.size());
        assertFalse(results.get(0).toString().contains("SECRET"));
        assertEquals(ID, results.get(0).videoId);
        assertEquals("T", results.get(0).title);
    }

    // ── YouTubeSearchResult.of ────────────────────────────────────────────────

    @Test
    public void ofRejectsUnusableRowsAndNormalisesTherest() {
        assertNull(YouTubeSearchResult.of(null, "T", "C", ""));
        assertNull(YouTubeSearchResult.of("short", "T", "C", ""));
        assertNull(YouTubeSearchResult.of(ID, null, "C", ""));
        assertNull(YouTubeSearchResult.of(ID, "  ", "C", ""));

        YouTubeSearchResult r = YouTubeSearchResult.of("  " + ID + "  ", "T", null, null);
        assertNotNull(r);
        assertEquals(ID, r.videoId);
        assertEquals("", r.channel);
        assertEquals("", r.thumbnail);
    }

    @Test
    public void identityIsTheVideoIdAndContentComparesEveryRenderedField() {
        YouTubeSearchResult a = YouTubeSearchResult.of(ID, "Title A", "Chan", "https://t/1.jpg");
        YouTubeSearchResult b = YouTubeSearchResult.of(ID, "Title B", "Chan", "https://t/1.jpg");
        YouTubeSearchResult c = YouTubeSearchResult.of(ID2, "Title A", "Chan", "https://t/1.jpg");

        assertEquals("same video id is the same item (DiffUtil identity)", a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);

        assertFalse("differing titles are differing content", a.sameContentAs(b));
        assertTrue(a.sameContentAs(YouTubeSearchResult.of(ID, "Title A", "Chan", "https://t/1.jpg")));
        assertFalse(a.sameContentAs(null));
    }

    // ── Error mapping ─────────────────────────────────────────────────────────

    @Test
    public void extractsTheServerErrorStringWhenPresent() {
        assertEquals("Too many searches — try again in a minute",
                YouTubeSearchParser.extractServerError(
                        "{\"error\":\"Too many searches — try again in a minute\"}"));
        assertNull(YouTubeSearchParser.extractServerError("{\"error\":\"\"}"));
        assertNull(YouTubeSearchParser.extractServerError("{\"results\":[]}"));
        assertNull("plain-text 413 body is not JSON",
                YouTubeSearchParser.extractServerError("Request body too large"));
        assertNull(YouTubeSearchParser.extractServerError(null));
    }

    @Test
    public void everyMappedStatusProducesNonEmptyUserFacingCopy() {
        int[] statuses = {
                YouTubeSearchParser.STATUS_NOT_CONFIGURED,
                YouTubeSearchParser.STATUS_NETWORK_FAILURE,
                400, 401, 413, 429, 500, 502, 503, 504, 418, 599,
        };
        for (int s : statuses) {
            String m = YouTubeSearchParser.messageForStatus(s, null);
            assertNotNull("null message for status " + s, m);
            assertFalse("empty message for status " + s, m.trim().isEmpty());
        }
    }

    @Test
    public void quotaExhaustionIsPresentedAsTemporaryWithAFallbackPath() {
        // The server maps YouTube's 403/429 to a 503 with this exact wording.
        String m = YouTubeSearchParser.messageForStatus(503,
                "{\"error\":\"Search is temporarily unavailable. Try again later.\"}");
        assertTrue(m.contains("temporarily unavailable"));
        assertTrue("must tell the user they can still paste a link", m.contains("paste a YouTube link"));
    }

    @Test
    public void unconfiguredServerKeyIsAlsoSurfacedAsRecoverable() {
        String m = YouTubeSearchParser.messageForStatus(503, "{\"error\":\"Search is not configured\"}");
        assertTrue(m.contains("not configured"));
        assertTrue(m.contains("paste a YouTube link"));
    }

    @Test
    public void plainTextStatusesAreNeverEchoedToTheUser() {
        String m413 = YouTubeSearchParser.messageForStatus(413, "Request body too large");
        assertFalse(m413.contains("Request body too large"));

        // A 500 body carries a log correlation id; showing it leaks internal detail.
        String m500 = YouTubeSearchParser.messageForStatus(500, "Server error (ref: 7f3a91)");
        assertFalse(m500.contains("7f3a91"));
        assertFalse(m500.contains("ref:"));
    }

    @Test
    public void unauthorizedIsRewrittenIntoSomethingActionable() {
        String m = YouTubeSearchParser.messageForStatus(401, "{\"error\":\"Invalid token\"}");
        assertFalse("terse server wording is not shown", m.contains("Invalid token"));
        assertTrue(m.toLowerCase().contains("sign"));
    }

    @Test
    public void noMappedMessageCanLeakCredentialOrUpstreamDetail() {
        // Mirrors the server-side assertion, on the client side: even if a body arrived
        // containing leaky substrings, the mapped message must not carry them through.
        String leaky = "{\"error\":\"failed GET https://www.googleapis.com/youtube/v3/search"
                + "?key=AIzaSyLEAKED&project=12345 quotaExceeded\"}";
        int[] statuses = {
                YouTubeSearchParser.STATUS_NOT_CONFIGURED,
                YouTubeSearchParser.STATUS_NETWORK_FAILURE,
                401, 413, 500,
        };
        for (int s : statuses) {
            String m = YouTubeSearchParser.messageForStatus(s, leaky).toLowerCase();
            assertFalse("status " + s + " leaked a key", m.contains("aizasy"));
            assertFalse("status " + s + " leaked a key param", m.contains("key="));
            assertFalse("status " + s + " leaked an upstream host", m.contains("googleapis"));
            assertFalse("status " + s + " leaked a project id", m.contains("project"));
        }
    }

    @Test
    public void retryabilityDistinguishesUserErrorsFromTransientFailures() {
        assertFalse(YouTubeSearchParser.isRetryable(YouTubeSearchParser.STATUS_NOT_CONFIGURED));
        assertFalse(YouTubeSearchParser.isRetryable(400));
        assertFalse(YouTubeSearchParser.isRetryable(413));

        assertTrue(YouTubeSearchParser.isRetryable(YouTubeSearchParser.STATUS_NETWORK_FAILURE));
        assertTrue(YouTubeSearchParser.isRetryable(401));
        assertTrue(YouTubeSearchParser.isRetryable(429));
        assertTrue(YouTubeSearchParser.isRetryable(502));
        assertTrue(YouTubeSearchParser.isRetryable(503));
        assertTrue(YouTubeSearchParser.isRetryable(504));
    }
}
