package com.duoshield.app.call.watch;

/**
 * One row of a YouTube search response, as returned by the DuoShield push server's
 * {@code POST /youtubeSearch} endpoint.
 *
 * <p>Pure Java — no Android and no Firebase imports — so it is unit testable on the JVM,
 * matching the style of {@link WatchTogetherState} and {@link YouTubeUrlParser}.
 *
 * <p><strong>The server already sanitises these four fields</strong> (allow-list projection,
 * 11-char video-id validation, https-only thumbnails). {@link #of} re-applies the same rules
 * anyway: the response is attacker-reachable in principle, and {@code videoId} is the one
 * value that ends up inside a WebView, so it is re-validated on the client before it can get
 * anywhere near the player. Defence in depth, not distrust of the server.
 *
 * <p>Only {@code videoId} is ever handed to the existing Watch Together session — the title,
 * channel and thumbnail exist purely to render the picker and are discarded on selection.
 */
public final class YouTubeSearchResult {

    /** Thumbnails must be https; a cleartext URL is dropped rather than loaded. */
    private static final String HTTPS_PREFIX = "https://";

    /** Always an 11-char YouTube video id — validated by {@link #of}. */
    public final String videoId;

    /** Non-empty display title. */
    public final String title;

    /** Channel name; may be {@code ""} but never {@code null}. */
    public final String channel;

    /** https thumbnail URL; may be {@code ""} but never {@code null}. */
    public final String thumbnail;

    private YouTubeSearchResult(String videoId, String title, String channel, String thumbnail) {
        this.videoId   = videoId;
        this.title     = title;
        this.channel   = channel;
        this.thumbnail = thumbnail;
    }

    /**
     * Builds a result, or returns {@code null} when the row is not renderable/playable.
     *
     * <p>A row is dropped — never repaired — when it has no valid 11-char {@code videoId} or
     * no title, because both cases would produce a dead list row: one that either shows
     * nothing or fails silently when tapped. Missing channel/thumbnail are merely cosmetic
     * and are normalised to {@code ""}.
     */
    public static YouTubeSearchResult of(String videoId, String title, String channel, String thumbnail) {
        String id = videoId == null ? null : videoId.trim();
        if (!YouTubeUrlParser.isValidVideoId(id)) return null;

        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) return null;

        String c = channel == null ? "" : channel.trim();

        String th = thumbnail == null ? "" : thumbnail.trim();
        if (!th.startsWith(HTTPS_PREFIX)) th = "";

        return new YouTubeSearchResult(id, t, c, th);
    }

    /** True when there is a thumbnail worth loading; otherwise the row shows a placeholder. */
    public boolean hasThumbnail() {
        return !thumbnail.isEmpty();
    }

    /**
     * Identity is the video id — that is what {@code DiffUtil.areItemsTheSame} needs, and two
     * rows for the same video are the same row regardless of how YouTube titled them.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof YouTubeSearchResult)) return false;
        return videoId.equals(((YouTubeSearchResult) o).videoId);
    }

    @Override
    public int hashCode() {
        return videoId.hashCode();
    }

    /**
     * Content comparison for {@code DiffUtil.areContentsTheSame} — every rendered field.
     * Separate from {@link #equals} on purpose: same item, possibly changed content.
     */
    public boolean sameContentAs(YouTubeSearchResult other) {
        return other != null
                && videoId.equals(other.videoId)
                && title.equals(other.title)
                && channel.equals(other.channel)
                && thumbnail.equals(other.thumbnail);
    }

    @Override
    public String toString() {
        // Deliberately does not include the title: this is only used in debug logs, and a
        // search result title is a proxy for what the user searched for. See the
        // "search terms are not logged" rule the server follows for the same reason.
        return "YouTubeSearchResult{" + videoId + "}";
    }
}
