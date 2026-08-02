package com.duoshield.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.duoshield.app.auth.AuthTokenHelper;
import com.duoshield.app.crypto.SeedPhraseHelper;
import com.duoshield.app.ui.RequestAccessActivity;
import com.duoshield.app.ui.SeedPhraseDisplayActivity;
import com.duoshield.app.util.SecurePrefs;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;

import org.signal.libsignal.protocol.IdentityKeyPair;

/**
 * Step 1 of new-account onboarding — the user picks a display name.
 *
 * On Continue: generates a BIP-39 mnemonic, derives the userId, signs in
 * via the push server's /mintToken endpoint, then hands off to
 * {@link SeedPhraseDisplayActivity} for the remaining key-setup steps.
 */
public class DisplayNameActivity extends AppCompatActivity {

    private static final String TAG = "DisplayNameActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_display_name);

        EditText etName  = findViewById(R.id.etDisplayName);
        MaterialButton    btnCont = findViewById(R.id.btnContinue);
        TextView          tvError = findViewById(R.id.tvError);

        // Enable button only when there is non-blank input.
        btnCont.setEnabled(false);
        etName.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int i, int c, int a) {}
            public void onTextChanged(CharSequence s, int i, int b, int c) {
                btnCont.setEnabled(s.toString().trim().length() > 0);
            }
            public void afterTextChanged(Editable s) {}
        });

        etName.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE && btnCont.isEnabled()) {
                btnCont.performClick();
                return true;
            }
            return false;
        });

        btnCont.setOnClickListener(v -> {
            String name = etName.getText() != null
                    ? etName.getText().toString().trim() : "";
            if (name.isEmpty()) {
                etName.setError("Please enter a display name");
                return;
            }
            etName.setError(null);
            hideKeyboard(etName);
            proceed(name, btnCont, tvError);
        });
    }

    // ── Account creation ──────────────────────────────────────────────────────

    private void proceed(String displayName, MaterialButton btnCont, TextView tvError) {
        btnCont.setEnabled(false);
        btnCont.setText("Creating account…");
        tvError.setVisibility(View.GONE);

        // Approved waitlist request id from RequestAccessActivity — required by
        // the server for brand-new accounts. Threaded here via
        // RecoveryPhraseWalkthroughActivity.
        String waitlistRequestId = getIntent().getStringExtra(RequestAccessActivity.EXTRA_WAITLIST_REQUEST_ID);

        // Eagerly initialise SecurePrefs for diagnostic logging.
        // Never blocks account creation — even the plaintext fallback is MODE_PRIVATE
        // (same protection as WhatsApp/Telegram on devices without hardware TEE).
        SecurePrefs.get(this);
        Log.i(TAG, "proceed: securePrefsAvailable=" + SecurePrefs.isAvailable());

        new Thread(() -> {
            try {
                Log.i(TAG, "[1/3] Generating mnemonic + identity keys…");

                String          mnemonic        = SeedPhraseHelper.generateMnemonic();
                byte[]          seed            = SeedPhraseHelper.mnemonicToSeed(mnemonic);
                IdentityKeyPair identityKeyPair = SeedPhraseHelper.deriveIdentityKeyPair(seed);
                String          userId          = SeedPhraseHelper.deriveUserId(seed);

                Log.i(TAG, "[1/3] Derivation complete");

                // Sign out any stale Firebase session. Also clear explicit_signout so
                // BaseActivity.onStart() in ConversationListActivity doesn't redirect back.
                SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
                prefs.edit()
                     .remove("explicit_signout")
                     .remove("signed_out_reason_inactivity")
                     .apply();
                FirebaseAuth.getInstance().signOut();

                Log.i(TAG, "[2/3] Fetching custom token from push server…");
                runOnUiThread(() -> btnCont.setText("Contacting server…"));

                // result[0] = String uid on success, null on failure
                // result[1] = String error message on failure, null on success
                final String[]  result = {null, null};
                final Object    lock   = new Object();

                // signInWithSeed spawns its own auth-token thread and delivers the
                // callback on the main thread — so we wait on the lock here.
                AuthTokenHelper.signInWithSeed(
                        userId,
                        identityKeyPair.getPublicKey().serialize(),
                        waitlistRequestId,
                        new AuthTokenHelper.Callback() {
                            @Override public void onSuccess(String firebaseUid) {
                                Log.i(TAG, "[2/3] Firebase sign-in complete");
                                synchronized (lock) {
                                    result[0] = firebaseUid;
                                    lock.notifyAll();
                                }
                            }
                            @Override public void onFailure(Exception e) {
                                Log.e(TAG, "[2/3] Firebase sign-in FAILED", e);
                                synchronized (lock) {
                                    result[1] = e.getMessage() != null ? e.getMessage()
                                            : "Sign-in failed. Please try again.";
                                    lock.notifyAll();
                                }
                            }
                        });

                synchronized (lock) {
                    if (result[0] == null && result[1] == null) {
                        lock.wait(70_000); // 30s connect + 30s read + 10s buffer
                    }
                }

                if (result[0] == null && result[1] == null) {
                    throw new Exception(
                            "Timed out waiting for server response. " +
                            "The server may be waking up — please wait 30 seconds and try again.");
                }
                if (result[1] != null) {
                    throw new Exception(result[1]);
                }

                String firebaseUid = result[0];

                // Persist uid + display name before navigating.
                prefs.edit()
                     .putString("my_uid", firebaseUid)
                     .putString("my_display_name", displayName)
                     .apply();

                Log.i(TAG, "[3/3] Navigating to SeedPhraseDisplayActivity");

                Intent intent = new Intent(this, SeedPhraseDisplayActivity.class);
                intent.putExtra("mnemonic",       mnemonic);
                intent.putExtra("display_name",   displayName);
                intent.putExtra("identity_key",   identityKeyPair.serialize());
                intent.putExtra("user_id",        userId);
                runOnUiThread(() -> {
                    startActivity(intent);
                    finish();
                });

            } catch (Exception e) {
                Log.e(TAG, "Account creation failed", e);
                String msg = e.getMessage() != null ? e.getMessage()
                        : "Something went wrong. Please try again.";
                runOnUiThread(() -> {
                    tvError.setText(msg);
                    tvError.setVisibility(View.VISIBLE);
                    btnCont.setEnabled(true);
                    btnCont.setText("Continue");
                });
            }
        }, "account-create").start();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void hideKeyboard(View view) {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }
}
