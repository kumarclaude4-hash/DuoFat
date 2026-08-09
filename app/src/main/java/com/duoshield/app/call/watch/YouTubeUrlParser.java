package com.duoshield.app.call.watch;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a YouTube video ID from whatever the user pastes.
 *
 * <p>Pure static logic with no Android or Firebase dependencies so it is unit testable on
 * the JVM, matching the existing {@code app/src/test/.../call} test style.
 *
 * <p>Only the 11-character video ID is ever synchronized between participants — never a
 * full URL. That keeps the synced payload minimal and prevents a malicious participant
 * from pushing an arbitrary URL into the other side's WebView.
 */
public final class YouTubeUrlParser {

    /** YouTube video IDs are exactly 11 chars of the URL-safe base64 alphabet. */
    private static final Pattern ID_ONLY = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    /**
     * Matches every common YouTube URL shape:
     * {@code watch?v=ID}, {@code youtu.be/ID}, {@code /embed/ID}, {@code /shorts/ID},
     * {@code /live/ID}, {@code /v/ID}.
     */
    private static final Pattern URL_FORMS = Pattern.compile(
            "(?:youtube(?:-nocookie)?\\.com/(?:watch\\?(?:.*&)?v=|embed/|shorts/|live/|v/)"
                    + "|youtu\\.be/)"
                    + "([A-Za-z0-9_-]{11})");

    /** Matches a {@code t}/{@code start} timestamp such as {@code t=90}, {@code t=1m30s}. */
    private static final Pattern TIME_PARAM = Pattern.compile(
            "[?&](?:t|start)=(?:(\\d+)h)?(?:(\\d+)m)?(\\d+)s?(?:&|$)");

    private YouTubeUrlParser() {
    }

    /**
     * @param input a full YouTube URL, a share link, or a bare video ID. May be null.
     * @return the 11-character video ID, or {@code null} when nothing valid is found.
     */
    public static String extractVideoId(String input) {
        if (input == null) return null;

        String trimmed = input.trim();
        if (trimmed.isEmpty()) return null;

        // Already a bare ID.
        if (ID_ONLY.matcher(trimmed).matches()) return trimmed;

        Matcher m = URL_FORMS.matcher(trimmed);
        if (m.find()) return m.group(1);

        return null;
    }

    /** True when {@code input} yields a usable video ID. */
    public static boolean isValid(String input) {
        return extractVideoId(input) != null;
    }

    /** True when {@code videoId} is exactly a well-formed video ID (used to validate remote state). */
    public static boolean isValidVideoId(String videoId) {
        return videoId != null && ID_ONLY.matcher(videoId).matches();
    }

    /**
     * Reads an optional start offset from the URL's {@code t}/{@code start} parameter,
     * supporting both raw seconds ({@code t=90}) and the {@code 1h2m3s} form.
     *
     * @return the offset in milliseconds, or 0 when absent or unparseable.
     */
    public static long extractStartMs(String input) {
        if (input == null) return 0L;

        Matcher m = TIME_PARAM.matcher(input);
        if (!m.find()) return 0L;

        try {
            long hours   = m.group(1) != null ? Long.parseLong(m.group(1)) : 0L;
            long minutes = m.group(2) != null ? Long.parseLong(m.group(2)) : 0L;
            long seconds = m.group(3) != null ? Long.parseLong(m.group(3)) : 0L;
            long total = (hours * 3600L + minutes * 60L + seconds) * 1000L;
            return Math.max(0L, total);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
