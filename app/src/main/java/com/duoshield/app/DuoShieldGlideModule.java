package com.duoshield.app;

import android.content.Context;
import androidx.annotation.NonNull;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.cache.DiskLruCacheFactory;
import com.bumptech.glide.load.engine.cache.LruResourceCache;
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.module.AppGlideModule;
import com.bumptech.glide.request.RequestOptions;
import com.duoshield.app.util.DevicePerformanceTier;

/**
 * Custom Glide configuration that scales cache sizes to available device RAM.
 *
 * <p>Glide's default memory allocation is ~25 % of the JVM heap ceiling.
 * On a low-RAM device (e.g. POCO C51 — Helio G36, 2 GB RAM) the heap ceiling
 * is ~192 MB; leaving 48 MB just for Glide crowds out Signal crypto,
 * SQLCipher, ExoPlayer, and the RecyclerView pool.
 *
 * <p>We apply a two-tier limit, selected by {@link DevicePerformanceTier}:
 * <ul>
 *   <li><b>{@link DevicePerformanceTier#LOW}</b>: 16 MB bitmap pool + 8 MB cache = 24 MB total,
 *       RGB_565 decoding and {@code AT_MOST} downsampling. Fits ~120–250 decoded thumbnails —
 *       ample for a chat scroll session.</li>
 *   <li><b>{@link DevicePerformanceTier#MID} / {@link DevicePerformanceTier#HIGH}</b>:
 *       32 MB bitmap pool + 16 MB cache = 48 MB, ARGB_8888.</li>
 * </ul>
 *
 * <p>Disk cache: Glide's default is 250 MB. We cap at 150 MB (low-RAM: 75 MB) to leave
 * headroom for B2StorageHelper's own disk cache and SQLCipher's WAL files. This still
 * covers thousands of decoded thumbnails and keeps cold-scroll instant even without network.
 */
@GlideModule
public class DuoShieldGlideModule extends AppGlideModule {

    private static final int BITMAP_POOL_MB_NORMAL  = 32;
    private static final int MEMORY_CACHE_MB_NORMAL = 16;
    private static final int BITMAP_POOL_MB_LOWRAM  = 16;
    private static final int MEMORY_CACHE_MB_LOWRAM = 8;
    private static final int DISK_CACHE_MB_NORMAL   = 150;
    private static final int DISK_CACHE_MB_LOWRAM   = 75;
    private static final long MB = 1024L * 1024L;

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        // Keyed on DevicePerformanceTier rather than getMemoryClass() alone. The old
        // `getMemoryClass() <= 128` test never fired on a 3–4 GB budget phone — a Helio P35
        // reports 192–256 MB — so exactly the devices that need the small budget were handed
        // the 48 MB one plus ARGB_8888 decoding. On an in-order Cortex-A53 the resulting GC
        // pressure from oversized chat thumbnails is the single biggest cause of scroll jank.
        boolean lowRam = DevicePerformanceTier.get(context) == DevicePerformanceTier.LOW;

        int bitmapPoolMb  = lowRam ? BITMAP_POOL_MB_LOWRAM  : BITMAP_POOL_MB_NORMAL;
        int memoryCacheMb = lowRam ? MEMORY_CACHE_MB_LOWRAM : MEMORY_CACHE_MB_NORMAL;
        int diskCacheMb   = lowRam ? DISK_CACHE_MB_LOWRAM   : DISK_CACHE_MB_NORMAL;

        builder.setBitmapPool(new LruBitmapPool(bitmapPoolMb * MB));
        builder.setMemoryCache(new LruResourceCache(memoryCacheMb * MB));
        // Explicit disk cache: Glide's default 250 MB competes with B2StorageHelper's own
        // disk cache. Cap it here so total disk pressure stays predictable.
        builder.setDiskCache(new DiskLruCacheFactory(
                () -> new java.io.File(context.getCacheDir(), "glide_image_cache"),
                diskCacheMb * MB));

        if (lowRam) {
            // RGB_565 uses 2 bytes/pixel vs ARGB_8888's 4 bytes — halves bitmap RAM
            // on low-RAM devices (e.g. POCO C51, 2 GB).  Chat thumbnails have no
            // transparency, so there is no visible quality loss for images.
            // Voice-note waveforms and avatars are drawn programmatically and are
            // unaffected by decode format.
            //
            // AT_MOST additionally guarantees no bitmap is ever decoded larger than the view
            // it lands in: without it a 4000x3000 camera photo is decoded at a scale Glide
            // picks for quality, which on a 720p screen is pure waste both in decode time on
            // an A53 and in pool residency afterwards.
            builder.setDefaultRequestOptions(
                    new RequestOptions()
                            .format(DecodeFormat.PREFER_RGB_565)
                            .downsample(DownsampleStrategy.AT_MOST));
        }
    }

    /** Disable manifest parsing — we have exactly one GlideModule and don't need the scan. */
    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
