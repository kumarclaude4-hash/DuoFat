package com.duoshield.app.util;

import java.util.Calendar;
import java.util.Locale;

/**
 * Small formatting helper shared by the photo and video viewers so their header
 * subtitles read the same way (WhatsApp/Telegram style): "Today, 12:39 PM",
 * "Yesterday, 9:04 AM", or "25 Aug 2026, 12:39 PM" for older media.
 */
public final class MediaMetaFormatter {

    private MediaMetaFormatter() {}

    public static String relativeDateTime(long epochMillis) {
        if (epochMillis <= 0) return "";

        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(epochMillis);

        String time = new java.text.SimpleDateFormat("h:mm a", Locale.getDefault())
                .format(then.getTime());

        if (isSameDay(now, then)) {
            return "Today, " + time;
        }
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (isSameDay(now, then)) {
            return "Yesterday, " + time;
        }

        String date = new java.text.SimpleDateFormat("d MMM yyyy", Locale.getDefault())
                .format(then.getTime());
        return date + ", " + time;
    }

    private static boolean isSameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }
}
