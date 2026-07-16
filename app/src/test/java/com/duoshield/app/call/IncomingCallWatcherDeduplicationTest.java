package com.duoshield.app.call;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for the deduplication logic in {@link IncomingCallWatcher}.
 *
 * <p>The FCM path and the Firestore watcher path can both fire for the same
 * incoming call. {@link IncomingCallWatcher#markShown(String)} and
 * {@link IncomingCallWatcher#isShown(String)} are the shared gate that
 * prevents the user from seeing two ringing screens for a single call.
 * These tests verify that gate never has a hole.
 */
public class IncomingCallWatcherDeduplicationTest {

    @Before
    public void clearShownSet() throws Exception {
        // Empty the static set between tests so state doesn't bleed across.
        Field f = IncomingCallWatcher.class.getDeclaredField("shownCallIds");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> set = (Set<String>) f.get(null);
        set.clear();
    }

    // ── markShown / isShown ───────────────────────────────────────────────────

    @Test
    public void isShown_falseBeforeMarkShown() {
        assertFalse("unknown call ID must not appear shown",
                IncomingCallWatcher.isShown("call-abc-123"));
    }

    @Test
    public void isShown_trueAfterMarkShown() {
        IncomingCallWatcher.markShown("call-xyz-456");
        assertTrue("call ID must be shown after markShown()",
                IncomingCallWatcher.isShown("call-xyz-456"));
    }

    @Test
    public void markShown_doesNotAffectOtherIds() {
        IncomingCallWatcher.markShown("call-a");
        assertFalse("marking call-a must not affect call-b",
                IncomingCallWatcher.isShown("call-b"));
    }

    @Test
    public void markShown_isIdempotent() {
        IncomingCallWatcher.markShown("call-dup");
        IncomingCallWatcher.markShown("call-dup");
        assertTrue("duplicate markShown() must still result in isShown()==true",
                IncomingCallWatcher.isShown("call-dup"));
    }

    @Test
    public void markShown_nullSafe() {
        // Should not throw; null call IDs are a defensive no-op
        IncomingCallWatcher.markShown(null);
        assertFalse("null call ID must not appear shown",
                IncomingCallWatcher.isShown(null));
    }

    @Test
    public void isShown_nullReturnsFalse() {
        assertFalse("null must never be 'shown'",
                IncomingCallWatcher.isShown(null));
    }

    @Test
    public void multipleCallIds_trackedIndependently() {
        IncomingCallWatcher.markShown("call-1");
        IncomingCallWatcher.markShown("call-2");
        IncomingCallWatcher.markShown("call-3");

        assertTrue(IncomingCallWatcher.isShown("call-1"));
        assertTrue(IncomingCallWatcher.isShown("call-2"));
        assertTrue(IncomingCallWatcher.isShown("call-3"));
        assertFalse(IncomingCallWatcher.isShown("call-4"));
    }

    // ── Auto-clear when the set grows too large ───────────────────────────────

    @Test
    public void markShown_autoClearAtOverflow() throws Exception {
        // markShown() clears the set when size > 100 before adding the new ID.
        // This prevents the static set from growing without bound over a session.
        Field f = IncomingCallWatcher.class.getDeclaredField("shownCallIds");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> set = (Set<String>) f.get(null);

        // Fill the set with 101 entries (triggers auto-clear on the next markShown)
        for (int i = 0; i < 101; i++) {
            set.add("old-call-" + i);
        }
        assertEquals(101, set.size());

        // This call should clear the set (101 > 100) and then add the new entry.
        IncomingCallWatcher.markShown("new-call");

        // The old entries must be gone; only the new one remains.
        assertTrue("new ID must be present after auto-clear",
                IncomingCallWatcher.isShown("new-call"));
        assertFalse("old entries must be cleared on overflow",
                IncomingCallWatcher.isShown("old-call-0"));
        assertFalse("old entries must be cleared on overflow",
                IncomingCallWatcher.isShown("old-call-50"));
    }
}
