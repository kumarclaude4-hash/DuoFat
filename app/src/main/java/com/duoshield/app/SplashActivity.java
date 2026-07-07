package com.duoshield.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * Shield Assembly splash — plays every time the app opens (~2.5 s).
 *
 * Animation sequence:
 *   Phase 1 (0 – 600 ms)   : Left & right shield halves slide in from edges.
 *   Phase 2 (600 – 1050 ms): Shockwave glow expands and fades on impact.
 *   Phase 3 (1050 – 1450 ms): Lock icon fades in on the shield face.
 *   Phase 4 (1450 – 1750 ms): Shield pulses once (scale up → settle).
 *   Phase 5 (1750 – 2150 ms): Wordmark + tagline slide up, badge fades in.
 *   Hold 600 ms, then navigate.
 *
 * MUST extend AppCompatActivity (pre-auth screen — not BaseActivity).
 */
public class SplashActivity extends AppCompatActivity {

    private static final String PREFS = "duoshield_prefs";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        View         leftHalf    = findViewById(R.id.shield_left);
        View         rightHalf   = findViewById(R.id.shield_right);
        View         lockIcon    = findViewById(R.id.lock_icon);
        View         glowView    = findViewById(R.id.glow_view);
        TextView     tvWordmark  = findViewById(R.id.tvWordmark);
        TextView     tvTagline   = findViewById(R.id.tvTagline);
        LinearLayout layoutBadge = findViewById(R.id.layoutBadge);
        TextView     tvVersion   = findViewById(R.id.tvVersion);

        // Version string
        try {
            String ver = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            if (tvVersion != null) tvVersion.setText("v" + ver);
        } catch (Exception ignored) {}

        // ── Initial states ────────────────────────────────────────────────────
        float offscreen = getResources().getDisplayMetrics().widthPixels / 2f + 150f;
        leftHalf .setTranslationX(-offscreen);
        rightHalf.setTranslationX( offscreen);
        lockIcon .setAlpha(0f);
        glowView .setAlpha(0f);

        // ── Phase 1: halves slide in (0 – 600 ms) ────────────────────────────
        ObjectAnimator slideLeft  = ObjectAnimator.ofFloat(leftHalf,  "translationX", -offscreen, 0f);
        ObjectAnimator slideRight = ObjectAnimator.ofFloat(rightHalf, "translationX",  offscreen, 0f);
        slideLeft .setDuration(600);
        slideRight.setDuration(600);
        slideLeft .setInterpolator(new DecelerateInterpolator(2.2f));
        slideRight.setInterpolator(new DecelerateInterpolator(2.2f));

        AnimatorSet assemble = new AnimatorSet();
        assemble.playTogether(slideLeft, slideRight);

        // ── Phase 2: shockwave glow (600 – 1050 ms) ──────────────────────────
        ObjectAnimator glowIn    = ObjectAnimator.ofFloat(glowView, "alpha",  0f,  1f);
        ObjectAnimator glowOut   = ObjectAnimator.ofFloat(glowView, "alpha",  1f,  0f);
        ObjectAnimator glowSx    = ObjectAnimator.ofFloat(glowView, "scaleX", 0.4f, 2.8f);
        ObjectAnimator glowSy    = ObjectAnimator.ofFloat(glowView, "scaleY", 0.4f, 2.8f);

        glowIn .setDuration(120);
        glowOut.setDuration(330);
        glowSx .setDuration(450);
        glowSy .setDuration(450);
        glowSx .setInterpolator(new DecelerateInterpolator(1.5f));
        glowSy .setInterpolator(new DecelerateInterpolator(1.5f));

        AnimatorSet shockIn  = new AnimatorSet(); shockIn .playTogether(glowIn,  glowSx, glowSy);
        AnimatorSet shockOut = new AnimatorSet(); shockOut.playTogether(glowOut);

        AnimatorSet shockwave = new AnimatorSet();
        shockwave.playSequentially(shockIn, shockOut);

        // ── Phase 3: lock icon engraves in (1050 – 1450 ms) ─────────────────
        ObjectAnimator lockFade   = ObjectAnimator.ofFloat(lockIcon, "alpha",  0f,   1f);
        ObjectAnimator lockScaleX = ObjectAnimator.ofFloat(lockIcon, "scaleX", 0.4f, 1f);
        ObjectAnimator lockScaleY = ObjectAnimator.ofFloat(lockIcon, "scaleY", 0.4f, 1f);
        lockFade  .setDuration(400);
        lockScaleX.setDuration(400);
        lockScaleY.setDuration(400);
        lockFade  .setInterpolator(new AccelerateDecelerateInterpolator());
        lockScaleX.setInterpolator(new DecelerateInterpolator(2f));
        lockScaleY.setInterpolator(new DecelerateInterpolator(2f));

        AnimatorSet engrave = new AnimatorSet();
        engrave.playTogether(lockFade, lockScaleX, lockScaleY);

        // ── Phase 4: shield pulse (1450 – 1750 ms) ───────────────────────────
        ObjectAnimator pulseUpLX   = ObjectAnimator.ofFloat(leftHalf,  "scaleX", 1f, 1.12f);
        ObjectAnimator pulseUpLY   = ObjectAnimator.ofFloat(leftHalf,  "scaleY", 1f, 1.12f);
        ObjectAnimator pulseUpRX   = ObjectAnimator.ofFloat(rightHalf, "scaleX", 1f, 1.12f);
        ObjectAnimator pulseUpRY   = ObjectAnimator.ofFloat(rightHalf, "scaleY", 1f, 1.12f);
        ObjectAnimator pulseUpIcon = ObjectAnimator.ofFloat(lockIcon,  "scaleX", 1f, 1.12f);
        ObjectAnimator pulseUpIconY= ObjectAnimator.ofFloat(lockIcon,  "scaleY", 1f, 1.12f);
        pulseUpLX.setDuration(150); pulseUpLY.setDuration(150);
        pulseUpRX.setDuration(150); pulseUpRY.setDuration(150);
        pulseUpIcon.setDuration(150); pulseUpIconY.setDuration(150);

        ObjectAnimator pulseDnLX   = ObjectAnimator.ofFloat(leftHalf,  "scaleX", 1.12f, 1f);
        ObjectAnimator pulseDnLY   = ObjectAnimator.ofFloat(leftHalf,  "scaleY", 1.12f, 1f);
        ObjectAnimator pulseDnRX   = ObjectAnimator.ofFloat(rightHalf, "scaleX", 1.12f, 1f);
        ObjectAnimator pulseDnRY   = ObjectAnimator.ofFloat(rightHalf, "scaleY", 1.12f, 1f);
        ObjectAnimator pulseDnIcon = ObjectAnimator.ofFloat(lockIcon,  "scaleX", 1.12f, 1f);
        ObjectAnimator pulseDnIconY= ObjectAnimator.ofFloat(lockIcon,  "scaleY", 1.12f, 1f);
        pulseDnLX.setDuration(150); pulseDnLY.setDuration(150);
        pulseDnRX.setDuration(150); pulseDnRY.setDuration(150);
        pulseDnIcon.setDuration(150); pulseDnIconY.setDuration(150);

        AnimatorSet pulseUp   = new AnimatorSet();
        pulseUp.playTogether(pulseUpLX, pulseUpLY, pulseUpRX, pulseUpRY, pulseUpIcon, pulseUpIconY);
        AnimatorSet pulseDown = new AnimatorSet();
        pulseDown.playTogether(pulseDnLX, pulseDnLY, pulseDnRX, pulseDnRY, pulseDnIcon, pulseDnIconY);
        AnimatorSet pulse = new AnimatorSet();
        pulse.playSequentially(pulseUp, pulseDown);

        // ── Phase 5: wordmark + tagline + badge (1750 – 2150 ms) ─────────────
        ObjectAnimator wordAlpha  = ObjectAnimator.ofFloat(tvWordmark,  "alpha",        0f, 1f);
        ObjectAnimator wordTransY = ObjectAnimator.ofFloat(tvWordmark,  "translationY", 20f, 0f);
        ObjectAnimator tagAlpha   = ObjectAnimator.ofFloat(tvTagline,   "alpha",        0f, 1f);
        ObjectAnimator tagTransY  = ObjectAnimator.ofFloat(tvTagline,   "translationY", 16f, 0f);
        ObjectAnimator badgeAlpha = ObjectAnimator.ofFloat(layoutBadge, "alpha",        0f, 1f);
        ObjectAnimator verAlpha   = ObjectAnimator.ofFloat(tvVersion,   "alpha",        0f, 1f);

        wordAlpha .setDuration(280);
        wordTransY.setDuration(280);
        wordTransY.setInterpolator(new DecelerateInterpolator());
        tagAlpha  .setDuration(280); tagAlpha .setStartDelay(80);
        tagTransY .setDuration(280); tagTransY.setStartDelay(80);
        tagTransY .setInterpolator(new DecelerateInterpolator());
        badgeAlpha.setDuration(300); badgeAlpha.setStartDelay(200);
        verAlpha  .setDuration(300); verAlpha  .setStartDelay(200);

        AnimatorSet text = new AnimatorSet();
        text.playTogether(wordAlpha, wordTransY, tagAlpha, tagTransY, badgeAlpha, verAlpha);

        // ── Chain all phases sequentially ─────────────────────────────────────
        AnimatorSet fullAnimation = new AnimatorSet();
        fullAnimation.playSequentially(assemble, shockwave, engrave, pulse, text);

        fullAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                new Handler(Looper.getMainLooper()).postDelayed(
                        SplashActivity.this::navigate, 550);
            }
        });

        fullAnimation.start();
    }

    private void navigate() {
        // addAuthStateListener fires immediately if the auth state is already
        // known, so we never route to SignInActivity just because getCurrentUser()
        // returned null a millisecond too early on a cold start.
        FirebaseAuth.getInstance().addAuthStateListener(new FirebaseAuth.AuthStateListener() {
            @Override
            public void onAuthStateChanged(@NonNull FirebaseAuth auth) {
                auth.removeAuthStateListener(this);
                if (isFinishing() || isDestroyed()) return;

                FirebaseUser user = auth.getCurrentUser();
                SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                String myUid = prefs.getString("my_uid", null);

                Intent next;
                // F30 fix: If a duress wipe is in progress the Firebase session may
                // still appear valid while the background thread hasn't yet called
                // signOut(). Route to SignInActivity to show the welcome screen and
                // block the auto-route there until the wipe completes.
                boolean wipeInProgress = prefs.getBoolean("duress_wipe_in_progress", false);
                if (!wipeInProgress && user != null && myUid != null) {
                    next = new Intent(SplashActivity.this, MainActivity.class);
                } else {
                    next = new Intent(SplashActivity.this, SignInActivity.class);
                }
                next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(next);
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                finish();
            }
        });
    }
}
