package com.duoshield.app.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.view.View;

import com.duoshield.app.ui.NoInternetActivity;

public class NetworkStateHelper {

    /**
     * Set to {@code true} while a {@link NoInternetActivity} is on top of the task,
     * so that {@link #blockIfOffline(Activity)} does not stack a second copy while the
     * first is still resolving. Cleared by NoInternetActivity in onStop().
     *
     * <p>Mirrors the pattern used by {@code BaseActivity.lockScreenActive}.</p>
     */
    public static volatile boolean noInternetActive = false;

    public static boolean isOnline(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager)
            ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network net = cm.getActiveNetwork();
        if (net == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    public static void updateBanner(Context ctx, View banner) {
        if (banner == null) return;
        banner.setVisibility(isOnline(ctx) ? View.GONE : View.VISIBLE);
    }

    /**
     * Full offline lockout. If the device has no internet connection, launches the
     * blocking {@link NoInternetActivity} on top of {@code activity} and returns
     * {@code true}; callers should stop further work when this returns true.
     *
     * <p>Call from {@code onStart()} of every screen that must not operate offline.
     * Deliberately NOT called from the lock screen or the device PIN gate: the duress
     * secondary PIN must still be enterable with no connectivity so its local wipe can
     * fire, with the server-side account lock draining once the network returns (the
     * "wipe locally now, lock server later" durable-intent design).</p>
     */
    public static boolean blockIfOffline(Activity activity) {
        if (activity == null) return false;
        if (isOnline(activity)) return false;
        if (noInternetActive) return true;
        noInternetActive = true;
        Intent intent = new Intent(activity, NoInternetActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(intent);
        return true;
    }
}
