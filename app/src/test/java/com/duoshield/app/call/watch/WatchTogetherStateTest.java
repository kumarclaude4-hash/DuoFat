package com.duoshield.app.call.watch;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for the Watch Together synchronization model.
 *
 * <p>These cover the pure logic that decides what a follower does with an incoming
 * state: sequence-based ordering, position projection across elapsed local time, and the
 * drift threshold that keeps small differences from causing constant re-seeks.
 *
 * <p>Runs on the JVM with no Android or Firebase dependency, matching {@code CallStateTest}.
 */
public class WatchTogetherStateTest {

    // ── Sequence ordering / echo suppression ──────────────────────────────────

    @Test
    public void firstStateIsAlwaysApplied() {
        WatchTogetherState incoming = new WatchTogetherState();
        incoming.seq = 1;
        assertTrue("A first state must always be applied",
                WatchTogetherState.shouldApply(null, incoming));
    }

    @Test
    public void higherSeqIsApplied() {
        WatchTogetherState applied = new WatchTogetherState();
        applied.seq = 4;

        WatchTogetherState incoming = new WatchTogetherState();
        incoming.seq = 5;

        assertTrue(WatchTogetherState.shouldApply(applied, incoming));
    }

    @Test
    public void equalSeqIsRejected() {
        // This is the local-echo case: Firestore replays our own write back to us.
        WatchTogetherState applied = new WatchTogetherState();
        applied.seq = 7;

        WatchTogetherState incoming = new WatchTogetherState();
        incoming.seq = 7;

        assertFalse("Re-applying the same seq would fight the local player",
                WatchTogetherState.shouldApply(applied, incoming));
    }

    @Test
    public void equalSeqWithDifferentContentIsAppliedAsARaceResolution() {
        // Two devices both write the very first state (e.g. simultaneously pasting a
        // link). Firestore resolves the race server-side to one winning document; the
        // "losing" writer's own listener then receives that winning snapshot stamped
        // with the SAME seq it used for its own now-overwritten write. This must be
        // applied — it is what Firestore actually holds — not dropped as an echo.
        WatchTogetherState applied = new WatchTogetherState();
        applied.seq = 1;
        applied.videoId = "aaaaaaaaaaa";
        applied.lastActionBy = "uid-a";

        WatchTogetherState incoming = new WatchTogetherState();
        incoming.seq = 1;
        incoming.videoId = "bbbbbbbbbbb";
        incoming.lastActionBy = "uid-b";

        assertTrue("A same-seq write from a different participant is a race, not an echo",
                WatchTogetherState.shouldApply(applied, incoming));
    }

    @Test
    public void equalSeqWithIdenticalContentIsRejectedAsAnEcho() {
        WatchTogetherState applied = new WatchTogetherState();
        applied.seq = 4;
        applied.videoId = "aaaaaaaaaaa";
        applied.lastActionBy = "uid-a";
        applied.positionMs = 5_000L;

        WatchTogetherState incoming = applied.copy();

        assertFalse("A byte-for-byte echo of our own applied write must not reapply",
                WatchTogetherState.shouldApply(applied, incoming));
    }

    @Test
    public void lowerSeqIsRejected() {
        WatchTogetherState applied = new WatchTogetherState();
        applied.seq = 9;

        WatchTogetherState stale = new WatchTogetherState();
        stale.seq = 3;

        assertFalse("Out-of-order delivery must not rewind playback",
                WatchTogetherState.shouldApply(applied, stale));
    }

    @Test
    public void nullIncomingIsRejected() {
        assertFalse(WatchTogetherState.shouldApply(new WatchTogetherState(), null));
        assertFalse(WatchTogetherState.shouldApply(null, null));
    }

    // ── Position projection ───────────────────────────────────────────────────

    @Test
    public void pausedStateDoesNotAdvance() {
        WatchTogetherState s = new WatchTogetherState();
        s.playing = false;
        s.positionMs = 30_000L;

        assertEquals("A paused session must project to its stored position",
                30_000L, WatchTogetherState.projectedPositionMs(s, 60_000L));
    }

    @Test
    public void playingStateAdvancesWithElapsedTime() {
        WatchTogetherState s = new WatchTogetherState();
        s.playing = true;
        s.positionMs = 10_000L;
        s.playbackRate = 1.0d;

        assertEquals(15_000L, WatchTogetherState.projectedPositionMs(s, 5_000L));
    }

    @Test
    public void playbackRateScalesProjection() {
        WatchTogetherState s = new WatchTogetherState();
        s.playing = true;
        s.positionMs = 0L;
        s.playbackRate = 2.0d;

        assertEquals("At 2x, 5s of wall clock is 10s of video",
                10_000L, WatchTogetherState.projectedPositionMs(s, 5_000L));

        s.playbackRate = 0.5d;
        assertEquals(2_500L, WatchTogetherState.projectedPositionMs(s, 5_000L));
    }

    @Test
    public void negativeElapsedIsTreatedAsZero() {
        WatchTogetherState s = new WatchTogetherState();
        s.playing = true;
        s.positionMs = 8_000L;

        assertEquals("A backwards local clock must not rewind the target",
                8_000L, WatchTogetherState.projectedPositionMs(s, -5_000L));
    }

    @Test
    public void nullStateProjectsToZero() {
        assertEquals(0L, WatchTogetherState.projectedPositionMs(null, 5_000L));
    }

    @Test
    public void projectionNeverGoesNegative() {
        WatchTogetherState s = new WatchTogetherState();
        s.playing = false;
        s.positionMs = -1_000L;   // defensive: should never occur after fromMap()

        assertEquals(0L, WatchTogetherState.projectedPositionMs(s, 0L));
    }

    // ── Drift threshold ───────────────────────────────────────────────────────

    @Test
    public void smallDriftDoesNotTriggerSeek() {
        // 500ms out of sync: correcting is more jarring than the drift.
        assertFalse(WatchTogetherState.shouldSeek(10_000L, 10_500L));
        assertFalse(WatchTogetherState.shouldSeek(10_500L, 10_000L));
    }

    @Test
    public void largeDriftTriggersSeekInBothDirections() {
        assertTrue("Local behind target must seek forward",
                WatchTogetherState.shouldSeek(10_000L, 20_000L));
        assertTrue("Local ahead of target must seek back",
                WatchTogetherState.shouldSeek(20_000L, 10_000L));
    }

    @Test
    public void driftExactlyAtThresholdDoesNotSeek() {
        long t = WatchTogetherState.DRIFT_THRESHOLD_MS;
        assertFalse("Threshold is exclusive", WatchTogetherState.shouldSeek(0L, t));
        assertTrue(WatchTogetherState.shouldSeek(0L, t + 1));
    }

    @Test
    public void customThresholdIsHonoured() {
        assertTrue(WatchTogetherState.shouldSeek(0L, 600L, 500L));
        assertFalse(WatchTogetherState.shouldSeek(0L, 400L, 500L));
    }

    // ── Serialization round-trip ──────────────────────────────────────────────

    @Test
    public void roundTripPreservesAllFields() {
        WatchTogetherState original = new WatchTogetherState();
        original.active = true;
        original.videoId = "dQw4w9WgXcQ";
        original.hostUid = "uid-host";
        original.playing = true;
        original.positionMs = 42_000L;
        original.playbackRate = 1.5d;
        original.updatedAtMs = 1_700_000_000_000L;
        original.seq = 12L;
        original.lastActionBy = "uid-host";
        original.lastAction = WatchTogetherState.ACTION_SEEK;

        WatchTogetherState parsed = WatchTogetherState.fromMap(original.toMap());

        assertNotNull(parsed);
        assertTrue(parsed.active);
        assertEquals("dQw4w9WgXcQ", parsed.videoId);
        assertEquals("uid-host", parsed.hostUid);
        assertTrue(parsed.playing);
        assertEquals(42_000L, parsed.positionMs);
        assertEquals(1.5d, parsed.playbackRate, 0.0001d);
        assertEquals(1_700_000_000_000L, parsed.updatedAtMs);
        assertEquals(12L, parsed.seq);
        assertEquals("uid-host", parsed.lastActionBy);
        assertEquals(WatchTogetherState.ACTION_SEEK, parsed.lastAction);
    }

    @Test
    public void fromMapToleratesMissingFields() {
        // A partially written doc must never crash the call screen.
        WatchTogetherState parsed = WatchTogetherState.fromMap(new HashMap<>());

        assertNotNull(parsed);
        assertFalse(parsed.active);
        assertNull(parsed.videoId);
        assertFalse(parsed.playing);
        assertEquals(0L, parsed.positionMs);
        assertEquals(WatchTogetherState.DEFAULT_PLAYBACK_RATE, parsed.playbackRate, 0.0001d);
        assertEquals(0L, parsed.seq);
    }

    @Test
    public void fromMapToleratesFirestoreNumberWidening() {
        // Firestore may hand back Long where an Integer was written, and vice versa.
        Map<String, Object> data = new HashMap<>();
        data.put(WatchTogetherState.F_POSITION_MS, 5000);          // Integer
        data.put(WatchTogetherState.F_SEQ, 3L);                    // Long
        data.put(WatchTogetherState.F_PLAYBACK_RATE, 1);           // Integer for a double field

        WatchTogetherState parsed = WatchTogetherState.fromMap(data);

        assertNotNull(parsed);
        assertEquals(5000L, parsed.positionMs);
        assertEquals(3L, parsed.seq);
        assertEquals(1.0d, parsed.playbackRate, 0.0001d);
    }

    @Test
    public void fromMapRejectsWrongTypesWithoutCrashing() {
        Map<String, Object> data = new HashMap<>();
        data.put(WatchTogetherState.F_ACTIVE, "yes");        // String, not Boolean
        data.put(WatchTogetherState.F_POSITION_MS, "abc");   // String, not Number
        data.put(WatchTogetherState.F_VIDEO_ID, 42);         // Number, not String

        WatchTogetherState parsed = WatchTogetherState.fromMap(data);

        assertNotNull(parsed);
        assertFalse("Non-boolean active must fall back to false", parsed.active);
        assertEquals(0L, parsed.positionMs);
        assertNull(parsed.videoId);
    }

    @Test
    public void fromMapNormalizesNonPositiveRate() {
        Map<String, Object> data = new HashMap<>();
        data.put(WatchTogetherState.F_PLAYBACK_RATE, 0d);
        assertEquals(WatchTogetherState.DEFAULT_PLAYBACK_RATE,
                WatchTogetherState.fromMap(data).playbackRate, 0.0001d);

        data.put(WatchTogetherState.F_PLAYBACK_RATE, -2d);
        assertEquals(WatchTogetherState.DEFAULT_PLAYBACK_RATE,
                WatchTogetherState.fromMap(data).playbackRate, 0.0001d);
    }

    @Test
    public void fromMapReturnsNullForNullInput() {
        assertNull(WatchTogetherState.fromMap(null));
    }

    @Test
    public void emptyStringsAreNormalizedToNull() {
        // toMap() writes "" for null strings; fromMap() must invert that.
        WatchTogetherState empty = new WatchTogetherState();
        WatchTogetherState parsed = WatchTogetherState.fromMap(empty.toMap());

        assertNotNull(parsed);
        assertNull(parsed.videoId);
        assertNull(parsed.hostUid);
        assertNull(parsed.lastAction);
        assertNull(parsed.lastActionBy);
    }

    // ── isPlayable / copy ─────────────────────────────────────────────────────

    @Test
    public void isPlayableRequiresBothActiveAndVideoId() {
        WatchTogetherState s = new WatchTogetherState();
        assertFalse("Inactive with no video is not playable", s.isPlayable());

        s.active = true;
        assertFalse("Active with no video is not playable", s.isPlayable());

        s.videoId = "dQw4w9WgXcQ";
        assertTrue(s.isPlayable());

        s.active = false;
        assertFalse("Ended session is not playable even with a video", s.isPlayable());
    }

    @Test
    public void copyPreservesEveryField() {
        WatchTogetherState s = new WatchTogetherState();
        s.active = true;
        s.videoId = "abcdefghijk";
        s.hostUid = "host";
        s.playing = true;
        s.positionMs = 1234L;
        s.playbackRate = 1.25d;
        s.updatedAtMs = 999L;
        s.seq = 5L;
        s.lastAction = WatchTogetherState.ACTION_PLAY;
        s.lastActionBy = "host";

        WatchTogetherState c = s.copy();

        assertEquals(s.active, c.active);
        assertEquals(s.videoId, c.videoId);
        assertEquals(s.hostUid, c.hostUid);
        assertEquals(s.playing, c.playing);
        assertEquals(s.positionMs, c.positionMs);
        assertEquals(s.playbackRate, c.playbackRate, 0.0001d);
        assertEquals(s.updatedAtMs, c.updatedAtMs);
        assertEquals(s.seq, c.seq);
        assertEquals(s.lastAction, c.lastAction);
        assertEquals(s.lastActionBy, c.lastActionBy);
    }

    @Test
    public void copyIsIndependentOfOriginal() {
        WatchTogetherState s = new WatchTogetherState();
        s.positionMs = 100L;

        WatchTogetherState c = s.copy();
        c.positionMs = 999L;

        assertEquals("Mutating the copy must not affect the original", 100L, s.positionMs);
    }

    // ── Rejoin scenario ───────────────────────────────────────────────────────

    /**
     * Regression test for the stale-sample drift bug.
     *
     * <p>Positions arrive from the player page only once per 500ms tick, so a raw sample is
     * on average half a tick out of date and — while playing — always stale in the same
     * direction: it reads low. Comparing that raw sample against a target projected to the
     * current instant therefore reported a device that was perfectly in sync as being a tick
     * behind, so a forward seek was issued on every reconcile pass. Both sides of the
     * comparison must be projected to the same instant.
     */
    @Test
    public void inSyncFollowerDoesNotSeekWhenBothSidesShareAnInstant() {
        WatchTogetherState s = new WatchTogetherState();
        s.active = true;
        s.videoId = "dQw4w9WgXcQ";
        s.playing = true;
        s.positionMs = 30_000L;
        s.playbackRate = 1.0d;

        // 400ms after the snapshot the target has advanced to 30_400.
        long target = WatchTogetherState.projectedPositionMs(s, 400L);
        assertEquals(30_400L, target);

        // The player last reported 30_000 at snapshot time and has not ticked since. Judged
        // raw it looks 400ms behind; judged as an estimate advanced by its own 400ms age it
        // is exactly on target. Neither crosses the 1500ms threshold on its own...
        assertFalse(WatchTogetherState.shouldSeek(30_000L, target));
        assertFalse(WatchTogetherState.shouldSeek(30_400L, target));

        // ...but at 2x the same one-tick gap is twice as many ms of video, and the raw
        // sample alone is what pushed it over the edge into a spurious seek.
        s.playbackRate = 2.0d;
        s.positionMs = 30_000L;
        long fastTarget = WatchTogetherState.projectedPositionMs(s, 900L);
        assertEquals(31_800L, fastTarget);
        assertTrue("Raw sample overstates drift and triggers a needless seek",
                WatchTogetherState.shouldSeek(30_000L, fastTarget));
        assertFalse("An age-corrected estimate is correctly judged in sync",
                WatchTogetherState.shouldSeek(31_800L, fastTarget));
    }

    /**
     * A paused session is a fixed point: repeated reconciles must not accumulate drift, no
     * matter how much local time passes between them. This is what makes it safe for
     * {@code onResume()} to reconcile against an already-applied state.
     */
    @Test
    public void repeatedProjectionOfPausedStateIsStable() {
        WatchTogetherState s = new WatchTogetherState();
        s.playing = false;
        s.positionMs = 12_345L;

        assertEquals(12_345L, WatchTogetherState.projectedPositionMs(s, 0L));
        assertEquals(12_345L, WatchTogetherState.projectedPositionMs(s, 1_000L));
        assertEquals(12_345L, WatchTogetherState.projectedPositionMs(s, 600_000L));
        assertFalse("A paused player already at position must never seek",
                WatchTogetherState.shouldSeek(12_345L, 12_345L));
    }

    @Test
    public void rejoinProjectsForwardFromStoredState() {
        // A participant backgrounds the app, returns 20s later, and re-reads the doc.
        // The host wrote positionMs=60_000 while playing; the follower's own local
        // measurement of elapsed time is what advances the target.
        WatchTogetherState hostState = new WatchTogetherState();
        hostState.active = true;
        hostState.videoId = "dQw4w9WgXcQ";
        hostState.playing = true;
        hostState.positionMs = 60_000L;
        hostState.playbackRate = 1.0d;

        long target = WatchTogetherState.projectedPositionMs(hostState, 20_000L);

        assertEquals(80_000L, target);
        assertTrue("A fresh player at 0 must seek to catch up",
                WatchTogetherState.shouldSeek(0L, target));
    }
}
