package com.duoshield.app;

import android.app.ActivityManager;
import android.content.Context;
import androidx.annotation.NonNull;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.cache.LruResourceCache;
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool;
import com.bumptech.glide.module.AppGlideModule;

/**
 * Custom Glide configuration that scales cache sizes to available device RAM.
 *
 * <p>Glide's default memory allocation is ~25 % of the JVM heap ceiling.
 * On a low-RAM device (e.g. POCO C51 — Helio G36, 2 GB RAM) the heap ceiling
 * is ~192 MB; leaving 48 MB just for Glide crowds out Signal crypto,
 * SQLCipher, ExoPlayer, and the RecyclerView pool.
 *
 * <p>We apply a two-tier limit:
 * <ul>
 *   <li><b>Low RAM (memoryClass ≤ 128 MB)</b>: 16 MB bitmap pool + 8 MB cache = 24 MB total.
 *       Fits ~120–250 decoded thumbnails — ample for a chat scroll session.</li>
 *   <li><b>Standard RAM (memoryClass &gt; 128 MB)</b>: 32 MB bitmap pool + 16 MB cache = 48 MB.</li>
 * </ul>
 */
@GlideModule
public class DuoShieldGlideModule extends AppGlideModule {

    /** memoryClass threshold below which we use the low-RAM Glide budget. */
    private static final int LOW_RAM_MEMORY_CLASS_MB = 128;

    private static final int BITMAP_POOL_MB_NORMAL  = 32;
    private static final int MEMORY_CACHE_MB_NORMAL = 16;
    private static final int BITMAP_POOL_MB_LOWRAM  = 16;
    private static final int MEMORY_CACHE_MB_LOWRAM = 8;
    private static final long MB = 1024L * 1024L;

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        boolean lowRam = am != null && am.getMemoryClass() <= LOW_RAM_MEMORY_CLASS_MB;

        int bitmapPoolMb  = lowRam ? BITMAP_POOL_MB_LOWRAM  : BITMAP_POOL_MB_NORMAL;
        int memoryCacheMb = lowRam ? MEMORY_CACHE_MB_LOWRAM : MEMORY_CACHE_MB_NORMAL;

        builder.setBitmapPool(new LruBitmapPool(bitmapPoolMb * MB));
        builder.setMemoryCache(new LruResourceCache(memoryCacheMb * MB));
    }

    /** Disable manifest parsing — we have exactly one GlideModule and don't need the scan. */
    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
