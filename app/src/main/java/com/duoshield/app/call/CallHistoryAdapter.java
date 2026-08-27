package com.duoshield.app.call;

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
    private static final SimpleDateFormat FMT_TIME = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private static final SimpleDateFormat FMT_DAY = new SimpleDateFormat("EEE", Locale.getDefault());
    private static final SimpleDateFormat FMT_DATE = new SimpleDateFormat("dd/MM/yy", Locale.getDefault());

    public interface OnItemLongClickListener {
        void onLongClick(CallRecord record);
    }

    private List<CallRecord> items = new ArrayList<>();
    private final OnItemLongClickListener longClickListener;

    public CallHistoryAdapter(OnItemLongClickListener longClickListener) {
        this.longClickListener = longClickListener;
    }

    public void setItems(List<CallRecord> newItems) {
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return items.size(); }
            @Override public int getNewListSize() { return newItems.size(); }
            @Override public boolean areItemsTheSame(int oldIndex, int newIndex) {
                return items.get(oldIndex).id.equals(newItems.get(newIndex).id);
            }
            @Override public boolean areContentsTheSame(int oldIndex, int newIndex) {
                CallRecord a = items.get(oldIndex), b = newItems.get(newIndex);
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
        h.tvName.setText(r.partnerName.isEmpty() ? r.partnerId : r.partnerName);
        h.tvTime.setText(formatTime(r.startedAt));
        h.ivVideoFlag.setVisibility(r.isVideo ? View.VISIBLE : View.GONE);

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
        TextView tvName, tvDetail, tvTime;

        VH(View v) {
            super(v);
            ivType = v.findViewById(R.id.ivCallType);
            ivVideoFlag = v.findViewById(R.id.ivCallVideoFlag);
            tvName = v.findViewById(R.id.tvCallHistoryName);
            tvDetail = v.findViewById(R.id.tvCallHistoryDetail);
            tvTime = v.findViewById(R.id.tvCallHistoryTime);
        }
    }

    private String formatTime(long epochMs) {
        if (epochMs == 0) return "";
        long diff = System.currentTimeMillis() - epochMs;
        Date date = new Date(epochMs);
        if (diff < TimeUnit.DAYS.toMillis(1)) return FMT_TIME.format(date);
        if (diff < TimeUnit.DAYS.toMillis(7)) return FMT_DAY.format(date);
        return FMT_DATE.format(date);
    }

    private String formatDuration(int seconds) {
        if (seconds <= 0) return "0:00";
        int minutes = seconds / 60, remaining = seconds % 60;
        if (minutes >= 60) {
            return String.format(Locale.US, "%d:%02d:%02d", minutes / 60, minutes % 60, remaining);
        }
        return String.format(Locale.US, "%d:%02d", minutes, remaining);
    }
}
