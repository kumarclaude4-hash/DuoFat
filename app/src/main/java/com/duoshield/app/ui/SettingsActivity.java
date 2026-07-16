package com.duoshield.app.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.widget.Toolbar;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.duoshield.app.BaseActivity;
import com.duoshield.app.R;
import com.duoshield.app.call.TurnBandwidthTracker;
import com.duoshield.app.util.B2StorageHelper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends BaseActivity {

    private SharedPreferences prefs;
    private ImageView         ivProfilePhoto;
    private TextView          tvProfileDisplayName;

    private ActivityResultLauncher<String> pickPhotoLauncher;

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.duoshield.app.util.UiModeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);

        // Register the profile photo picker before setContentView / super lifecycle
        pickPhotoLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadProfilePhoto(uri); });

        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.settingsToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);

        // ── Profile header ────────────────────────────────────────────────────
        ivProfilePhoto       = findViewById(R.id.ivProfilePhoto);
        tvProfileDisplayName = findViewById(R.id.tvProfileDisplayName);
        View btnChangePhoto  = findViewById(R.id.btnChangePhoto);
        View layoutProfileNameRow = findViewById(R.id.layoutProfileNameRow);

        // Populate name
        String myName = prefs.getString("my_display_name", null);
        if (tvProfileDisplayName != null)
            tvProfileDisplayName.setText(myName != null && !myName.isEmpty() ? myName : "—");

        // Compat display name row (hidden, but still wired)
        TextView tvDisplayName = findViewById(R.id.tvDisplayName);
        if (tvDisplayName != null) {
            String name = prefs.getString("my_display_name", null);
            tvDisplayName.setText(name != null && !name.isEmpty() ? name : "—");
        }

        // Load profile photo — local disk cache first (instant, no network),
        // then fall back to authenticated B2 download.
        loadProfilePhoto();

        if (layoutProfileNameRow != null)
            layoutProfileNameRow.setOnClickListener(v -> showNameEditDialog());
        if (btnChangePhoto != null)
            btnChangePhoto.setOnClickListener(v -> pickPhotoLauncher.launch("image/*"));
        if (ivProfilePhoto != null)
            ivProfilePhoto.setOnClickListener(v -> openProfilePhotoViewer());

        // ── Navigation rows ───────────────────────────────────────────────────
        LinearLayout rowSecurityPrivacy = findViewById(R.id.rowSecurityPrivacy);
        if (rowSecurityPrivacy != null) {
            rowSecurityPrivacy.setOnClickListener(v ->
                startActivity(new Intent(this, SecurityPrivacySettingsActivity.class)));
        }

        LinearLayout rowAppearanceNotifications = findViewById(R.id.rowAppearanceNotifications);
        if (rowAppearanceNotifications != null) {
            rowAppearanceNotifications.setOnClickListener(v ->
                startActivity(new Intent(this, AppearanceNotificationsSettingsActivity.class)));
        }

        LinearLayout rowBackupStorage = findViewById(R.id.rowBackupStorage);
        if (rowBackupStorage != null) {
            rowBackupStorage.setOnClickListener(v ->
                startActivity(new Intent(this, BackupStorageSettingsActivity.class)));
        }

        LinearLayout rowDangerZone = findViewById(R.id.rowDangerZone);
        if (rowDangerZone != null) {
            rowDangerZone.setOnClickListener(v ->
                startActivity(new Intent(this, DangerZoneSettingsActivity.class)));
        }

        // ── Call data usage row ───────────────────────────────────────────────
        LinearLayout rowCallDataUsage = findViewById(R.id.rowCallDataUsage);
        TextView     tvCallDataSubtitle = findViewById(R.id.tvCallDataSubtitle);

        TurnBandwidthTracker tracker = TurnBandwidthTracker.get(this);
        String subtitleText = String.format(Locale.US, "%.2f GB / 100 GB used this month",
                tracker.getUsedGb());
        if (tvCallDataSubtitle != null) tvCallDataSubtitle.setText(subtitleText);

        if (rowCallDataUsage != null) {
            rowCallDataUsage.setOnClickListener(v -> showCallDataDialog());
        }
    }

    // ── Call data usage dialog ────────────────────────────────────────────────

    private void showCallDataDialog() {
        TurnBandwidthTracker tracker = TurnBandwidthTracker.get(this);
        float usedGb      = tracker.getUsedGb();
        float remainingGb = (float) (tracker.getRemainingBytes() / (1024.0 * 1024.0 * 1024.0));
        int   percent     = tracker.getUsedPercent();

        // Compute next reset date (1st of next month)
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.add(Calendar.MONTH, 1);
        String resetDate = new SimpleDateFormat("MMMM d, yyyy", Locale.US).format(cal.getTime());

        // Build the dialog content view
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int dp = (int) getResources().getDisplayMetrics().density;
        root.setPadding(24 * dp, 20 * dp, 24 * dp, 8 * dp);

        // Progress bar
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(percent);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 10 * dp));
        // Tint: green → amber → red
        int barColor;
        if (percent < 70)      barColor = 0xFF00C9E0; // cyan accent
        else if (percent < 90) barColor = 0xFFFFB300; // amber
        else                   barColor = 0xFFFF5252; // red
        bar.getProgressDrawable().setColorFilter(
                barColor, android.graphics.PorterDuff.Mode.SRC_IN);
        root.addView(bar);

        // Percentage label
        TextView tvPct = new TextView(this);
        tvPct.setText(String.format(Locale.US, "%d%% of 100 GB used", percent));
        tvPct.setTextColor(barColor);
        tvPct.setTextSize(13f);
        LinearLayout.LayoutParams pctParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pctParams.topMargin = 8 * dp;
        tvPct.setLayoutParams(pctParams);
        root.addView(tvPct);

        // Stats rows
        root.addView(makeDialogRow("Used this month",
                String.format(Locale.US, "%.2f GB", usedGb), dp));
        root.addView(makeDialogRow("Remaining",
                String.format(Locale.US, "%.2f GB", remainingGb), dp));
        root.addView(makeDialogRow("Resets on", resetDate, dp));

        // Note
        TextView tvNote = new TextView(this);
        tvNote.setText("Only calls relayed through TURN count toward this limit. "
                + "Direct peer-to-peer calls are free and not included.");
        tvNote.setTextSize(11f);
        tvNote.setTextColor(0xFF888888);
        tvNote.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        noteParams.topMargin = 16 * dp;
        tvNote.setLayoutParams(noteParams);
        root.addView(tvNote);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Call Data Usage")
                .setView(root)
                .setPositiveButton("OK", null)
                .show();
    }

    /** Helper: a two-column row for the call data dialog. */
    private LinearLayout makeDialogRow(String label, String value, int dp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = 12 * dp;
        row.setLayoutParams(rowParams);

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextColor(0xFFAAAAAA);
        tvLabel.setTextSize(13f);
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvLabel);

        TextView tvValue = new TextView(this);
        tvValue.setText(value);
        tvValue.setTextColor(Color.WHITE);
        tvValue.setTextSize(13f);
        tvValue.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        row.addView(tvValue);

        return row;
    }

    // ── Profile name edit ─────────────────────────────────────────────────────

    private void showNameEditDialog() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        et.setHint("Your display name");
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        String current = prefs.getString("my_display_name", "");
        et.setText(current);
        et.setSelection(et.getText().length());

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        container.setPadding(pad * 2, pad, pad * 2, 0);
        container.addView(et);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Change Display Name")
                .setView(container)
                .setPositiveButton("Save", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Name cannot be empty.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.edit().putString("my_display_name", name).apply();
                    if (tvProfileDisplayName != null) tvProfileDisplayName.setText(name);
                    // Also update the hidden legacy tvDisplayName if present
                    TextView legacy = findViewById(R.id.tvDisplayName);
                    if (legacy != null) legacy.setText(name);
                    // Persist to Firestore so other users/devices see the new name
                    saveNameToFirestore(name);
                    Toast.makeText(this, "Name updated.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void propagatePhotoToConversations(String myUid, String photoUrl) {
        FirebaseFirestore.getInstance()
                .collection("chats")
                .whereArrayContains("participants", myUid)
                .get()
                .addOnSuccessListener(snap -> {
                    for (com.google.firebase.firestore.DocumentSnapshot doc : snap.getDocuments()) {
                        java.util.List<String> participants =
                                (java.util.List<String>) doc.get("participants");
                        if (participants == null) continue;
                        String partnerUid = null;
                        for (String p : participants) {
                            if (p != null && !p.equals(myUid)) { partnerUid = p; break; }
                        }
                        if (partnerUid == null) continue;
                        doc.getReference()
                           .update("partnerPhotoUrl_" + partnerUid, photoUrl)
                           .addOnFailureListener(e ->
                               android.util.Log.w("Settings", "propagatePhoto non-critical: " + e.getMessage()));
                    }
                })
                .addOnFailureListener(e ->
                        android.util.Log.w("Settings", "propagatePhoto query failed (non-critical): " + e.getMessage()));
    }

    private void saveNameToFirestore(String name) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .set(Collections.singletonMap("displayName", name),
                        com.google.firebase.firestore.SetOptions.merge())
                .addOnFailureListener(e ->
                        android.util.Log.w("Settings", "displayName Firestore write failed", e));
    }

    // ── Profile photo upload (B2) ─────────────────────────────────────────────

    private void uploadProfilePhoto(Uri uri) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Not signed in.", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Uploading photo…", Toast.LENGTH_SHORT).show();
        bgExecutor.execute(() -> {
            try {
                byte[] plain = readUriBytes(uri);
                if (plain == null || plain.length == 0) throw new Exception("Empty file");
                String objectKey = "avatars/" + user.getUid() + "_" + System.currentTimeMillis() + ".jpg";
                String b2Path = B2StorageHelper.uploadFile(plain, objectKey, "image/jpeg", null);
                runOnUiThread(() -> onPhotoUploaded(b2Path, plain, user));
            } catch (Exception e) {
                android.util.Log.e("Settings", "Photo upload failed", e);
                runOnUiThread(() ->
                        Toast.makeText(this, "Upload failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** Loads the user's own profile photo: local disk cache → B2 download. */
    private void loadProfilePhoto() {
        if (ivProfilePhoto == null) return;
        // 1. Instant local load from disk cache (written on every successful upload).
        java.io.File localAvatar = new java.io.File(getFilesDir(), "own_avatar.jpg");
        if (localAvatar.exists()) {
            Glide.with(this).load(localAvatar)
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person)
                    .transform(new CircleCrop())
                    .into(ivProfilePhoto);
            return;
        }
        // 2. Fall back to authenticated B2 download (first launch after install).
        String photoUrl = prefs.getString("my_photo_url", null);
        if (photoUrl != null && !photoUrl.isEmpty()) {
            com.duoshield.app.util.GlideHelper.loadAvatar(this, photoUrl, ivProfilePhoto);
        }
    }

    /**
     * Persists the uploaded avatar bytes to disk so loadProfilePhoto() can serve
     * them instantly on every subsequent app open without a B2 round-trip.
     */
    private void saveOwnAvatarToDisk(byte[] bytes) {
        bgExecutor.execute(() -> {
            try {
                java.io.File f = new java.io.File(getFilesDir(), "own_avatar.jpg");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                    fos.write(bytes);
                }
            } catch (Exception e) {
                android.util.Log.w("Settings", "Failed to cache own avatar to disk", e);
            }
        });
    }

    private void onPhotoUploaded(String b2Path, byte[] plainBytes, FirebaseUser user) {
        prefs.edit().putString("my_photo_url", b2Path).apply();
        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .set(Collections.singletonMap("photoUrl", b2Path),
                        com.google.firebase.firestore.SetOptions.merge());
        propagatePhotoToConversations(user.getUid(), b2Path);
        // Persist to disk so future app opens load without a B2 round-trip.
        saveOwnAvatarToDisk(plainBytes);
        if (ivProfilePhoto != null && !isDestroyed() && !isFinishing()) {
            // Render straight from the bytes we just uploaded instead of re-fetching
            // b2Path from B2 — a re-fetch immediately after upload could race the
            // storage backend's read-after-write consistency (or a fronting CDN/cache)
            // and silently fail or return the previous object, which is what made the
            // new photo "update for other users" (who load it later, once it has
            // settled) but never visibly refresh here in the uploader's own app.
            Glide.with(this).load(plainBytes)
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person)
                    .transform(new CircleCrop())
                    .into(ivProfilePhoto);
        }
        Toast.makeText(this, "Photo updated!", Toast.LENGTH_SHORT).show();
    }

    private void openProfilePhotoViewer() {
        // Prefer the locally cached file — instant load, no network needed.
        java.io.File localFile = new java.io.File(getFilesDir(), "own_avatar.jpg");
        String urlToShow;
        if (localFile.exists() && localFile.length() > 0) {
            urlToShow = localFile.toURI().toString(); // file:///data/user/0/.../own_avatar.jpg
        } else {
            urlToShow = prefs.getString("my_photo_url", null);
        }
        if (urlToShow == null || urlToShow.isEmpty()) {
            Toast.makeText(this, "No profile photo set yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, com.duoshield.app.FullScreenImageActivity.class);
        i.putExtra(com.duoshield.app.FullScreenImageActivity.EXTRA_URL, urlToShow);
        // No media key — avatars are plain JPEGs (not AES-encrypted).
        startActivity(i);
    }

    private byte[] readUriBytes(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) return null;
            byte[] buf = new byte[32_768];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            android.util.Log.e("Settings", "readUriBytes failed", e);
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!bgExecutor.isShutdown()) bgExecutor.shutdown();
    }
}
