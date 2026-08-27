package com.duoshield.app.util;

import com.google.firebase.Timestamp;

import java.util.Date;

/** Shared conversion and display rules for online/last-seen presence. */
public final class PresenceFormatter {
    private PresenceFormatter() {}

    /** Converts Firestore timestamp representations into epoch milliseconds. */
    public static long timestampMillis(Object value) {
        if (value instanceof Timestamp) return ((Timestamp) value).toDate().getTime();
        if (value instanceof Date) return ((Date) value).getTime();
        if (value instanceof Number) return ((Number) value).longValue();
        return 0L;
    }

    /** Formats the same status string in the conversation list and chat header. */
    public static String format(long epochMs, boolean online) {
        return LastSeenFormatter.format(epochMs, online);
    }
}
