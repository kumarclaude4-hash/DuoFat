package com.duoshield.app.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.duoshield.app.R;
import com.duoshield.app.auth.WaitlistHelper;
import com.duoshield.app.util.ButtonPressAnimator;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

/**
 * Gate in front of new-account creation. DuoShield does not accept public
 * self-serve signups — a fresh install must request access here and wait for
 * manual approval (a Firebase console / admin-script action) before it can
 * proceed into {@link RecoveryPhraseWalkthroughActivity}.
 *
 * <p>Restoring an existing account never passes through this screen —
 * {@link com.duoshield.app.SignInActivity} routes straight to
 * {@link RestoreFromSeedActivity} for that.
 */
public class RequestAccessActivity extends AppCompatActivity {

    public static final String EXTRA_WAITLIST_REQUEST_ID = "waitlist_request_id";

    private static final String PREFS_NAME = "duoshield_prefs";
    private static final String KEY_REQUEST_ID = "waitlist_request_id";

    private TextView tvSubtitle, tvRequestId, tvError;
    private MaterialButton btnPrimary, btnSecondary;
    private LinearProgressIndicator progress;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_request_access);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        tvSubtitle   = findViewById(R.id.tvSubtitle);
        tvRequestId  = findViewById(R.id.tvRequestId);
        tvError      = findViewById(R.id.tvError);
        btnPrimary   = findViewById(R.id.btnPrimary);
        btnSecondary = findViewById(R.id.btnSecondary);
        progress     = findViewById(R.id.progressAccess);
        ButtonPressAnimator.attach(btnPrimary);

        refreshState();
    }

    /** Renders the screen based on whether a request id is already cached locally. */
    private void refreshState() {
        String cached = prefs.getString(KEY_REQUEST_ID, null);
        tvError.setVisibility(View.GONE);
        if (cached == null) {
            tvSubtitle.setText("New accounts require approval. Request access below — "
                    + "you'll be able to check your status any time.");
            tvRequestId.setVisibility(View.GONE);
            btnSecondary.setVisibility(View.GONE);
            btnPrimary.setText("Request access");
            btnPrimary.setOnClickListener(v -> submitRequest());
        } else {
            tvSubtitle.setText("Your access request is on file. Check back here any time — "
                    + "you'll be able to continue as soon as it's approved.");
            tvRequestId.setText("Request ID: " + cached);
            tvRequestId.setVisibility(View.VISIBLE);
            btnSecondary.setVisibility(View.VISIBLE);
            btnSecondary.setOnClickListener(v -> {
                prefs.edit().remove(KEY_REQUEST_ID).apply();
                refreshState();
            });
            btnPrimary.setText("Check status");
            btnPrimary.setOnClickListener(v -> checkStatus(cached));
        }
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        btnPrimary.setEnabled(!busy);
        btnSecondary.setEnabled(!busy);
    }

    private void submitRequest() {
        setBusy(true);
        WaitlistHelper.requestAccess(new WaitlistHelper.RequestCallback() {
            @Override public void onSuccess(String requestId) {
                setBusy(false);
                prefs.edit().putString(KEY_REQUEST_ID, requestId).apply();
                refreshState();
            }
            @Override public void onFailure(Exception e) {
                setBusy(false);
                showError(e);
            }
        });
    }

    private void checkStatus(String requestId) {
        setBusy(true);
        WaitlistHelper.checkStatus(requestId, new WaitlistHelper.StatusCallback() {
            @Override public void onSuccess(String status) {
                setBusy(false);
                switch (status) {
                    case "approved":
                        Intent intent = new Intent(RequestAccessActivity.this,
                                RecoveryPhraseWalkthroughActivity.class);
                        intent.putExtra(EXTRA_WAITLIST_REQUEST_ID, requestId);
                        startActivity(intent);
                        finish();
                        break;
                    case "used":
                        // Already consumed by a previous account-creation run
                        // (e.g. the app was killed right after success). Nothing
                        // more to do with this token — clear it.
                        prefs.edit().remove(KEY_REQUEST_ID).apply();
                        tvError.setText("This request has already been used. "
                                + "Request access again if you need a new account.");
                        tvError.setVisibility(View.VISIBLE);
                        refreshState();
                        break;
                    case "not_found":
                        prefs.edit().remove(KEY_REQUEST_ID).apply();
                        tvError.setText("This request could not be found. Please request access again.");
                        tvError.setVisibility(View.VISIBLE);
                        refreshState();
                        break;
                    default: // "pending"
                        tvError.setText("Still waiting on approval — check back later.");
                        tvError.setVisibility(View.VISIBLE);
                        break;
                }
            }
            @Override public void onFailure(Exception e) {
                setBusy(false);
                showError(e);
            }
        });
    }

    private void showError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : "Something went wrong. Please try again.";
        tvError.setText(msg);
        tvError.setVisibility(View.VISIBLE);
    }
}
