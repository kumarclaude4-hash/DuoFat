package com.duoshield.app.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class DateHeaderHelper {

    public static String getLabel(long epochMs) {
        Calendar now  = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(epochMs);

        if (isSameDay(now, then)) return "Today";
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (isSameDay(now, then)) return "Yesterday";

        now = Calendar.getInstance();
        now.add(Calendar.DAY_OF_YEAR, -6);
        if (then.after(now))
            return new SimpleDateFormat("EEEE", Locale.getDefault()).format(new Date(epochMs));

        return new SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(new Date(epochMs));
    }

    public static boolean needsHeader(long prev, long current) {
        if (prev <= 0) return true;
        Calendar a = Calendar.getInstance(); a.setTimeInMillis(prev);
        Calendar b = Calendar.getInstance(); b.setTimeInMillis(current);
        return !isSameDay(a, b);
    }

    private static boolean isSameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
            && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }
}
