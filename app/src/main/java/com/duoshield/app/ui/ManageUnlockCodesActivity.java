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

        View btnRemoveSecondaryCode = findViewById(R.id.btnRemoveSecondaryCode);
        View btnContinueEntry = findViewById(R.id.btnContinueEntry);
        View btnCancelEntry   = findViewById(R.id.btnCancelEntry);
        View btnConfirmExplain = findViewById(R.id.btnConfirmExplain);
        View btnCancelExplain  = findViewById(R.id.btnCancelExplain);

        btnAddCode.setOnClickListener(v -> showEntryPanel());
        btnCancelEntry.setOnClickListener(v -> showListPanel());
        btnContinueEntry.setOnClickListener(v -> onContinueEntry());
        btnConfirmExplain.setOnClickListener(v -> onConfirmExplain());
        btnCancelExplain.setOnClickListener(v -> showListPanel());
        btnRemoveSecondaryCode.setOnClickListener(v -> confirmRemoveSecondaryCode());

        refreshListPanel();
    }

    private void refreshListPanel() {
        boolean hasSecondary = DuressManager.hasDuressPin(this);
        rowSecondaryCode.setVisibility(hasSecondary ? View.VISIBLE : View.GONE);
        // Only one additional code is supported today — hide the add action
        // once one is configured rather than implying unlimited codes.
        btnAddCode.setVisibility(hasSecondary ? View.GONE : View.VISIBLE);
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
                showListPanel();
            });
        });
    }

    private void confirmRemoveSecondaryCode() {
        EditText etCurrent = new EditText(this);
        etCurrent.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        etCurrent.setHint("Primary code");
        etCurrent.setMaxLines(1);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        container.setPadding(pad * 2, pad, pad * 2, 0);
        container.addView(etCurrent);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Remove this code")
                .setMessage("Enter your primary code to confirm removal.")
                .setView(container)
                .setPositiveButton("Remove", (d, w) -> {
                    String entered = etCurrent.getText().toString().trim();
                    bgExecutor.execute(() -> {
                        boolean ok = PinManager.verifyPin(this, entered);
                        runOnUiThread(() -> {
                            if (ok) {
                                DuressManager.clearDuressPin(this);
                                Toast.makeText(this, "Code removed.", Toast.LENGTH_SHORT).show();
                                showListPanel();
                            } else {
                                Toast.makeText(this, "Incorrect code.", Toast.LENGTH_SHORT).show();
                            }
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bgExecutor.shutdownNow();
    }
}
