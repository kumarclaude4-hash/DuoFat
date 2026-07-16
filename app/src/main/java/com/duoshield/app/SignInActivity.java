package com.duoshield.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.duoshield.app.crypto.signal.SignalKeyManager;
import com.duoshield.app.ui.AddContactActivity;
import com.duoshield.app.ui.RestoreFromSeedActivity;
import com.duoshield.app.util.AppLockManager;
import com.duoshield.app.util.FcmTokenHelper;
import com.duoshield.app.util.SecurePrefs;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.duoshield.app.util.ButtonPressAnimator;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

/**
 * Session-style welcome screen — the app launcher.
 *
 * <p>New users see animated chat bubbles then tap "Create account" →
 * {@link DisplayNameActivity} → identity setup.</p>
 *
 * <p>Returning users (Firebase session + Signal keys present) are routed
 * silently via {@link #route(String)} without showing the welcome UI.</p>
 */
public class SignInActivity extends AppCompatActivity {

    private static final String TAG = "SignInActivity";

    private FirebaseAuth      mAuth;
    private FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // BUG-F-09 fix: check if user is already logged in BEFORE inflating layout.
        // This prevents the brief flash of the welcome screen for returning users.
        mAuth = FirebaseAuth.getInstance();
        FirebaseUser user = mAuth.getCurrentUser();

        // F30 fix: If a duress wipe is in progress, treat the session as signed-out
        // regardless of what FirebaseAuth reports.
        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        boolean wipeInProgress  = prefs.getBoolean("duress_wipe_in_progress", false);
        boolean signalInit      = SignalKeyManager.isInitialized(this);

        Log.i(TAG, "onCreate: firebaseUser=" + (user != null ? user.getUid() : "null")
                + "  signalInitialized=" + signalInit
                + "  securePrefsAvailable=" + SecurePrefs.isAvailable()
                + "  wipeInProgress=" + wipeInProgress);

        if (!wipeInProgress && user != null && signalInit) {
            Log.i(TAG, "onCreate: returning user — routing directly uid=" + user.getUid());
            route(user.getUid());
            return;
        }

        if (!wipeInProgress && user != null && !signalInit) {
            Log.w(TAG, "onCreate: Firebase user exists but Signal NOT initialized "
                    + "(SecurePrefs.isAvailable=" + SecurePrefs.isAvailable() + ") "
                    + "— showing sign-in screen for re-setup.");
        }

        // User not logged in — show welcome UI.
        setContentView(R.layout.activity_sign_in);
        db = FirebaseFirestore.getInstance();

        // Show a banner if the user was auto-signed-out due to inactivity (BUG-D-AS01).
        if (prefs.getBoolean("signed_out_reason_inactivity", false)) {
            prefs.edit().remove("signed_out_reason_inactivity").apply();
            android.widget.Toast.makeText(this,
                    "You were signed out due to inactivity. Sign in to continue.",
                    android.widget.Toast.LENGTH_LONG).show();
        }

        MaterialButton btnCreate  = findViewById(R.id.btnCreate);
        MaterialButton btnRestore = findViewById(R.id.btnRestore);

        ButtonPressAnimator.attach(btnCreate);
        ButtonPressAnimator.attach(btnRestore);

        btnCreate.setOnClickListener(v ->
                startActivity(new Intent(this, DisplayNameActivity.class)));

        btnRestore.setOnClickListener(v ->
                startActivity(new Intent(this, RestoreFromSeedActivity.class)));

        // Style Terms/Privacy links in the footer
        TextView tvTerms = findViewById(R.id.tvTerms);
        if (tvTerms != null) {
            String full = "By using this service, you agree to our\nTerms of Service and Privacy Policy";
            SpannableString ss = new SpannableString(full);
            int accentColor = 0xFF7B63FB;
            int tosStart = full.indexOf("Terms of Service");
            ss.setSpan(new ForegroundColorSpan(accentColor), tosStart, tosStart + 16, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int ppStart = full.indexOf("Privacy Policy");
            ss.setSpan(new ForegroundColorSpan(accentColor), ppStart, ppStart + 14, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tvTerms.setText(ss);
        }

        // Animate chat bubbles in after a short delay
        animateBubbles();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Auth state check already done in onCreate.
        // This is now just a safety check for edge cases.
        if (mAuth == null) mAuth = FirebaseAuth.getInstance();
        FirebaseUser user = mAuth.getCurrentUser();
        boolean wipeInProgress = getSharedPreferences("duoshield_prefs", MODE_PRIVATE)
                .getBoolean("duress_wipe_in_progress", false);
        if (!wipeInProgress && user != null && SignalKeyManager.isInitialized(this)) {
            Log.i(TAG, "onStart: returning user detected — routing  uid=" + user.getUid());
            route(user.getUid());
        }
    }

    // ── Bubble animation ──────────────────────────────────────────────────────

    private void animateBubbles() {
        View bubble1 = findViewById(R.id.bubble1);
        View bubble2 = findViewById(R.id.bubble2);

        Animation anim = AnimationUtils.loadAnimation(this, R.anim.bubble_fade_in);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            bubble1.setVisibility(View.VISIBLE);
            bubble1.startAnimation(anim);

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (isFinishing() || isDestroyed()) return;
                Animation anim2 = AnimationUtils.loadAnimation(this, R.anim.bubble_fade_in);
                bubble2.setVisibility(View.VISIBLE);
                bubble2.startAnimation(anim2);
            }, 500);

        }, 400);
    }

    // ── Returning-user routing ────────────────────────────────────────────────

    private void route(String uid) {
        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        prefs.edit().putString("my_uid", uid).apply();
        AppLockManager.onAppForegrounded(this);

        // BUG-F-12 fix: Register FCM token immediately. Even if the app is locked,
        // we must ensure the server has a valid token to deliver notifications.
        FcmTokenHelper.register(this);

        Log.i(TAG, "route: → ConversationListActivity  uid=" + uid);
        Intent intent = new Intent(this, ConversationListActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
