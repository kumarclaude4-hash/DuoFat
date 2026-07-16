package com.duoshield.app.call;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.duoshield.app.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView adapter for ephemeral in-call chat messages.
 * Two view types: own messages (right-aligned purple) and partner messages (left-aligned dark).
 */
public class InCallChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_MINE    = 0;
    private static final int VIEW_PARTNER = 1;

    private final List<InCallChatMessage> messages;
    private final String partnerName;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm a", Locale.US);

    public InCallChatAdapter(List<InCallChatMessage> messages, String partnerName) {
        this.messages    = messages;
        this.partnerName = partnerName != null ? partnerName : "";
    }

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).isMine ? VIEW_MINE : VIEW_PARTNER;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_MINE) {
            View v = inflater.inflate(R.layout.item_incall_msg_mine, parent, false);
            return new MineHolder(v);
        } else {
            View v = inflater.inflate(R.layout.item_incall_msg_partner, parent, false);
            return new PartnerHolder(v);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        InCallChatMessage msg = messages.get(position);
        String timeStr = timeFmt.format(new Date(msg.timestamp));

        if (holder instanceof MineHolder) {
            MineHolder h = (MineHolder) holder;
            h.tvText.setText(msg.text);
            h.tvTime.setText(timeStr);

        } else if (holder instanceof PartnerHolder) {
            PartnerHolder h = (PartnerHolder) holder;
            String initial = partnerName.isEmpty() ? "?"
                    : partnerName.substring(0, 1).toUpperCase(Locale.US);
            h.tvAvatar.setText(initial);
            h.tvName.setText(partnerName);
            h.tvText.setText(msg.text);
            h.tvTime.setText(timeStr);
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    // ── ViewHolders ───────────────────────────────────────────────────────────

    static class MineHolder extends RecyclerView.ViewHolder {
        final TextView tvText;
        final TextView tvTime;

        MineHolder(@NonNull View v) {
            super(v);
            tvText = v.findViewById(R.id.tvMineText);
            tvTime = v.findViewById(R.id.tvMineTime);
        }
    }

    static class PartnerHolder extends RecyclerView.ViewHolder {
        final TextView tvAvatar;
        final TextView tvName;
        final TextView tvText;
        final TextView tvTime;

        PartnerHolder(@NonNull View v) {
            super(v);
            tvAvatar = v.findViewById(R.id.tvPartnerAvatar);
            tvName   = v.findViewById(R.id.tvPartnerSenderName);
            tvText   = v.findViewById(R.id.tvPartnerText);
            tvTime   = v.findViewById(R.id.tvPartnerTime);
        }
    }
}
