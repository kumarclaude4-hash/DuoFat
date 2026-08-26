package com.duoshield.app.call;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.duoshield.app.R;
import com.duoshield.app.db.CallRecord;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class CallHistoryAdapter extends RecyclerView.Adapter<CallHistoryAdapter.VH> {

    // SimpleDateFormat is not thread-safe, but onBindViewHolder always runs on the
    // main thread, so sharing three static instances (one per format pattern) is safe
    // and avoids re-allocating + re-compiling the pattern on every list row bind.
    private static final SimpleDateFormat FMT_TIME =
            new SimpleDateFormat("HH:mm",    Locale.getDefault());
    private static final SimpleDateFormat FMT_DAY  =
            new SimpleDateFormat("EEE",      Locale.getDefault());
    private static final SimpleDateFormat FMT_DATE =
            new SimpleDateFormat("dd/MM/yy", Locale.getDefault());

    public interface OnItemLongClickListener {
        void onLongClick(CallRecord record);
    }

    /** Fired when the user taps the play button on a recorded call. */
    public interface OnPlayRecordingListener {
        void onPlayRecording(CallRecord record);
    }

    private List<CallRecord> items = new ArrayList<>();
    private final OnItemLongClickListener longClickListener;
    private final OnPlayRecordingListener playListener;

    /** id of the row currently playing, so its button can show a "stop" glyph. {@code null} = none. */
    private String playingId = null;

    public CallHistoryAdapter(OnItemLongClickListener longClickListener,
                              OnPlayRecordingListener playListener) {
        this.longClickListener = longClickListener;
        this.playListener      = playListener;
    }

    /**
     * Marks which row (if any) is currently playing and repaints so the play/stop glyphs update.
     * Pass {@code null} to clear.
     */
    public void setPlayingId(String id) {
        this.playingId = id;
        notifyDataSetChanged();
    }

    public void setItems(List<CallRecord> newItems) {
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return items.size(); }
            @Override public int getNewListSize() { return newItems.size(); }
            @Override public boolean areItemsTheSame(int o, int n) {
                return items.get(o).id.equals(newItems.get(n).id);
            }
            @Override public boolean areContentsTheSame(int o, int n) {
                CallRecord a = items.get(o), b = newItems.get(n);
                return a.outcome.equals(b.outcome) && a.durationSeconds == b.durationSeconds;
            }
        });
        items = new ArrayList<>(newItems);
        result.dispatchUpdatesTo(this);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_call_history, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        CallRecord r = items.get(position);
        Context ctx = h.itemView.getContext();

        h.tvName.setText(r.partnerName.isEmpty() ? r.partnerId : r.partnerName);
        h.tvTime.setText(formatTime(r.startedAt));
        h.ivVideoFlag.setVisibility(r.isVideo ? View.VISIBLE : View.GONE);

        // Direction + outcome icon
        boolean missed = CallRecord.OUTCOME_MISSED.equals(r.outcome)
                || CallRecord.OUTCOME_DECLINED.equals(r.outcome);
        if (missed) {
            h.ivType.setImageResource(R.drawable.ic_call_missed);
            h.tvDetail.setText("Missed");
            h.tvDetail.setTextColor(0xFFD96A7C);
        } else if (CallRecord.DIRECTION_INCOMING.equals(r.direction)) {
            h.ivType.setImageResource(R.drawable.ic_call_incoming);
            h.tvDetail.setText(formatDuration(r.durationSeconds));
            h.tvDetail.setTextColor(0xFF9A8FB0);
        } else if (CallRecord.OUTCOME_FAILED.equals(r.outcome)) {
            h.ivType.setImageResource(R.drawable.ic_call_missed);
            h.tvDetail.setText("Failed");
            h.tvDetail.setTextColor(0xFFD96A7C);
        } else {
            h.ivType.setImageResource(R.drawable.ic_call_outgoing);
            h.tvDetail.setText(formatDuration(r.durationSeconds));
            h.tvDetail.setTextColor(0xFF9A8FB0);
        }

        h.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) longClickListener.onLongClick(r);
            return true;
        });
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivType, ivVideoFlag;
        TextView  tvName, tvDetail, tvTime;
        VH(View v) {
            super(v);
            ivType     = v.findViewById(R.id.ivCallType);
            ivVideoFlag = v.findViewById(R.id.ivCallVideoFlag);
            tvName     = v.findViewById(R.id.tvCallHistoryName);
            tvDetail   = v.findViewById(R.id.tvCallHistoryDetail);
            tvTime     = v.findViewById(R.id.tvCallHistoryTime);
        }
    }

    private String formatTime(long epochMs) {
        if (epochMs == 0) return "";
        long now = System.currentTimeMillis();
        long diff = now - epochMs;
        Date date = new Date(epochMs);
        if (diff < TimeUnit.DAYS.toMillis(1)) {
            return FMT_TIME.format(date);
        } else if (diff < TimeUnit.DAYS.toMillis(7)) {
            return FMT_DAY.format(date);
        } else {
            return FMT_DATE.format(date);
        }
    }

    private String formatDuration(int seconds) {
        if (seconds <= 0) return "0:00";
        int m = seconds / 60, s = seconds % 60;
        if (m >= 60) {
            return String.format(Locale.US, "%d:%02d:%02d", m / 60, m % 60, s);
        }
        return String.format(Locale.US, "%d:%02d", m, s);
    }
}
