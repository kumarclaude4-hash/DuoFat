package com.duoshield.app;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.duoshield.app.crypto.signal.SignalKeyManager;
import com.duoshield.app.notifications.NotificationHelper;

import com.duoshield.app.security.PendingLockStore;
import com.duoshield.app.util.AppLockManager;
import com.duoshield.app.util.FcmTokenHelper;
import com.duoshield.app.util.FirebaseCostGuard;
import com.duoshield.app.util.PinManager;
import com.duoshield.app.ConversationListActivity;
import com.duoshield.app.ui.AddContactActivity;
import com.duoshield.app.ui.ForcedDuressRotationActivity;
import com.duoshield.app.ui.ForcedPrimaryPinRotationActivity;
import com.duoshield.app.ui.SetupPinActivity;
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
        // S08-H2: honor the "Allow screenshots" preference (secure by default)
        // instead of unconditionally allowing screenshots on this screen.
        BaseActivity.applyScreenshotSecurity(this);

        FrameLayout splash = new FrameLayout(this);
        splash.setBackgroundColor(0xFF191620);
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
        // The first-run PermissionsOnboardingActivity now batch-requests
        // POST_NOTIFICATIONS (along with camera/mic/media) before we ever get here,
        // so skip this fallback once that has run — otherwise a user who declined
        // notifications on the onboarding screen would be re-prompted immediately.
        // Legacy installs that predate onboarding (flag never set) keep the original
        // behaviour so they still get asked at least once.
        if (!com.duoshield.app.ui.PermissionsOnboardingActivity.isCompleted(this)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
        // F30 fix: Respect the reset-pending routing guard. If the flag is set, the
        // local reset background thread is still running — treat the user as signed
        // out and fall through to SignInActivity, which will also block auto-routing.
        boolean wipeInProgress = com.duoshield.app.security.DuressManager.isResetPending(this);
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

        // S06-M6: if the app was killed mid-rotation (RestoreFromSeedActivity
        // discovered accountLock/{uid}.rotationRequired and sent the user into the
        // 2-screen forced-rotation chain, but the server ack — /acknowledgeRotation
        // — never completed), resume at the correct screen instead of falling
        // through to the ordinary routing below. This must run before the
        // pending_pin_setup_ check: a rotation in progress always takes priority
        // over an unrelated interrupted PIN setup, and the two flags cannot both be
        // meaningfully set for the same account at once in practice, but ordering
        // this first keeps the priority explicit rather than incidental.
        if (PendingLockStore.isRotationDue(this)) {
            Class<?> dest = PendingLockStore.isRotationPrimaryDone(this)
                    ? ForcedDuressRotationActivity.class
                    : ForcedPrimaryPinRotationActivity.class;
            Log.i("MainActivity", "route: → " + dest.getSimpleName() + " (interrupted mid-rotation) uid=" + myUid);
            Intent rotationIntent = new Intent(this, dest);
            startActivity(rotationIntent);
            finish();
            return;
        }

        // If the app was killed anywhere between account creation and PIN setup
        // completing, this account was left half-created — Firebase sign-in and
        // Signal keys exist, but SetupPinActivity never finished. Route back
        // there instead of ConversationListActivity so the user can finish.
        // See SeedPhraseDisplayActivity (sets the flag) / SetupPinActivity
        // (clears it on success). Legacy accounts predating this flow never
        // have the flag set, so they are unaffected.
        if (myUid != null && prefs.getBoolean("pending_pin_setup_" + myUid, false)) {
            // Self-heal a stale marker: if this account already has a PIN hash, setup
            // genuinely completed and the flag is simply left over — clear it and
            // continue instead of sending the user to set a PIN they already set.
            // Older builds could strand the flag this way because PinManager.setPin()
            // was void and SetupPinActivity cleared the marker unconditionally, so a
            // PIN stored by any other path (Settings, a device-PIN promotion during
            // restore) left the flag on with a real PIN present. Without this, the
            // flag routed the user back here on every single launch.
            if (PinManager.hasPinSet(this)) {
                Log.i("MainActivity", "route: clearing stale pending_pin_setup_ (PIN already set) uid=" + myUid);
                prefs.edit().remove("pending_pin_setup_" + myUid).apply();
            } else {
                Log.i("MainActivity", "route: → SetupPinActivity (interrupted mid-setup) uid=" + myUid);
                Intent setupIntent = new Intent(this, SetupPinActivity.class);
                setupIntent.putExtra(SetupPinActivity.EXTRA_ACCOUNT_CREATED, true);
                startActivity(setupIntent);
                finish();
                return;
            }
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
