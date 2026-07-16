package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class ArchiveHelper {

    private static final String PREFS_NAME = "duoshield_archive";
    private static final String KEY        = "archived_ids";

    public static void archive(Context ctx, String conversationId) {
        if (conversationId == null) return;
        Set<String> ids = new HashSet<>(getArchived(ctx));
        ids.add(conversationId);
        prefs(ctx).edit().putStringSet(KEY, ids).apply();
    }

    public static void unarchive(Context ctx, String conversationId) {
        if (conversationId == null) return;
        Set<String> ids = new HashSet<>(getArchived(ctx));
        ids.remove(conversationId);
        prefs(ctx).edit().putStringSet(KEY, ids).apply();
    }

    public static boolean isArchived(Context ctx, String conversationId) {
        if (conversationId == null) return false;
        return getArchived(ctx).contains(conversationId);
    }

    public static Set<String> getArchived(Context ctx) {
        Set<String> raw = prefs(ctx).getStringSet(KEY, null);
        return raw != null ? new HashSet<>(raw) : Collections.emptySet();
    }

    public static int getArchivedCount(Context ctx) {
        return getArchived(ctx).size();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
