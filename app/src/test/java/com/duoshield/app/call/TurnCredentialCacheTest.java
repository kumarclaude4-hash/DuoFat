package com.duoshield.app.call;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link TurnCredentialCache} — no Android context required.
 *
 * <p>The singleton instance is reset between tests via reflection so each test
 * starts with a clean slate (the singleton pattern would otherwise let state
 * leak between tests in the same JVM process).
 */
public class TurnCredentialCacheTest {

    @Before
    public void resetSingleton() throws Exception {
        // Null the static `instance` field so each test creates a fresh object.
        Field f = TurnCredentialCache.class.getDeclaredField("instance");
        f.setAccessible(true);
        f.set(null, null);
    }

    // ── isValid ──────────────────────────────────────────────────────────────

    @Test
    public void isValid_falseWhenEmpty() {
        assertFalse("empty cache must not be valid",
                TurnCredentialCache.get().isValid());
    }

    @Test
    public void isValid_trueAfterSet() {
        TurnCredentialCache.get().set(
                new String[]{"turn:example.com:3478"}, "user1", "cred1");
        assertTrue("cache must be valid immediately after set()",
                TurnCredentialCache.get().isValid());
    }

    @Test
    public void isValid_falseAfterTtlExpires() throws Exception {
        TurnCredentialCache cache = TurnCredentialCache.get();
        cache.set(new String[]{"turn:example.com:3478"}, "user1", "cred1");

        // Backdating the fetchedAtMs field by 2 hours simulates TTL expiry
        // without sleeping in the test (which would be too slow).
        Field fetchedAtMs = TurnCredentialCache.class.getDeclaredField("fetchedAtMs");
        fetchedAtMs.setAccessible(true);
        long twoHoursAgo = System.currentTimeMillis() - (2 * 60 * 60 * 1_000L);
        fetchedAtMs.set(cache, twoHoursAgo);

        assertFalse("cache must be invalid after TTL expiry", cache.isValid());
    }

    @Test
    public void isValid_trueJustBeforeTtlExpires() throws Exception {
        TurnCredentialCache cache = TurnCredentialCache.get();
        cache.set(new String[]{"turn:example.com:3478"}, "user1", "cred1");

        // 59 minutes old — still within the 1-hour TTL
        Field fetchedAtMs = TurnCredentialCache.class.getDeclaredField("fetchedAtMs");
        fetchedAtMs.setAccessible(true);
        long fiftyNineMinutesAgo = System.currentTimeMillis() - (59 * 60 * 1_000L);
        fetchedAtMs.set(cache, fiftyNineMinutesAgo);

        assertTrue("cache must still be valid 59 minutes after set()", cache.isValid());
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    @Test
    public void getUrls_returnsSetUrls() {
        String[] urls = {"turn:a.com:3478", "turns:b.com:5349?transport=tcp"};
        TurnCredentialCache.get().set(urls, "user", "cred");
        assertArrayEquals("getUrls() must return exactly what was set",
                urls, TurnCredentialCache.get().getUrls());
    }

    @Test
    public void getUsername_returnsSetUsername() {
        TurnCredentialCache.get().set(
                new String[]{"turn:x.com"}, "myuser", "mycred");
        assertEquals("getUsername() must return what was set",
                "myuser", TurnCredentialCache.get().getUsername());
    }

    @Test
    public void getCredential_returnsSetCredential() {
        TurnCredentialCache.get().set(
                new String[]{"turn:x.com"}, "user", "s3cr3t");
        assertEquals("getCredential() must return what was set",
                "s3cr3t", TurnCredentialCache.get().getCredential());
    }

    @Test
    public void getUrls_nullBeforeSet() {
        assertNull("getUrls() must be null before set()",
                TurnCredentialCache.get().getUrls());
    }

    // ── Overwrite ─────────────────────────────────────────────────────────────

    @Test
    public void set_overwritesPreviousValues() {
        TurnCredentialCache cache = TurnCredentialCache.get();
        cache.set(new String[]{"turn:old.com"}, "olduser", "oldcred");
        cache.set(new String[]{"turn:new.com"}, "newuser", "newcred");

        assertEquals("second set() must overwrite username",
                "newuser", cache.getUsername());
        assertEquals("second set() must overwrite credential",
                "newcred", cache.getCredential());
        assertArrayEquals("second set() must overwrite URLs",
                new String[]{"turn:new.com"}, cache.getUrls());
        assertTrue("cache must remain valid after overwrite", cache.isValid());
    }

    // ── Singleton identity ─────────────────────────────────────────────────

    @Test
    public void get_returnsSameInstance() {
        TurnCredentialCache a = TurnCredentialCache.get();
        TurnCredentialCache b = TurnCredentialCache.get();
        assertSame("get() must always return the same instance", a, b);
    }

    @Test
    public void set_visibleOnSameInstance() {
        TurnCredentialCache.get().set(
                new String[]{"turn:example.com"}, "u", "p");
        // A second call to get() must see the same cached data
        assertTrue("set() on one get() call must be visible on another",
                TurnCredentialCache.get().isValid());
    }
}
