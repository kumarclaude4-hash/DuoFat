package com.duoshield.app.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;
import android.view.View;

import com.duoshield.app.BuildConfig;
import com.duoshield.app.ui.NoInternetActivity;

import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The app's hard "no offline use" wall.
 *
 * <p>DuoShield must not be operable without a live connection <b>to its own server</b>.
 * That is a strictly stronger property than "the device has a network interface", and the
 * two used to be conflated here: {@link #isOnline(Context)} tested only
 * {@code NET_CAPABILITY_INTERNET}, which is present on a Wi-Fi network sitting behind a
 * captive portal, on a hotel/airport SSID that resolves nothing, on a link whose data
 * allowance is exhausted, and on any connection where DNS, TLS or the push server itself
 * is down or blocked. On every one of those the old gate answered "online" and let the
 * user into real app content — where the account-lock latch, the duress lock drain and
 * every authorization decision are unenforceable, because all of them live server-side.
 *
 * <p>That made the wall bypassable without airplane mode: an AP that answers DHCP and
 * nothing else was enough to operate a wiped or server-locked account's UI as though
 * nothing were wrong. Proving reachability of the actual server closes that and makes the
 * gate mean what its javadoc always claimed.
 *
 * <p><b>Deliberate exemptions.</b> The gate is never applied to
 * {@code LockScreenActivity}, {@code DevicePinGateActivity} or {@link NoInternetActivity}.
 * The secondary (duress) PIN is entered on the lock screen and MUST stay enterable with no
 * connectivity at all, so its local wipe fires immediately; the server-side account lock
 * is recorded as a durable intent and drains once the network returns (see
 * {@code DuressManager} / {@code AccountLockWorker}). Gating the lock screen would hand an
 * adversary a way to disable duress simply by pulling the network.
 */
public class NetworkStateHelper {

    private static final String TAG = "NetworkStateHelper";

    /**
     * Set to {@code true} while a {@link NoInternetActivity} is on top of the task,
     * so that {@link #blockIfOffline(Activity)} does not stack a second copy while the
     * first is still resolving. Cleared by NoInternetActivity in onStop().
     *
     * <p>Mirrors the pattern used by {@code BaseActivity.lockScreenActive}.</p>
     */
    public static volatile boolean noInternetActive = false;

    /** How long a confirmed server contact is trusted before it must be re-proved. */
    private static final long SERVER_OK_TTL_MS = 3 * 60 * 1000L;

    /** Probe timeouts — short, because this decision gates screen entry. */
    private static final int PROBE_CONNECT_TIMEOUT_MS = 4000;
    private static final int PROBE_READ_TIMEOUT_MS    = 4000;

    /** Timestamp of the last confirmed contact with the push server, 0 if never. */
    private static volatile long lastServerOkAt = 0L;

    /** Guards against piling up concurrent probes from several onStart() calls. */
    private static volatile boolean probeInFlight = false;

    /**
     * The most recent foreground activity that asked to be gated. Held weakly so a
     * finished activity is never retained, and used only to raise the gate from a probe
     * that completed after {@link #blockIfOffline(Activity)} had already returned.
     */
    private static volatile WeakReference<Activity> gateHost = new WeakReference<>(null);

    // ── Transport-level checks ────────────────────────────────────────────────

    /**
     * True only when the active network is one Android has actually
     * <em>validated</em> as reaching the internet.
     *
     * <p>{@code NET_CAPABILITY_VALIDATED} is the difference between "there is a link" and
     * "traffic sent over it comes back": it is the platform's own captive-portal /
     * walled-garden probe result. Requiring it, rather than only
     * {@code NET_CAPABILITY_INTERNET} (which merely declares intent), is what stops a
     * network that answers DHCP and nothing else from reading as connectivity.
     * {@code NOT_SUSPENDED} additionally excludes a link that exists but is momentarily
     * carrying no data.
     */
    public static boolean hasValidatedTransport(Context ctx) {
        if (ctx == null) return false;
        ConnectivityManager cm = (ConnectivityManager)
            ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network net = cm.getActiveNetwork();
        if (net == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        if (caps == null) return false;
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
    }

    /**
     * Retained name, tightened meaning: "online" now requires a validated transport.
     * Used by the lightweight in-screen banner, where the answer must be synchronous and
     * a server probe therefore cannot be run.
     */
    public static boolean isOnline(Context ctx) {
        return hasValidatedTransport(ctx);
    }

    // ── Server-level checks ───────────────────────────────────────────────────

    /** True when the server was confirmed reachable recently enough to still trust. */
    public static boolean hasFreshServerContact() {
        long ok = lastServerOkAt;
        return ok != 0L && (System.currentTimeMillis() - ok) < SERVER_OK_TTL_MS;
    }

    /**
     * Records confirmed contact with the push server. Any code path that completes a
     * successful server round-trip may call this to keep the gate warm; it is what makes
     * ordinary use of the app self-sustaining rather than purely probe-driven.
     */
    public static void markServerContact() {
        lastServerOkAt = System.currentTimeMillis();
    }

    /** Forget any cached success, so the next gate check must re-prove reachability. */
    public static void invalidateServerContact() {
        lastServerOkAt = 0L;
    }

    /**
     * Blocking probe of {@code GET <PUSH_SERVER_URL>/health}. Never call on the main
     * thread.
     *
     * <p>Any HTTP status below 500 counts as reached: the point is to prove packets get to
     * <em>this</em> server and back, not to assert what it replied — a WAF 403 or a 404
     * from a reshuffled route still proves the connection exists. A 5xx, a timeout, a DNS
     * failure and a TLS failure all count as unreachable.
     *
     * <p>An unconfigured {@code PUSH_SERVER_URL} is treated as unreachable: a build with
     * no server to talk to is precisely the case that must not be usable.
     */
    public static boolean probeServerBlocking() {
        String base = BuildConfig.PUSH_SERVER_URL;
        if (base == null || base.isEmpty()) {
            Log.w(TAG, "PUSH_SERVER_URL not configured — treating server as unreachable.");
            return false;
        }
        String endpoint = base.endsWith("/") ? base + "health" : base + "/health";
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(PROBE_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(PROBE_READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            int code = conn.getResponseCode();
            boolean reached = code < 500;
            if (reached) markServerContact();
            else Log.d(TAG, "server probe reached a broken server: HTTP " + code);
            return reached;
        } catch (Exception e) {
            Log.d(TAG, "server probe failed: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Re-proves reachability off the main thread and raises the blocking gate if the
     * server cannot be reached. Self-throttling: at most one probe runs at a time.
     */
    private static void probeServerAsync() {
        if (probeInFlight) return;
        probeInFlight = true;
        new Thread(() -> {
            try {
                if (!probeServerBlocking()) raiseGate();
            } finally {
                probeInFlight = false;
            }
        }, "server-reachability-probe").start();
    }

    /** Raises the blocking gate over the last activity that asked to be gated. */
    private static void raiseGate() {
        final Activity host = gateHost.get();
        if (host == null || host.isFinishing() || host.isDestroyed()) return;
        if (noInternetActive) return;
        host.runOnUiThread(() -> {
            if (noInternetActive) return;
            if (host.isFinishing() || host.isDestroyed()) return;
            noInternetActive = true;
            Intent intent = new Intent(host, NoInternetActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            host.startActivity(intent);
        });
    }

    // ── The gate ──────────────────────────────────────────────────────────────

    /**
     * Full offline lockout. Returns {@code true} when {@code activity} must not proceed,
     * having launched {@link NoInternetActivity} on top of it; callers stop all further
     * work when this returns true.
     *
     * <p>Three-way, fail-closed decision:
     * <ol>
     *   <li>No validated transport → block immediately and synchronously.</li>
     *   <li>Transport present but the server has not been confirmed reachable within
     *       {@link #SERVER_OK_TTL_MS} — which includes every cold start, since nothing has
     *       been confirmed yet — → block immediately. The gate screen itself runs the
     *       probe and dismisses the instant the server answers, so a genuinely online user
     *       sees it only briefly.</li>
     *   <li>Server confirmed recently → proceed, and re-prove in the background. If that
     *       probe fails the gate is raised a moment later, so a server that goes away
     *       mid-session still walls the app off.</li>
     * </ol>
     *
     * <p>Call from {@code onStart()} of every screen that must not operate without the
     * server. Deliberately NOT called from the lock screen, the device PIN gate, or the
     * gate's own screen — see the class javadoc for why the duress path must stay
     * reachable offline.
     */
    public static boolean blockIfOffline(Activity activity) {
        if (activity == null) return false;
        gateHost = new WeakReference<>(activity);

        if (hasValidatedTransport(activity) && hasFreshServerContact()) {
            // Trusted, but verify — asynchronously, so screen entry is not delayed.
            probeServerAsync();
            return false;
        }

        if (noInternetActive) return true;
        noInternetActive = true;
        Intent intent = new Intent(activity, NoInternetActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(intent);
        return true;
    }

    // ── Banner ────────────────────────────────────────────────────────────────

    public static void updateBanner(Context ctx, View banner) {
        if (banner == null) return;
        banner.setVisibility(isOnline(ctx) ? View.GONE : View.VISIBLE);
    }
}
