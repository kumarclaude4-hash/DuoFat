package com.duoshield.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.duoshield.app.BuildConfig;
import com.duoshield.app.ConversationListActivity;
import com.duoshield.app.R;
import com.duoshield.app.security.DuressManager;
import com.duoshield.app.security.PendingLockStore;
import com.duoshield.app.util.PinManager;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screen 2 of the forced post-unfreeze rotation (S06-M6, {@code
 * SESSION-06-DURESS.md §8}). Reached only from {@link ForcedPrimaryPinRotationActivity}
 * once the new primary PIN (P2) is set.
 *
 * <p>Forces a fresh secondary/duress code into slot B — never carries forward
 * whatever slot B held going into rotation, because a restore-from-seed can happen
 * on a fresh device where the real code was never stored locally, in which case
 * slot B holds nothing but a decoy at this point. Unlike {@link
 * ManageUnlockCodesActivity} (opt-in, gated behind server-side duress eligibility),
 * this screen is unconditional once {@code rotationRequired} is set: the account
 * only reaches this flow because slot B was already armed and actually used to
 * trigger a real wipe, so there is nothing to gate.
 *
 * <p>Reuses {@code ManageUnlockCodesActivity}'s confirm-current-PIN and
 * prefix-overlap-rejection pattern, but confirms against the PIN set moments ago on
 * screen 1 (P2) rather than an existing PIN carried across the Activity boundary —
 * no plaintext PIN is ever passed between screens.
 *
 * <p>On save, calls {@code POST /acknowledgeRotation} to clear the server-side flag.
 * If that call fails, both local codes stay set (nothing unsafe about retrying) and
 * the user is shown a retry panel rather than being sent back to either PIN screen —
 * see {@link #panelAckRetry}. No skip, no back-button escape.
 */
public class ForcedDuressRotationActivity extends AppCompatActivity {

    private LinearLayout panelEntry, panelExplain, panelAckRetry;
    private EditText etNewCode, etConfirmCode;
    private TextView tvError;

    private String pendingCode; // held between the entry panel and the explanation panel

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forced_duress_rotation);

        panelEntry    = findViewById(R.id.panelEntry);
        panelExplain  = findViewById(R.id.panelExplain);
        panelAckRetry = findViewById(R.id.panelAckRetry);
        etNewCode     = findViewById(R.id.etNewCode);
        etConfirmCode = findViewById(R.id.etConfirmCode);
        tvError       = findViewById(R.id.tvError);

        MaterialButton btnContinueEntry = findViewById(R.id.btnContinueEntry);
        MaterialButton btnConfirmExplain = findViewById(R.id.btnConfirmExplain);
        MaterialButton btnRetryAck = findViewById(R.id.btnRetryAck);

        btnContinueEntry.setOnClickListener(v -> onContinueEntry());
        btnConfirmExplain.setOnClickListener(v -> onConfirmExplain());
        btnRetryAck.setOnClickListener(v -> retryAcknowledge());

        // A relaunch mid-flow (app killed after slot B was armed but before the
        // server ack succeeded) lands straight back here via MainActivity.route().
        // Both local codes are already set at that point, so skip re-collecting
        // either PIN and go straight to retrying the one thing that didn't finish.
        //
        // Deliberately checks the dedicated rotation-scoped flag rather than
        // DuressManager.hasDuressPin() directly: slot B can already be armed on a
        // fresh arrival at this screen in the wipe scenario (duress logout
        // deliberately keeps the old code armed so a restore of the same account
        // stays gated), so "armed" alone can't distinguish "screen 2 hasn't run
        // yet" from "screen 2 already replaced it and is only waiting on the ack."
        if (PendingLockStore.isRotationDuressDone(this)) {
            showAckRetryPanel();
        }
    }

    private void onContinueEntry() {
        String code    = etNewCode.getText().toString().trim();
        String confirm = etConfirmCode.getText().toString().trim();

        if (code.length() < 4 || code.length() > 6) {
            Toast.makeText(this, "Code must be 4–6 digits.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!code.equals(confirm)) {
            Toast.makeText(this, "Codes don't match.", Toast.LENGTH_SHORT).show();
            return;
        }

        promptCurrentPinAndValidate(code);
    }

    /**
     * Re-authenticates against the primary PIN set on screen 1 (P2), then uses its
     * plaintext — held only for the duration of this method, never persisted or
     * logged — to reject any prefix overlap with {@code candidateCode}. Identical
     * reasoning to {@code ManageUnlockCodesActivity.promptCurrentPinAndValidate}.
     */
    private void promptCurrentPinAndValidate(String candidateCode) {
        EditText etCurrent = new EditText(this);
        etCurrent.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        etCurrent.setHint("Your new PIN");
        etCurrent.setMaxLines(1);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        container.setPadding(pad * 2, pad, pad * 2, 0);
        container.addView(etCurrent);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Confirm your new PIN to continue")
                .setView(container)
                .setCancelable(false) // mandatory screen — no dismiss-and-strand
                .setPositiveButton("Confirm", (d, w) -> {
                    String currentPin = etCurrent.getText().toString().trim();
                    bgExecutor.execute(() -> {
                        boolean authentic = PinManager.verifyPin(this, currentPin);
                        boolean overlaps = authentic && (
                                currentPin.equals(candidateCode)
                                        || currentPin.startsWith(candidateCode)
                                        || candidateCode.startsWith(currentPin));
                        runOnUiThread(() -> {
                            if (!authentic) {
                                Toast.makeText(this, "Incorrect PIN.", Toast.LENGTH_SHORT).show();
                            } else if (overlaps) {
                                Toast.makeText(this,
                                    "This code is already in use. Choose a different one.",
                                    Toast.LENGTH_LONG).show();
                            } else {
                                pendingCode = candidateCode;
                                showExplainPanel();
                            }
                        });
                    });
                })
                // No negative/cancel button — this screen is mandatory.
                .show();
    }

    private void showExplainPanel() {
        panelEntry.setVisibility(View.GONE);
        panelExplain.setVisibility(View.VISIBLE);
        panelAckRetry.setVisibility(View.GONE);
    }

    private void showAckRetryPanel() {
        panelEntry.setVisibility(View.GONE);
        panelExplain.setVisibility(View.GONE);
        panelAckRetry.setVisibility(View.VISIBLE);
    }

    private void onConfirmExplain() {
        if (pendingCode == null) return;
        String codeToSave = pendingCode;
        bgExecutor.execute(() -> {
            boolean saved = DuressManager.setDuressPin(this, codeToSave);
            runOnUiThread(() -> {
                if (!saved) {
                    Toast.makeText(this, "Could not save. Try again.", Toast.LENGTH_LONG).show();
                    panelExplain.setVisibility(View.GONE);
                    panelEntry.setVisibility(View.VISIBLE);
                    return;
                }
                pendingCode = null;
                // Slot B is armed on this device now — mark screen 2 as genuinely
                // done (see the onCreate resume check above for why this can't be
                // inferred from DuressManager.hasDuressPin() alone) and retry the
                // server ack from here rather than re-collecting either PIN if it
                // fails.
                PendingLockStore.setRotationDuressDone(this, true);
                showAckRetryPanel();
                acknowledgeRotation();
            });
        });
    }

    private void retryAcknowledge() {
        acknowledgeRotation();
    }

    private void acknowledgeRotation() {
        tvError.setVisibility(View.GONE);
        findViewById(R.id.btnRetryAck).setEnabled(false);
        bgExecutor.execute(() -> {
            Exception failure = null;
            try {
                acknowledgeRotationViaServer();
            } catch (Exception e) {
                failure = e;
            }
            final Exception finalFailure = failure;
            runOnUiThread(() -> {
                findViewById(R.id.btnRetryAck).setEnabled(true);
                if (finalFailure != null) {
                    android.util.Log.w("ForcedDuressRotation", "acknowledgeRotation failed", finalFailure);
                    showError("Couldn't confirm with the server. Check your connection and try again.");
                    return;
                }

                // Only now — server confirmed — clear the rotation intent entirely.
                // Both PINs are already durably saved locally; this just lifts the
                // routing block that was keeping the user on these two screens.
                PendingLockStore.clearRotationDue(this);

                boolean unpaired = PendingLockStore.isRotationUnpaired(this);
                Class<?> dest = unpaired ? AddContactActivity.class : ConversationListActivity.class;
                Intent intent = new Intent(this, dest);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            });
        });
    }

    /**
     * Modeled on {@code RestoreFromSeedActivity.migrateOldUidViaServer()}: Bearer
     * Firebase ID token, {@code POST /acknowledgeRotation}, JSON body built via
     * {@code JSONObject} rather than string concatenation.
     */
    private void acknowledgeRotationViaServer() throws Exception {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            throw new IllegalStateException("Rotation acknowledgement requires an authenticated session.");
        }
        final String[]    tokenHolder = {null};
        final Exception[] tokenErr    = {null};
        final Object      tokenLock   = new Object();
        user.getIdToken(false)
                .addOnSuccessListener(result -> {
                    synchronized (tokenLock) { tokenHolder[0] = result.getToken(); tokenLock.notifyAll(); }
                })
                .addOnFailureListener(e -> {
                    synchronized (tokenLock) { tokenErr[0] = e; tokenLock.notifyAll(); }
                });
        synchronized (tokenLock) {
            if (tokenHolder[0] == null && tokenErr[0] == null) tokenLock.wait(15_000);
        }
        if (tokenErr[0] != null) {
            throw new Exception("Could not authenticate rotation acknowledgement.", tokenErr[0]);
        }
        String idToken = tokenHolder[0];
        if (idToken == null) {
            throw new Exception("Could not authenticate rotation acknowledgement.");
        }

        String serverUrl = BuildConfig.PUSH_SERVER_URL;
        java.net.URL url = new java.net.URL(serverUrl + "/acknowledgeRotation");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + idToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);

        JSONObject reqBodyObj = new JSONObject();
        reqBodyObj.put("userId", user.getUid());
        String body = reqBodyObj.toString();
        try (java.io.OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes("UTF-8"));
        }

        int code = conn.getResponseCode();
        String response = "";
        java.io.InputStream responseStream = code >= 200 && code < 300
                ? conn.getInputStream() : conn.getErrorStream();
        if (responseStream != null) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(responseStream, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) response += line;
            }
        }
        android.util.Log.d("ForcedDuressRotation", "acknowledgeRotation server response: HTTP " + code);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new java.io.IOException("Rotation acknowledgement failed (HTTP " + code + "): " + response);
        }
    }

    private void showError(String message) {
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
    }

    @Override
    public void onBackPressed() {
        // No skipping — the account is locked out of the app until the server
        // confirms the rotation.
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bgExecutor.shutdownNow();
    }
}
