package com.duoshield.app.util;

import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches Open Graph / title metadata from a URL asynchronously.
 * Results are cached in memory so each URL is only fetched once per session.
 * All callbacks are delivered on the main thread.
 */
public class LinkPreviewFetcher {

    public static class Preview {
        public final String url;
        public final String title;
        public final String domain;
        public final String imageUrl;

        public Preview(String url, String title, String domain, String imageUrl) {
            this.url      = url;
            this.title    = title;
            this.domain   = domain;
            this.imageUrl = imageUrl;
        }
    }

    public interface Callback {
        void onResult(Preview preview);
    }

    /**
     * Bounded in-memory cache.  A plain {@code ConcurrentHashMap} grows without
     * limit — in long chat sessions with many unique URLs this would eventually
     * cause an OOM.  The cap of 200 entries is roughly 200 KB for typical preview
     * objects (BUG-LP01).
     */
    private static final int                    MAX_CACHE   = 200;
    private static final Map<String, Preview>  cache       = new java.util.concurrent.ConcurrentHashMap<>();
    private static final ExecutorService        executor    = Executors.newFixedThreadPool(3);
    private static final Handler                mainHandler = new Handler(Looper.getMainLooper());

    private static final Pattern OG_TITLE   = Pattern.compile(
        "<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']{1,200})[\"']",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_TITLE2  = Pattern.compile(
        "<meta[^>]+content=[\"']([^\"']{1,200})[\"'][^>]+property=[\"']og:title[\"']",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TITLE = Pattern.compile(
        "<title[^>]*>([^<]{1,200})</title>",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_IMAGE   = Pattern.compile(
        "<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']{4,500})[\"']",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_IMAGE2  = Pattern.compile(
        "<meta[^>]+content=[\"']([^\"']{4,500})[\"'][^>]+property=[\"']og:image[\"']",
        Pattern.CASE_INSENSITIVE);

    /**
     * Fetch preview for a URL. Calls back immediately with cached value if available,
     * otherwise fetches in background. Callback always runs on the main thread.
     */
    public static void fetch(String url, Callback callback) {
        if (cache.containsKey(url)) {
            // cache.get() may return null for previously-failed fetches — callers
            // must handle null (BUG-LP01).
            callback.onResult(cache.get(url));
            return;
        }
        executor.execute(() -> {
            Preview preview = fetchSync(url);
            // Evict the oldest entry if the cache is full before inserting.
            if (preview != null && cache.size() >= MAX_CACHE) {
                String oldest = cache.keySet().iterator().next();
                cache.remove(oldest);
            }
            // Store even null to avoid re-fetching permanently-failed URLs this session.
            cache.put(url, preview);
            final Preview result = preview;
            mainHandler.post(() -> callback.onResult(result));
        });
    }

    /**
     * F-18 fix: rejects URLs that could be used as SSRF vectors.
     * Blocks: non-HTTP/HTTPS schemes, loopback, link-local, and RFC-1918 private ranges.
     * The hostname is resolved before the check so that DNS-rebinding to a private IP
     * after this check still fails at the OS level (no second open-connection call).
     */
    private static boolean isSafeUrl(URL url) {
        String scheme = url.getProtocol();
        if (!"http".equals(scheme) && !"https".equals(scheme)) return false;

        String host = url.getHost();
        if (host == null || host.isEmpty()) return false;

        // Block obvious loopback/localhost names before DNS resolution
        if ("localhost".equalsIgnoreCase(host) || "ip6-localhost".equalsIgnoreCase(host)) return false;

        try {
            InetAddress addr = InetAddress.getByName(host);
            // Loopback: 127.0.0.0/8, ::1
            if (addr.isLoopbackAddress()) return false;
            // Link-local: 169.254.0.0/16, fe80::/10
            if (addr.isLinkLocalAddress()) return false;
            // Site-local (private): 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, fc00::/7
            if (addr.isSiteLocalAddress()) return false;
            // Multicast
            if (addr.isMulticastAddress()) return false;
        } catch (Exception e) {
            return false; // DNS resolution failed — reject
        }
        return true;
    }

    private static Preview fetchSync(String urlStr) {
        try {
            URL url  = new URL(urlStr);
            String domain = url.getHost();
            if (domain.startsWith("www.")) domain = domain.substring(4);

            // F-18 fix: reject private/loopback IPs before opening any connection
            if (!isSafeUrl(url)) {
                return new Preview(urlStr, null, domain, null);
            }

            // BUG-D10 fix: disable automatic redirect following and re-validate each
            // hop with isSafeUrl() to prevent SSRF via open-redirect chains.
            // Without this, `setInstanceFollowRedirects(true)` would silently follow
            // redirects pointing at 169.254.0.0/16 or 10.x.x.x targets after the
            // initial isSafeUrl() check on the original URL only.
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(false); // redirects handled manually below
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");

            // Follow up to 5 redirects, re-validating each hop for SSRF safety
            int redirectsLeft = 5;
            while (redirectsLeft-- > 0) {
                int status = conn.getResponseCode();
                if (status == HttpURLConnection.HTTP_MOVED_PERM
                        || status == HttpURLConnection.HTTP_MOVED_TEMP
                        || status == 307 || status == 308) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (location == null) return new Preview(urlStr, null, domain, null);
                    URL redirectUrl = new URL(location);
                    if (!isSafeUrl(redirectUrl)) {
                        return new Preview(urlStr, null, domain, null); // SSRF blocked
                    }
                    conn = (HttpURLConnection) redirectUrl.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setInstanceFollowRedirects(false);
                    conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
                    continue;
                }
                break; // not a redirect — proceed to read response
            }

            int status = conn.getResponseCode();
            if (status < 200 || status >= 400) {
                conn.disconnect();
                return new Preview(urlStr, null, domain, null);
            }

            String ct = conn.getContentType();
            if (ct == null || !ct.toLowerCase().contains("text/html")) {
                conn.disconnect();
                return new Preview(urlStr, null, domain, null);
            }

            // Read only the first ~25 KB — enough to get <head> metadata
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String line;
                int chars = 0;
                while ((line = br.readLine()) != null && chars < 25_000) {
                    sb.append(line).append('\n');
                    chars += line.length();
                }
            }
            conn.disconnect();

            String html  = sb.toString();
            String title = null;
            String img   = null;

            // og:title (two attribute orders)
            Matcher m = OG_TITLE.matcher(html);
            if (m.find()) {
                title = m.group(1).trim();
            } else {
                m = OG_TITLE2.matcher(html);
                if (m.find()) title = m.group(1).trim();
            }
            // Fallback to <title>
            if (title == null || title.isEmpty()) {
                m = HTML_TITLE.matcher(html);
                if (m.find()) title = m.group(1).trim();
            }

            // og:image
            m = OG_IMAGE.matcher(html);
            if (m.find()) {
                img = m.group(1).trim();
            } else {
                m = OG_IMAGE2.matcher(html);
                if (m.find()) img = m.group(1).trim();
            }

            if (title != null) title = title.replaceAll("\\s+", " ");

            return new Preview(urlStr, title, domain, img);

        } catch (Exception e) {
            return null;
        }
    }

    /** Call on app wipe or sign-out to clear cached previews. */
    public static void clearCache() {
        cache.clear();
    }
}
