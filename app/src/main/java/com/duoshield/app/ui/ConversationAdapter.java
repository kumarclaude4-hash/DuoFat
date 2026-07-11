package com.duoshield.app.ui;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.duoshield.app.R;
import com.duoshield.app.models.Conversation;
import com.duoshield.app.util.LastSeenFormatter;
import com.duoshield.app.util.TimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class ConversationAdapter
        extends RecyclerView.Adapter<ConversationAdapter.ViewHolder> {

    public interface OnConversationClickListener {
        void onClick(Conversation conv);
        void onLongClick(Conversation conv);
    }

    private List<Conversation> items = new ArrayList<>();
    private final OnConversationClickListener listener;

    /** Teal-palette shades for avatar initials when no photo is available. */
    private static final int[] AVATAR_COLORS = {
        0xFF3A2898, 0xFF4A38B0, 0xFF6654E8, 0xFF5040C0, 0xFF2A1880
    };

    public ConversationAdapter(OnConversationClickListener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setConversations(List<Conversation> newList) {
        if (newList == null) newList = new ArrayList<>();
        final List<Conversation> oldList = items;
        final List<Conversation> finalNew = newList;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldList.size(); }
            @Override public int getNewListSize() { return finalNew.size(); }
            @Override public boolean areItemsTheSame(int o, int n) {
                String oid = oldList.get(o).id;
                String nid = finalNew.get(n).id;
                return oid != null && oid.equals(nid);
            }
            @Override public boolean areContentsTheSame(int o, int n) {
                Conversation a = oldList.get(o), b = finalNew.get(n);
                return safeEquals(a.lastMessage, b.lastMessage)
                    && a.lastMessageTs == b.lastMessageTs
                    && a.unreadCount == b.unreadCount
                    && a.isOnline == b.isOnline
                    && a.isTyping == b.isTyping
                    && a.lastSeen == b.lastSeen
                    && safeEquals(a.avatarUrl, b.avatarUrl)
                    && safeEquals(a.partnerName, b.partnerName);
            }
        });
        items = new ArrayList<>(newList);
        diff.dispatchUpdatesTo(this);
    }

    @NonNull @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_conversation, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int pos) {
        Conversation c = items.get(pos);

        // Name
        String name = c.partnerName != null ? c.partnerName : "Unknown";
        h.name.setText(name);

        // Timestamp
        h.time.setText(TimeFormatter.format(c.lastMessageTs));

        // Preview vs typing
        if (c.isTyping) {
            h.preview.setVisibility(View.GONE);
            h.typing.setVisibility(View.VISIBLE);
        } else {
            h.preview.setVisibility(View.VISIBLE);
            h.typing.setVisibility(View.GONE);
            h.preview.setText(c.lastMessage != null ? c.lastMessage : "");
        }

        // Unread badge
        int unread = c.unreadCount;
        if (unread > 0 && !c.isMuted) {
            h.badge.setVisibility(View.VISIBLE);
            h.badge.setText(unread > 99 ? "99+" : String.valueOf(unread));
        } else {
            h.badge.setVisibility(View.GONE);
        }

        // Online dot
        h.onlineDot.setVisibility(c.isOnline ? View.VISIBLE : View.GONE);

        // Last seen / online subtitle
        if (h.tvLastSeen != null) {
            if (c.isOnline) {
                h.tvLastSeen.setVisibility(View.VISIBLE);
                h.tvLastSeen.setText("Online");
                h.tvLastSeen.setTextColor(0xFF6BBF8A);
            } else if (c.lastSeen > 0) {
                h.tvLastSeen.setVisibility(View.VISIBLE);
                h.tvLastSeen.setText(LastSeenFormatter.format(c.lastSeen, false));
                h.tvLastSeen.setTextColor(0xFF9A8FB0);
            } else {
                h.tvLastSeen.setVisibility(View.GONE);
            }
        }

        // Avatar: photo → initials fallback
        String avatarUrl = c.avatarUrl;
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            h.initial.setVisibility(View.GONE);
            h.avatar.setVisibility(View.VISIBLE);
            com.duoshield.app.util.GlideHelper.loadAvatar(h.avatar.getContext(), avatarUrl, h.avatar);
        } else {
            h.avatar.setVisibility(View.INVISIBLE);
            h.initial.setVisibility(View.VISIBLE);
            String initial = name.isEmpty() ? "?" : String.valueOf(name.charAt(0)).toUpperCase();
            h.initial.setText(initial);
            int color = AVATAR_COLORS[Math.abs(name.hashCode()) % AVATAR_COLORS.length];
            h.initial.getBackground().setTint(color);
        }

        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onClick(c); });
        h.itemView.setOnLongClickListener(v -> { if (listener != null) listener.onLongClick(c); return true; });
    }

    @Override public int getItemCount() { return items.size(); }

    @Override public long getItemId(int position) {
        String id = items.get(position).id;
        return id != null ? id.hashCode() & 0xFFFFFFFFL : position;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView avatar;
        TextView  initial, name, preview, time, badge, typing, tvLastSeen;
        View      onlineDot;

        ViewHolder(@NonNull View v) {
            super(v);
            avatar     = v.findViewById(R.id.iv_avatar);
            initial    = v.findViewById(R.id.tv_avatar_initial);
            name       = v.findViewById(R.id.tv_name);
            preview    = v.findViewById(R.id.tv_preview);
            time       = v.findViewById(R.id.tv_time);
            badge      = v.findViewById(R.id.tv_badge);
            typing     = v.findViewById(R.id.tv_typing);
            onlineDot  = v.findViewById(R.id.online_dot);
            tvLastSeen = v.findViewById(R.id.tv_last_seen);
        }
    }

    private static boolean safeEquals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
