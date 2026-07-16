package com.duoshield.app.util;

public class LastSeenFormatter {

    public static String format(long epochMs, boolean isOnline) {
        if (isOnline) return "online";
        if (epochMs <= 0) return "last seen recently";
        long diff = System.currentTimeMillis() - epochMs;
        if (diff < 60_000)      return "last seen just now";
        if (diff < 3_600_000)   return "last seen " + (diff / 60_000) + " min ago";
        if (diff < 86_400_000)  return "last seen " + (diff / 3_600_000) + "h ago";
        if (diff < 172_800_000) return "last seen yesterday";
        return "last seen " + TimeFormatter.format(epochMs);
    }
}
