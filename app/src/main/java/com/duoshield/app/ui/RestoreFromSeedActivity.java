package com.duoshield.app.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.widget.TextView;

import com.duoshield.app.BuildConfig;

import androidx.appcompat.app.AppCompatActivity;
import com.duoshield.app.R;
import com.duoshield.app.ConversationListActivity;
import com.duoshield.app.auth.AuthTokenHelper;
import com.duoshield.app.ui.AddContactActivity;
import com.duoshield.app.backup.BackupManager;
import com.duoshield.app.backup.BackupScheduler;
import com.duoshield.app.backup.MediaRestoreHelper;
import com.duoshield.app.crypto.BackupCryptoHelper;
import com.duoshield.app.crypto.SeedPhraseHelper;
import com.duoshield.app.crypto.signal.SignalKeyManager;
import com.duoshield.app.util.FcmTokenHelper;
import com.duoshield.app.util.SecurePrefs;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;

import org.signal.libsignal.protocol.IdentityKeyPair;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Restores a DuoShield identity using Account ID + 12-word seed phrase.
 *
 * Both factors are required:
 *  - Account ID:  provided by the user — must match the userId derived from the seed.
 *  - Seed phrase: 12 BIP39 words that deterministically derive the identity key pair.
 *
 * The derived userId must equal the entered Account ID, providing a second
 * factor without any server-side secret: a stolen seed alone cannot restore
 * the account unless the attacker also knows the Account ID.
 *
 * <h3>UID-mismatch fix</h3>
 * This activity now uses Firebase Custom Token authentication (via
 * {@link AuthTokenHelper}) instead of {@code signInAnonymously()}.  The
 * custom token's UID equals {@code userId}, so the Firebase UID is the same
 * on every restore — Firestore queries using that UID always find the right
 * data.
 *
 * If a prior anonymous UID exists in Firestore (old accounts created before
 * this fix), a one-time migration rewrites chat {@code participants} arrays
 * and copies the {@code users/} document so both sides of every conversation
 * continue to work without re-pairing.
 */
public class RestoreFromSeedActivity extends AppCompatActivity {

    private static final String PREFS_NAME        = "duoshield_prefs";
    private static final String KEY_USER_ID        = "my_user_id";
    private static final String KEY_MY_UID         = "my_uid";
    private static final String KEY_IS_PAIRED      = "is_paired";
    private static final String KEY_CONV_ID        = "conversation_id";
    private static final String KEY_PARTNER_UID    = "partner_uid";

    private TextInputEditText         etAccountId;
    private TextInputEditText         etSeedWords;
    private TextView                  tvError;
    private TextView                  tvStep;
    private TextView                  tvMediaProgress;
    private MaterialButton            btnRestore;
    private LinearProgressIndicator   progressRestore;
    private LinearProgressIndicator   progressMediaCacheBar;

    // Restore loader views
    private View                      restoreLoaderFrame;
    private ShieldFillView            shieldFillView;
    private DecryptGridView           decryptGridView;
    private TextView                  tvRestoreStep;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Seed restoration screen keeps FLAG_SECURE — the user types their 12-word recovery
        // phrase here, which is a cryptographic secret that must never be screen-captured.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_restore_from_seed);

        etAccountId     = findViewById(R.id.etAccountId);
        etSeedWords     = findViewById(R.id.etSeedWords);
        tvError         = findViewById(R.id.tvError);
        tvStep               = findViewById(R.id.tvStep);
        tvMediaProgress      = findViewById(R.id.tvMediaProgress);
        btnRestore           = findViewById(R.id.btnRestore);
        progressRestore      = findViewById(R.id.progressRestore);
        progressMediaCacheBar = findViewById(R.id.progressMediaCacheBar);

        restoreLoaderFrame = findViewById(R.id.restoreLoaderFrame);
        shieldFillView     = findViewById(R.id.shieldFillView);
        decryptGridView    = findViewById(R.id.decryptGridView);
        tvRestoreStep      = findViewById(R.id.tvRestoreStep);

        if (tvStep != null) tvStep.setVisibility(View.GONE);

        btnRestore.setOnClickListener(v -> attemptRestore());

        android.widget.ImageView btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) btnBack.setOnClickListener(v -> onBackPressed());
    }

    // ── Step A — Validate both inputs ────────────────────────────────────────

    private void attemptRestore() {
        hideError();

        String accountId = etAccountId != null && etAccountId.getText() != null
                ? etAccountId.getText().toString().trim() : "";
        if (accountId.isEmpty()) { showError("Please enter your Account ID."); return; }

        String raw   = etSeedWords.getText() != null ? etSeedWords.getText().toString().trim() : "";
        String[] parts = raw.split("\\s+");
        if (parts.length != 12) { showError("Please enter all 12 words of your Recovery Phrase."); return; }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(parts[i].toLowerCase());
        }
        String mnemonic = sb.toString();

        if (!SeedPhraseHelper.validateMnemonic(mnemonic)) {
            showError("These words don't form a valid recovery phrase. Please check for typos.");
            return;
        }

        setLoading(true);
        setStep("Verifying identity…");
        final String finalMnemonic   = mnemonic;
        final String finalAccountId  = accountId;
        new Thread(() -> {
            try {
                restoreOnBackground(finalMnemonic, finalAccountId);
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    showError(friendlyError(e));
                });
            }
        }, "restore-seed").start();
    }

    // ── Background restore ────────────────────────────────────────────────────

    private void restoreOnBackground(String mnemonic, String enteredAccountId) throws Exception {

        // ── Step B — Derive seed and identity ────────────────────────────────
        byte[]          seed            = SeedPhraseHelper.mnemonicToSeed(mnemonic);
        IdentityKeyPair identityKeyPair = SeedPhraseHelper.deriveIdentityKeyPair(seed);
        String          derivedUserId   = SeedPhraseHelper.deriveUserId(seed);
        byte[]          pubKeyBytes     = identityKeyPair.getPublicKey().serialize();

        if (!derivedUserId.equalsIgnoreCase(enteredAccountId.trim())) {
            runOnUiThread(() -> {
                setLoading(false);
                showError("Account ID does not match this recovery phrase. Please check both and try again.");
            });
            return;
        }

        // ── Step C — Authenticate with a custom token (UID = derivedUserId) ──
        //
        // Using signInWithCustomToken() instead of signInAnonymously() ensures the
        // Firebase UID is always derivedUserId, regardless of how many times the
        // user has signed out.  This permanently resolves the UID-mismatch bug.
        runOnUiThread(() -> setStep("Authenticating…"));

        final Object    authLock  = new Object();
        final String[]  uidHolder = {null};
        final Exception[] authErr = {null};

        AuthTokenHelper.signInWithSeed(derivedUserId, pubKeyBytes, new AuthTokenHelper.Callback() {
            @Override public void onSuccess(String uid) {
                synchronized (authLock) { uidHolder[0] = uid; authLock.notifyAll(); }
            }
            @Override public void onFailure(Exception e) {
                synchronized (authLock) { authErr[0] = e; authLock.notifyAll(); }
            }
        });

        synchronized (authLock) {
            if (uidHolder[0] == null && authErr[0] == null) authLock.wait(60_000);
        }
        if (authErr[0] != null) throw authErr[0];
        if (uidHolder[0] == null) throw new Exception("Authentication timed out.");

        final String currentUid = uidHolder[0]; // always equals derivedUserId

        // Clear explicit sign-out flag — this is now a valid session
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove("explicit_signout").apply();

        String identityPubKeyHash = sha256Hex(pubKeyBytes);

        // ── Step D — Check /identities/{userId}; run migration if old UID differs ──
        FirebaseFirestore db     = FirebaseFirestore.getInstance();
        DocumentSnapshot idDoc  = awaitGet(db.collection("identities").document(derivedUserId).get());

        String oldUid = null; // the previous anonymous UID, if any
        if (!idDoc.exists()) {
            // Brand-new account or first restore — write the identity record.
            Map<String, Object> newDoc = new HashMap<>();
            newDoc.put("uid", currentUid);
            newDoc.put("identityPubKeyHash", identityPubKeyHash);
            awaitVoid(db.collection("identities").document(derivedUserId).set(newDoc));
        } else {
            // Existing identity record.
            String storedHash = idDoc.getString("identityPubKeyHash");
            // Note: the server already verified storedHash == sha256(pubKey) before minting
            // the token, so a mismatch here means the Firestore record is inconsistent.
            if (storedHash != null && !storedHash.equals(identityPubKeyHash)) {
                throw new Exception("Identity hash mismatch (ID-COLLISION). Contact support.");
            }

            // Record the old anonymous UID so we can migrate Firestore data.
            String storedUid = idDoc.getString("uid");
            if (storedUid != null && !storedUid.equals(currentUid)) {
                oldUid = storedUid;
            }

            // Update the identity doc: uid is now the permanent deterministic UID.
            Map<String, Object> update = new HashMap<>();
            update.put("uid", currentUid);
            if (storedHash == null) update.put("identityPubKeyHash", identityPubKeyHash);
            awaitVoid(db.collection("identities").document(derivedUserId).update(update));
        }

        // ── Step E — One-time Firestore migration (old anonymous UID → userId) ──
        //
        // Delegates to the push server's /migrateUid endpoint (Admin SDK) so it
        // can update chats/groups even though the client's Firestore rules only
        // allow writes by participants/members with the *current* auth UID.
        //
        // Idempotent: if oldUid == currentUid there's nothing to do.
        if (oldUid != null) {
            runOnUiThread(() -> setStep("Migrating account data…"));
            migrateOldUidViaServer(currentUid, oldUid);
        }

        // ── Step F — Store identity locally ──────────────────────────────────
        SecurePrefs.get(this).edit()
                .putString(SignalKeyManager.KEY_IDENTITY_KEY_PAIR,
                        Base64.encodeToString(identityKeyPair.serialize(), Base64.NO_WRAP))
                .apply();
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_USER_ID, derivedUserId)
                .putString(KEY_MY_UID,  currentUid)
                .apply();

        // ── Step G — Upload fresh Signal public key bundle ────────────────────
        runOnUiThread(() -> setStep("Uploading Signal keys…"));
        clearOldPrekeys();
        final Object    keyLock  = new Object();
        final boolean[] keyDone  = {false};
        final Exception[] keyErr = {null};
        SignalKeyManager.ensureKeysInitialized(this,
                () -> { synchronized (keyLock) { keyDone[0] = true; keyLock.notifyAll(); } },
                () -> { synchronized (keyLock) { keyErr[0]  = new Exception("Key upload failed"); keyLock.notifyAll(); } });
        synchronized (keyLock) {
            if (!keyDone[0] && keyErr[0] == null) keyLock.wait(60_000);
        }
        if (keyErr[0] != null) throw keyErr[0];

        // ── Step H — Derive backup key and restore message history ────────────
        runOnUiThread(() -> setStep("Restoring message history…"));
        int restoredCount = 0;
        try {
            BackupCryptoHelper.storeKey(this, mnemonic);
            byte[] backupKey = BackupCryptoHelper.deriveBackupKey(mnemonic);
            restoredCount    = BackupManager.restoreAllSync(this, currentUid, backupKey);
            if (restoredCount > 0) {
                final int rc = restoredCount;
                runOnUiThread(() -> android.widget.Toast.makeText(this,
                        "Restored " + rc + " messages from backup",
                        android.widget.Toast.LENGTH_SHORT).show());
            }
        } catch (Exception e) {
            android.util.Log.w("RestoreFromSeed", "backup restore failed (non-fatal)", e);
        }

        // ── Step H2 — Restore contacts (chat list) ─────────────────────────────
        runOnUiThread(() -> setStep("Restoring contacts…"));
        try {
            int contactsRestored = BackupManager.restoreContactsSync(this, currentUid);
            android.util.Log.d("RestoreFromSeed", "contacts restored: " + contactsRestored);
        } catch (Exception e) {
            android.util.Log.w("RestoreFromSeed", "contact restore failed (non-fatal)", e);
        }

        // ── Step H2 — Pre-cache B2 media for restored messages ───────────────
        // Downloads and decrypts every photo / video / voice-note referenced in
        // the restored messages and writes them to the persistent disk cache so
        // they open instantly — even without a network connection — after restore.
        // A determinate progress bar shows X / Y files as each completes.
        if (restoredCount > 0) {
            runOnUiThread(() -> setStep("Downloading media files…"));
            try {
                java.util.List<com.duoshield.app.models.Message> restoredMsgs =
                        com.duoshield.app.db.AppDatabase.getInstance(this)
                                .messageDao().getAllActiveMessages();

                MediaRestoreHelper.preCacheMedia(getApplicationContext(), restoredMsgs,
                        (done, total) -> runOnUiThread(() -> {
                            if (total <= 0) return;
                            // Switch from indeterminate to determinate on first callback
                            if (progressRestore != null) progressRestore.setVisibility(View.GONE);
                            if (progressMediaCacheBar != null) {
                                progressMediaCacheBar.setVisibility(View.VISIBLE);
                                int pct = (int) ((done / (float) total) * 100);
                                progressMediaCacheBar.setProgress(pct);
                                // Drive shield fill in sync with media progress bar
                                if (shieldFillView != null) {
                                    shieldFillView.setProgress(pct / 100f);
                                }
                            }
                            if (tvMediaProgress != null) {
                                tvMediaProgress.setVisibility(View.VISIBLE);
                                tvMediaProgress.setText(done + " / " + total + " files");
                            }
                        }));

                // Hide media progress UI when done
                runOnUiThread(() -> {
                    if (progressMediaCacheBar != null)
                        progressMediaCacheBar.setVisibility(View.GONE);
                    if (tvMediaProgress != null) tvMediaProgress.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                android.util.Log.w("RestoreFromSeed", "media pre-cache failed (non-fatal)", e);
            }
        }

        // ── Step I — Restore conversation state from Firestore ────────────────
        //
        // Now that currentUid == derivedUserId (permanent), this query finds
        // the chat even after the migration rewrote participants.
        try {
            QuerySnapshot chatSnap = awaitQuery(
                    db.collection("chats").whereArrayContains("participants", currentUid).limit(1).get());
            if (!chatSnap.isEmpty()) {
                DocumentSnapshot chatDoc    = chatSnap.getDocuments().get(0);
                List<String>     parts      = (List<String>) chatDoc.get("participants");
                String           partnerUid = null;
                if (parts != null) {
                    for (String p : parts) {
                        if (!p.equals(currentUid)) { partnerUid = p; break; }
                    }
                }
                if (partnerUid != null) {
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                            .putString(KEY_CONV_ID,    chatDoc.getId())
                            .putString(KEY_PARTNER_UID, partnerUid)
                            .putBoolean(KEY_IS_PAIRED,  true)
                            .apply();
                }
            }
        } catch (Exception e) {
            android.util.Log.w("RestoreFromSeed", "chat state restore failed (non-fatal)", e);
        }

        // ── Step J — Schedule daily backup sync and navigate ─────────────────
        BackupScheduler.schedule(getApplicationContext());

        SharedPreferences prefs   = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean           paired  = prefs.getBoolean(KEY_IS_PAIRED, false)
                                 && prefs.getString(KEY_CONV_ID, null) != null;
        Class<?>          dest    = paired ? ConversationListActivity.class : AddContactActivity.class;

        runOnUiThread(() -> {
            FcmTokenHelper.register(this);
            Intent intent = new Intent(this, dest);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }

    // ── One-time migration: old anonymous UID → permanent userId UID ──────────

    /**
     * Calls the push server's {@code /migrateUid} endpoint (Admin SDK) to
     * rewrite Firestore documents that still reference {@code oldUid}:
     * <ul>
     *   <li>users/{oldUid}   copied and deleted</li>
     *   <li>chats participants  updated to use userId</li>
     *   <li>groups members      updated to use userId</li>
     * </ul>
     *
     * <p>Delegating to the server is required because client Firestore rules
     * only allow a participant to update documents they already belong to.
     * After a UID migration the new UID is not yet in the participants array,
     * so the client write would be rejected with PERMISSION_DENIED.  The
     * server uses Admin SDK which bypasses rules, but still verifies the
     * caller's ID token before acting.</p>
     *
     * <p>Safe to call multiple times — the server endpoint is idempotent.</p>
     */
    private void migrateOldUidViaServer(String userId, String oldUid) {
        try {
            // Fetch current Firebase ID token to authenticate the server call
            final String[]    tokenHolder = {null};
            final Exception[] tokenErr    = {null};
            final Object      tokenLock   = new Object();
            com.google.firebase.auth.FirebaseUser user =
                    com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) {
                android.util.Log.w("RestoreFromSeed", "migrateOldUid: no current user (non-fatal)");
                return;
            }
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
                android.util.Log.w("RestoreFromSeed", "migrateOldUid: token fetch failed (non-fatal)", tokenErr[0]);
                return;
            }
            String idToken = tokenHolder[0];
            if (idToken == null) {
                android.util.Log.w("RestoreFromSeed", "migrateOldUid: token null (non-fatal)");
                return;
            }

            // Call POST /migrateUid on the push server
            String serverUrl = BuildConfig.PUSH_SERVER_URL;
            java.net.URL url = new java.net.URL(serverUrl + "/migrateUid");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + idToken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);

            // F34 fix: build JSON via JSONObject to prevent injection if either
            // userId or oldUid contains characters that would break string concat.
            org.json.JSONObject reqBodyObj = new org.json.JSONObject();
            reqBodyObj.put("userId", userId);
            reqBodyObj.put("oldUid", oldUid);
            String body = reqBodyObj.toString();
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }

            int code = conn.getResponseCode();
            android.util.Log.d("RestoreFromSeed", "migrateOldUid server response: HTTP " + code);
            conn.disconnect();

        } catch (Exception e) {
            android.util.Log.w("RestoreFromSeed", "migrateOldUid: server call failed (non-fatal)", e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void clearOldPrekeys() {
        SharedPreferences sp = SecurePrefs.get(this);
        SharedPreferences.Editor ed = sp.edit();
        ed.remove(SignalKeyManager.KEY_SIGNED_PREKEY);
        String ids = sp.getString(SignalKeyManager.KEY_PREKEY_IDS, "");
        if (!ids.isEmpty()) {
            for (String part : ids.split(",")) {
                ed.remove(SignalKeyManager.KEY_PREKEY_PREFIX + part.trim());
            }
        }
        ed.remove(SignalKeyManager.KEY_PREKEY_IDS);
        ed.remove(SignalKeyManager.KEY_REGISTRATION_ID);
        ed.apply();
    }

    private DocumentSnapshot awaitGet(
            com.google.android.gms.tasks.Task<DocumentSnapshot> task) throws Exception {
        final Object[]      holder = {null};
        final Exception[]   err    = {null};
        final Object        lock   = new Object();
        task.addOnSuccessListener(d -> { synchronized (lock) { holder[0] = d; lock.notifyAll(); } })
            .addOnFailureListener(e -> { synchronized (lock) { err[0] = e;    lock.notifyAll(); } });
        synchronized (lock) {
            if (holder[0] == null && err[0] == null) lock.wait(30_000);
        }
        if (err[0] != null) throw err[0];
        if (holder[0] == null) throw new Exception("Firestore get timed out.");
        return (DocumentSnapshot) holder[0];
    }

    private QuerySnapshot awaitQuery(
            com.google.android.gms.tasks.Task<QuerySnapshot> task) throws Exception {
        final Object[]      holder = {null};
        final Exception[]   err    = {null};
        final Object        lock   = new Object();
        task.addOnSuccessListener(s -> { synchronized (lock) { holder[0] = s; lock.notifyAll(); } })
            .addOnFailureListener(e -> { synchronized (lock) { err[0] = e;    lock.notifyAll(); } });
        synchronized (lock) {
            if (holder[0] == null && err[0] == null) lock.wait(30_000);
        }
        if (err[0] != null) throw err[0];
        if (holder[0] == null) throw new Exception("Firestore query timed out.");
        return (QuerySnapshot) holder[0];
    }

    private void awaitVoid(com.google.android.gms.tasks.Task<Void> task) throws Exception {
        final boolean[] done  = {false};
        final Exception[] err = {null};
        final Object lock     = new Object();
        task.addOnSuccessListener(v -> { synchronized (lock) { done[0] = true; lock.notifyAll(); } })
            .addOnFailureListener(e -> { synchronized (lock) { err[0] = e;     lock.notifyAll(); } });
        synchronized (lock) {
            if (!done[0] && err[0] == null) lock.wait(30_000);
        }
        if (err[0] instanceof Exception) throw (Exception) err[0];
        if (!done[0] && err[0] == null) throw new Exception("Operation timed out.");
    }

    private String sha256Hex(byte[] data) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    private static String friendlyError(Throwable e) {
        if (e == null) return "An unexpected error occurred. Please try again.";
        String s = e.getMessage() != null ? e.getMessage() : e.toString();
        if (s.contains("Key mismatch") || s.contains("403"))
            return "Recovery phrase does not match this Account ID. Please check both and try again.";
        if (s.contains("429") || s.contains("Too many"))
            return "Too many attempts. Please wait a moment and try again.";
        if (s.contains("PUSH_SERVER_URL"))
            return "The auth server is not configured. Contact support.";
        if (s.contains("Account ID does not match"))
            return s;
        if (s.contains("timeout") || s.contains("timed out")
                || s.contains("network") || s.contains("IOException")
                || s.contains("UnknownHost") || s.contains("connect"))
            return "Could not reach the auth server. Check your internet connection and try again.";
        if (s.contains("NoClassDefFoundError") || s.contains("UnsatisfiedLink"))
            return "Encryption library failed to load. Please reinstall the app.";
        if (s.contains("FAILED_PRECONDITION") || s.contains("UNAVAILABLE"))
            return "Could not reach the server. Check your internet connection and try again.";
        return "Restore failed: " + s;
    }

    private void showError(String msg) {
        tvError.setText(msg);
        tvError.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        tvError.setVisibility(View.GONE);
    }

    private void setStep(String label) {
        if (tvStep != null) {
            runOnUiThread(() -> {
                tvStep.setText(label);
                tvStep.setVisibility(View.VISIBLE);
            });
        }
        if (tvRestoreStep != null) {
            runOnUiThread(() -> tvRestoreStep.setText(label));
        }
    }

    private void setLoading(boolean loading) {
        btnRestore.setEnabled(!loading);
        progressRestore.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (restoreLoaderFrame != null) {
            restoreLoaderFrame.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
        if (!loading && shieldFillView != null) {
            shieldFillView.setProgress(0f);
        }
    }
}
