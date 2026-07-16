package com.duoshield.app.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.view.View;

public class NetworkStateHelper {

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
}
