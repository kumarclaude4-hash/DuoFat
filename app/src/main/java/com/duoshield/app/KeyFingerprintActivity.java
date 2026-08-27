package com.duoshield.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;

import com.duoshield.app.crypto.signal.SignalKeyManager;
import com.duoshield.app.ui.TrustGlyphView;
import com.duoshield.app.util.SafetyWords;
import com.duoshield.app.util.SecurePrefs;
import com.duoshield.app.util.VerificationStore;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.firestore.FirebaseFirestore;
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

    /** UX-1: persistent verification badge views. */
    private LinearLayout verifiedBadge;
    private ImageView    ivVerifiedBadge;
    private TextView     tvVerifiedBadge;
    private TextView     tvVerifyHint;
    private TextView     tvMyFingerprintView;
    private TextView     tvPartnerFingerprintView;

    /** Signature feature: the Trust Seal (deterministic emblem + safety words). */
    private View           trustSealCard;
    private TrustGlyphView trustGlyph;
    private TextView       tvSafetyWords;

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
        tvMyFingerprintView      = tvMyFingerprint;
        tvPartnerFingerprintView = tvPartnerFingerprint;
        verifiedBadge   = findViewById(R.id.verifiedBadge);
        ivVerifiedBadge = findViewById(R.id.ivVerifiedBadge);
        tvVerifiedBadge = findViewById(R.id.tvVerifiedBadge);
        tvVerifyHint    = findViewById(R.id.tvVerifyHint);
        trustSealCard   = findViewById(R.id.trust_seal_card);
        trustGlyph      = findViewById(R.id.trust_glyph);
        tvSafetyWords   = findViewById(R.id.tv_safety_words);
        if (trustGlyph != null) {
            trustGlyph.setVerifiedColor(
                    androidx.core.content.ContextCompat.getColor(this, R.color.ds_verified));
        }
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
                    String shown = formatFingerprint(myFingerprintHex);
                    tvMyFingerprint.setText(shown);
                    // UX-8: TalkBack would otherwise spell the monospace hex one character
                    // at a time. Announce it in 4-char groups, which is how the value is
                    // meant to be compared aloud with the contact.
                    tvMyFingerprint.setContentDescription(
                            getString(R.string.verify_a11y_your_fingerprint, spokenFingerprint(shown)));
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
                applyPartnerKey(tvPartnerFingerprint, partnerIdentityB64);
            } else if (partnerUid != null) {
                // Key not in local storage yet (session not yet established) — fetch from Firestore.
                if (tvPartnerFingerprint != null)
                    tvPartnerFingerprint.setText("Loading…");
                final String uid = partnerUid;
                final TextView tv = tvPartnerFingerprint;
                FirebaseFirestore.getInstance()
                        .collection("users").document(uid)
                        .collection("public_keys").document("bundle")
                        .get()
                        .addOnSuccessListener(doc -> {
                            if (doc == null || !doc.exists()) {
                                if (tv != null) tv.setText("Partner's key not found. Ask them to open the app.");
                                return;
                            }
                            String b64 = doc.getString("identityKey");
                            if (b64 == null) {
                                if (tv != null) tv.setText("Partner's key not found. Ask them to open the app.");
                                return;
                            }
                            // Cache it locally for next time
                            SecurePrefs.get(KeyFingerprintActivity.this)
                                    .edit().putString("signal_partner_identity_key_" + uid, b64).apply();
                            new Handler(Looper.getMainLooper()).post(() -> applyPartnerKey(tv, b64));
                        })
                        .addOnFailureListener(e -> {
                            if (tv != null) tv.setText("Could not load partner's key — check connection.");
                        });
            } else if (tvPartnerFingerprint != null) {
                tvPartnerFingerprint.setText("Open from a chat conversation to verify a specific contact.");
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
            showVerifyDialog(false, getString(R.string.verify_no_key_body));
            return;
        }

        if (scanned.equals(partnerFingerprintHex.toLowerCase())) {
            // UX-1: record the verification durably. VerificationStore.markVerified() also
            // clears safety_num_changed_<uid> in the same commit, which subsumes the F23
            // behaviour below — the flag is still only cleared on an actual match, never on
            // a VERIFY tap alone.
            if (partnerUid != null) {
                VerificationStore.markVerified(this, partnerUid, partnerFingerprintHex);
            }
            renderVerificationState();
            // Seal the Trust Seal emblem the moment verification passes.
            updateTrustSeal();
            showVerifyDialog(true, getString(R.string.verify_passed_body));
        } else {
            showVerifyDialog(false, getString(R.string.verify_failed_body));
        }
    }

    /**
     * UX-5 fix: the title/body no longer carry "✅"/"❌" emoji. The dialog title already
     * states pass/fail, and emoji in trust-critical copy read awkwardly under TalkBack and
     * render inconsistently across OEM emoji fonts.
     */
    private void showVerifyDialog(boolean match, String message) {
        new AlertDialog.Builder(this)
                .setTitle(match ? R.string.verify_passed_title : R.string.verify_failed_title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    // ── UX-1: durable verification badge ──────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        // Re-read on resume: the key may have changed (and the verification been voided by
        // DuoShieldSignalStore) while this screen was in the background.
        renderVerificationState();
        updateTrustSeal();
    }

    /**
     * Renders the persistent verification badge from {@link VerificationStore}.
     *
     * <p>Three states, because "not verified" and "verified but the key has since changed"
     * are very different things and collapsing them would be the more dangerous of the two
     * readings:
     * <ul>
     *   <li><b>Verified</b> — green shield, "Verified on {date}".</li>
     *   <li><b>Out of date</b> — amber, key changed after verification.</li>
     *   <li><b>Not verified</b> — muted grey, neutral prompt to scan.</li>
     * </ul>
     */
    private void renderVerificationState() {
        if (verifiedBadge == null || tvVerifiedBadge == null) return;

        // No specific contact in context (opened from Settings rather than a chat) — a
        // per-contact badge would be meaningless here.
        if (partnerUid == null) {
            verifiedBadge.setVisibility(android.view.View.GONE);
            if (tvVerifyHint != null) tvVerifyHint.setText(R.string.fingerprint_info);
            return;
        }

        verifiedBadge.setVisibility(android.view.View.VISIBLE);
        boolean verified = VerificationStore.isVerified(this, partnerUid);
        boolean stale    = verified
                && VerificationStore.isStale(this, partnerUid, partnerFingerprintHex);

        int color;
        int bg;
        String label;
        int hint;

        if (verified && !stale) {
            color = androidx.core.content.ContextCompat.getColor(this, R.color.ds_verified);
            bg    = androidx.core.content.ContextCompat.getColor(this, R.color.ds_verified_bg);
            String on = VerificationStore.formatVerifiedOn(this, partnerUid);
            label = on != null
                    ? getString(R.string.verify_state_verified_on, on)
                    : getString(R.string.verify_state_verified);
            hint  = R.string.verify_hint_verified;
        } else if (stale) {
            color = androidx.core.content.ContextCompat.getColor(this, R.color.ds_warning);
            bg    = androidx.core.content.ContextCompat.getColor(this, R.color.ds_unverified_bg);
            label = getString(R.string.verify_state_stale);
            hint  = R.string.verify_hint_stale;
        } else {
            color = androidx.core.content.ContextCompat.getColor(this, R.color.ds_unverified);
            bg    = androidx.core.content.ContextCompat.getColor(this, R.color.ds_unverified_bg);
            label = getString(R.string.verify_state_not_verified);
            hint  = R.string.verify_hint_not_verified;
        }

        tvVerifiedBadge.setText(label);
        tvVerifiedBadge.setTextColor(color);
        if (ivVerifiedBadge != null) {
            ivVerifiedBadge.setImageResource(verified && !stale
                    ? R.drawable.ic_verified_shield
                    : R.drawable.ic_warning);
            ivVerifiedBadge.setImageTintList(android.content.res.ColorStateList.valueOf(color));
            // Not-verified is a neutral default state, not a warning — don't show an alarm
            // icon for a contact the user simply hasn't gotten around to verifying.
            ivVerifiedBadge.setVisibility(verified || stale
                    ? android.view.View.VISIBLE : android.view.View.GONE);
        }
        verifiedBadge.setBackgroundTintList(android.content.res.ColorStateList.valueOf(bg));
        // The pill is a status readout, so give TalkBack the whole thing as one label.
        verifiedBadge.setContentDescription(label);
        if (tvVerifyHint != null) tvVerifyHint.setText(hint);
    }

    /** Guards against re-running the entrance animation when the seal is unchanged. */
    private String appliedSealKey;

    /**
     * Signature feature. Derives the shared Trust Seal (emblem + safety words) from BOTH
     * fingerprints and renders it. Both contacts compute the identical seal because
     * {@link SafetyWords#combinedSeed} sorts the two hexes before hashing, so the emblem is a
     * fast, human-checkable proxy for the authoritative hex/QR comparison below.
     *
     * <p>Only meaningful with a partner in context; hidden when opened from Settings.
     */
    private void updateTrustSeal() {
        if (trustSealCard == null) return;
        if (myFingerprintHex == null || partnerFingerprintHex == null) {
            trustSealCard.setVisibility(android.view.View.GONE);
            return;
        }
        byte[] seed = SafetyWords.combinedSeed(myFingerprintHex, partnerFingerprintHex);
        if (seed == null) {
            trustSealCard.setVisibility(android.view.View.GONE);
            return;
        }
        trustSealCard.setVisibility(android.view.View.VISIBLE);

        String key = myFingerprintHex + "|" + partnerFingerprintHex;
        if (!key.equals(appliedSealKey)) {
            appliedSealKey = key;
            if (trustGlyph != null) trustGlyph.setSeed(seed);
            if (tvSafetyWords != null) {
                tvSafetyWords.setText(SafetyWords.phrase(seed));
                String spoken = SafetyWords.spokenPhrase(seed);
                if (spoken != null) {
                    tvSafetyWords.setContentDescription(
                            getString(R.string.trust_seal_a11y, spoken));
                }
            }
        }

        if (trustGlyph != null && partnerUid != null) {
            boolean verified = VerificationStore.isVerified(this, partnerUid)
                    && !VerificationStore.isStale(this, partnerUid, partnerFingerprintHex);
            trustGlyph.setVerified(verified);
        }
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

    private void applyPartnerKey(TextView tv, String identityB64) {
        try {
            byte[] raw = Base64.decode(identityB64, Base64.NO_WRAP);
            IdentityKey partnerKey = new IdentityKey(raw, 0);
            partnerFingerprintHex = sha256Hex(partnerKey.serialize());
            if (tv != null) {
                String shown = formatFingerprint(partnerFingerprintHex);
                tv.setText(shown);
                // UX-8: group-by-group announcement, see tvMyFingerprint above.
                tv.setContentDescription(
                        getString(R.string.verify_a11y_partner_fingerprint, spokenFingerprint(shown)));
            }
        } catch (Exception e) {
            if (tv != null) tv.setText("Error reading partner key.");
        }
        // UX-1: the badge depends on the partner fingerprint, which may arrive
        // asynchronously from Firestore — re-render once it is known so a stale
        // verification can be detected against the key actually on file.
        renderVerificationState();
        // The Trust Seal likewise depends on the partner fingerprint being known.
        updateTrustSeal();
    }

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

    /**
     * UX-8: turns "A1B2 C3D4 …" into "A1B2, C3D4, …" so TalkBack pauses between groups
     * instead of running the hex together or spelling it out character by character.
     */
    private String spokenFingerprint(String formatted) {
        if (formatted == null) return "";
        return formatted.trim().replaceAll("\\s+", ", ");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
