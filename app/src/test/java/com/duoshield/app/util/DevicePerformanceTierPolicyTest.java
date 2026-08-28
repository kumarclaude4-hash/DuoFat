package com.duoshield.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Guards the explicit LOW resource contract without depending on Android runtime probes. */
public class DevicePerformanceTierPolicyTest {

    @Test
    public void lowTierUsesStrictBoundedBudgets() {
        DevicePerformanceTier low = DevicePerformanceTier.LOW;

        assertEquals(250, low.initialChatWindow());
        assertEquals(12, low.recyclerViewCacheSize());
        assertEquals(8, low.recyclerViewPrefetchCount());
        assertEquals(2, low.mediaConcurrency());
        assertEquals(12, low.mediaPreviewCount());
        assertEquals(1080, low.listImageEdgePx());
        assertTrue(low.shortCpuWorkerCount() >= 2 && low.shortCpuWorkerCount() <= 3);
        assertEquals(3, low.shortIoWorkerCount());
        assertEquals(128, low.shortJobQueueCapacity());
        assertEquals(1, low.linkPreviewConcurrency());
        assertEquals(24, low.linkPreviewCacheEntries());
        assertEquals(32L * 1024L * 1024L, low.firestoreCacheBytes());
        assertEquals(8L * 1024L * 1024L, low.glideBitmapPoolBytes());
        assertEquals(6L * 1024L * 1024L, low.glideMemoryCacheBytes());
        assertEquals(50L * 1024L * 1024L, low.glideDiskCacheBytes());
    }

    @Test
    public void strongerTiersRetainLargerExistingBehavior() {
        DevicePerformanceTier low = DevicePerformanceTier.LOW;
        DevicePerformanceTier mid = DevicePerformanceTier.MID;

        assertTrue(mid.initialChatWindow() > low.initialChatWindow());
        assertTrue(mid.mediaConcurrency() > low.mediaConcurrency());
        assertTrue(mid.listImageEdgePx() > low.listImageEdgePx());
        assertTrue(mid.glideMemoryCacheBytes() > low.glideMemoryCacheBytes());
        assertEquals(DevicePerformanceTier.LOW, DevicePerformanceTier.LOW.demoted());
        assertEquals(DevicePerformanceTier.LOW, DevicePerformanceTier.MID.demoted());
        assertEquals(DevicePerformanceTier.MID, DevicePerformanceTier.HIGH.demoted());
    }
}
