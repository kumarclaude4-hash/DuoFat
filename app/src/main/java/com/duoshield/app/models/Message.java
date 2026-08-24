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
