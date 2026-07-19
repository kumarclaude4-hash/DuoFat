package com.duoshield.app.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;

import com.duoshield.app.BuildConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;

/**
 * Backblaze B2 storage helper — S3-compatible API, AWS Signature V4, AES-256-GCM E2EE.
 *
 * SECURITY RULES:
 *  1. All bytes are AES-256-GCM encrypted BEFORE upload using a per-file random key.
 *  2. The per-file key (Base64) is stored in the Firestore message doc as "mediaKey".
 *  3. Downloads are public (bucket allows public GET) — safe because content is E2EE.
 *  4. On-wire format: [12-byte IV | ciphertext | 16-byte GCM auth tag]
 *  5. Paths are stored with "b2:" prefix to distinguish from Supabase paths.
 *
 * Firestore message doc:
 * {
 *   "type":     "image" | "video",
 *   "path":     "b2:media/<chatId>/<uuid>.jpg",
 *   "mediaKey": "<Base64 AES-256 key>",
 *   "isEncrypted": true,
 *   ...
 * }
 */
public final class B2StorageHelper {

    public static String getEndpoint() {
        String ep = BuildConfig.B2_ENDPOINT == null ? "" : BuildConfig.B2_ENDPOINT.trim();
        return ep.isEmpty() ? "https://s3.ca-east-006.backblazeb2.com" : ep;
    }
    public static String getRegion() {
        String r = BuildConfig.B2_REGION == null ? "" : BuildConfig.B2_REGION.trim();
        return r.isEmpty() ? "ca-east-006" : r;
    }
    private static String getKeyId() {
        return BuildConfig.B2_KEY_ID == null ? "" : BuildConfig.B2_KEY_ID.trim();
    }
    private static String getAppKey() {
        return BuildConfig.B2_APPLICATION_KEY == null ? "" : BuildConfig.B2_APPLICATION_KEY.trim();
    }
    /** @deprecated Use {@link #getEndpoint()} */
    public static final String ENDPOINT       = "https://s3.ca-east-006.backblazeb2.com";
    /** @deprecated Use {@link #getRegion()} */
    public static final String REGION         = "ca-east-006";
    public static final String SERVICE        = "s3";
    public static final String B2_PATH_PREFIX = "b2:";

    private static final int    CONNECT_TIMEOUT_MS  = 15_000;
    private static final int    READ_TIMEOUT_MS     = 30_000;
    private static final int    BUFFER_SIZE         = 32_768;
    private static final int    MAX_RETRIES         = 3;
    private static final long   INITIAL_BACKOFF_MS  = 1_000L;
    private static final long   MAX_BACKOFF_MS      = 30_000L;

    // ── Singleton OkHttpClient — shared across all B2 requests for TCP/TLS reuse ──────────────
    // ConnectionPool keeps up to 5 idle sockets alive for 5 minutes.
    // The first request pays the full TCP + TLS handshake cost; every subsequent
    // request to the same host reuses the open socket and costs only the HTTP round-trip
    // (~180-250 ms vs. ~900-1400 ms for a cold connection from India to ca-east-006).
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    // ── In-memory decrypted-media cache (adaptive: 1/5 of max heap, min 32 MB, max 96 MB) ─────
    private static final LruCache<String, byte[]> MEDIA_CACHE;
    static {
        long maxHeap  = Runtime.getRuntime().maxMemory();
        int  cacheMax = (int) Math.min(96L * 1024 * 1024,
                               Math.max(32L * 1024 * 1024, maxHeap / 5));
        MEDIA_CACHE = new LruCache<String, byte[]>(cacheMax) {
            @Override protected int sizeOf(String key, byte[] value) { return value.length; }
        };
    }

    // ── In-flight request deduplication ──────────────────────────────────────
    // If 10 list rows all request the same avatar/media before the first download
    // completes, without this they would each fire a separate network call and
    // decryption. With this map, only the first fires; the rest queue on it and
    // all receive the result together once the single download finishes.
    private static final ConcurrentHashMap<String, List<MediaCallback>> IN_FLIGHT =
            new ConcurrentHashMap<>();

    // ── Bounded thread pool for concurrent media downloads ───────────────────
    // Adaptive: 2 threads for low-RAM devices (JVM heap ≤ 256 MB, e.g. POCO C51
    // with 2–3 GB RAM where MIUI leaves ~192 MB for the app), 3 threads otherwise.
    // Each thread holds an encrypted + decrypted buffer simultaneously (up to 2×
    // the file size), so reducing from 3→2 cuts peak buffer pressure by ~33 MB
    // on a 10 MB video download — meaningful when total usable heap is ~150 MB.
    private static final ExecutorService MEDIA_POOL;
    static {
        long maxHeap = Runtime.getRuntime().maxMemory();
        int  threads = (maxHeap <= 256L * 1024 * 1024) ? 2 : 3;
        MEDIA_POOL = Executors.newFixedThreadPool(threads);
    }
    private static final int    GCM_IV_LEN         = 12;
    private static final int    GCM_TAG_LEN        = 128;
    private static final String TAG                = "B2Storage";
    private static final String EMPTY_BODY_HASH    =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private B2StorageHelper() {}

    // ── Retry infrastructure ──────────────────────────────────────────────────

    /**
     * Thrown for HTTP 4xx responses — configuration errors that won't be fixed
     * by retrying (wrong credentials, bucket not found, bad request format).
     */
    static final class NonRetryableException extends IOException {
        final int httpCode;
        NonRetryableException(int httpCode, String message) {
            super(message);
            this.httpCode = httpCode;
        }
    }

    @FunctionalInterface
    private interface B2Operation<T> {
        T call() throws Exception;
    }

    /**
     * Executes {@code op} with up to {@link #MAX_RETRIES} automatic retries
     * on transient network failures, using exponential backoff.
     *
     * <ul>
     *   <li>{@link NonRetryableException} (HTTP 4xx) — re-thrown immediately, no retry.</li>
     *   <li>Any other {@link Exception} (IOException, timeout, 5xx) — retried up to
     *       {@link #MAX_RETRIES} times with delays of 1 s, 2 s, 4 s (capped at 30 s).</li>
     * </ul>
     *
     * @param opName  Short label for logcat messages (e.g. "upload:media/chat1/uuid.jpg").
     * @param op      The B2 network operation to execute.
     */
    private static <T> T withRetry(String opName, B2Operation<T> op) throws Exception {
        long backoff = INITIAL_BACKOFF_MS;
        Exception last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return op.call();
            } catch (NonRetryableException e) {
                throw e;
            } catch (Exception e) {
                last = e;
                if (attempt < MAX_RETRIES) {
                    Log.w(TAG, opName + " failed (attempt " + (attempt + 1) + "/" + MAX_RETRIES
                            + "), retrying in " + backoff + " ms — " + e.getMessage());
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
                } else {
                    Log.e(TAG, opName + " failed after " + MAX_RETRIES + " retries.", e);
                }
            }
        }
        throw last;
    }

    // ── Connection pre-warming ────────────────────────────────────────────────

    /**
     * Pre-warms the TCP + TLS connection to B2 on a new daemon thread.
     * Call once at app startup (e.g. from {@code DuoShieldApp.onCreate()}).
     * The HTTP response code does not matter — the goal is only to establish
     * and pool the socket so every subsequent upload/download skips the
     * ~700-1000 ms handshake overhead.
     */
    public static void warmConnection() {
        new Thread(() -> {
            try {
                Request r = new Request.Builder()
                        .url(getEndpoint() + "/" + getBucket() + "/")
                        .head()
                        .build();
                try (Response resp = HTTP_CLIENT.newCall(r).execute()) {
                    Log.d(TAG, "B2 connection pre-warmed (HTTP " + resp.code() + ").");
                }
            } catch (Exception e) {
                Log.d(TAG, "B2 pre-warm (non-fatal): " + e.getMessage());
            }
        }, "b2-warmup").start();
    }

    // ── Path helpers ─────────────────────────────────────────────────────────

    /** Returns true when value is a B2 storage path (starts with "b2:"). */
    public static boolean isB2Path(String value) {
        return value != null && value.startsWith(B2_PATH_PREFIX);
    }

    /**
     * F10 fix — verifies a B2 path belongs to the given conversation before deletion.
     *
     * <p>All paths written by this app follow the pattern
     * {@code b2:media/<conversationId>/...} or {@code b2:voice/<conversationId>/...}.
     * A crafted message doc with an attacker-chosen {@code path} value (e.g. pointing at
     * another conversation's media) would pass the plain {@link #isB2Path(String)} check,
     * so every deletion path that is driven by a Firestore document's {@code path} field
     * (SelfDestructWorker, B2CleanupWorker, GroupChatActivity) must call this method
     * instead of (or in addition to) {@link #isB2Path(String)}.
     *
     * @param b2Path         the path field read from a Firestore message document.
     * @param conversationId the conversation / group ID the message belongs to.
     * @return {@code true} only if the path is a valid B2 path AND its second segment
     *         equals {@code conversationId}.
     */
    public static boolean isOwnedB2Path(String b2Path, String conversationId) {
        if (!isB2Path(b2Path) || conversationId == null) return false;
        // Object key after the "b2:" prefix: "media/<convId>/..." or "voice/<convId>/..."
        String objectKey = toObjectKey(b2Path);
        String[] parts = objectKey.split("/", 3);
        // parts[0] = "media" or "voice", parts[1] = conversationId, parts[2] = filename
        return parts.length >= 2 && conversationId.equals(parts[1]);
    }

    /** Strips the "b2:" prefix to get the raw S3 object key. */
    public static String toObjectKey(String b2Path) {
        return b2Path.startsWith(B2_PATH_PREFIX)
                ? b2Path.substring(B2_PATH_PREFIX.length()) : b2Path;
    }

    /** Constructs the public download URL for a B2 path. */
    public static String toPublicUrl(String b2Path) {
        return getEndpoint() + "/" + getBucket() + "/" + toObjectKey(b2Path);
    }

    public static String getBucket() {
        String b = (BuildConfig.B2_BUCKET == null) ? "" : BuildConfig.B2_BUCKET.trim();
        return b.isEmpty() ? "DuoShield-" : b;
    }

    /** Returns the first 4 and last 4 chars of the key ID, masked in the middle. */
    public static String getMaskedKeyId() {
        String k = BuildConfig.B2_KEY_ID == null ? "" : BuildConfig.B2_KEY_ID.trim();
        if (k.isEmpty()) return "(not set)";
        if (k.length() <= 8) return k.substring(0, 2) + "****";
        return k.substring(0, 4) + "…" + k.substring(k.length() - 4);
    }

    /** Returns true if both key ID and application key are non-empty. */
    public static boolean areCredentialsConfigured() {
        return !getKeyId().isEmpty() && !getAppKey().isEmpty();
    }

    /**
     * Same as {@link #testConnection()} but also returns how many milliseconds
     * the round-trip took. Thread-safe; call from a background thread.
     *
     * @return A two-element array: [errorMessage_or_null, latencyMs_string]
     */
    public static String[] testConnectionTimed() {
        long start = System.currentTimeMillis();
        String err = testConnection();
        long latencyMs = System.currentTimeMillis() - start;
        return new String[]{ err, String.valueOf(latencyMs) };
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    /** Encrypted bytes + the Base64 key to store in Firestore. */
    public static final class EncryptedMedia {
        public final byte[] data;
        public final String keyBase64;
        EncryptedMedia(byte[] data, String keyBase64) {
            this.data = data; this.keyBase64 = keyBase64;
        }
    }

    public interface ProgressCallback {
        /** Called on a background thread. */
        void onProgress(int percent);
    }

    public interface MediaCallback {
        void onLoaded(byte[] plainBytes);
        void onError(Exception e);
    }

    /**
     * Loads raw avatar bytes (profile/partner photos — uploaded as plain JPEGs,
     * not AES-GCM encrypted like message media) via an authenticated SigV4 GET.
     *
     * <p>Avatars were previously exposed to the UI as a "public" URL from
     * {@link #toPublicUrl(String)} and handed straight to Glide. The bucket only
     * accepts SigV4-signed requests (see {@link #downloadFile(String)}), so an
     * unauthenticated Glide fetch of that URL always returned 403 — the exact
     * "Photo uploaded, but couldn't load the preview" symptom. Route avatars
     * through the same authenticated pipeline as every other B2 object instead.
     */
    public static void loadAvatarBytes(String b2Path, MediaCallback cb) {
        if (b2Path == null || b2Path.isEmpty()) {
            new Handler(Looper.getMainLooper()).post(() ->
                    cb.onError(new IOException("Avatar path is null or empty")));
            return;
        }
        byte[] cached = MEDIA_CACHE.get(b2Path);
        if (cached != null) {
            new Handler(Looper.getMainLooper()).post(() -> cb.onLoaded(cached));
            return;
        }
        // Deduplication: queue behind any already-in-flight request for this path
        synchronized (IN_FLIGHT) {
            List<MediaCallback> waiters = IN_FLIGHT.get(b2Path);
            if (waiters != null) { waiters.add(cb); return; }
            List<MediaCallback> list = new ArrayList<>();
            list.add(cb);
            IN_FLIGHT.put(b2Path, list);
        }
        MEDIA_POOL.execute(() -> {
            List<MediaCallback> waiters;
            synchronized (IN_FLIGHT) { waiters = IN_FLIGHT.remove(b2Path); }
            if (waiters == null) return;
            try {
                byte[] raw = downloadFile(b2Path);
                MEDIA_CACHE.put(b2Path, raw);
                new Handler(Looper.getMainLooper()).post(() -> {
                    for (MediaCallback c : waiters) c.onLoaded(raw);
                });
            } catch (Exception e) {
                Log.e(TAG, "loadAvatarBytes failed: " + b2Path, e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    for (MediaCallback c : waiters) c.onError(e);
                });
            }
        });
    }

    /**
     * OkHttp {@link RequestBody} that streams a byte array in {@link #BUFFER_SIZE}-chunks
     * and reports progress via {@link ProgressCallback} so the UI progress bar stays smooth.
     * Re-entrant: {@code writeTo()} can be called multiple times (needed for retries).
     */
    private static final class ProgressRequestBody extends RequestBody {
        private final byte[]           data;
        private final MediaType        mediaType;
        private final ProgressCallback cb;

        ProgressRequestBody(byte[] data, MediaType mediaType, ProgressCallback cb) {
            this.data = data;
            this.mediaType = mediaType;
            this.cb = cb;
        }

        @Override public MediaType contentType()   { return mediaType; }
        @Override public long      contentLength() { return data.length; }

        @Override public void writeTo(BufferedSink sink) throws IOException {
            int written = 0;
            while (written < data.length) {
                int len = Math.min(BUFFER_SIZE, data.length - written);
                sink.write(data, written, len);
                written += len;
                if (cb != null) cb.onProgress((int) (100L * written / data.length));
            }
        }
    }

    // ── AES-256-GCM encryption ────────────────────────────────────────────────

    /**
     * AES-256-GCM encrypts {@code plain} with a fresh random key.
     * On-wire: [12-byte IV | ciphertext | 16-byte GCM auth tag]
     */
    public static EncryptedMedia encryptForUpload(byte[] plain) throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256, new SecureRandom());
        SecretKey key = kg.generateKey();
        byte[] iv = new byte[GCM_IV_LEN];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN, iv));
        byte[] ct = cipher.doFinal(plain);
        byte[] data = new byte[GCM_IV_LEN + ct.length];
        System.arraycopy(iv, 0, data, 0, GCM_IV_LEN);
        System.arraycopy(ct, 0, data, GCM_IV_LEN, ct.length);
        return new EncryptedMedia(data, Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP));
    }

    /** AES-256-GCM decrypts bytes received from B2. Inverse of {@link #encryptForUpload}. */
    public static byte[] decryptAfterDownload(byte[] data, String keyBase64) throws Exception {
        if (keyBase64 == null || keyBase64.isEmpty()) return data;
        if (data.length < GCM_IV_LEN + 16)
            throw new IOException("Encrypted blob too short: " + data.length);
        byte[] decodedKey = Base64.decode(keyBase64, Base64.NO_WRAP);
        SecretKey key = new SecretKeySpec(decodedKey, "AES");
        byte[] iv = Arrays.copyOfRange(data, 0, GCM_IV_LEN);
        byte[] ct = Arrays.copyOfRange(data, GCM_IV_LEN, data.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN, iv));
        return cipher.doFinal(ct);
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Uploads {@code data} to B2 at {@code objectKey} using S3 PutObject + AWS SigV4.
     *
     * <p>Automatically retries up to {@link #MAX_RETRIES} times on transient network errors
     * with exponential backoff (1 s, 2 s, 4 s). HTTP 4xx responses are not retried.
     *
     * <p>Uses the shared {@link #HTTP_CLIENT} connection pool — first call pays TCP + TLS
     * overhead; subsequent calls to the same endpoint reuse the open socket.
     *
     * @return "b2:<objectKey>" — store this as the Firestore "path" field.
     * @throws NonRetryableException for HTTP 4xx (bad credentials, bad request).
     * @throws Exception             on network error after all retries exhausted.
     */
    public static String uploadFile(byte[] data, String objectKey,
                                    String contentType, ProgressCallback cb) throws Exception {
        // F9 fix: when B2 credentials are not baked into the APK, obtain a
        // server-generated presigned PUT URL and upload directly — the B2 key
        // never touches the device in new builds.
        if (getKeyId().isEmpty()) {
            return uploadViaPresignedUrl(data, objectKey, contentType, cb);
        }
        String bucket = getBucket();
        String region = getRegion();
        String host   = "s3." + region + ".backblazeb2.com";

        return withRetry("upload:" + objectKey, () -> {
            Date now         = new Date();
            String dateStamp = utcFormat("yyyyMMdd", now);
            String amzDate   = utcFormat("yyyyMMdd'T'HHmmss'Z'", now);
            String bodyHash  = sha256Hex(data);
            String urlStr    = getEndpoint() + "/" + bucket + "/" + objectKey;

            String canonicalHeaders =
                    "content-length:" + data.length + "\n"
                    + "content-type:" + contentType + "\n"
                    + "host:" + host + "\n"
                    + "x-amz-content-sha256:" + bodyHash + "\n"
                    + "x-amz-date:" + amzDate + "\n";
            String signedHeaders = "content-length;content-type;host;x-amz-content-sha256;x-amz-date";
            String canonicalUri  = "/" + bucket + "/" + objectKey;

            String canonicalRequest = "PUT\n" + canonicalUri + "\n\n"
                    + canonicalHeaders + "\n" + signedHeaders + "\n" + bodyHash;
            String credentialScope = dateStamp + "/" + region + "/" + SERVICE + "/aws4_request";
            String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n"
                    + credentialScope + "\n"
                    + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            byte[] signingKey   = getSigningKey(dateStamp, region);
            String signature    = hmacSha256Hex(signingKey, stringToSign);
            String authorization = "AWS4-HMAC-SHA256 Credential="
                    + getKeyId() + "/" + credentialScope
                    + ", SignedHeaders=" + signedHeaders
                    + ", Signature=" + signature;

            MediaType mt   = MediaType.parse(contentType);
            RequestBody rb = new ProgressRequestBody(data, mt, cb);
            Request request = new Request.Builder()
                    .url(urlStr)
                    .put(rb)
                    .addHeader("Authorization",        authorization)
                    .addHeader("x-amz-date",           amzDate)
                    .addHeader("x-amz-content-sha256", bodyHash)
                    .build();

            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                int code = response.code();
                if (code == 200 || code == 201) {
                    Log.d(TAG, "B2 uploaded: " + objectKey);
                    return B2_PATH_PREFIX + objectKey;
                }
                ResponseBody errBody = response.body();
                String err = errBody != null ? errBody.string() : "";
                if (code >= 400 && code < 500) {
                    throw new NonRetryableException(code,
                            "B2 upload failed [" + code + "]: " + err);
                }
                throw new IOException("B2 upload failed [" + code + "]: " + err);
            }
        });
    }

    // ── Download (authenticated SigV4 GET) ───────────────────────────────────

    /**
     * Downloads raw encrypted bytes from B2 using a SigV4-authenticated GET.
     * Automatically retries on transient failures; HTTP 4xx is not retried.
     */
    public static byte[] downloadFile(String b2Path) throws Exception {
        // F9 fix: bucket is public-read (content is E2EE — no plaintext stored
        // at rest). Skip SigV4 when credentials are absent; use a simple GET.
        if (getKeyId().isEmpty()) {
            return downloadViaPublicUrl(b2Path);
        }
        String objectKey = toObjectKey(b2Path);
        String bucket    = getBucket();
        String region    = getRegion();
        String host      = "s3." + region + ".backblazeb2.com";

        return withRetry("download:" + objectKey, () -> {
            Date now         = new Date();
            String dateStamp = utcFormat("yyyyMMdd", now);
            String amzDate   = utcFormat("yyyyMMdd'T'HHmmss'Z'", now);
            String urlStr    = getEndpoint() + "/" + bucket + "/" + objectKey;

            String canonicalHeaders =
                    "host:" + host + "\n"
                    + "x-amz-content-sha256:" + EMPTY_BODY_HASH + "\n"
                    + "x-amz-date:" + amzDate + "\n";
            String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
            String canonicalUri  = "/" + bucket + "/" + objectKey;

            String canonicalRequest = "GET\n" + canonicalUri + "\n\n"
                    + canonicalHeaders + "\n" + signedHeaders + "\n" + EMPTY_BODY_HASH;
            String credentialScope = dateStamp + "/" + region + "/" + SERVICE + "/aws4_request";
            String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n"
                    + credentialScope + "\n"
                    + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            byte[] signingKey   = getSigningKey(dateStamp, region);
            String signature    = hmacSha256Hex(signingKey, stringToSign);
            String authorization = "AWS4-HMAC-SHA256 Credential="
                    + getKeyId() + "/" + credentialScope
                    + ", SignedHeaders=" + signedHeaders
                    + ", Signature=" + signature;

            Request request = new Request.Builder()
                    .url(urlStr)
                    .get()
                    .addHeader("Authorization",        authorization)
                    .addHeader("x-amz-date",           amzDate)
                    .addHeader("x-amz-content-sha256", EMPTY_BODY_HASH)
                    .build();

            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                int code = response.code();
                if (code == 200) {
                    ResponseBody body = response.body();
                    if (body == null)
                        throw new IOException("B2 download: empty response body for " + objectKey);
                    byte[] bytes = body.bytes();
                    Log.d(TAG, "B2 downloaded: " + objectKey + " (" + bytes.length + " bytes)");
                    return bytes;
                }
                ResponseBody errBody = response.body();
                String err = errBody != null ? errBody.string() : "";
                if (code >= 400 && code < 500) {
                    throw new NonRetryableException(code,
                            "B2 download failed [" + code + "]: " + err);
                }
                throw new IOException("B2 download failed [" + code + "]: " + err);
            }
        });
    }

    // ── loadMedia (download + decrypt, async) ─────────────────────────────────

    /**
     * Downloads and decrypts B2 media on the shared thread pool.
     *
     * Cache priority (fastest → slowest):
     *  1. In-memory LRU cache (same-session repeat views — instant)
     *  2. Persistent disk cache in {@code ctx.getFilesDir()/b2_cache/} (survives restarts)
     *  3. Live B2 download over the network (retried up to {@link #MAX_RETRIES} times)
     *
     * Delivers decrypted bytes on the main thread.
     *
     * @param ctx optional — if non-null, the persistent disk cache is checked/populated.
     */
    public static void loadMedia(Context ctx, String b2Path, String keyBase64, MediaCallback cb) {
        if (b2Path == null || b2Path.isEmpty()) {
            new Handler(Looper.getMainLooper()).post(() ->
                    cb.onError(new IOException("B2 path is null or empty")));
            return;
        }
        // 1. In-memory cache
        byte[] cached = MEDIA_CACHE.get(b2Path);
        if (cached != null) {
            new Handler(Looper.getMainLooper()).post(() -> cb.onLoaded(cached));
            return;
        }
        // 2. Persistent disk cache
        if (ctx != null) {
            byte[] disk = readDiskCache(ctx, b2Path);
            if (disk != null) {
                MEDIA_CACHE.put(b2Path, disk);
                new Handler(Looper.getMainLooper()).post(() -> cb.onLoaded(disk));
                return;
            }
        }
        // 3. Download from B2 (with automatic retry) — deduplicated
        synchronized (IN_FLIGHT) {
            List<MediaCallback> waiters = IN_FLIGHT.get(b2Path);
            if (waiters != null) { waiters.add(cb); return; }
            List<MediaCallback> list = new ArrayList<>();
            list.add(cb);
            IN_FLIGHT.put(b2Path, list);
        }
        final Context fCtx = ctx;
        MEDIA_POOL.execute(() -> {
            List<MediaCallback> waiters;
            synchronized (IN_FLIGHT) { waiters = IN_FLIGHT.remove(b2Path); }
            if (waiters == null) return;
            try {
                byte[] raw   = downloadFile(b2Path);
                byte[] plain = decryptAfterDownload(raw, keyBase64);
                MEDIA_CACHE.put(b2Path, plain);
                if (fCtx != null) writeDiskCache(fCtx, b2Path, plain);
                new Handler(Looper.getMainLooper()).post(() -> {
                    for (MediaCallback c : waiters) c.onLoaded(plain);
                });
            } catch (Exception e) {
                Log.e(TAG, "loadMedia failed: " + b2Path, e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    for (MediaCallback c : waiters) c.onError(e);
                });
            }
        });
    }

    /**
     * Backwards-compatible overload (no disk cache).
     * Prefer the {@code (Context, ...)} variant where a Context is available.
     */
    public static void loadMedia(String b2Path, String keyBase64, MediaCallback cb) {
        loadMedia(null, b2Path, keyBase64, cb);
    }

    /** Returns cached decrypted bytes for {@code b2Path}, or {@code null} if not cached. */
    public static byte[] getCached(String b2Path) {
        return b2Path != null ? MEDIA_CACHE.get(b2Path) : null;
    }

    // ── Video thumbnail generation (encrypted videos) ──────────────────────────
    //
    // Encrypted B2 videos can't be handed to Glide directly — Glide's frame
    // extraction only understands a real URI/URL, not an AES-256-GCM blob. Since
    // the video already has to be downloaded + decrypted for playback anyway, we
    // reuse that same pipeline here, then pull a single frame with
    // MediaMetadataRetriever and cache the resulting JPEG bytes separately so
    // repeat binds (RecyclerView scroll) never redo the decode.

    private static final LruCache<String, byte[]> THUMB_CACHE =
            new LruCache<String, byte[]>(8 * 1024 * 1024) {
                @Override protected int sizeOf(String key, byte[] value) { return value.length; }
            };

    public interface ThumbnailCallback {
        void onLoaded(byte[] jpegBytes);
        void onError(Exception e);
    }

    /** Returns a cached JPEG thumbnail for {@code b2Path}, or {@code null} if not yet generated. */
    public static byte[] getCachedThumb(String b2Path) {
        return b2Path != null ? THUMB_CACHE.get(b2Path) : null;
    }

    /**
     * Downloads + decrypts the video at {@code b2Path} (sharing the same cache as
     * {@link #loadMedia}) and extracts a single JPEG thumbnail frame. Delivers on
     * the main thread.
     */
    public static void loadVideoThumbnail(Context ctx, String b2Path, String keyBase64,
                                           ThumbnailCallback cb) {
        if (b2Path == null || b2Path.isEmpty()) {
            new Handler(Looper.getMainLooper()).post(() ->
                    cb.onError(new IOException("B2 path is null or empty")));
            return;
        }
        byte[] cachedThumb = THUMB_CACHE.get(b2Path);
        if (cachedThumb != null) {
            new Handler(Looper.getMainLooper()).post(() -> cb.onLoaded(cachedThumb));
            return;
        }
        loadMedia(ctx, b2Path, keyBase64, new MediaCallback() {
            @Override public void onLoaded(byte[] plainVideoBytes) {
                MEDIA_POOL.execute(() -> {
                    try {
                        byte[] jpeg = extractThumbnailJpeg(ctx, b2Path, plainVideoBytes);
                        if (jpeg == null) throw new IOException("no frame extracted");
                        THUMB_CACHE.put(b2Path, jpeg);
                        new Handler(Looper.getMainLooper()).post(() -> cb.onLoaded(jpeg));
                    } catch (Exception e) {
                        Log.w(TAG, "loadVideoThumbnail: extraction failed for " + b2Path, e);
                        new Handler(Looper.getMainLooper()).post(() -> cb.onError(e));
                    }
                });
            }
            @Override public void onError(Exception e) {
                cb.onError(e);
            }
        });
    }

    /** Writes {@code plainVideoBytes} to a scratch file and pulls a frame near the start. */
    private static byte[] extractThumbnailJpeg(Context ctx, String b2Path, byte[] plainVideoBytes)
            throws Exception {
        File tmp = File.createTempFile("thumb_", ".mp4", ctx.getCacheDir());
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(plainVideoBytes);
            }
            retriever.setDataSource(tmp.getAbsolutePath());
            Bitmap frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            frame.compress(Bitmap.CompressFormat.JPEG, 82, baos);
            frame.recycle();
            return baos.toByteArray();
        } finally {
            retriever.release();
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** Removes a single entry from the in-memory cache (call after delete). */
    public static void evictCache(String b2Path) {
        if (b2Path != null) MEDIA_CACHE.remove(b2Path);
    }

    /** Evicts a path from both in-memory and disk caches. Requires context for disk. */
    public static void evictCache(Context ctx, String b2Path) {
        if (b2Path == null) return;
        MEDIA_CACHE.remove(b2Path);
        if (ctx != null) {
            File f = diskCacheFile(ctx, b2Path);
            if (f != null && f.exists()) f.delete();
        }
    }

    // ── Persistent disk cache helpers ─────────────────────────────────────────

    private static final String DISK_CACHE_DIR      = "b2_cache";
    /** Maximum total size of the on-disk media cache in bytes (100 MB).
     *  On a POCO C51 (64 GB eMMC 5.1) the available storage shrinks quickly
     *  once MIUI system files, photos, and app data are accounted for.
     *  LRU eviction (oldest-first) keeps the cache bounded without a wipe. */
    private static final long   DISK_CACHE_MAX_BYTES = 100L * 1024 * 1024;

    private static File diskCacheFile(Context ctx, String b2Path) {
        try {
            // Use SHA-256 of the b2Path as the filename to avoid path-separator issues
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(b2Path.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            File dir = new File(ctx.getFilesDir(), DISK_CACHE_DIR);
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, hex.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readDiskCache(Context ctx, String b2Path) {
        File f = diskCacheFile(ctx, b2Path);
        if (f == null || !f.exists()) return null;
        try (FileInputStream fis = new FileInputStream(f)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = fis.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "readDiskCache failed for " + b2Path, e);
            return null;
        }
    }

    private static void writeDiskCache(Context ctx, String b2Path, byte[] plain) {
        File f = diskCacheFile(ctx, b2Path);
        if (f == null) return;
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(plain);
        } catch (Exception e) {
            Log.w(TAG, "writeDiskCache failed for " + b2Path, e);
            return;
        }
        // Enforce the 100 MB cap after each write (oldest-first LRU eviction).
        // Called from a MEDIA_POOL background thread so disk I/O is safe here.
        enforceDiskCacheLimit(ctx);
    }

    /**
     * Evicts the oldest cache files until total size is below {@link #DISK_CACHE_MAX_BYTES}.
     * Uses last-modified time as the LRU proxy; files untouched longest are deleted first.
     * Must be called from a background thread (performs file I/O).
     */
    private static void enforceDiskCacheLimit(Context ctx) {
        try {
            File dir = new File(ctx.getFilesDir(), DISK_CACHE_DIR);
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) return;

            // Calculate total cache size
            long totalBytes = 0;
            for (File f : files) totalBytes += f.length();
            if (totalBytes <= DISK_CACHE_MAX_BYTES) return;

            // Sort oldest-first by last-modified time
            java.util.Arrays.sort(files, (a, b) ->
                    Long.compare(a.lastModified(), b.lastModified()));

            // Evict oldest until under the limit
            for (File f : files) {
                if (totalBytes <= DISK_CACHE_MAX_BYTES) break;
                long sz = f.length();
                if (f.delete()) {
                    totalBytes -= sz;
                    Log.d(TAG, "diskCache evicted: " + f.getName() + " (" + sz + " B)");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "enforceDiskCacheLimit failed: " + e.getMessage());
        }
    }

    /**
     * Pre-populates the disk cache for a set of media paths.
     * Used by {@link com.duoshield.app.backup.MediaRestoreHelper} during account restore.
     * Must be called from a background thread.
     */
    public static void preCacheSync(Context ctx, String b2Path, String keyBase64) {
        if (ctx == null || b2Path == null || b2Path.isEmpty()) return;
        // Already cached — skip download
        if (MEDIA_CACHE.get(b2Path) != null) return;
        File f = diskCacheFile(ctx, b2Path);
        if (f != null && f.exists()) return;
        try {
            byte[] raw   = downloadFile(b2Path);
            byte[] plain = decryptAfterDownload(raw, keyBase64);
            MEDIA_CACHE.put(b2Path, plain);
            writeDiskCache(ctx, b2Path, plain);
            Log.d(TAG, "preCacheSync: cached " + b2Path + " (" + plain.length + " B)");
        } catch (Exception e) {
            Log.w(TAG, "preCacheSync: skipped " + b2Path + " — " + e.getMessage());
        }
    }

    /**
     * Deletes all files in the persistent disk cache.
     * Call from {@link com.duoshield.app.util.WipeHelper} during a full wipe.
     */
    public static void clearDiskCache(Context ctx) {
        if (ctx == null) return;
        File dir = new File(ctx.getFilesDir(), DISK_CACHE_DIR);
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) f.delete();
        MEDIA_CACHE.evictAll();
        Log.d(TAG, "clearDiskCache: wiped " + (files.length) + " cached files.");
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /**
     * Synchronously deletes the object at {@code b2Path} using S3 DeleteObject + AWS SigV4.
     * Retries on transient network errors. 404 is treated as success (already gone).
     * Call from a background thread.
     */
    public static void deleteFile(String b2Path) throws Exception {
        if (b2Path == null || b2Path.isEmpty()) return;
        // F9 fix: route deletion through server endpoint when credentials are absent.
        if (getKeyId().isEmpty()) {
            deleteViaServer(b2Path);
            return;
        }
        String objectKey = toObjectKey(b2Path);
        String bucket    = getBucket();
        String region    = getRegion();
        String host      = "s3." + region + ".backblazeb2.com";

        withRetry("delete:" + objectKey, () -> {
            Date now       = new Date();
            String dateStamp = utcFormat("yyyyMMdd", now);
            String amzDate   = utcFormat("yyyyMMdd'T'HHmmss'Z'", now);
            String urlStr    = getEndpoint() + "/" + bucket + "/" + objectKey;

            String canonicalHeaders =
                    "host:" + host + "\n"
                    + "x-amz-content-sha256:" + EMPTY_BODY_HASH + "\n"
                    + "x-amz-date:" + amzDate + "\n";
            String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
            String canonicalUri  = "/" + bucket + "/" + objectKey;

            String canonicalRequest = "DELETE\n" + canonicalUri + "\n\n"
                    + canonicalHeaders + "\n" + signedHeaders + "\n" + EMPTY_BODY_HASH;
            String credentialScope = dateStamp + "/" + region + "/" + SERVICE + "/aws4_request";
            String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n"
                    + credentialScope + "\n"
                    + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            byte[] signingKey   = getSigningKey(dateStamp, region);
            String signature    = hmacSha256Hex(signingKey, stringToSign);
            String authorization = "AWS4-HMAC-SHA256 Credential="
                    + getKeyId() + "/" + credentialScope
                    + ", SignedHeaders=" + signedHeaders
                    + ", Signature=" + signature;

            Request request = new Request.Builder()
                    .url(urlStr)
                    .delete()
                    .addHeader("Authorization",        authorization)
                    .addHeader("x-amz-date",           amzDate)
                    .addHeader("x-amz-content-sha256", EMPTY_BODY_HASH)
                    .build();

            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                int code = response.code();
                if (code == 200 || code == 204 || code == 404) {
                    Log.d(TAG, "B2 deleted: " + objectKey + " (" + code + ")");
                    return null;
                }
                ResponseBody errBody = response.body();
                String err = errBody != null ? errBody.string() : "";
                if (code >= 400 && code < 500) {
                    throw new NonRetryableException(code,
                            "B2 delete failed [" + code + "]: " + err);
                }
                throw new IOException("B2 delete failed [" + code + "] for: " + objectKey);
            }
        });
    }

    // ── AWS SigV4 helpers ─────────────────────────────────────────────────────

    private static byte[] getSigningKey(String dateStamp, String region) throws Exception {
        byte[] kSecret  = ("AWS4" + getAppKey()).getBytes(StandardCharsets.UTF_8);
        byte[] kDate    = hmacSha256(kSecret,   dateStamp);
        byte[] kRegion  = hmacSha256(kDate,     region);
        byte[] kService = hmacSha256(kRegion,   SERVICE);
        return hmacSha256(kService, "aws4_request");
    }

    /**
     * Tests connectivity to B2 by issuing a signed GET to the bucket root.
     * Not retried — this is a one-shot diagnostic check.
     * Call from a background thread.
     * @return null on success, error message on failure.
     */
    public static String testConnection() {
        try {
            if (!getKeyId().isEmpty() && !getAppKey().isEmpty()) {
                // Direct SigV4 test — credentials baked into APK
                String bucket  = getBucket();
                String region  = getRegion();
                String urlStr  = getEndpoint() + "/" + bucket + "?max-keys=1";
                String host    = "s3." + region + ".backblazeb2.com";
                Date now       = new Date();
                String dateStamp = utcFormat("yyyyMMdd", now);
                String amzDate   = utcFormat("yyyyMMdd'T'HHmmss'Z'", now);
                String canonicalHeaders =
                        "host:" + host + "\n"
                        + "x-amz-content-sha256:" + EMPTY_BODY_HASH + "\n"
                        + "x-amz-date:" + amzDate + "\n";
                String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
                String canonicalRequest = "GET\n/" + bucket + "\nmax-keys=1\n"
                        + canonicalHeaders + "\n" + signedHeaders + "\n" + EMPTY_BODY_HASH;
                String credentialScope = dateStamp + "/" + region + "/" + SERVICE + "/aws4_request";
                String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n"
                        + credentialScope + "\n"
                        + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
                byte[] signingKey   = getSigningKey(dateStamp, region);
                String signature    = hmacSha256Hex(signingKey, stringToSign);
                String authorization = "AWS4-HMAC-SHA256 Credential="
                        + getKeyId() + "/" + credentialScope
                        + ", SignedHeaders=" + signedHeaders
                        + ", Signature=" + signature;

                Request request = new Request.Builder()
                        .url(urlStr)
                        .get()
                        .addHeader("Authorization",        authorization)
                        .addHeader("x-amz-date",           amzDate)
                        .addHeader("x-amz-content-sha256", EMPTY_BODY_HASH)
                        .build();

                try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                    int code = response.code();
                    if (code == 200 || code == 204) return null;
                    ResponseBody errBody = response.body();
                    String body = errBody != null ? errBody.string() : "";
                    // F36 fix: use masked key ID to avoid exposing credential in on-screen error card
                    if (code == 403) return "HTTP 403 — credentials rejected by B2 for bucket \""
                            + bucket + "\". Verify B2_KEY_ID='" + getMaskedKeyId()
                            + "' endpoint='" + getEndpoint()
                            + "' and B2_APPLICATION_KEY are correct.\nB2 response: " + body;
                    if (code == 404) return "HTTP 404 — bucket \"" + bucket
                            + "\" not found. Verify the B2_BUCKET secret matches your actual bucket name exactly.\nB2 response: " + body;
                    return "HTTP " + code + " — check bucket name and credentials.\nB2 response: " + body;
                }
            } else {
                // Presign-server path — credentials live on the server (F9 design).
                // Test by requesting a presigned URL for a probe key; if the server
                // returns one, B2 credentials are correctly configured on Render.
                String idToken = getIdTokenSync();
                if (idToken == null) {
                    return "Not signed in — open the app and sign in, then test again.";
                }
                java.net.URL url = new java.net.URL(
                        com.duoshield.app.BuildConfig.PUSH_SERVER_URL + "/b2PresignedPut");
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + idToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                org.json.JSONObject reqBody = new org.json.JSONObject();
                reqBody.put("objectKey", "_connection_test_/probe.bin");
                reqBody.put("contentType", "application/octet-stream");
                conn.getOutputStream().write(reqBody.toString().getBytes(StandardCharsets.UTF_8));
                conn.getOutputStream().close();
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code == 200) {
                    return null; // presign server returned a valid URL — B2 is configured
                } else if (code == 503) {
                    return "Push server returned 503 — B2_KEY_ID and B2_APPLICATION_KEY are not set "
                            + "on your Render deployment. Add them in the Render dashboard → "
                            + "Environment, then redeploy the service.";
                } else if (code == 401 || code == 403) {
                    return "Push server rejected auth (HTTP " + code + ") — "
                            + "sign out and back in, then test again.";
                } else {
                    return "Push server returned HTTP " + code + " — check server logs on Render.";
                }
            }
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    // ── F9: Presigned URL / server-proxy helpers ──────────────────────────────

    /**
     * Uploads {@code data} to B2 using a server-generated presigned PUT URL.
     * Used when {@code B2_KEY_ID} / {@code B2_APPLICATION_KEY} are empty in the APK.
     */
    private static String uploadViaPresignedUrl(byte[] data, String objectKey,
                                                String contentType, ProgressCallback cb)
            throws Exception {
        String presigned = fetchPresignedPutUrl(objectKey, contentType);
        MediaType   mt   = MediaType.parse(contentType);
        RequestBody rb   = new ProgressRequestBody(data, mt, cb);
        Request request  = new Request.Builder()
                .url(presigned)
                .put(rb)
                .header("Content-Type", contentType)
                .build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            int code = response.code();
            if (code == 200 || code == 201) {
                Log.d(TAG, "B2 presigned uploaded: " + objectKey);
                return B2_PATH_PREFIX + objectKey;
            }
            ResponseBody errBody = response.body();
            String err = errBody != null ? errBody.string() : "";
            if (code >= 400 && code < 500) {
                throw new NonRetryableException(code,
                        "B2 presigned PUT failed [" + code + "]: " + err);
            }
            throw new IOException("B2 presigned PUT failed [" + code + "]: " + err);
        }
    }

    /**
     * Downloads from the B2 public endpoint (no auth).
     * Safe because content is always AES-256-GCM encrypted before upload.
     */
    private static byte[] downloadViaPublicUrl(String b2Path) throws Exception {
        String objectKey = toObjectKey(b2Path);
        String urlStr    = "https://s3." + getRegion() + ".backblazeb2.com/"
                         + getBucket() + "/" + objectKey;
        Request request = new Request.Builder().url(urlStr).get().build();
        return withRetry("dl-public:" + objectKey, () -> {
            try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                int code = response.code();
                if (code == 200) {
                    ResponseBody body = response.body();
                    if (body == null) throw new IOException("Empty B2 response body");
                    return body.bytes();
                }
                if (code >= 400 && code < 500) {
                    throw new NonRetryableException(code,
                            "B2 public GET failed [" + code + "]: " + objectKey);
                }
                throw new IOException("B2 public GET failed [" + code + "]: " + objectKey);
            }
        });
    }

    /**
     * Deletes an object via the push server's {@code /b2Delete} endpoint.
     * Used when credentials are not baked into the APK (F9 fix).
     */
    private static void deleteViaServer(String b2Path) throws Exception {
        String objectKey = toObjectKey(b2Path);
        String idToken   = getIdTokenSync();
        if (idToken == null) throw new Exception("Not authenticated — server delete unavailable");

        java.net.URL url = new java.net.URL(BuildConfig.PUSH_SERVER_URL + "/b2Delete");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + idToken);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        org.json.JSONObject body = new org.json.JSONObject();
        body.put("objectKey", objectKey);
        conn.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
        conn.getOutputStream().close();
        int code = conn.getResponseCode();
        conn.disconnect();
        if (code != 200 && code != 204 && code != 404) {
            throw new IOException("Server /b2Delete returned HTTP " + code);
        }
        Log.d(TAG, "B2 server-deleted: " + objectKey);
    }

    /**
     * Fetches a presigned S3 PUT URL from the push server.
     * Blocks the calling thread for up to 10 seconds.
     */
    private static String fetchPresignedPutUrl(String objectKey, String contentType) throws Exception {
        String idToken = getIdTokenSync();
        if (idToken == null) throw new Exception("Not authenticated — presign unavailable");

        java.net.URL url = new java.net.URL(BuildConfig.PUSH_SERVER_URL + "/b2PresignedPut");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + idToken);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        org.json.JSONObject reqBody = new org.json.JSONObject();
        reqBody.put("objectKey", objectKey);
        reqBody.put("contentType", contentType);
        conn.getOutputStream().write(reqBody.toString().getBytes(StandardCharsets.UTF_8));
        conn.getOutputStream().close();
        int code = conn.getResponseCode();
        if (code != 200) throw new IOException("Presign server returned HTTP " + code);
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        conn.disconnect();
        return new org.json.JSONObject(sb.toString()).getString("url");
    }

    /**
     * Synchronously retrieves the current user's Firebase ID token.
     * Background-thread safe (uses lock/notify — compatible with Firebase tasks API).
     */
    private static String getIdTokenSync() {
        com.google.firebase.auth.FirebaseUser user =
            com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return null;
        final String[] holder = {null};
        final Object   lock   = new Object();
        user.getIdToken(false)
            .addOnSuccessListener(r -> {
                synchronized (lock) { holder[0] = r.getToken(); lock.notifyAll(); }
            })
            .addOnFailureListener(e -> {
                synchronized (lock) { lock.notifyAll(); }
            });
        synchronized (lock) {
            if (holder[0] == null) {
                try { lock.wait(10_000); } catch (InterruptedException ignored) {}
            }
        }
        return holder[0];
    }

    private static byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacSha256Hex(byte[] key, String data) throws Exception {
        return bytesToHex(hmacSha256(key, data));
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return bytesToHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static String sha256Hex(String data) throws Exception {
        return sha256Hex(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String utcFormat(String pattern, Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(date);
    }
}
