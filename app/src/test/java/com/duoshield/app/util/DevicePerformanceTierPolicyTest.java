package com.duoshield.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Guards the explicit LOW resource contract without depending on Android runtime probes. */
public class DevicePerformanceTierPolicyTest {

    @Test
    public void lowTierUsesStrictBoundedBudgets() {
        DevicePerformanceTier low = DevicePerformanceTier.LOW;

        assertEquals(120, low.initialChatWindow());
        assertEquals(4, low.recyclerViewCacheSize());
        assertEquals(2, low.recyclerViewPrefetchCount());
        assertEquals(1, low.mediaConcurrency());
        assertEquals(6, low.mediaPreviewCount());
        assertEquals(720, low.listImageEdgePx());
        assertEquals(1, low.shortCpuWorkerCount());
        assertEquals(2, low.shortIoWorkerCount());
        assertEquals(32, low.shortJobQueueCapacity());
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
