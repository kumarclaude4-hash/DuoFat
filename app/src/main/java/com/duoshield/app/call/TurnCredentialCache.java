package com.duoshield.app.call;

/**
 * Thread-safe in-memory cache for Cloudflare TURN credentials.
 *
 * <p>TTL is 1 hour — well within the 24-hour Cloudflare expiry window.
 * {@link TurnCredentialFetcher} populates the cache; {@link CallManager}
 * reads it in {@code buildIceServers()}.
 */
public class TurnCredentialCache {

    private static final long TTL_MS = 60 * 60 * 1_000L; // 1 hour

    private static volatile TurnCredentialCache instance;

    private volatile String[] urls;
    private volatile String   username;
    private volatile String   credential;
    private volatile long     fetchedAtMs = 0L;

    private TurnCredentialCache() {}

    public static TurnCredentialCache get() {
        if (instance == null) {
            synchronized (TurnCredentialCache.class) {
                if (instance == null) instance = new TurnCredentialCache();
            }
        }
        return instance;
    }

    public synchronized void set(String[] urls, String username, String credential) {
        this.urls       = urls;
        this.username   = username;
        this.credential = credential;
        this.fetchedAtMs = System.currentTimeMillis();
    }

    /** Returns true if credentials are present and younger than {@link #TTL_MS}. */
    public synchronized boolean isValid() {
        return urls != null && username != null && credential != null
                && (System.currentTimeMillis() - fetchedAtMs) < TTL_MS;
    }

    public synchronized String[] getUrls()       { return urls; }
    public synchronized String   getUsername()   { return username; }
    public synchronized String   getCredential() { return credential; }
}
