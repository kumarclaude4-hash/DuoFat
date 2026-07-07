package com.duoshield.app.security;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import java.util.concurrent.Executor;

public class BiometricHelper {

    public interface AuthCallback {
        void onSuccess();
        void onFailure();
    }

    private static int resolveAuthenticators(Context context) {
        BiometricManager bm = BiometricManager.from(context);
        if (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == BiometricManager.BIOMETRIC_SUCCESS) {
            return BiometricManager.Authenticators.BIOMETRIC_STRONG;
        }
        if (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                == BiometricManager.BIOMETRIC_SUCCESS) {
            return BiometricManager.Authenticators.BIOMETRIC_WEAK;
        }
        return -1;
    }

    /**
     * Shows a biometric prompt (fingerprint or face).
     * Tries BIOMETRIC_STRONG first; falls back to BIOMETRIC_WEAK for devices
     * where face-unlock or fingerprint is only class-2 (weak) certified.
     * Never falls back to device PIN — DuoShield has its own app PIN for that.
     */
    public static void authenticate(FragmentActivity activity, AuthCallback callback) {
        int authenticators = resolveAuthenticators(activity);
        if (authenticators == -1) {
            callback.onFailure();
            return;
        }

        Executor executor = ContextCompat.getMainExecutor(activity);

        BiometricPrompt prompt = new BiometricPrompt(activity, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        callback.onSuccess();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode,
                                                      @NonNull CharSequence errString) {
                        callback.onFailure();
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        // Single bad attempt — BiometricPrompt shows retry UI automatically
                    }
                });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("DuoShield")
                .setSubtitle("Use fingerprint or face to unlock")
                .setAllowedAuthenticators(authenticators)
                .setNegativeButtonText("Use PIN instead")
                .build();

        prompt.authenticate(promptInfo);
    }

    /** Returns true if the device has any enrolled biometrics (strong or weak) that can authenticate. */
    public static boolean isAvailable(Context context) {
        return resolveAuthenticators(context) != -1;
    }
}
