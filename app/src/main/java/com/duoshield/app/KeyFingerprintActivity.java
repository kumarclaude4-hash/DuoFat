package com.duoshield.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Base64;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;

import com.duoshield.app.crypto.signal.SignalKeyManager;
import com.duoshield.app.util.SecurePrefs;
import com.google.android.material.button.MaterialButton;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanIntentResult;
import com.journeyapps.barcodescanner.ScanOptions;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;

import java.security.MessageDigest;

public class KeyFingerprintActivity extends BaseActivity {

    private String myFingerprintHex;
    private String partnerFingerprintHex;
    /** F23: UID passed from ChatMediaActivity; used to clear safety_num_changed flag on match. */
    private String partnerUid;
    /** F23: true when launched from the safety-number banner VERIFY button. */
    private boolean clearSafetyNumOnMatch;

    // Must be registered as a field so it is ready before onCreate()
    private final ActivityResultLauncher<ScanOptions> scanLauncher =
            registerForActivityResult(new ScanContract(), this::onScanResult);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_key_fingerprint);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Key Fingerprint");
        }
        if (toolbar != null) toolbar.setNavigationOnClickListener(v -> finish());

        TextView tvMyFingerprint      = findViewById(R.id.tv_my_fingerprint);
        TextView tvPartnerFingerprint = findViewById(R.id.tv_partner_fingerprint);
        MaterialButton btnShowQr      = findViewById(R.id.btn_show_qr);
        MaterialButton btnScanPartner = findViewById(R.id.btn_scan_partner);
        MaterialButton btnShare       = findViewById(R.id.btn_share);

        // My fingerprint — SHA-256 of my Signal identity public key bytes
        try {
            IdentityKeyPair kp = SignalKeyManager.getIdentityKeyPair(this);
            if (kp != null) {
                byte[] pubBytes = kp.getPublicKey().serialize();
                myFingerprintHex = sha256Hex(pubBytes);
                if (tvMyFingerprint != null) {
                    tvMyFingerprint.setText(formatFingerprint(myFingerprintHex));
                }
            } else if (tvMyFingerprint != null) {
                tvMyFingerprint.setText("Identity key not generated yet.");
            }
        } catch (Exception e) {
            if (tvMyFingerprint != null) tvMyFingerprint.setText("Error reading identity key.");
        }

        // F23: read Intent extras for partner context and whether to clear the
        // safety-number-changed flag on a successful QR match.
        clearSafetyNumOnMatch = getIntent().getBooleanExtra("clear_safety_num_on_match", false);

        // Partner fingerprint — read from SecurePrefs (stored during X3DH).
        // F22 fix: DuoShieldSignalStore.saveIdentity() now writes an address-scoped key
        // ("signal_partner_identity_key_<partnerUid>") on every saveIdentity() call, including
        // first-use, so this screen is no longer blank immediately after pairing.
        //
        // Resolution order for partnerUid:
        //   1. Intent extra "partner_uid" — set by ChatMediaActivity.checkSafetyNumberBanner()
        //      for the in-chat launch path (multi-contact safe).
        //   2. SharedPrefs "partner_uid" — set by ChatMediaActivity on chat open (1:1 fallback).
        //   3. null → fall back to legacy unscoped key (pre-F22 existing installs).
        try {
            android.content.SharedPreferences sp = SecurePrefs.get(this);
            partnerUid = getIntent().getStringExtra("partner_uid");
            if (partnerUid == null) {
                partnerUid = getSharedPreferences("duoshield_prefs", MODE_PRIVATE)
                        .getString("partner_uid", null);
            }
            String partnerIdentityB64 = null;
            if (partnerUid != null) {
                partnerIdentityB64 = sp.getString("signal_partner_identity_key_" + partnerUid, null);
            }
            if (partnerIdentityB64 == null) {
                // Legacy fallback for existing installs (unscoped key written by old code)
                partnerIdentityB64 = sp.getString("signal_partner_identity_key", null);
            }
            if (partnerIdentityB64 != null) {
                byte[] raw = Base64.decode(partnerIdentityB64, Base64.NO_WRAP);
                IdentityKey partnerKey = new IdentityKey(raw, 0);
                partnerFingerprintHex = sha256Hex(partnerKey.serialize());
                if (tvPartnerFingerprint != null) {
                    tvPartnerFingerprint.setText(formatFingerprint(partnerFingerprintHex));
                }
            } else if (tvPartnerFingerprint != null) {
                // F22: if still unresolved (no partner context at all), guide the user
                // to open the fingerprint screen from inside a specific chat.
                tvPartnerFingerprint.setText("Not available — open from a chat conversation to verify a specific contact.");
            }
        } catch (Exception e) {
            if (tvPartnerFingerprint != null) tvPartnerFingerprint.setText("Error reading partner key.");
        }

        if (btnShowQr != null) {
            btnShowQr.setOnClickListener(v -> showFingerprintQrDialog());
        }

        if (btnScanPartner != null) {
            btnScanPartner.setOnClickListener(v -> launchPartnerScan());
        }

        if (btnShare != null) {
            btnShare.setOnClickListener(v -> shareFingerprint());
        }
    }

    @Override public boolean onSupportNavigateUp() { finish(); return true; }

    // ── Scan partner's fingerprint QR ─────────────────────────────────────────

    private void launchPartnerScan() {
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt("Scan your partner's key fingerprint QR");
        options.setBeepEnabled(false);
        options.setBarcodeImageEnabled(false);
        options.setOrientationLocked(false);
        scanLauncher.launch(options);
    }

    private void onScanResult(ScanIntentResult result) {
        if (result == null || result.getContents() == null) return;

        String scanned = result.getContents().trim().toLowerCase();

        if (partnerFingerprintHex == null) {
            showVerifyDialog(false,
                    "No partner key on file yet.\nComplete pairing before verifying fingerprints.");
            return;
        }

        if (scanned.equals(partnerFingerprintHex.toLowerCase())) {
            // F23 fix: only clear the safety_num_changed flag after a successful match,
            // not unconditionally when the user taps VERIFY in ChatMediaActivity.
            if (clearSafetyNumOnMatch && partnerUid != null) {
                getSharedPreferences("duoshield_prefs", MODE_PRIVATE)
                        .edit().remove("safety_num_changed_" + partnerUid).apply();
            }
            showVerifyDialog(true,
                    "✅  Fingerprints match!\n\nYou are talking to the right person and this "
                    + "conversation is secure.");
        } else {
            showVerifyDialog(false,
                    "❌  Fingerprints do NOT match.\n\nDo not continue this conversation. "
                    + "Your partner's key may have changed or someone may be intercepting your messages.");
        }
    }

    private void showVerifyDialog(boolean match, String message) {
        new AlertDialog.Builder(this)
                .setTitle(match ? "Verification Passed" : "Verification Failed")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    // ── Show my fingerprint as QR ─────────────────────────────────────────────

    private void showFingerprintQrDialog() {
        if (myFingerprintHex == null || myFingerprintHex.isEmpty()) {
            Toast.makeText(this, "Fingerprint not available yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            BarcodeEncoder encoder = new BarcodeEncoder();
            Bitmap bitmap = encoder.encodeBitmap(
                    myFingerprintHex.toUpperCase(), BarcodeFormat.QR_CODE, 600, 600);

            int dp16 = dp(16);
            int dp8  = dp(8);

            LinearLayout container = new LinearLayout(this);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setPadding(dp16, dp8, dp16, dp8);

            TextView subtitle = new TextView(this);
            subtitle.setText("Show this to your partner so they can scan and verify your identity key.");
            subtitle.setTextSize(13f);
            subtitle.setTextColor(0xFFC8C2D8);
            subtitle.setPadding(0, 0, 0, dp16);
            container.addView(subtitle);

            ImageView qrView = new ImageView(this);
            qrView.setImageBitmap(bitmap);
            qrView.setBackgroundColor(Color.WHITE);
            int size = dp(260);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.setMargins(0, 0, 0, dp8);
            qrView.setLayoutParams(lp);
            container.addView(qrView);

            TextView fpLabel = new TextView(this);
            fpLabel.setText(formatFingerprint(myFingerprintHex));
            fpLabel.setTextSize(12f);
            fpLabel.setTypeface(android.graphics.Typeface.MONOSPACE);
            fpLabel.setTextColor(0xFF9A81FF);
            fpLabel.setGravity(Gravity.CENTER_HORIZONTAL);
            fpLabel.setPadding(0, dp8, 0, 0);
            container.addView(fpLabel);

            new AlertDialog.Builder(this)
                    .setTitle("Your Key QR")
                    .setView(container)
                    .setPositiveButton("Done", null)
                    .show();

        } catch (WriterException e) {
            Toast.makeText(this, "Could not generate QR code.", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Share fingerprint as text ─────────────────────────────────────────────

    private void shareFingerprint() {
        if (myFingerprintHex == null) return;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT,
                "My DuoShield key fingerprint:\n" + formatFingerprint(myFingerprintHex));
        startActivity(Intent.createChooser(intent, "Share fingerprint"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String sha256Hex(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return "error"; }
    }

    private String formatFingerprint(String hex) {
        if (hex == null || hex.length() < 32) return hex;
        String t = hex.substring(0, 32).toUpperCase();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i += 4) {
            if (i > 0) sb.append(" ");
            sb.append(t, i, Math.min(i + 4, t.length()));
        }
        return sb.toString();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
