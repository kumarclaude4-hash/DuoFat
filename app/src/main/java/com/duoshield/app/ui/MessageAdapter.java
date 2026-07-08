package com.duoshield.app.ui;

import android.animation.ObjectAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.duoshield.app.R;
import com.duoshield.app.models.Message;
import com.duoshield.app.util.DateHeaderHelper;
import com.duoshield.app.util.LinkPreviewFetcher;
import com.duoshield.app.util.LinkPreviewHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnVoicePlayListener {
        void onVoicePlay(Message m, ImageView playPauseBtn, WaveformView waveform, TextView durationView);
    }
    public interface OnMessageLongPressListener {
        void onLongPress(Message m, View anchor);
    }
    public interface OnRetryListener {
        void onRetry(Message m);
    }
    public interface OnReplyTapListener {
        /** Called when the user taps the reply-quote strip inside a bubble. */
        void onReplyTap(String originalMessageId);
    }

    private static final int TYPE_DATE = 0;
    private static final int TYPE_MSG  = 1;

    private List<Message>                    messages       = new ArrayList<>();
    private List<Object>                     displayItems   = new ArrayList<>(); // String | Message
    private final String                     myUid;
    private final OnVoicePlayListener        voiceListener;
    private final OnMessageLongPressListener longPressListener;
    private final OnRetryListener            retryListener;
    private final Set<String>                pinnedIds      = new HashSet<>();
    private String                           playingMsgId   = null;
    private String                           partnerName    = null;
    private String                           highlightedMsgId = null;
    private OnReplyTapListener               replyTapListener = null;
    /** O(1) msgId → senderUid lookup built in rebuildDisplay(); eliminates O(n) scan in onBindViewHolder. */
    private final Map<String, String>        senderByMsgId  = new HashMap<>();

    public MessageAdapter(List<Message> messages, String myUid,
                          OnVoicePlayListener vl, OnMessageLongPressListener ll,
                          OnRetryListener rl) {
        this.messages          = messages != null ? messages : new ArrayList<>();
        this.myUid             = myUid;
        this.voiceListener     = vl;
        this.longPressListener = ll;
        this.retryListener     = rl;
        setHasStableIds(true);
        rebuildDisplay();
    }

    /** Called from ChatMediaActivity once the partner's display name is loaded from Firestore. */
    public void setPartnerName(String name) {
        this.partnerName = name;
    }

    public void setOnReplyTapListener(OnReplyTapListener l) {
        this.replyTapListener = l;
    }

    /**
     * Briefly flashes the item at the given message id — used when the user taps a reply quote
     * and the RecyclerView scrolls to the original message.
     */
    public void highlightMessage(String msgId) {
        String prev = highlightedMsgId;
        highlightedMsgId = msgId;
        if (prev != null) notifyMsgById(prev);
        notifyMsgById(msgId);
        // Auto-clear so the flash doesn't linger if the item is recycled later
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (msgId.equals(highlightedMsgId)) {
                highlightedMsgId = null;
                notifyMsgById(msgId);
            }
        }, 1300);
    }

    /** Returns the adapter position of a message by its id, or -1 if not in the display list. */
    public int findPositionById(String msgId) {
        if (msgId == null) return -1;
        for (int i = 0; i < displayItems.size(); i++) {
            Object item = displayItems.get(i);
            if (item instanceof Message && msgId.equals(((Message) item).getId())) {
                return i;
            }
        }
        return -1;
    }

    /** Replace entire list — uses DiffUtil to animate changes. */
    public void setMessages(List<Message> newList) {
        if (newList == null) newList = new ArrayList<>();
        final List<Object> oldDisplay = new ArrayList<>(displayItems);
        messages = newList;
        rebuildDisplay();
        final List<Object> newDisplay = new ArrayList<>(displayItems);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldDisplay.size(); }
            @Override public int getNewListSize() { return newDisplay.size(); }
            @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                Object o = oldDisplay.get(oldPos);
                Object n = newDisplay.get(newPos);
                if (o instanceof Message && n instanceof Message)
                    return java.util.Objects.equals(((Message) o).getId(), ((Message) n).getId());
                if (o instanceof String && n instanceof String)
                    return o.equals(n);
                return false;
            }
            @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                // For date-separator strings, identity is content.
                // For messages, re-bind on every update (they mutate in place).
                Object o = oldDisplay.get(oldPos);
                Object n = newDisplay.get(newPos);
                if (o instanceof String && n instanceof String) return o.equals(n);
                return false;
            }
        });
        diff.dispatchUpdatesTo(this);
    }

    /** Append a single message. */
    public void appendMessage(Message m) {
        messages.add(m);
        int oldSize = displayItems.size();
        rebuildDisplay();
        int inserted = displayItems.size() - oldSize;
        if (inserted > 0) {
            // 1 item (message only) or 2 items (date header + message) may be inserted.
            notifyItemRangeInserted(oldSize, inserted);
        }
    }

    /**
     * Update a single message in-place (reaction, status, text, etc.).
     *
     * <p>displayItems holds references to the same Message objects as messages, so
     * the mutation is already visible in displayItems immediately. rebuildDisplay()
     * is intentionally NOT called — date separators do not change when a message
     * field changes. Only notifyItemChanged() for the exact position is needed.
     */
    public void updateMessage(String msgId, java.util.function.Consumer<Message> mutator) {
        if (msgId == null || mutator == null) return;
        for (Message msg : messages) {
            if (msgId.equals(msg.getId())) {
                mutator.accept(msg);
                // Find the position of this message in displayItems and notify it.
                for (int j = 0; j < displayItems.size(); j++) {
                    Object item = displayItems.get(j);
                    if (item instanceof Message && msgId.equals(((Message) item).getId())) {
                        notifyItemChanged(j);
                        return;
                    }
                }
                // Safety fallback — shouldn't happen since displayItems mirrors messages.
                notifyDataSetChanged();
                return;
            }
        }
    }

    /** Remove a message by id — uses DiffUtil to animate the deletion. */
    public void removeMessage(String msgId) {
        if (msgId == null) return;
        final List<Object> oldDisplay = new ArrayList<>(displayItems);
        messages.removeIf(m -> msgId.equals(m.getId()));
        rebuildDisplay();
        final List<Object> newDisplay = new ArrayList<>(displayItems);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldDisplay.size(); }
            @Override public int getNewListSize() { return newDisplay.size(); }
            @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                Object o = oldDisplay.get(oldPos);
                Object n = newDisplay.get(newPos);
                if (o instanceof Message && n instanceof Message)
                    return java.util.Objects.equals(((Message) o).getId(), ((Message) n).getId());
                if (o instanceof String && n instanceof String) return o.equals(n);
                return false;
            }
            @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                Object o = oldDisplay.get(oldPos);
                Object n = newDisplay.get(newPos);
                if (o instanceof String && n instanceof String) return o.equals(n);
                return false;
            }
        });
        diff.dispatchUpdatesTo(this);
    }

    public void updatePinnedIds(Set<String> ids) {
        Set<String> oldIds = new HashSet<>(pinnedIds);
        pinnedIds.clear();
        if (ids != null) pinnedIds.addAll(ids);
        
        // PERF-OPT-04: Only notify items that changed pinned status (not the entire list).
        // This eliminates unnecessary rebinds and visual flickers.
        Set<String> changed = new HashSet<>();
        for (String id : oldIds) {
            if (!pinnedIds.contains(id)) changed.add(id);
        }
        for (String id : pinnedIds) {
            if (!oldIds.contains(id)) changed.add(id);
        }
        for (String msgId : changed) {
            notifyMsgById(msgId);
        }
    }

    /**
     * Mark a voice message as currently playing (or pass null to stop all).
     *
     * <p>Only two items need to be rebound: the previously-playing message
     * (pause icon → play icon) and the newly-playing message (play icon → pause
     * icon). Everything else in the list is unchanged.
     */
    public void setPlayingMessageId(String msgId) {
        String oldId = playingMsgId;
        playingMsgId = msgId;
        notifyMsgById(oldId);   // revert old playing item to play icon
        notifyMsgById(msgId);   // set new playing item to pause icon
    }

    /** Find a message by id in displayItems and call notifyItemChanged for its position only. */
    private void notifyMsgById(String msgId) {
        if (msgId == null) return;
        for (int j = 0; j < displayItems.size(); j++) {
            Object item = displayItems.get(j);
            if (item instanceof Message && msgId.equals(((Message) item).getId())) {
                notifyItemChanged(j);
                return;
            }
        }
    }

    public List<Message> getMessages() { return messages; }

    public Object getItemAt(int position) {
        if (position >= 0 && position < displayItems.size()) {
            return displayItems.get(position);
        }
        return null;
    }

    private void rebuildDisplay() {
        // §3.7 fix: the old approach called DateHeaderHelper.getLabel() (a "now"-relative
        // function) to both DECIDE whether to insert a header and to FORMAT its text.
        // Because getLabel() returns "Today"/"Yesterday" relative to the current clock,
        // any rebuildDisplay() call that crosses midnight recomputed every message's label
        // against a different "now", producing repeating "Today / Yesterday / Today / …"
        // headers whenever the adapter was refreshed after midnight.
        //
        // Fix: use DateHeaderHelper.needsHeader(prevTs, currentTs) — which compares two
        // absolute timestamps against each other (not against "now") — to DECIDE placement,
        // and use getLabel() only to FORMAT the text of a header that has already been placed.
        displayItems.clear();
        senderByMsgId.clear();
        long lastTimestamp = -1;
        for (Message m : messages) {
            if (DateHeaderHelper.needsHeader(lastTimestamp, m.getTimestamp())) {
                displayItems.add(DateHeaderHelper.getLabel(m.getTimestamp()));
                lastTimestamp = m.getTimestamp();
            }
            displayItems.add(m);
            // Build O(1) sender lookup for reply-author resolution in onBindViewHolder.
            if (m.getId() != null && m.getSender() != null) {
                senderByMsgId.put(m.getId(), m.getSender());
            }
        }
    }

    // ── Item counts & types ──────────────────────────────────────────

    @Override public int getItemCount() { return displayItems.size(); }

    @Override public long getItemId(int position) {
        Object item = displayItems.get(position);
        if (item instanceof Message) return ((Message) item).getId().hashCode() & 0xFFFFFFFFL;
        return item.hashCode() & 0xFFFFFFFFL;
    }

    @Override public int getItemViewType(int position) {
        return (displayItems.get(position) instanceof String) ? TYPE_DATE : TYPE_MSG;
    }

    // ── ViewHolder creation ──────────────────────────────────────────

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_DATE) {
            return new DateViewHolder(inf.inflate(R.layout.item_date_header, parent, false));
        }
        return new MsgViewHolder(inf.inflate(R.layout.item_message, parent, false));
    }

    // ── Binding ──────────────────────────────────────────────────────

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (getItemViewType(position) == TYPE_DATE) {
            ((DateViewHolder) holder).label.setText((String) displayItems.get(position));
            return;
        }
        bindMessage((MsgViewHolder) holder, (Message) displayItems.get(position));
    }

    private void bindMessage(MsgViewHolder h, Message msg) {
        boolean mine = myUid != null && myUid.equals(msg.getSender());
        String  type = msg.getMediaType();
        Context ctx  = h.itemView.getContext();

        // Reset all content views
        h.textView.setVisibility(View.GONE);
        h.imageView.setVisibility(View.GONE);
        h.videoContainer.setVisibility(View.GONE);
        h.contactCardContainer.setVisibility(View.GONE);
        h.voiceNoteContainer.setVisibility(View.GONE);
        h.replyPreviewContainer.setVisibility(View.GONE);
        h.reactionText.setVisibility(View.GONE);
        if (h.replyAuthorText != null) h.replyAuthorText.setVisibility(View.GONE);
        h.senderLabel.setVisibility(View.GONE);
        h.pinIndicatorRow.setVisibility(View.GONE);
        h.linkPreviewCard.setVisibility(View.GONE);
        h.starIcon.setVisibility(msg.starred ? View.VISIBLE : View.GONE);

        // Restore default bubble padding (overridden to 0 for image/video below)
        int p13 = dp(ctx, 13); int p9 = dp(ctx, 9); int p7 = dp(ctx, 7);
        h.bubble.setPadding(p13, p9, p13, p7);

        // ── Bubble alignment ────────────────────────────────────────
        FrameLayout.LayoutParams lp =
            (FrameLayout.LayoutParams) h.bubble.getLayoutParams();
        lp.gravity = mine ? Gravity.END : Gravity.START;
        h.bubble.setLayoutParams(lp);

        // ── Bubble background — Sanctuary vs Classic ────────────────
        boolean sanctuary = com.duoshield.app.util.UiModeHelper.isSanctuary(ctx);
        if (sanctuary) {
            h.bubble.setBackground(ContextCompat.getDrawable(ctx,
                    mine ? R.drawable.bg_bubble_mine : R.drawable.bg_bubble_theirs));
        } else {
            h.bubble.setBackgroundResource(mine
                    ? R.drawable.bg_bubble_mine_classic
                    : R.drawable.bg_bubble_theirs_classic);
        }

        // ── Partner sender label ────────────────────────────────────
        if (!mine) {
            h.senderLabel.setVisibility(View.VISIBLE);
            h.senderLabel.setText(partnerName != null && !partnerName.isEmpty()
                    ? partnerName : "");
        }

        // ── Pin indicator ───────────────────────────────────────────
        if (pinnedIds.contains(msg.getId())) {
            h.pinIndicatorRow.setVisibility(View.VISIBLE);
            LinearLayout.LayoutParams pinLp =
                (LinearLayout.LayoutParams) h.pinIndicatorRow.getLayoutParams();
            pinLp.gravity = mine ? Gravity.END : Gravity.START;
            h.pinIndicatorRow.setLayoutParams(pinLp);
        }

        // ── Reply preview (WhatsApp-style: sender name + preview text) ─
        String rp = msg.getReplyPreview();
        String replyId = msg.getReplyToId();
        if (rp != null && !rp.isEmpty()) {
            h.replyPreviewContainer.setVisibility(View.VISIBLE);
            h.replyPreviewText.setText(rp);

            // Tap reply strip → scroll to the original message
            final String replyIdFinal = replyId;
            h.replyPreviewContainer.setOnClickListener(v -> {
                if (replyTapListener != null && replyIdFinal != null) {
                    replyTapListener.onReplyTap(replyIdFinal);
                }
            });

            // Resolve sender name via O(1) map (pre-built in rebuildDisplay).
            if (h.replyAuthorText != null && replyId != null) {
                String originalSenderUid = senderByMsgId.get(replyId);
                if (originalSenderUid != null) {
                    String authorLabel = originalSenderUid.equals(myUid)
                            ? "You"
                            : (partnerName != null ? partnerName : "Partner");
                    h.replyAuthorText.setText(authorLabel);
                    // Tint: green for "You", accent for partner
                    h.replyAuthorText.setTextColor(originalSenderUid.equals(myUid)
                            ? 0xFF6BBF8A
                            : ContextCompat.getColor(ctx, R.color.ds_accent));
                    h.replyAuthorText.setVisibility(View.VISIBLE);
                }
            }
        }

        // ── Content ─────────────────────────────────────────────────
        if ("video".equals(type)) {
            // Media goes edge-to-edge on start/top like WhatsApp, but the meta row
            // (timestamp + ticks) lives inside this same padding box — a hard 0 end
            // padding left it glued to the bubble's rounded corner with no breathing
            // room at all, which read as "the video bubble has no padding". Keep a
            // small end/bottom margin so the media still hugs the bubble while the
            // timestamp/ticks get a bit of clearance.
            int bottomPad = (int)(7 * ctx.getResources().getDisplayMetrics().density);
            int endPad    = (int)(6 * ctx.getResources().getDisplayMetrics().density);
            h.bubble.setPadding(0, 0, endPad, bottomPad);
            h.videoContainer.setVisibility(View.VISIBLE);
            String vidRef = msg.getMediaUrl();
            String vidKey = msg.getMediaKey();
            if (com.duoshield.app.util.B2StorageHelper.isB2Path(vidRef)) {
                // B2 encrypted video — tap opens MediaViewerActivity (ExoPlayer).
                // Real thumbnails require downloading + decrypting the video, so show a
                // neutral placeholder immediately and swap in the extracted frame once ready.
                h.videoThumbnail.setTag(vidRef);
                Glide.with(ctx).load(R.drawable.ic_play_video).into(h.videoThumbnail);
                byte[] cachedThumb = com.duoshield.app.util.B2StorageHelper.getCachedThumb(vidRef);
                if (cachedThumb != null) {
                    Glide.with(ctx).load(cachedThumb).centerCrop().into(h.videoThumbnail);
                } else {
                    com.duoshield.app.util.B2StorageHelper.loadVideoThumbnail(ctx, vidRef, vidKey,
                            new com.duoshield.app.util.B2StorageHelper.ThumbnailCallback() {
                        @Override public void onLoaded(byte[] jpegBytes) {
                            if (vidRef.equals(h.videoThumbnail.getTag())) {
                                Glide.with(ctx).load(jpegBytes).centerCrop().into(h.videoThumbnail);
                            }
                        }
                        @Override public void onError(Exception e) {
                            // Keep the static placeholder — non-fatal, video still plays fine.
                        }
                    });
                }
                h.videoContainer.setOnClickListener(v -> {
                    Intent i = new Intent(ctx, com.duoshield.app.MediaViewerActivity.class);
                    i.putExtra(com.duoshield.app.MediaViewerActivity.EXTRA_URL, vidRef);
                    i.putExtra(com.duoshield.app.MediaViewerActivity.EXTRA_MEDIA_KEY, vidKey);
                    ctx.startActivity(i);
                });

            } else {
                // Legacy Firebase Storage URL
                Glide.with(ctx).load(vidRef)
                     .placeholder(R.drawable.ic_play_video).centerCrop().into(h.videoThumbnail);
                h.videoContainer.setOnClickListener(v -> {
                    Intent i = new Intent(ctx, com.duoshield.app.MediaViewerActivity.class);
                    i.putExtra(com.duoshield.app.MediaViewerActivity.EXTRA_URL, vidRef);
                    ctx.startActivity(i);
                });
            }

        } else if ("voice".equals(type)) {
            h.voiceNoteContainer.setVisibility(View.VISIBLE);
            boolean playing = msg.getId() != null && msg.getId().equals(playingMsgId);
            h.voicePlayPauseBtn.setImageResource(
                playing ? android.R.drawable.ic_media_pause : R.drawable.ic_play_audio);
            // Tag views with message ID so async callbacks can detect stale ViewHolders
            h.voicePlayPauseBtn.setTag(msg.getId());
            h.voiceWaveform.setTag(msg.getId());
            h.voiceDuration.setTag(msg.getId());
            // Load waveform bars for display (sender has them from recording; receiver from Firestore).
            // Messages loaded from Room cache lack amplitudes (@Ignore field) — generate a
            // deterministic synthetic waveform from the message ID so the UI never shows a flat line.
            List<Integer> amps = msg.getWaveAmplitudes();
            if (amps != null && !amps.isEmpty()) {
                h.voiceWaveform.setAmplitudes(amps);
            } else {
                h.voiceWaveform.setAmplitudes(syntheticAmplitudes(msg.getId()));
            }
            h.voicePlayPauseBtn.setOnClickListener(v -> {
                if (voiceListener != null)
                    voiceListener.onVoicePlay(msg, h.voicePlayPauseBtn,
                        h.voiceWaveform, h.voiceDuration);
            });
            // WhatsApp's voice bubble stays static — all the "is this playing" feedback
            // comes from the play/pause icon swap and the waveform's own progress thumb,
            // not from animating the bubble itself.
            stopBreathingAnim(h.bubble);

        } else if ("contact_card".equals(type)) {
            h.contactCardContainer.setVisibility(View.VISIBLE);
            String[] p = (msg.getText() != null ? msg.getText() : "").split("\\|", 2);
            h.cardName.setText(p.length > 0 ? p[0] : "DuoShield User");
            String uid = p.length > 1 ? p[1] : "";
            h.cardUid.setText(uid.isEmpty() ? "" : "ID: " + uid);
            h.cardCopyBtn.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager)
                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("uid", uid));
                    Toast.makeText(ctx, "UID copied", Toast.LENGTH_SHORT).show();
                }
            });

        } else if (msg.getMediaUrl() != null && !msg.getMediaUrl().isEmpty()) {
            // Media goes edge-to-edge — remove start/top/end padding, keep bottom for timestamp
            int bottomPad = (int)(7 * ctx.getResources().getDisplayMetrics().density);
            int endPad    = (int)(6 * ctx.getResources().getDisplayMetrics().density);
            h.bubble.setPadding(0, 0, endPad, bottomPad);
            h.imageView.setVisibility(View.VISIBLE);
            String imgRef = msg.getMediaUrl();
            String imgKey = msg.getMediaKey();

            // Tap → full-screen image viewer (PhotoView pinch-zoom)
            h.imageView.setOnClickListener(v -> {
                Intent i = new Intent(ctx, com.duoshield.app.FullScreenImageActivity.class);
                i.putExtra(com.duoshield.app.FullScreenImageActivity.EXTRA_URL, imgRef);
                i.putExtra(com.duoshield.app.FullScreenImageActivity.EXTRA_MEDIA_KEY, imgKey);
                ctx.startActivity(i);
            });

            if (com.duoshield.app.util.B2StorageHelper.isB2Path(imgRef)) {
                // B2 encrypted image — serve from cache instantly, or download+decrypt async
                h.imageView.setTag(imgRef);
                // Check the in-memory cache first — avoids showing a placeholder for already-loaded images
                byte[] cached = com.duoshield.app.util.B2StorageHelper.getCached(imgRef);
                if (cached != null) {
                    Glide.with(ctx).load(cached).centerCrop().into(h.imageView);
                } else {
                    Glide.with(ctx).load(R.drawable.ic_image).centerCrop().into(h.imageView);
                    com.duoshield.app.util.B2StorageHelper.loadMedia(ctx, imgRef, imgKey,
                            new com.duoshield.app.util.B2StorageHelper.MediaCallback() {
                        @Override public void onLoaded(byte[] plainBytes) {
                            if (imgRef.equals(h.imageView.getTag())) {
                                Glide.with(ctx).load(plainBytes).centerCrop().into(h.imageView);
                            }
                        }
                        @Override public void onError(Exception e) {
                            if (imgRef.equals(h.imageView.getTag())) {
                                Glide.with(ctx).load(android.R.drawable.ic_dialog_alert)
                                     .into(h.imageView);
                            }
                        }
                    });
                }
            } else {
                // Legacy Firebase Storage URL
                Glide.with(ctx).load(imgRef)
                     .placeholder(android.R.drawable.ic_menu_gallery).into(h.imageView);
            }

        } else {
            // Plain text — show text and check for link preview
            h.textView.setVisibility(View.VISIBLE);
            h.textView.setText(msg.getText());
            bindLinkPreview(h, msg, ctx);
        }

        // ── Edited label ────────────────────────────────────────────
        h.editedLabel.setVisibility(msg.isEdited() ? View.VISIBLE : View.GONE);

        // ── Timestamp ───────────────────────────────────────────────
        long ts = msg.getTimestamp();
        if (ts > 0) {
            h.timestampView.setText(new java.text.SimpleDateFormat("HH:mm",
                java.util.Locale.getDefault()).format(new java.util.Date(ts)));
        }

        // ── Delivery ticks ──────────────────────────────────────────
        com.duoshield.app.util.MessageStatusHelper.bind(h.tickIcon, msg,
            myUid != null ? myUid : "");

        // ── Seen-at label ────────────────────────────────────────────
        // Show "Seen HH:mm" below the blue ticks for my outgoing messages only.
        if (h.tvSeenAt != null) {
            boolean isMine = myUid != null && myUid.equals(msg.getSender());
            boolean isRead = "read".equals(msg.getStatus());
            long readAt    = msg.getReadAt();
            if (isMine && isRead && readAt > 0) {
                String seenTime = new java.text.SimpleDateFormat("HH:mm",
                        java.util.Locale.getDefault()).format(new java.util.Date(readAt));
                h.tvSeenAt.setText("Seen " + seenTime);
                h.tvSeenAt.setVisibility(android.view.View.VISIBLE);
            } else {
                h.tvSeenAt.setVisibility(android.view.View.GONE);
            }
        }

        // ── Reply-quote highlight flash ─────────────────────────────
        if (msg.getId() != null && msg.getId().equals(highlightedMsgId)) {
            // Accent-tinted flash on the whole row; fades as highlightedMsgId clears
            h.itemView.setBackgroundColor(0x2200A884);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> h.itemView.setBackgroundColor(0x00000000), 1100);
        } else {
            h.itemView.setBackgroundColor(0x00000000);
        }

        // ── Reaction (WhatsApp style: floating emoji pill below bubble corner) ──
        String reaction = msg.getReaction();
        if (reaction != null && !reaction.isEmpty()) {
            h.reactionText.setVisibility(View.VISIBLE);
            h.reactionText.setText(reaction);
            h.reactionText.setBackground(ContextCompat.getDrawable(ctx, R.drawable.bg_reaction_badge));
            LinearLayout.LayoutParams rlp = (LinearLayout.LayoutParams) h.reactionText.getLayoutParams();
            int margin8 = (int) (8 * ctx.getResources().getDisplayMetrics().density);
            // Align pill to same side as bubble; pull it up to float against bubble bottom
            rlp.gravity   = mine ? Gravity.END : Gravity.START;
            rlp.topMargin = -(int)(20 * ctx.getResources().getDisplayMetrics().density);
            rlp.leftMargin  = mine ? 0 : margin8;
            rlp.rightMargin = mine ? margin8 : 0;
            h.reactionText.setLayoutParams(rlp);
        }

        h.bubble.setOnClickListener(v -> {
            if ("failed".equals(msg.getStatus()) && retryListener != null) {
                retryListener.onRetry(msg);
            }
        });

        h.bubble.setOnLongClickListener(v -> {
            if (longPressListener != null) longPressListener.onLongPress(msg, v);
            return true;
        });
    }

    // ── Link preview ──────────────────────────────────────────────────

    private void bindLinkPreview(MsgViewHolder h, Message msg, Context ctx) {
        String text = msg.getText();
        if (text == null || text.isEmpty()) return;

        String url = LinkPreviewHelper.extractFirstUrl(text);
        if (url == null) return;

        // Tag the card with the message id so stale async callbacks don't corrupt recycled views
        h.linkPreviewCard.setTag(msg.getId());

        // PERF-OPT-05: Fetch link previews asynchronously without blocking the bind thread.
        // If the preview is already cached, it will be returned immediately from the cache
        // (which is the common case for repeated URLs). Only uncached URLs trigger network I/O.
        LinkPreviewFetcher.fetch(url, preview -> {
            // Verify this view holder still belongs to the same message
            if (!msg.getId().equals(h.linkPreviewCard.getTag())) return;
            if (preview == null) return;

            h.linkPreviewCard.setVisibility(View.VISIBLE);

            // Domain
            h.linkPreviewDomain.setText(preview.domain != null ? preview.domain : "");

            // Title (hide row if empty)
            if (preview.title != null && !preview.title.isEmpty()) {
                h.linkPreviewTitle.setVisibility(View.VISIBLE);
                h.linkPreviewTitle.setText(preview.title);
            } else {
                h.linkPreviewTitle.setVisibility(View.GONE);
            }

            // OG image
            if (preview.imageUrl != null && !preview.imageUrl.isEmpty()) {
                h.linkPreviewImage.setVisibility(View.VISIBLE);
                Glide.with(ctx).load(preview.imageUrl)
                     .centerCrop()
                     .placeholder(android.R.drawable.ic_menu_gallery)
                     .into(h.linkPreviewImage);
            } else {
                h.linkPreviewImage.setVisibility(View.GONE);
            }

            // Tap to open in browser
            h.linkPreviewCard.setOnClickListener(v -> {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(preview.url));
                ctx.startActivity(i);
            });
        });
    }

    // ── Legacy bubble animation cleanup ────────────────────────────────────────
    // Voice bubbles used to "breathe" (scale-pulse) while playing; that's been
    // dropped in favor of WhatsApp's static-bubble look. stopBreathingAnim() is
    // kept so any bubble that was mid-animation before this change (or a recycled
    // view holder) always gets reset back to scale 1.0.

    /** Tag key used to store the running ObjectAnimator on the bubble view. */
    private static final int TAG_BREATHING_ANIM = R.id.voicePlayPauseBtn;

    /** Stops the breathing animation and resets scale to 1.0. */
    private static void stopBreathingAnim(View bubbleView) {
        Object tag = bubbleView.getTag(TAG_BREATHING_ANIM);
        if (tag instanceof ObjectAnimator) {
            ((ObjectAnimator) tag).cancel();
        }
        bubbleView.setScaleX(1f);
        bubbleView.setScaleY(1f);
        bubbleView.setTag(TAG_BREATHING_ANIM, null);
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof MsgViewHolder) {
            stopBreathingAnim(((MsgViewHolder) holder).bubble);
        }
    }

    // ── ViewHolders ──────────────────────────────────────────────────

    static class DateViewHolder extends RecyclerView.ViewHolder {
        TextView label;
        DateViewHolder(View v) {
            super(v);
            label = v.findViewById(R.id.tvDateLabel);
        }
    }

    static class MsgViewHolder extends RecyclerView.ViewHolder {
        TextView     senderLabel, textView, cardName, cardUid,
                     replyAuthorText, replyPreviewText, reactionText, editedLabel,
                     timestampView, voiceDuration,
                     linkPreviewDomain, linkPreviewTitle,
                     tvSeenAt;
        ImageView    imageView, videoThumbnail, videoPlayBtn,
                     tickIcon, starIcon, voicePlayPauseBtn, linkPreviewImage;
        LinearLayout bubble, voiceNoteContainer, pinIndicatorRow, linkPreviewCard;
        FrameLayout  bubbleWrapper;
        View         videoContainer, contactCardContainer, replyPreviewContainer;
        WaveformView voiceWaveform;
        Button       cardCopyBtn;

        MsgViewHolder(View v) {
            super(v);
            senderLabel           = v.findViewById(R.id.senderLabel);
            pinIndicatorRow       = v.findViewById(R.id.pinIndicatorRow);
            bubbleWrapper         = v.findViewById(R.id.bubbleWrapper);
            bubble                = v.findViewById(R.id.messageBubble);
            textView              = v.findViewById(R.id.messageText);
            imageView             = v.findViewById(R.id.messageImage);
            videoContainer        = v.findViewById(R.id.videoContainer);
            videoThumbnail        = v.findViewById(R.id.videoThumbnail);
            videoPlayBtn          = v.findViewById(R.id.videoPlayBtn);
            contactCardContainer  = v.findViewById(R.id.contactCardContainer);
            cardName              = v.findViewById(R.id.cardName);
            cardUid               = v.findViewById(R.id.cardUid);
            cardCopyBtn           = v.findViewById(R.id.cardCopyBtn);
            tickIcon              = v.findViewById(R.id.tickIcon);
            starIcon              = v.findViewById(R.id.starIcon);
            replyPreviewContainer = v.findViewById(R.id.replyPreviewContainer);
            replyAuthorText       = v.findViewById(R.id.replyAuthorText);
            replyPreviewText      = v.findViewById(R.id.replyPreviewText);
            reactionText          = v.findViewById(R.id.reactionText);
            editedLabel           = v.findViewById(R.id.editedLabel);
            timestampView         = v.findViewById(R.id.messageTimestamp);
            voiceNoteContainer    = v.findViewById(R.id.voiceNoteContainer);
            voicePlayPauseBtn     = v.findViewById(R.id.voicePlayPauseBtn);
            voiceWaveform         = v.findViewById(R.id.voiceWaveform);
            voiceDuration         = v.findViewById(R.id.voiceDuration);
            // Link preview
            linkPreviewCard       = v.findViewById(R.id.linkPreviewCard);
            linkPreviewImage      = v.findViewById(R.id.linkPreviewImage);
            linkPreviewDomain     = v.findViewById(R.id.linkPreviewDomain);
            linkPreviewTitle      = v.findViewById(R.id.linkPreviewTitle);
            tvSeenAt              = v.findViewById(R.id.tvSeenAt);
        }
    }

    /**
     * Generates a deterministic pseudo-random amplitude list for voice notes that were
     * loaded from Room cache (where the @Ignore {@code waveAmplitudes} field is null).
     * Uses the message ID as a seed so the waveform is stable across re-binds.
     */
    private static List<Integer> syntheticAmplitudes(String msgId) {
        long seed = msgId != null ? msgId.hashCode() : 0L;
        java.util.Random rng = new java.util.Random(seed);
        List<Integer> amps = new ArrayList<>(40);
        // Generate a speech-like envelope: rises, peaks, then tapers
        for (int i = 0; i < 40; i++) {
            float envelope = (float) Math.sin(Math.PI * i / 39.0);
            int amp = (int)(200 + envelope * 2800 * (0.4f + 0.6f * rng.nextFloat()));
            amps.add(Math.max(100, amp));
        }
        return amps;
    }

    /** Convert dp to pixels. */
    private static int dp(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }

    /** Format milliseconds → "m:ss" */
    public static String formatDuration(int ms) {
        int secs = ms / 1000;
        return String.format(Locale.US, "%d:%02d", secs / 60, secs % 60);
    }
}

