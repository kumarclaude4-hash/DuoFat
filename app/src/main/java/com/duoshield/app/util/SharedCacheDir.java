package com.duoshield.app.util;

import android.content.Context;

import java.io.File;

/**
 * S08-M2 fix: the only files this app ever hands to {@code FileProvider} are
 * camera captures, one shared/decrypted image, and the chat-export ZIP — all
 * of which used to be written directly at {@code getCacheDir()}'s top level.
 * {@code file_paths.xml} declared {@code path="."} for the cache root, so
 * every {@code Uri} grant issued for any of them was, in principle, scoped to
 * the *entire* cache directory — including S08-H3's plaintext Glide disk
 * cache and any other scratch file living there.
 *
 * <p>This class centralizes the one FileProvider-grantable root
 * ({@code getCacheDir()/shared/}) that {@code file_paths.xml} now declares,
 * split into subdirectories by purpose so callers never write directly at
 * the cache root when the result is going to be shared:
 * <ul>
 *   <li>{@link #camera} — {@code shared/camera/} — camera-app capture targets
 *       ({@code cam_*.jpg}, {@code grp_cam_*.jpg})</li>
 *   <li>{@link #media} — {@code shared/media/} — already-decrypted media
 *       shared via {@code ACTION_SEND} ({@code share_*.jpg})</li>
 *   <li>{@link #export} — {@code shared/export/} — full chat-export ZIPs
 *       ({@code DuoShield_Export_*.zip})</li>
 * </ul>
 *
 * <p>{@link TempFileCleaner} sweeps this same {@code shared/} tree (see its
 * {@code doWork()}) using the identical filename rules it already applied at
 * the cache root, so relocating these files here does not change when they
 * get deleted — only which URI root a grant for them exposes.
 */
public final class SharedCacheDir {

    /** Root of every FileProvider-grantable path, relative to getCacheDir(). Must match file_paths.xml's "shared" cache-path. */
    public static final String ROOT_NAME = "shared";

    private SharedCacheDir() {
    }

    public static File camera(Context ctx) {
        return subdir(ctx, "camera");
    }

    public static File media(Context ctx) {
        return subdir(ctx, "media");
    }

    public static File export(Context ctx) {
        return subdir(ctx, "export");
    }

    /** The shared root itself, e.g. for TempFileCleaner's sweep. */
    public static File root(Context ctx) {
        return new File(ctx.getCacheDir(), ROOT_NAME);
    }

    private static File subdir(Context ctx, String name) {
        File dir = new File(root(ctx), name);
        // mkdirs() is a no-op (returns false but leaves the directory intact)
        // if it already exists, so this is safe to call on every use.
        dir.mkdirs();
        return dir;
    }
}
