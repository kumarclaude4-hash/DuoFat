package com.duoshield.app.ui;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;

import com.duoshield.app.BaseActivity;
import com.duoshield.app.R;
import com.duoshield.app.security.DuressManager;
import com.duoshield.app.util.PinManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Deliberately generic "manage unlock codes" screen — the app PIN and the
 * second (self-wipe) code are presented as interchangeable "codes" with no
 * feature name attached to either. See UX audit item #2 / Appendix A.
 *
 * <p>The actual self-wipe behavior of a secondary code is explained exactly
 * once, in {@code panelExplain}, right before that code is saved — never
 * anywhere else in the running app. The backing store is still
 * {@link DuressManager}; only the UI-facing vocabulary changed.
 */
public class ManageUnlockCodesActivity extends BaseActivity {

    private LinearLayout panelList, panelEntry, panelExplain;
    private LinearLayout rowSecondaryCode;
    private com.google.android.material.button.MaterialButton btnAddCode;
    private EditText etNewCode, etConfirmCode;

    private String pendingCode; // held between the entry panel and the explanation panel

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_unlock_codes);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        panelList    = findViewById(R.id.panelList);
        panelEntry   = findViewById(R.id.panelEntry);
        panelExplain = findViewById(R.id.panelExplain);
        rowSecondaryCode = findViewById(R.id.rowSecondaryCode);
        btnAddCode   = findViewById(R.id.btnAddCode);
        etNewCode    = findViewById(R.id.etNewCode);
        etConfirmCode = findViewById(R.id.etConfirmCode);

        View btnContinueEntry = findViewById(R.id.btnContinueEntry);
        View btnCancelEntry   = findViewById(R.id.btnCancelEntry);
        View btnConfirmExplain = findViewById(R.id.btnConfirmExplain);
        View btnCancelExplain  = findViewById(R.id.btnCancelExplain);

        btnAddCode.setOnClickListener(v -> showEntryPanel());
        btnCancelEntry.setOnClickListener(v -> showListPanel());
        btnContinueEntry.setOnClickListener(v -> onContinueEntry());
        btnConfirmExplain.setOnClickListener(v -> onConfirmExplain());
        btnCancelExplain.setOnClickListener(v -> showListPanel());

        refreshListPanel();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Pull the server-side enrollment flag again on every visit. Enrollment is
        // granted out-of-band by the operator, so it normally lands while the
        // account is already signed in; refreshing only at sign-in meant a
        // just-enrolled user opened this screen and found nothing here. Only the
        // list panel is re-rendered, so a refresh landing mid-flow cannot yank the
        // entry or explanation panel out from under the user.
        DuressManager.refreshEligibility(this, () -> {
            if (isFinishing() || isDestroyed()) return;
            if (panelList.getVisibility() == View.VISIBLE) refreshListPanel();
        });
    }

    private void refreshListPanel() {
        boolean hasSecondary = DuressManager.hasDuressPin(this);
        // No-trace: the secondary-code row is never shown, configured or not.
        // Once a code exists there is nothing left to browse to on this screen —
        // removal only happens by clearing the primary code entirely (which
        // cascades to DuressManager.clearDuressPin()). This avoids a "configured"
        // indicator that would tell anyone with the primary code that a second
        // one exists, which defeats the point of it.
        rowSecondaryCode.setVisibility(View.GONE);
        // Only accounts explicitly enrolled server-side ever see the option to
        // add a second code at all — everyone else gets an ordinary single-code
        // screen with no hint the capability exists.
        boolean eligible = DuressManager.isDuressEligibleCached(this);
        btnAddCode.setVisibility((eligible && !hasSecondary) ? View.VISIBLE : View.GONE);
    }

    private void showListPanel() {
        etNewCode.setText("");
        etConfirmCode.setText("");
        pendingCode = null;
        panelList.setVisibility(View.VISIBLE);
        panelEntry.setVisibility(View.GONE);
        panelExplain.setVisibility(View.GONE);
        refreshListPanel();
    }

    private void showEntryPanel() {
        panelList.setVisibility(View.GONE);
        panelEntry.setVisibility(View.VISIBLE);
        panelExplain.setVisibility(View.GONE);
        etNewCode.requestFocus();
    }

    private void showExplainPanel() {
        panelList.setVisibility(View.GONE);
        panelEntry.setVisibility(View.GONE);
        panelExplain.setVisibility(View.VISIBLE);
    }

    private void onContinueEntry() {
        String code = etNewCode.getText().toString().trim();
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
     * Re-authenticates with the existing primary PIN, then uses its plaintext —
     * held only for the duration of this method, never persisted or logged — to
     * reject any overlap with {@code candidateCode} before the secondary code is
     * ever saved.
     *
     * <p>This replaces a previous check that only asked "does {@code
     * candidateCode} hash to the same value as the stored primary PIN" — a single
     * PBKDF2 comparison that caught an exact match but nothing else. A prefix
     * overlap between the two codes was still possible and dangerous:
     * {@code LockScreenActivity} verifies on an explicit confirm tap or on
     * reaching {@code MAX_PIN_LEN}, not at a fixed length, so if one code is a
     * leading prefix of the other, a deliberate confirm partway through typing
     * the longer one reads as the shorter one instead. With the real primary
     * PIN's plaintext in hand here — rather than just its hash — both prefix
     * directions can be checked with a single string comparison and zero
     * additional hashing, instead of the brute-force search over unknown digits
     * that checking a hash for a prefix relationship would otherwise require.
     */
    private void promptCurrentPinAndValidate(String candidateCode) {
        EditText etCurrent = new EditText(this);
        etCurrent.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        etCurrent.setHint("Current PIN");
        etCurrent.setMaxLines(1);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        container.setPadding(pad * 2, pad, pad * 2, 0);
        container.addView(etCurrent);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Confirm your current PIN to continue")
                .setView(container)
                .setPositiveButton("Confirm", (d, w) -> {
                    String currentPin = etCurrent.getText().toString().trim();
                    bgExecutor.execute(() -> {
                        boolean authentic = PinManager.verifyPin(this, currentPin);
                        // Plaintext-to-plaintext only — no hashing needed for the
                        // overlap check itself, and both values fall out of scope
                        // as soon as this lambda returns.
                        boolean overlaps = authentic && (
                                currentPin.equals(candidateCode)
                                        || currentPin.startsWith(candidateCode)
                                        || candidateCode.startsWith(currentPin));
                        runOnUiThread(() -> {
                            if (!authentic) {
                                Toast.makeText(this, "Incorrect PIN.", Toast.LENGTH_SHORT).show();
                            } else if (overlaps) {
                                // Deliberately says nothing about *why*, same reasoning
                                // as SecurityPrivacySettingsActivity.doSavePin(): naming
                                // "your PIN" would confirm to anyone at this screen that
                                // the two codes are compared against each other at all.
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
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void onConfirmExplain() {
        if (pendingCode == null) { showListPanel(); return; }
        String codeToSave = pendingCode;
        bgExecutor.execute(() -> {
            // The return value used to be discarded, and "Code saved." + finish()
            // ran unconditionally. setDuressPin() returns false without writing
            // anything when no Firebase user is signed in (an expired token or a
            // background sign-out leaves duressKey()/armedKey() null) or when the
            // PBKDF2/EncryptedSharedPreferences write throws — so a save that armed
            // nothing at all was indistinguishable from a successful one, and the
            // user was told their second code was active when it was not.
            boolean saved = DuressManager.setDuressPin(this, codeToSave);
            runOnUiThread(() -> {
                if (!saved) {
                    // Stay on this screen with the entry flow intact so the user can
                    // retry immediately. Deliberately generic: the message must not
                    // hint at *why* it failed, and it is the same string whatever the
                    // cause. Nothing is armed, so refreshListPanel() below brings the
                    // add button back rather than hiding it as it would after a real
                    // save.
                    Toast.makeText(this, "Could not save. Try again.", Toast.LENGTH_LONG).show();
                    showListPanel();
                    return;
                }
                Toast.makeText(this, "Code saved.", Toast.LENGTH_SHORT).show();
                // Leave the screen entirely rather than returning to the list.
                // Returning would show a bare "CODES / Primary code · Active" list
                // with the add button now gone — an empty space where an option used
                // to be, which is itself a tell. Settings re-evaluates on resume and
                // the entry row is gone by the time the user lands back there, so
                // this screen is unreachable from now on.
                pendingCode = null;
                etNewCode.setText("");
                etConfirmCode.setText("");
                finish();
            });
        });
    }

    // Removing an already-configured secondary code is intentionally NOT exposed
    // here — that would require showing a "configured" affordance, which is the
    // exact trace this screen exists to avoid. Clearing the primary code in
    // Settings cascades to DuressManager.clearDuressPin() instead.

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bgExecutor.shutdownNow();
    }
}
