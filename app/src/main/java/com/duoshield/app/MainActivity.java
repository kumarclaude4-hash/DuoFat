package com.duoshield.app;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.duoshield.app.crypto.signal.SignalKeyManager;
import com.duoshield.app.notifications.NotificationHelper;

import com.duoshield.app.util.AppLockManager;
import com.duoshield.app.util.FcmTokenHelper;
import com.duoshield.app.util.FirebaseCostGuard;
import com.duoshield.app.ConversationListActivity;
import com.duoshield.app.ui.AddContactActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.util.Collections;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_POST_NOTIFICATIONS = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Apply or clear FLAG_SECURE based on the global screenshot preference.
        // Default: screenshots BLOCKED (pref absent → FLAG_SECURE on) — see F20 fix note
        // in BaseActivity.onCreate() for the full rationale.
        boolean allowScreenshots = getSharedPreferences("duoshield_prefs", MODE_PRIVATE)
                .getBoolean("app_screenshot_enabled", false);
        if (allowScreenshots) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }

        FrameLayout splash = new FrameLayout(this);
        splash.setBackgroundColor(0xFFFFFFFF);
        ProgressBar spinner = new ProgressBar(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        splash.addView(spinner, params);
        setContentView(splash);

        // Bug 4 fix: on Android 13+ (TIRAMISU), requestPermissions() is asynchronous.
        // The old code called requestPermissions() and then IMMEDIATELY called route(),
        // so route() ran before the user responded to the permission dialog. This could
        // cause FCM token registration to happen without POST_NOTIFICATIONS permission,
        // silently failing to show notifications on fresh installs.
        //
        // Fix: if the permission is not yet granted, request it and return. route() is
        // called from onRequestPermissionsResult() once the user responds. If permission
        // is already granted (or SDK < 33, where no runtime permission is needed), call
        // proceedAfterPermission() directly.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_POST_NOTIFICATIONS);
                return; // wait for onRequestPermissionsResult before routing
            }
        }
        proceedAfterPermission();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            // Proceed regardless of grant/deny — DuoShield works without notifications,
            // but FCM-based push will be silent if denied on Android 13+.
            proceedAfterPermission();
        }
    }

    private void proceedAfterPermission() {
        // F30 fix: Respect the duress-wipe-in-progress routing guard. If the flag is
        // set, the wipe background thread is still running — treat the user as signed
        // out and fall through to SignInActivity, which will also block auto-routing.
        boolean wipeInProgress = getSharedPreferences("duoshield_prefs", MODE_PRIVATE)
                .getBoolean("duress_wipe_in_progress", false);
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (!wipeInProgress && currentUser != null) {
            if (!SignalKeyManager.isInitialized(this)) {
                // Identity keys missing (factory reset or app data wipe) - send to SignInActivity
                // so user can restore their identity or create a new one.
                Intent intent = new Intent(this, SignInActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            } else {
                route();
            }
        } else {
            startActivity(new Intent(this, SignInActivity.class));
            finish();
        }
    }

    private void route() {
        // 1. Signal keys check moved to proceedAfterPermission() for early guarding.
        //    They are initialised in SeedPhraseDisplayActivity / RestoreFromSeedActivity.

        // 2. Notification channels
        NotificationHelper.createChannel(this);

        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        String myUid = FirebaseAuth.getInstance().getUid();
        if (myUid != null) {
            prefs.edit().putString("my_uid", myUid).apply();
        }

        // 3. Refresh FCM token.
        // F-04 fix: skip identity writes if the app is currently locked (PIN set and
        // backgrounded > 3 min). Announcing the device to Firestore before the user
        // passes the PIN gate leaks presence. ConversationListActivity extends
        // BaseActivity which will redirect to LockScreenActivity on its own onStart();
        // once unlocked, the next cold-start (or a manual refresh) re-uploads the token.
        // ecPublicKey upload removed — legacy ECDH field; Signal public key bundle is
        // uploaded in SeedPhraseDisplayActivity / RestoreFromSeedActivity instead.
        // BUG-F-12 fix: Register FCM token immediately. Even if the app is locked,
        // we must ensure the server has a valid token to deliver notifications.
        // Presence is not leaked as FcmTokenHelper only writes the token, not "online" status.
        FcmTokenHelper.register(this);

        // 4. Route: Logged-in users with keys always go to ConversationListActivity.
        // The list activity handles fetching conversations from Firestore or Room.
        // AddContactActivity is only for users with no contacts yet (handled inside ConversationListActivity).
        startActivity(new Intent(this, ConversationListActivity.class));
        finish();
    }
}
