package com.duoshield.app.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.duoshield.app.DisplayNameActivity;
import com.duoshield.app.R;
import com.duoshield.app.util.ButtonPressAnimator;
import com.google.android.material.button.MaterialButton;

/**
 * Brief first-run explanation shown only before a new account is created.
 * Returning users recover through RestoreFromSeedActivity and never pass here.
 */
public class RecoveryPhraseWalkthroughActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recovery_phrase_walkthrough);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        MaterialButton continueButton = findViewById(R.id.btnContinue);
        ButtonPressAnimator.attach(continueButton);
        continueButton.setOnClickListener(v -> {
            startActivity(new Intent(this, DisplayNameActivity.class));
            finish();
        });
    }
}