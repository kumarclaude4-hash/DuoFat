package com.duoshield.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.duoshield.app.R;
import com.duoshield.app.auth.InviteHelper;
import com.duoshield.app.util.ButtonPressAnimator;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

/** Gate in front of new-account creation using an admin-issued, single-use invite. */
public class RequestAccessActivity extends AppCompatActivity {

    public static final String EXTRA_INVITE_TOKEN = "invite_token";

    private EditText inputInvite;
    private TextView tvError;
    private MaterialButton btnPrimary;
    private LinearProgressIndicator progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_request_access);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        inputInvite = findViewById(R.id.inputInvite);
        tvError = findViewById(R.id.tvError);
        btnPrimary = findViewById(R.id.btnPrimary);
        progress = findViewById(R.id.progressAccess);
        ButtonPressAnimator.attach(btnPrimary);

        btnPrimary.setOnClickListener(v -> validateInvite());
        inputInvite.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            validateInvite();
            return true;
        });
    }

    private void validateInvite() {
        String inviteToken = InviteHelper.normalize(inputInvite.getText().toString());
        if (inviteToken.isEmpty()) {
            showError("Enter your invite code to continue.");
            return;
        }
        setBusy(true);
        tvError.setVisibility(View.GONE);
        InviteHelper.validate(inviteToken, new InviteHelper.Callback() {
            @Override public void onSuccess(boolean valid) {
                setBusy(false);
                if (!valid) {
                    showError("That invite code is invalid or no longer available.");
                    return;
                }
                Intent intent = new Intent(RequestAccessActivity.this,
                        RecoveryPhraseWalkthroughActivity.class);
                intent.putExtra(EXTRA_INVITE_TOKEN, inviteToken);
                startActivity(intent);
                finish();
            }

            @Override public void onFailure(Exception error) {
                setBusy(false);
                showError(error.getMessage() != null
                        ? error.getMessage()
                        : "Unable to validate invite right now. Please try again.");
            }
        });
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        btnPrimary.setEnabled(!busy);
        inputInvite.setEnabled(!busy);
    }

    private void showError(String message) {
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
    }
}
