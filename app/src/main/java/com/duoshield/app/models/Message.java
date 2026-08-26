package com.duoshield.app.models;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.ColumnInfo;
import androidx.room.Ignore;
import androidx.annotation.NonNull;
import java.util.List;

@Entity(tableName = "messages")
public class Message {

    @PrimaryKey @NonNull @ColumnInfo(name = "id")      public String  id;
    @ColumnInfo(name = "conversationId")               public String  conversationId;
    @ColumnInfo(name = "sender")                       public String  sender;
    @ColumnInfo(name = "text")                         public String  text;
    @ColumnInfo(name = "timestamp")                    public long    timestamp;
    @ColumnInfo(name = "isEncrypted")                  public boolean isEncrypted;
    @ColumnInfo(name = "mediaUrl")                     public String  mediaUrl;
    @ColumnInfo(name = "mediaType")                    public String  mediaType;
    @ColumnInfo(name = "delivered")                    public boolean delivered;
    @ColumnInfo(name = "seen")                         public boolean seen;
    @ColumnInfo(name = "replyToId")                    public String  replyToId;
    @ColumnInfo(name = "replyPreview")                 public String  replyPreview;
    @ColumnInfo(name = "expiresAt")                    public long    expiresAt;
    @ColumnInfo(name = "reaction")                     public String  reaction;
    @ColumnInfo(name = "edited")                       public boolean edited;
    @ColumnInfo(name = "starred")                      public boolean starred;
    @ColumnInfo(name = "status")                       public String  status;
    @ColumnInfo(name = "sigType", defaultValue = "0")  public int     sigType;
    @ColumnInfo(name = "mediaKey")                     public String  mediaKey;
    @ColumnInfo(name = "isDeleted", defaultValue = "0")  public boolean isDeleted;

    /** True when this message was forwarded from another conversation. */
    @ColumnInfo(name = "forwarded", defaultValue = "0")  public boolean forwarded;

    /**
     * Optional caption for photo/video messages — shown below the media in the bubble.
     * Empty string means no caption.
     */
    @ColumnInfo(name = "caption", defaultValue = "")  public String caption;
    public String getCaption()     { return caption != null ? caption : ""; }
    public void   setCaption(String v) { caption = v; }

    /**
     * JSON array of media items for multi-photo/video messages, e.g.:
     * [{"url":"b2:...","key":"base64","type":"image"}, ...]
     * Null for single-media and text messages.
     */
    @ColumnInfo(name = "media_items")  public String mediaItems;
    public String getMediaItems()      { return mediaItems; }
    public void   setMediaItems(String v) { mediaItems = v; }

    /**
     * Inline thumbnail — a ~1.5 KB JPEG postage stamp of the photo or video, AES-GCM
     * sealed under {@link #mediaKey} and base64-encoded, carried inside the message
     * document itself rather than fetched separately.
     *
     * <p>This is what lets a media bubble paint the moment the Firestore snapshot lands.
     * Without it the bubble has only {@code mediaUrl} + {@code mediaKey}, so it must wait
     * on a full B2 download and decrypt before it can show anything — and for video that
     * meant pulling the entire object down purely to extract one frame.
     *
     * <p>Null for text messages, for voice notes, and for media sent by older clients
     * that predate this field. Every consumer therefore has to treat it as optional and
     * fall back to the existing download path.
     *
     * @see com.duoshield.app.util.InlineThumb
     */
    @ColumnInfo(name = "thumb")  public String thumb;
    public String getThumb()     { return thumb; }
    public void   setThumb(String v) { thumb = v; }

    /**
     * True when this message's media is stored in the chunked, range-addressable v2 format
     * rather than the original single whole-blob AES-GCM layout.
     *
     * <p>The two formats are not distinguishable from the object's own bytes at the point where
     * playback has to choose a decrypt path, and sniffing a leading version byte would mean
     * letting an attacker-influenced byte pick which code path runs. So the format is recorded
     * on the message that names the object and carried explicitly — see
     * {@link com.duoshield.app.util.B2StorageHelper#CHUNKED_FORMAT_VERSION}.
     *
     * <p>Unlike {@link #thumb} this is a non-null boolean defaulting to false, and that default
     * is meaningful rather than a placeholder: every row written before this field existed
     * genuinely holds whole-blob media, so "absent" and "false" describe the same reality. A
     * nullable tri-state would add an "unknown" case that no reader could act on.
     */
    @ColumnInfo(name = "chunked", defaultValue = "0")  public boolean chunked;
    public boolean isChunked()      { return chunked; }
    public void    setChunked(boolean v) { chunked = v; }

    /**
     * Total voice-note duration in milliseconds, known at record time and stored
     * so the bubble can show the real length at rest (before playback starts)
     * instead of a static placeholder.
     */
    @ColumnInfo(name = "durationMs", defaultValue = "0") public int durationMs;
    public int getDurationMs() { return durationMs; }
    public void setDurationMs(int v) { durationMs = v; }

    /**
     * Millisecond timestamp when the partner read this message.
     * Populated from Firestore {@code readAt} field on a MODIFIED event.
     * Not stored in Room (marked @Ignore) — it is live data shown only in the
     * current session, and it only matters for outgoing messages the sender can see.
     */
    @androidx.room.Ignore
    public long readAt;

    /**
     * Downsampled amplitude bars for waveform display on voice messages.
     * Persisted as a comma-separated string in the {@code amplitudes} column
     * (Room has no List&lt;Integer&gt; TypeConverter registered) so real,
     * recorded/received amplitudes survive Room reload — a chat reopened from
     * local cache used to fall back to a synthetic fake waveform because this
     * was previously @Ignore-d.
     */
    @ColumnInfo(name = "amplitudes")
    public String amplitudesCsv;

    /** In-memory cache of the parsed CSV — avoids re-parsing on every bind. */
    @Ignore
    private List<Integer> waveAmplitudes;

    public List<Integer> getWaveAmplitudes() {
        if (waveAmplitudes == null && amplitudesCsv != null && !amplitudesCsv.isEmpty()) {
            List<Integer> parsed = new java.util.ArrayList<>();
            for (String part : amplitudesCsv.split(",")) {
                try { parsed.add(Integer.parseInt(part.trim())); } catch (NumberFormatException ignored) {}
            }
            waveAmplitudes = parsed;
        }
        return waveAmplitudes;
    }

    public void setWaveAmplitudes(List<Integer> v) {
        waveAmplitudes = v;
        if (v == null || v.isEmpty()) {
            amplitudesCsv = null;
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < v.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(v.get(i));
            }
            amplitudesCsv = sb.toString();
        }
    }

    /**
     * Per-user reactions as a JSON object mapping uid to a single emoji, e.g.
     * {@code {"uidA":"\uD83D\uDC4D","uidB":"\u2764\uFE0F"}}.
     *
     * <p>This replaces the single-valued {@link #reaction} column, which could only ever hold
     * one emoji for the whole message — so the second person to react silently overwrote the
     * first, and neither could tell whose reaction they were looking at or remove only their
     * own. The legacy column is kept for rows (and peers) written before this field existed;
     * see {@link #getReactionsMap()} for how the two are reconciled.
     *
     * <p>Stored as JSON text rather than a normalised child table because reactions are always
     * read and written together with their message and never queried independently.
     */
    @ColumnInfo(name = "reactions")  public String reactions;

    /** In-memory cache of the parsed JSON — avoids re-parsing on every adapter bind. */
    @Ignore
    private java.util.LinkedHashMap<String, String> reactionsCache;

    public String getReactions() { return reactions; }

    public void setReactions(String v) {
        reactions      = v;
        reactionsCache = null;   // invalidate — next read re-parses
    }

    /**
     * Parsed uid → emoji map, never null.
     *
     * <p>Falls back to the legacy single {@link #reaction} value under the sentinel uid
     * {@link #LEGACY_REACTION_UID} when the map is absent, so a message reacted to by an older
     * client still shows its emoji and the local user can still clear it.
     */
    public java.util.Map<String, String> getReactionsMap() {
        if (reactionsCache != null) return reactionsCache;
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        if (reactions != null && !reactions.isEmpty()) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(reactions);
                for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String uid   = it.next();
                    String emoji = o.optString(uid, "");
                    if (!emoji.isEmpty()) map.put(uid, emoji);
                }
            } catch (org.json.JSONException ignored) {
                // Malformed JSON: treat as no reactions rather than crashing a bind.
            }
        }
        if (map.isEmpty() && reaction != null && !reaction.isEmpty()) {
            map.put(LEGACY_REACTION_UID, reaction);
        }
        reactionsCache = map;
        return map;
    }

    /**
     * Sentinel key for a reaction carried in the legacy {@link #reaction} column, where the
     * reacting user was never recorded. Surfacing it under a reserved uid (rather than
     * discarding it) keeps old reactions visible and removable.
     */
    @Ignore
    public static final String LEGACY_REACTION_UID = "__legacy__";

    /** This user's current reaction, or null if they have not reacted. */
    public String getReactionFor(String uid) {
        return getReactionsMap().get(uid);
    }

    /** Sets (or replaces) {@code uid}'s reaction. */
    public void setReactionFor(String uid, String emoji) {
        java.util.Map<String, String> map =
                new java.util.LinkedHashMap<>(getReactionsMap());
        map.put(uid, emoji);
        writeReactionsMap(map);
    }

    /**
     * Clears {@code uid}'s reaction. Also drops any legacy value, because a user removing
     * "their" reaction on an old message means removing the one shown to them.
     */
    public void removeReactionFor(String uid) {
        java.util.Map<String, String> map =
                new java.util.LinkedHashMap<>(getReactionsMap());
        map.remove(uid);
        map.remove(LEGACY_REACTION_UID);
        writeReactionsMap(map);
        reaction = null;
    }

    private void writeReactionsMap(java.util.Map<String, String> map) {
        if (map.isEmpty()) {
            reactions = null;
        } else {
            reactions = new org.json.JSONObject(map).toString();
        }
        reactionsCache = null;
    }

    /**
     * Display summary for the reaction pill: each distinct emoji once, in insertion order,
     * with a count appended when more than one person picked the same one (e.g. {@code "👍2❤️"}).
     * Empty string when nobody has reacted.
     */
    public String getReactionSummary() {
        java.util.Map<String, String> map = getReactionsMap();
        if (map.isEmpty()) return "";
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String emoji : map.values()) {
            Integer c = counts.get(emoji);
            counts.put(emoji, c == null ? 1 : c + 1);
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            sb.append(e.getKey());
            if (e.getValue() > 1) sb.append(e.getValue());
        }
        return sb.toString();
    }

    public Message() {}

    /** 6-arg convenience constructor (no media). */
    public Message(@NonNull String id, String conversationId, String sender,
                   String text, long timestamp, boolean isEncrypted) {
        this.id             = id;
        this.conversationId = conversationId;
        this.sender         = sender;
        this.text           = text;
        this.timestamp      = timestamp;
        this.isEncrypted    = isEncrypted;
        this.status         = "sent";
    }

    /** 8-arg convenience constructor (with media). */
    public Message(@NonNull String id, String conversationId, String sender,
                   String text, long timestamp, boolean isEncrypted,
                   String mediaUrl, String mediaType) {
        this(id, conversationId, sender, text, timestamp, isEncrypted);
        this.mediaUrl  = mediaUrl;
        this.mediaType = mediaType;
    }

    @NonNull public String getId()           { return id; }
    public String  getConversationId()       { return conversationId; }
    public String  getSender()               { return sender; }
    public String  getText()                 { return text; }
    public long    getTimestamp()            { return timestamp; }
    public boolean isEncrypted()             { return isEncrypted; }
    public String  getMediaUrl()             { return mediaUrl; }
    public String  getMediaType()            { return mediaType; }
    public boolean isDelivered()             { return delivered; }
    public boolean isSeen()                  { return seen; }
    public String  getReplyToId()            { return replyToId; }
    public String  getReplyPreview()         { return replyPreview; }
    public long    getExpiresAt()            { return expiresAt; }
    public String  getReaction()             { return reaction; }
    public boolean isEdited()                { return edited; }
    public String  getStatus()               { return status != null ? status : "sent"; }
    public String  getMediaKey()             { return mediaKey; }

    public void setId(@NonNull String v)     { id = v; }
    public void setConversationId(String v)  { conversationId = v; }
    public void setSender(String v)          { sender = v; }
    public void setText(String v)            { text = v; }
    public void setTimestamp(long v)         { timestamp = v; }
    public void setEncrypted(boolean v)      { isEncrypted = v; }
    public void setMediaUrl(String v)        { mediaUrl = v; }
    public void setMediaType(String v)       { mediaType = v; }
    public void setDelivered(boolean v)      { delivered = v; }
    public void setSeen(boolean v)           { seen = v; }
    public void setReplyToId(String v)       { replyToId = v; }
    public void setReplyPreview(String v)    { replyPreview = v; }
    public void setExpiresAt(long v)         { expiresAt = v; }
    public void setReaction(String v)        { reaction = v; }
    public void setEdited(boolean v)         { edited = v; }
    public void setStatus(String v)          { status = v; }
    public void setMediaKey(String v)        { mediaKey = v; }
    public boolean isDeleted()               { return isDeleted; }
    public void setDeleted(boolean v)        { isDeleted = v; }
    public boolean isForwarded()             { return forwarded; }
    public void setForwarded(boolean v)      { forwarded = v; }
    public long    getReadAt()               { return readAt; }
    public void    setReadAt(long v)         { readAt = v; }
}
