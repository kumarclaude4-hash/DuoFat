package com.duoshield.app;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.duoshield.app.models.Message;
import com.duoshield.app.util.TimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SearchResultsAdapter extends RecyclerView.Adapter<SearchResultsAdapter.VH> {

    public interface OnResultClickListener {
        void onResultClick(Message message);
    }

    private List<Message>       messages    = new ArrayList<>();
    private String              myUid;
    private String              partnerName;
    private String              currentQuery = "";
    private OnResultClickListener clickListener;

    /**
     * True for a cross-conversation list (global search / Starred Messages), where each
     * row can belong to a different chat — so the label must show which chat it's from
     * rather than the fixed "You"/"Partner" used for a single-conversation search.
     */
    private boolean globalMode;

    /** conversationId -> display name (contact or group name), populated only in global mode. */
    private java.util.Map<String, String> conversationNames;

    public SearchResultsAdapter(List<Message> messages) {
        this.messages = messages != null ? messages : new ArrayList<>();
    }

    public void setUids(String myUid, String partnerName) {
        this.myUid       = myUid;
        this.partnerName = partnerName;
    }

    public void setGlobalMode(boolean global) {
        this.globalMode = global;
    }

    public void setConversationNames(java.util.Map<String, String> names) {
        this.conversationNames = names;
        if (globalMode) notifyDataSetChanged();
    }

    public void setQuery(String query) {
        this.currentQuery = query != null ? query.trim().toLowerCase(Locale.getDefault()) : "";
    }

    public void setOnResultClickListener(OnResultClickListener l) {
        this.clickListener = l;
    }

    public void setMessages(List<Message> newList) {
        if (newList == null) newList = new ArrayList<>();
        final List<Message> oldList  = messages;
        final List<Message> finalNew = newList;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldList.size(); }
            @Override public int getNewListSize() { return finalNew.size(); }
            @Override public boolean areItemsTheSame(int o, int n) {
                String oid = oldList.get(o).getId();
                String nid = finalNew.get(n).getId();
                return oid != null && oid.equals(nid);
            }
            @Override public boolean areContentsTheSame(int o, int n) {
                String a = oldList.get(o).getText();
                String b = finalNew.get(n).getText();
                return (a == null && b == null) || (a != null && a.equals(b));
            }
        });
        messages = finalNew;
        diff.dispatchUpdatesTo(this);
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_search_result, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        Message m = messages.get(pos);

        // ── Sender label ──────────────────────────────────────────────
        // In global mode (all conversations mixed together) this shows which chat the
        // result is from, since "You"/"Partner" alone can't distinguish between chats.
        // In single-conversation mode it shows who sent the message, as before.
        if (h.tvSender != null) {
            if (globalMode) {
                String name = conversationNames != null ? conversationNames.get(m.getConversationId()) : null;
                h.tvSender.setText(name != null ? name : "Unknown chat");
            } else {
                boolean mine = myUid != null && myUid.equals(m.getSender());
                h.tvSender.setText(mine ? "You" : (partnerName != null ? partnerName : "Partner"));
            }
        }

        // ── Timestamp ─────────────────────────────────────────────────
        h.tvTime.setText(TimeFormatter.format(m.getTimestamp()));

        // ── Message text with highlighted match snippet ────────────────
        String text = m.getText();
        if (text == null || text.isEmpty()) {
            h.tvText.setText(com.duoshield.app.util.MessageLabelHelper.describe(m));
        } else if (!currentQuery.isEmpty()) {
            h.tvText.setText(buildHighlightedSnippet(text, currentQuery));
        } else {
            h.tvText.setText(text);
        }

        // ── Click → jump to message in chat ───────────────────────────
        h.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onResultClick(m);
        });
    }

    @Override public int getItemCount() { return messages.size(); }

    /**
     * Returns a compact snippet (~150 chars) centred on the first match,
     * with every occurrence of {@code query} rendered in accent colour + bold.
     */
    private CharSequence buildHighlightedSnippet(String text, String query) {
        String lower = text.toLowerCase(Locale.getDefault());
        int matchStart = lower.indexOf(query);
        if (matchStart < 0) return text;

        String snippet    = text;
        if (text.length() > 150) {
            int from = Math.max(0, matchStart - 40);
            int to   = Math.min(text.length(), from + 150);
            snippet = (from > 0 ? "…" : "") + text.substring(from, to) + (to < text.length() ? "…" : "");
        }

        String lowerSnip = snippet.toLowerCase(Locale.getDefault());
        SpannableString ss = new SpannableString(snippet);
        int start = 0;
        while (true) {
            int idx = lowerSnip.indexOf(query, start);
            if (idx < 0) break;
            int end = idx + query.length();
            ss.setSpan(new ForegroundColorSpan(0xFF9A81FF), idx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ss.setSpan(new BackgroundColorSpan(0x1A00C9E0),  idx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ss.setSpan(new StyleSpan(Typeface.BOLD),          idx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            start = end;
        }
        return ss;
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvText, tvTime, tvSender;
        VH(View v) {
            super(v);
            tvText   = v.findViewById(R.id.tv_text);
            tvTime   = v.findViewById(R.id.tv_time);
            tvSender = v.findViewById(R.id.tv_sender);
        }
    }
}
