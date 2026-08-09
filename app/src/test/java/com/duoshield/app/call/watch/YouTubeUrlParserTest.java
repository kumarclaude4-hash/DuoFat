package com.duoshield.app.call.watch;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link YouTubeUrlParser}.
 *
 * <p>Covers every URL shape a user might paste, plus the rejection cases that keep an
 * arbitrary URL from reaching the WebView. Runs on the JVM, matching {@code CallStateTest}.
 */
public class YouTubeUrlParserTest {

    private static final String ID = "dQw4w9WgXcQ";

    // ── Accepted forms ────────────────────────────────────────────────────────

    @Test
    public void bareIdIsAccepted() {
        assertEquals(ID, YouTubeUrlParser.extractVideoId(ID));
    }

    @Test
    public void standardWatchUrl() {
        assertEquals(ID, YouTubeUrlParser.extractVideoId(
                "https://www.youtube.com/watch?v=" + ID));
    }

    @Test
    public void watchUrlWithoutScheme() {
        assertEquals(ID, YouTubeUrlParser.extractVideoId("youtube.com/watch?v=" + ID));
    }

    @Test
    public void watchUrlWithExtraParamsBeforeV() {
        assertEquals(ID, YouTubeUrlParser.extractVideoId(
                "https://www.youtube.com/watch?app=desktop&feature=share&v=" + ID));
    }

    @Test
    public void watchUrlWithExtraParamsAfterV() {
        assertEquals(ID, YouTubeUrlParser.extractVideoId(
                "https://www.youtube.com/watch?v=" + ID + "&t=42s&list=PLabc"));
    }

    @Test
    public void shortShareUrl() {
        assertEquals(ID, YouTubeUrlParser.extractVideoId("https://youtu.be/" + ID));
    }

    @Test
    public void shortShareUrlWithTimestamp() {
        assertEquals(ID, YouTubeUrlParser.extractVideoId("https://youtu.be/" + ID + "?t=90"));
    }

    @Test
    public void embedUrl() {
        assertEquals(ID, YouTubeUrlParser.extractVideoId(
                "https://www.youtube.com/embed/" + ID));
    }

    @Test
    public void nocookieEmbedUrl() {
        assertEquals(ID, YouTubeUrlParser.extractVideoId(
                "https://www.youtube-nocookie.com/embed/" + ID));
    }

    @Test
    public void shortsUrl() {
        assertEquals(ID, YouTubeUrlParser.extractVideoId(
                "https://www.youtube.com/shorts/" + ID));
    }

    @Test
    public void liveUrl() {
        assertEquals(ID, YouTubeUrlParser.extractVideoId(
                "https://www.youtube.com/live/" + ID));
    }

    @Test
    public void legacyVUrl() {
        assertEquals(ID, YouTubeUrlParser.extractVideoId("https://www.youtube.com/v/" + ID));
    }

    @Test
    public void mobileUrl() {
        assertEquals(ID, YouTubeUrlParser.extractVideoId("https://m.youtube.com/watch?v=" + ID));
    }

    @Test
    public void surroundingWhitespaceIsTrimmed() {
        assertEquals(ID, YouTubeUrlParser.extractVideoId("   " + ID + "  "));
        assertEquals(ID, YouTubeUrlParser.extractVideoId(
                "  https://youtu.be/" + ID + " "));
    }

    @Test
    public void idsWithUnderscoreAndHyphenAreAccepted() {
        assertEquals("a_b-c_d-e_f", YouTubeUrlParser.extractVideoId("a_b-c_d-e_f"));
        assertEquals("a_b-c_d-e_f",
                YouTubeUrlParser.extractVideoId("https://youtu.be/a_b-c_d-e_f"));
    }

    // ── Rejected forms ────────────────────────────────────────────────────────

    @Test
    public void nullIsRejected() {
        assertNull(YouTubeUrlParser.extractVideoId(null));
        assertFalse(YouTubeUrlParser.isValid(null));
    }

    @Test
    public void emptyAndBlankAreRejected() {
        assertNull(YouTubeUrlParser.extractVideoId(""));
        assertNull(YouTubeUrlParser.extractVideoId("   "));
    }

    @Test
    public void tooShortIdIsRejected() {
        assertNull(YouTubeUrlParser.extractVideoId("abc123"));
    }

    @Test
    public void tooLongIdIsRejected() {
        assertNull(YouTubeUrlParser.extractVideoId("dQw4w9WgXcQextra"));
    }

    @Test
    public void nonYouTubeUrlIsRejected() {
        assertNull(YouTubeUrlParser.extractVideoId("https://vimeo.com/123456789"));
        assertNull(YouTubeUrlParser.extractVideoId("https://example.com/watch?v=" + ID));
    }

    @Test
    public void plainTextIsRejected() {
        assertNull(YouTubeUrlParser.extractVideoId("hello world"));
    }

    @Test
    public void channelUrlIsRejected() {
        assertNull(YouTubeUrlParser.extractVideoId(
                "https://www.youtube.com/@someChannelName"));
    }

    @Test
    public void javascriptUrlIsRejected() {
        // Guards the WebView: nothing but a clean video ID may ever be loaded.
        assertNull(YouTubeUrlParser.extractVideoId("javascript:alert(1)"));
    }

    @Test
    public void idWithInvalidCharactersIsRejected() {
        assertNull(YouTubeUrlParser.extractVideoId("abc!def@ghi"));   // 11 chars, bad alphabet
    }

    // ── isValid / isValidVideoId ──────────────────────────────────────────────

    @Test
    public void isValidMirrorsExtract() {
        assertTrue(YouTubeUrlParser.isValid("https://youtu.be/" + ID));
        assertTrue(YouTubeUrlParser.isValid(ID));
        assertFalse(YouTubeUrlParser.isValid("not a video"));
    }

    @Test
    public void isValidVideoIdAcceptsOnlyBareIds() {
        assertTrue(YouTubeUrlParser.isValidVideoId(ID));
        assertFalse("A full URL is not a bare id",
                YouTubeUrlParser.isValidVideoId("https://youtu.be/" + ID));
        assertFalse(YouTubeUrlParser.isValidVideoId(null));
        assertFalse(YouTubeUrlParser.isValidVideoId(""));
        assertFalse(YouTubeUrlParser.isValidVideoId("short"));
    }

    // ── Start offset ──────────────────────────────────────────────────────────

    @Test
    public void plainSecondsTimestamp() {
        assertEquals(90_000L, YouTubeUrlParser.extractStartMs(
                "https://youtu.be/" + ID + "?t=90"));
    }

    @Test
    public void secondsWithSuffixTimestamp() {
        assertEquals(42_000L, YouTubeUrlParser.extractStartMs(
                "https://www.youtube.com/watch?v=" + ID + "&t=42s"));
    }

    @Test
    public void minutesAndSecondsTimestamp() {
        assertEquals(90_000L, YouTubeUrlParser.extractStartMs(
                "https://youtu.be/" + ID + "?t=1m30s"));
    }

    @Test
    public void hoursMinutesSecondsTimestamp() {
        assertEquals(3_723_000L, YouTubeUrlParser.extractStartMs(
                "https://youtu.be/" + ID + "?t=1h2m3s"));
    }

    @Test
    public void startParamIsAlsoRead() {
        assertEquals(30_000L, YouTubeUrlParser.extractStartMs(
                "https://www.youtube.com/embed/" + ID + "?start=30"));
    }

    @Test
    public void noTimestampYieldsZero() {
        assertEquals(0L, YouTubeUrlParser.extractStartMs("https://youtu.be/" + ID));
        assertEquals(0L, YouTubeUrlParser.extractStartMs(ID));
        assertEquals(0L, YouTubeUrlParser.extractStartMs(null));
    }
}
