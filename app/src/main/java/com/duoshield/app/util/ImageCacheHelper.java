package com.duoshield.app.util;

import android.content.Context;
import java.io.File;

public class ImageCacheHelper {

    private static final long MAX_SIZE = 50L * 1024 * 1024;

    public static long getCacheSize(Context ctx) {
        return dirSize(ctx.getCacheDir());
    }

    public static void trimIfNeeded(Context ctx) {
        File cache = ctx.getCacheDir();
        long size = dirSize(cache);
        if (size > MAX_SIZE) trimOldest(cache, size - MAX_SIZE);
    }

    private static long dirSize(File dir) {
        if (dir == null) return 0;
        long s = 0;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files)
            s += f.isDirectory() ? dirSize(f) : f.length();
        return s;
    }

    private static void trimOldest(File dir, long toFree) {
        File[] files = dir.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long freed = 0;
        for (File f : files) {
            if (freed >= toFree) break;
            freed += f.length();
            f.delete();
        }
    }
}
