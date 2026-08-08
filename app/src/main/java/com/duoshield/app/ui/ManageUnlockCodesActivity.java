package com.duoshield.app.ui;

import android.os.Bundle;
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

        bgExecutor.execute(() -> {
            boolean matchesPrimary = PinManager.verifyPin(this, code);
            runOnUiThread(() -> {
                if (matchesPrimary) {
                    Toast.makeText(this,
                        "This code is already in use. Choose a different one.",
                        Toast.LENGTH_LONG).show();
                } else {
                    pendingCode = code;
                    showExplainPanel();
                }
            });
        });
    }

    private void onConfirmExplain() {
        if (pendingCode == null) { showListPanel(); return; }
        String codeToSave = pendingCode;
        bgExecutor.execute(() -> {
            DuressManager.setDuressPin(this, codeToSave);
            runOnUiThread(() -> {
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
