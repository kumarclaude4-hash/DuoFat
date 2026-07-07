package com.duoshield.app;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.duoshield.app.auth.AuthTokenHelper;
import com.duoshield.app.crypto.SeedPhraseHelper;
import com.duoshield.app.ui.SeedPhraseDisplayActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.firebase.auth.FirebaseAuth;

import org.signal.libsignal.protocol.IdentityKeyPair;

/**
 * Step 2 of onboarding — the user picks a display name.
 *
 * <p>On "Continue":
 * <ol>
 *   <li>Validates that the name is non-empty and ≤30 chars.</li>
 *   <li>Generates a 12-word BIP39 mnemonic on a background thread.</li>
 *   <li>Derives the deterministic userId and identity key pair from the mnemonic.</li>
 *   <li>Calls the push server {@code /mintToken} to get a Firebase custom token
 *       whose UID equals {@code userId} — the same UID on every future sign-in.</li>
 *   <li>Signs into Firebase with the custom token.</li>
 *   <li>Navigates to {@link SeedPhraseDisplayActivity} passing the mnemonic
 *       and the chosen display name.</li>
 * </ol>
 *
 * <p>Using a custom token instead of {@code signInAnonymously()} ensures the
 * Firebase UID never changes across sign-outs, eliminating the UID-mismatch
 * bug that previously wiped Firestore data visibility after re-authentication.
 */
public class DisplayNameActivity extends AppCompatActivity {

    private EditText                  etName;
    private TextView                  tvError;
    private TextView                  tvStep;
    private MaterialButton            btnContinue;
    private LinearProgressIndicator   progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_display_name);

        etName      = findViewById(R.id.etDisplayName);
        tvError     = findViewById(R.id.tvError);
        tvStep      = findViewById(R.id.tvStep);
        btnContinue = findViewById(R.id.btnContinue);
        progress    = findViewById(R.id.progressSetup);

        if (tvStep != null) tvStep.setVisibility(View.GONE);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        etName.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) { proceed(); return true; }
            return false;
        });

        btnContinue.setOnClickListener(v -> proceed());
    }

    // ── Main entry point ──────────────────────────────────────────────────────

    private void proceed() {
        String name = etName.getText() == null ? "" : etName.getText().toString().trim();

        if (TextUtils.isEmpty(name)) { showError("Please enter a display name."); return; }
        if (name.length() > 30)      { showError("Display name must be 30 characters or fewer."); return; }

        hideError();
        setLoading(true);
        setStep("Generating identity…");

        // Sign out any stale session before creating a fresh account.
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() != null) auth.signOut();

        new Thread(() -> {
            try {
                // Step 1 — generate mnemonic
                String mnemonic = SeedPhraseHelper.generateMnemonic();

                // Step 2 — derive userId + identity key pair from the mnemonic.
                //           userId is deterministic: same seed → same userId → same Firebase UID.
                runOnUiThread(() -> setStep("Deriving identity key…"));
                byte[]          seed            = SeedPhraseHelper.mnemonicToSeed(mnemonic);
                IdentityKeyPair identityKeyPair = SeedPhraseHelper.deriveIdentityKeyPair(seed);
                String          userId          = SeedPhraseHelper.deriveUserId(seed);
                byte[]          pubKeyBytes     = identityKeyPair.getPublicKey().serialize();

                // Step 3 — get a Firebase custom token from the push server and sign in.
                //           The resulting UID equals userId — permanent across all future sign-ins.
                runOnUiThread(() -> setStep("Authenticating…"));

                // AuthTokenHelper.signInWithSeed() spawns its own thread; we need
                // the result synchronously here, so we block via a lock.
                final Object    lock      = new Object();
                final String[]  uidHolder = {null};
                final Exception[] errHolder = {null};

                AuthTokenHelper.signInWithSeed(userId, pubKeyBytes, new AuthTokenHelper.Callback() {
                    @Override public void onSuccess(String firebaseUid) {
                        synchronized (lock) { uidHolder[0] = firebaseUid; lock.notifyAll(); }
                    }
                    @Override public void onFailure(Exception e) {
                        synchronized (lock) { errHolder[0] = e; lock.notifyAll(); }
                    }
                });

                synchronized (lock) {
                    if (uidHolder[0] == null && errHolder[0] == null) lock.wait(60_000);
                }
                if (errHolder[0] != null) throw errHolder[0];
                if (uidHolder[0] == null) throw new Exception("Authentication timed out. Check your internet connection.");

                final String uid = uidHolder[0];

                // Step 4 — persist the UID and navigate.
                getSharedPreferences("duoshield_prefs", MODE_PRIVATE)
                        .edit()
                        .putString("my_uid",     uid)
                        .putString("my_user_id", userId)
                        .apply();

                runOnUiThread(() -> {
                    setLoading(false);
                    Intent intent = new Intent(this, SeedPhraseDisplayActivity.class);
                    intent.putExtra(SeedPhraseDisplayActivity.EXTRA_MNEMONIC,      mnemonic);
                    intent.putExtra(SeedPhraseDisplayActivity.EXTRA_DISPLAY_NAME,  name);
                    startActivity(intent);
                    finish();
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    showError(friendlyError(e));
                });
            }
        }, "account-create").start();
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void showError(String msg) {
        tvError.setText(msg);
        tvError.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        tvError.setVisibility(View.GONE);
    }

    private void setStep(String label) {
        if (tvStep != null) {
            tvStep.setText(label);
            tvStep.setVisibility(View.VISIBLE);
        }
    }

    private void setLoading(boolean on) {
        progress.setVisibility(on ? View.VISIBLE : View.GONE);
        btnContinue.setEnabled(!on);
        etName.setEnabled(!on);
        if (tvStep != null && !on) tvStep.setVisibility(View.GONE);
    }

    private static String friendlyError(Exception e) {
        if (e == null) return "An unexpected error occurred. Please try again.";
        String s = e.getMessage() != null ? e.getMessage() : e.toString();
        if (s.contains("PUSH_SERVER_URL is not configured"))
            return "The auth server URL is not configured. Contact support.";
        if (s.contains("Key mismatch") || s.contains("403"))
            return "Account ID mismatch. Please try again.";
        if (s.contains("429") || s.contains("Too many"))
            return "Too many attempts. Please wait a moment and try again.";
        if (s.contains("timeout") || s.contains("timed out")
                || s.contains("connect") || s.contains("network")
                || s.contains("IOException") || s.contains("UnknownHost"))
            return "Could not reach the auth server. Check your internet connection and try again.";
        return "Account setup failed. Please try again.";
    }
}
