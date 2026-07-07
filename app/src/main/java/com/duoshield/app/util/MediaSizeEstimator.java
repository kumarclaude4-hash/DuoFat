package com.duoshield.app.util;

import android.content.Context;
import java.io.File;

public class MediaSizeEstimator {

    public static long getCacheSizeBytes(Context ctx) {
        return dirSize(ctx.getCacheDir()) + dirSize(ctx.getFilesDir());
    }

    public static String getCacheSizeLabel(Context ctx) {
        long bytes = getCacheSizeBytes(ctx);
        if (bytes < 1024)             return bytes + " B";
        if (bytes < 1024 * 1024)      return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private static long dirSize(File dir) {
        if (dir == null) return 0;
        long s = 0;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files)
            s += f.isDirectory() ? dirSize(f) : f.length();
        return s;
    }
}
