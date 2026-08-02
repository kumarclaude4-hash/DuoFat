package com.duoshield.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.duoshield.app.ConversationListActivity;
import com.duoshield.app.R;
import com.duoshield.app.util.ButtonPressAnimator;
import com.duoshield.app.util.PinManager;
import com.google.android.material.button.MaterialButton;

/**
 * Mandatory PIN setup for brand-new accounts. Shown once, right after
 * {@link SeedPhraseDisplayActivity} finishes key setup and before the app
 * hands off into {@link ConversationListActivity}.
 *
 * <p>Every new account must have a PIN — it's no longer something the user
 * can skip and configure later from Settings. Existing installs that already
 * skipped PIN setup before this screen existed are untouched; this only
 * applies to the new-account creation path.</p>
 */
public class SetupPinActivity extends AppCompatActivity {

    /** Forwarded through unchanged to ConversationListActivity. */
    public static final String EXTRA_ACCOUNT_CREATED = SeedPhraseDisplayActivity.EXTRA_ACCOUNT_CREATED;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // PIN entry is a security-sensitive screen — never allow screenshots/recording.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_setup_pin);

        EditText etNewPin     = findViewById(R.id.etNewPin);
        EditText etConfirmPin = findViewById(R.id.etConfirmPin);
        TextView tvError      = findViewById(R.id.tvError);
        MaterialButton btnContinue = findViewById(R.id.btnContinue);
        ButtonPressAnimator.attach(btnContinue);

        btnContinue.setOnClickListener(v -> {
            String pin     = etNewPin.getText() != null ? etNewPin.getText().toString() : "";
            String confirm = etConfirmPin.getText() != null ? etConfirmPin.getText().toString() : "";

            if (pin.length() < 4 || pin.length() > 6) {
                showError(tvError, "PIN must be 4–6 digits.");
                return;
            }
            if (!pin.equals(confirm)) {
                showError(tvError, "PINs don't match. Try again.");
                etConfirmPin.setText("");
                return;
            }

            tvError.setVisibility(View.GONE);
            PinManager.setPin(this, pin);

            Intent intent = new Intent(this, ConversationListActivity.class);
            intent.putExtra(EXTRA_ACCOUNT_CREATED, getIntent().getBooleanExtra(EXTRA_ACCOUNT_CREATED, false));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }

    private void showError(TextView tvError, String message) {
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
    }

    @Override
    public void onBackPressed() {
        // No skipping — a new account must leave this screen with a PIN set.
    }
}
