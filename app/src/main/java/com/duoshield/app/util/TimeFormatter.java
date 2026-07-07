package com.duoshield.app.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class TimeFormatter {

    public static String format(long epochMs) {
        if (epochMs <= 0) return "";
        Date date = new Date(epochMs);
        Calendar now  = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTime(date);

        if (isSameDay(now, then))
            return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(date);
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (isSameDay(now, then)) return "Yesterday";
        if (withinWeek(then))
            return new SimpleDateFormat("EEE", Locale.getDefault()).format(date);
        return new SimpleDateFormat("d MMM", Locale.getDefault()).format(date);
    }

    public static String formatFull(long epochMs) {
        if (epochMs <= 0) return "";
        return new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
            .format(new Date(epochMs));
    }

    private static boolean isSameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
            && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static boolean withinWeek(Calendar then) {
        Calendar weekAgo = Calendar.getInstance();
        weekAgo.add(Calendar.DAY_OF_YEAR, -7);
        return then.after(weekAgo);
    }
}
