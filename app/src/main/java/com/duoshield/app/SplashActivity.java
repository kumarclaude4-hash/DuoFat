package com.duoshield.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.duoshield.app.ui.MatrixRainView;
import com.duoshield.app.ui.SignalPulseView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * Splash screen — matches the design prompt exactly.
 *
 * Animation timeline
 * ──────────────────
 *   0.0 s – 1.5 s  (ENTRANCE)
 *       Logo fades from α=0→1 and scales from 0.80→1.00.
 *       Interpolator: FastOutSlowInInterpolator (ease-out deceleration).
 *       No bounce, no overshoot, no rotation.
 *
 *   1.5 s – ∞       (BREATHING / idle)
 *       Seamless transition into a continuous scale pulse: 1.00 ↔ 1.02.
 *       One full cycle = 2 400 ms.  REVERSE repeat keeps it smooth.
 *       Simulates a calm "AI thinking" state.
 *
 *   1.5 s + 600 ms  (NAVIGATE)
 *       App navigates to the next screen while the logo is still breathing.
 *
 * MUST extend AppCompatActivity (pre-auth screen — not BaseActivity).
 */
public class SplashActivity extends AppCompatActivity {

    private static final String TAG   = "SplashActivity";
    private static final String PREFS = "duoshield_prefs";

    /** Duration of the entrance animation (fade-in + scale-up). */
    private static final long ENTRANCE_MS = 1_500L;

    /**
     * How long to stay on the splash after the entrance finishes before
     * navigating.  The logo is breathing during this hold.
     */
    private static final long POST_ENTRANCE_HOLD_MS = 600L;

    /** One half-cycle of the breathing pulse (expand OR contract). */
    private static final long BREATHE_HALF_MS = 1_200L;

    // Keep references so we can cancel if the activity is destroyed early.
    private ObjectAnimator breatheX;
    private ObjectAnimator breatheY;
    private MatrixRainView matrixRainView;
    private SignalPulseView signalPulseView;

    // ── Startup latency overlap (main-thread-only state) ─────────────────────
    // Routing used to *begin* only after the 2100 ms splash timeline finished, so the
    // FirebaseAuth resolution and the blocking resume/drain I/O were pure dead time stacked
    // on top of the animation. They are now kicked off in onCreate so they run *during* the
    // animation; the transition fires as soon as BOTH (a) the visual hold has elapsed and
    // (b) routing has resolved. On a slow in-order A53 (Poco C51) this removes the auth+I/O
    // tail from every single launch without changing the routing decision or the visuals.
    private Intent pendingDestination;   // set once the auth listener has decided the route
    private boolean minHoldElapsed;      // set when the splash timeline reaches its hold point
    private boolean navigated;           // guards against a double startActivity()
    private FirebaseAuth.AuthStateListener authListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        matrixRainView  = findViewById(R.id.matrixRainView);
        signalPulseView = findViewById(R.id.signalPulseView);

        // Resolve where to go NOW, in parallel with the entrance animation, instead of
        // waiting for the animation to end. The actual screen transition is still gated on
        // the visual hold (see tryNavigate), so the timeline is unchanged — only the dead
        // wait for auth + disk I/O is removed.
        beginRoutingResolution();

        View logo = findViewById(R.id.ivSplashLogo);

        // ── ENTRANCE: fade-in + scale-up over 1 500 ms ───────────────────────
        // Initial state is already set in the layout (alpha=0, scaleX/Y=0.8).
        logo.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(ENTRANCE_MS)
                .setInterpolator(new FastOutSlowInInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (isFinishing() || isDestroyed()) return;
                        startBreathing(logo);
                        scheduleNavigation();
                    }
                })
                .start();
    }

    // ── BREATHING: infinite 1.00 ↔ 1.02 pulse ────────────────────────────────

    private void startBreathing(View logo) {
        breatheX = ObjectAnimator.ofFloat(logo, "scaleX", 1f, 1.02f);
        breatheY = ObjectAnimator.ofFloat(logo, "scaleY", 1f, 1.02f);

        for (ObjectAnimator anim : new ObjectAnimator[]{breatheX, breatheY}) {
            anim.setDuration(BREATHE_HALF_MS);
            anim.setRepeatCount(ObjectAnimator.INFINITE);
            anim.setRepeatMode(ObjectAnimator.REVERSE);
            anim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            anim.start();
        }
    }

    // ── NAVIGATION ────────────────────────────────────────────────────────────

    private void scheduleNavigation() {
        // The visual hold: mark the timeline complete, then attempt the transition. If routing
        // already resolved during the animation (the common case), this navigates immediately;
        // otherwise tryNavigate() is a no-op here and the auth listener fires it the moment the
        // route resolves.
        mainHandler.postDelayed(() -> {
            minHoldElapsed = true;
            tryNavigate();
        }, POST_ENTRANCE_HOLD_MS);
    }

    /**
     * Kicks off everything needed to decide the destination, in parallel with the entrance
     * animation. Called from {@link #onCreate}. Nothing here touches the UI or transitions —
     * it only computes {@link #pendingDestination}; {@link #tryNavigate()} performs the actual
     * screen change once the visual hold has also elapsed.
     */
    private void beginRoutingResolution() {
        // S06-M5 / S06-H3: resume any interrupted teardown and drain any pending
        // account-lock intent so routing decisions are made against a resolved state. Both do
        // blocking I/O — per their javadocs they must not run on the main thread — hence the
        // background thread. Starting it here (rather than after the animation) gives it the
        // full ~2 s of the splash timeline to finish before the routing read below needs it.
        new Thread(() -> {
            try {
                com.duoshield.app.security.DuressManager.resumeInterruptedResetIfNeeded(
                        SplashActivity.this);
                com.duoshield.app.security.DuressManager.drainPendingLockIntent(
                        SplashActivity.this);
            } catch (Exception e) {
                Log.w(TAG, "Startup resume/drain failed (non-fatal): " + e.getMessage());
            }
        }, "splash-resume-drain").start();

        // addAuthStateListener fires immediately if auth state is already
        // known, preventing a false-logout on cold start (see splash-auth-fix
        // memory entry).
        authListener = new FirebaseAuth.AuthStateListener() {
            @Override
            public void onAuthStateChanged(@NonNull FirebaseAuth auth) {
                auth.removeAuthStateListener(this);
                authListener = null;
                if (isFinishing() || isDestroyed()) return;

                FirebaseUser      user          = auth.getCurrentUser();
                SharedPreferences prefs         = getSharedPreferences(PREFS, MODE_PRIVATE);
                String            myUid         = prefs.getString("my_uid", null);
                // S06-M5: isResetPending() also checks PendingLockStore's wipe-surviving
                // marker, not just this legacy plaintext flag — see DuressManager's
                // javadoc on why the legacy-only check could never see an interruption
                // that happened after step 4 of the wipe destroyed this very prefs file.
                boolean           wipeInProgress = com.duoshield.app.security.DuressManager
                        .isResetPending(SplashActivity.this);
                boolean           explicitSignout = prefs.getBoolean(BaseActivity.KEY_EXPLICIT_SIGNOUT, false);

                // Log the routing decision so field issues are diagnosable via adb
                // logcat — but never with a raw uid (S06-M3 client / S06-L4). A cleartext
                // "firebaseUser=<uid>" line in a bug report or logcat dump is a direct
                // statement of account identity, exactly what LogRedact exists to avoid.
                Log.i(TAG, "navigate: firebaseUser=" + com.duoshield.app.util.LogRedact.uid(
                                user != null ? user.getUid() : null)
                        + "  hasMyUid=" + (myUid != null)
                        + "  wipeInProgress=" + wipeInProgress
                        + "  explicitSignout=" + explicitSignout);

                Intent next;
                if (!wipeInProgress && user != null && myUid != null && !explicitSignout) {
                    Log.i(TAG, "navigate → MainActivity (authenticated)");
                    next = new Intent(SplashActivity.this, MainActivity.class);
                } else {
                    if (wipeInProgress)   Log.i(TAG, "navigate → SignInActivity (duress wipe in progress)");
                    else if (user == null) Log.i(TAG, "navigate → SignInActivity (no Firebase user)");
                    else if (myUid == null) Log.i(TAG, "navigate → SignInActivity (my_uid not persisted)");
                    else                  Log.i(TAG, "navigate → SignInActivity (explicit sign-out flag set)");
                    next = new Intent(SplashActivity.this, SignInActivity.class);
                }
                next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                pendingDestination = next;
                tryNavigate();
            }
        };
        FirebaseAuth.getInstance().addAuthStateListener(authListener);
    }

    /**
     * Performs the screen transition exactly once, and only when both preconditions hold: the
     * destination has been resolved AND the splash's visual hold has elapsed. Whichever of the
     * two finishes last triggers the actual navigation, so neither the animation nor the
     * auth/I/O work is ever wasted waiting on the other.
     */
    private void tryNavigate() {
        if (navigated || pendingDestination == null || !minHoldElapsed) return;
        if (isFinishing() || isDestroyed()) return;
        navigated = true;
        startActivity(pendingDestination);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (breatheX != null) breatheX.cancel();
        if (breatheY != null) breatheY.cancel();
        mainHandler.removeCallbacksAndMessages(null);
        if (authListener != null) {
            FirebaseAuth.getInstance().removeAuthStateListener(authListener);
            authListener = null;
        }
    }
}
