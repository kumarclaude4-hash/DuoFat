package com.duoshield.app.call.watch;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.duoshield.app.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Renders YouTube search results in the Watch Together picker.
 *
 * <p>Per project rule #5 the list is only ever replaced through {@link #setResults(List)},
 * which diffs with {@link DiffUtil}; {@code notifyDataSetChanged()} is never called. That
 * matters here beyond animation quality: re-typing a query usually returns a heavily
 * overlapping result set, and a full invalidation would reset scroll position and re-issue
 * every Glide request for thumbnails already on screen.
 *
 * <p>Thumbnails load with Glide, which the project already depends on for chat media — no new
 * image dependency was added.
 */
public class YouTubeSearchAdapter extends RecyclerView.Adapter<YouTubeSearchAdapter.ResultHolder> {

    /** Emits the tapped row's position; the Activity resolves it to a video id via state. */
    public interface OnResultClick {
        void onResultClick(int position);
    }

    private final Context context;
    private final OnResultClick clickListener;
    private List<YouTubeSearchResult> items = Collections.emptyList();

    public YouTubeSearchAdapter(Context context, OnResultClick clickListener) {
        this.context = context;
        this.clickListener = clickListener;
    }

    /**
     * Replaces the whole list, animating the difference.
     *
     * <p>The adapter reports positions, not objects, so the Activity always resolves a tap
     * against the live {@link YouTubeSearchState}. That keeps a single source of truth and is
     * what makes a click queued against a replaced list harmless.
     */
    public void setResults(List<YouTubeSearchResult> newList) {
        final List<YouTubeSearchResult> incoming =
                newList == null ? Collections.<YouTubeSearchResult>emptyList() : new ArrayList<>(newList);
        final List<YouTubeSearchResult> current = items;

        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return current.size();
            }

            @Override
            public int getNewListSize() {
                return incoming.size();
            }

            @Override
            public boolean areItemsTheSame(int oldPos, int newPos) {
                // Identity is the video id — see YouTubeSearchResult.equals.
                return current.get(oldPos).equals(incoming.get(newPos));
            }

            @Override
            public boolean areContentsTheSame(int oldPos, int newPos) {
                return current.get(oldPos).sameContentAs(incoming.get(newPos));
            }
        });

        items = incoming;
        diff.dispatchUpdatesTo(this);
    }

    @NonNull
    @Override
    public ResultHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_youtube_search_result, parent, false);
        return new ResultHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ResultHolder h, int position) {
        YouTubeSearchResult r = items.get(position);

        h.title.setText(r.title);

        if (r.channel.isEmpty()) {
            h.channel.setVisibility(View.GONE);
        } else {
            h.channel.setVisibility(View.VISIBLE);
            h.channel.setText(r.channel);
        }

        // Every branch either issues a fresh Glide request or clears the target, so a recycled
        // holder can never keep the previous row's thumbnail.
        if (r.hasThumbnail()) {
            Glide.with(context)
                 .load(r.thumbnail)
                 .placeholder(R.drawable.ic_image)
                 .centerCrop()
                 .into(h.thumbnail);
        } else {
            Glide.with(context).clear(h.thumbnail);
            h.thumbnail.setImageResource(R.drawable.ic_image);
        }

        h.itemView.setContentDescription(
                r.channel.isEmpty() ? r.title : (r.title + ", " + r.channel));

        h.itemView.setOnClickListener(v -> {
            if (clickListener == null) return;
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) clickListener.onResultClick(pos);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ResultHolder extends RecyclerView.ViewHolder {
        final ImageView thumbnail;
        final TextView title;
        final TextView channel;

        ResultHolder(@NonNull View v) {
            super(v);
            thumbnail = v.findViewById(R.id.ivResultThumb);
            title     = v.findViewById(R.id.tvResultTitle);
            channel   = v.findViewById(R.id.tvResultChannel);
        }
    }
}
