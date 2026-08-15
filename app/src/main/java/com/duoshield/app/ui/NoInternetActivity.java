package com.duoshield.app.ui;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.duoshield.app.R;
import com.duoshield.app.util.NetworkStateHelper;
import com.google.android.material.button.MaterialButton;

/**
 * Full-screen blocking gate shown whenever the device has no internet connection.
 *
 * <p>The app enforces a hard "no offline use" policy: {@code NetworkStateHelper.blockIfOffline}
 * launches this screen from every functional screen's {@code onStart()}. It cannot be
 * dismissed by the back button (that only sends the task to the background); it finishes
 * itself automatically the moment connectivity is restored, either via the registered
 * {@link ConnectivityManager.NetworkCallback} or the manual "Try again" button.</p>
 *
 * <p>Deliberately does NOT extend BaseActivity and is never launched over the lock screen
 * or device PIN gate — the duress secondary PIN must remain enterable offline so its local
 * wipe can fire.</p>
 */
public class NoInternetActivity extends AppCompatActivity {

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private TextView tvStatus;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_no_internet);

        tvStatus = findViewById(R.id.tvOfflineStatus);
        MaterialButton btnRetry = findViewById(R.id.btnRetry);
        if (btnRetry != null) btnRetry.setOnClickListener(v -> checkAndDismiss(true));

        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        NetworkStateHelper.noInternetActive = true;
        registerCallback();
        // Guard against a race where connectivity was restored between the offline
        // check that launched us and this screen actually coming up.
        checkAndDismiss(false);
    }

    private void registerCallback() {
        if (connectivityManager == null || networkCallback != null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                runOnUiThread(() -> checkAndDismiss(false));
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    runOnUiThread(() -> checkAndDismiss(false));
                }
            }
        };
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback);
        } catch (Exception ignored) {
            networkCallback = null;
        }
    }

    private void checkAndDismiss(boolean fromRetry) {
        if (NetworkStateHelper.isOnline(this)) {
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        if (fromRetry) {
            Toast.makeText(this, R.string.offline_still_offline, Toast.LENGTH_SHORT).show();
            if (tvStatus != null) {
                tvStatus.setVisibility(View.VISIBLE);
                tvStatus.setText(R.string.offline_status_checking);
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        NetworkStateHelper.noInternetActive = false;
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
            networkCallback = null;
        }
    }

    /**
     * Back must not be an escape hatch into the app while offline. Send the whole task
     * to the background (equivalent to Home) instead of dismissing this gate.
     */
    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }
}
