package com.duoshield.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import com.duoshield.app.BaseActivity;
import com.duoshield.app.ChatMediaActivity;
import com.duoshield.app.R;
import com.duoshield.app.contacts.ContactManager;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.WriterException;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanIntentResult;
import com.journeyapps.barcodescanner.ScanOptions;
import java.io.InputStream;

/**
 * "New Message" screen — adds a contact by Account ID, QR camera scan,
 * gallery QR image, or deep-link (duoshield://add/{id}).
 *
 * Features:
 *  - Tab 0 "Enter Account ID": type/paste ID; Copy + QR + Share own ID buttons.
 *  - Tab 1 "Scan QR Code": camera scan OR pick from gallery.
 *  - Clipboard auto-paste on open when clipboard holds a valid ID.
 *  - Deep-link handling: duoshield://add/{userId}.
 */
public class AddContactActivity extends BaseActivity {

    private ContactManager              contactManager;
    private String                      myUserId;

    private TextInputEditText           etPartnerUserId;
    private LinearLayout                tabEnterIdContent;
    private LinearLayout                tabScanQrContent;
    private LinearProgressIndicator     progressPairing;
    private MaterialButton              btnPair;
    private MaterialButton              btnScanQr;
    private MaterialButton              btnGallery;

    private final ActivityResultLauncher<ScanOptions> scanLauncher =
            registerForActivityResult(new ScanContract(), this::onScanResult);

    private final ActivityResultLauncher<Intent> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    this::onGalleryResult);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pairing);

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle("New Message");
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            }
            toolbar.setNavigationOnClickListener(v -> navigateBack());
        }

        contactManager    = new ContactManager(this);
        etPartnerUserId   = findViewById(R.id.etPartnerCode);
        tabEnterIdContent = findViewById(R.id.tabEnterIdContent);
        tabScanQrContent  = findViewById(R.id.tabScanQrContent);
        progressPairing   = findViewById(R.id.progressPairing);
        btnPair           = findViewById(R.id.btnPair);
        btnScanQr         = findViewById(R.id.btnScanQr);
        btnGallery        = findViewById(R.id.btnGallery);

        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        myUserId = prefs.getString("my_user_id", null);

        android.widget.TextView tvMyCode = findViewById(R.id.tvMyCode);
        if (tvMyCode != null) {
            tvMyCode.setText(myUserId != null ? myUserId : "—");
        }

        // Tab switching
        TabLayout tabLayout = findViewById(R.id.tabLayout);
        if (tabLayout != null) {
            tabLayout.addTab(tabLayout.newTab().setText("Enter Account ID"));
            tabLayout.addTab(tabLayout.newTab().setText("Scan QR Code"));
            tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override public void onTabSelected(TabLayout.Tab tab) { showTab(tab.getPosition()); }
                @Override public void onTabUnselected(TabLayout.Tab tab) {}
                @Override public void onTabReselected(TabLayout.Tab tab) {}
            });
        }

        // My ID buttons
        MaterialButton btnCopy = findViewById(R.id.btnCopyCode);
        if (btnCopy   != null) btnCopy.setOnClickListener(v -> copyMyId());

        MaterialButton btnShowQr = findViewById(R.id.btnShowQr);
        if (btnShowQr != null) btnShowQr.setOnClickListener(v -> showQrDialog());

        MaterialButton btnShare = findViewById(R.id.btnShare);
        if (btnShare  != null) btnShare.setOnClickListener(v -> shareMyId());

        // Scan tab buttons
        if (btnScanQr  != null) btnScanQr.setOnClickListener(v -> launchScanner());
        if (btnGallery != null) btnGallery.setOnClickListener(v -> launchGallery());

        // Next / Add Contact
        if (btnPair != null) {
            btnPair.setOnClickListener(v -> {
                String id = readPartnerInput();
                if (id.isEmpty()) {
                    Toast.makeText(this, "Enter your contact's Account ID.", Toast.LENGTH_SHORT).show();
                    return;
                }
                startAddContact(id);
            });
        }

        // Auto-paste clipboard if it holds a valid account ID
        tryPasteFromClipboard();

        // Handle deep-link: duoshield://add/<userId>
        handleDeepLink(getIntent());
    }

    // ── Deep link ─────────────────────────────────────────────────────────────

    private void handleDeepLink(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data == null) return;
        // scheme: duoshield, host: add, path: /<userId>
        String path = data.getPath();
        if (path != null && !path.isEmpty()) {
            String userId = path.startsWith("/") ? path.substring(1) : path;
            if (!userId.isEmpty() && etPartnerUserId != null) {
                etPartnerUserId.setText(userId);
                Toast.makeText(this, "Account ID loaded from link", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ── Clipboard auto-paste ──────────────────────────────────────────────────

    private void tryPasteFromClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence text = clip.getItemAt(0).getText();
            if (text == null) return;
            String s = text.toString().trim();
            // Match new XXXXX-XXXXX-XXX format (unambiguous base32)
            if (s.matches("[23456789A-HJ-NP-Z]{5}-[23456789A-HJ-NP-Z]{5}-[23456789A-HJ-NP-Z]{3}")) {
                if (etPartnerUserId != null &&
                        (etPartnerUserId.getText() == null
                                || etPartnerUserId.getText().toString().isEmpty())) {
                    etPartnerUserId.setText(s);
                    Toast.makeText(this, "Account ID pasted from clipboard", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception ignored) {}
    }

    // ── Tabs ──────────────────────────────────────────────────────────────────

    private void showTab(int position) {
        if (tabEnterIdContent == null || tabScanQrContent == null) return;
        tabEnterIdContent.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
        tabScanQrContent .setVisibility(position == 1 ? View.VISIBLE : View.GONE);
    }

    // ── My ID actions ─────────────────────────────────────────────────────────

    private void copyMyId() {
        if (myUserId == null || myUserId.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("user_id", myUserId));
        Toast.makeText(this, "Account ID copied!", Toast.LENGTH_SHORT).show();
    }

    private void shareMyId() {
        if (myUserId == null || myUserId.isEmpty() || "—".equals(myUserId)) {
            Toast.makeText(this, "Your Account ID isn't ready yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        String deepLink   = "duoshield://add/" + myUserId;
        String shareText  = "Add me on DuoShield!\n\n"
                + "Account ID: " + myUserId + "\n\n"
                + "Tap to open: " + deepLink;
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, shareText);
        startActivity(Intent.createChooser(shareIntent, "Share Account ID via…"));
    }

    private void showQrDialog() {
        if (myUserId == null || myUserId.isEmpty() || "—".equals(myUserId)) {
            Toast.makeText(this, "Your Account ID isn't ready yet. Restart the app.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            BarcodeEncoder encoder = new BarcodeEncoder();
            Bitmap bitmap = encoder.encodeBitmap(myUserId, BarcodeFormat.QR_CODE, 600, 600);

            int dp16 = dp(16), dp8 = dp(8);

            LinearLayout container = new LinearLayout(this);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setPadding(dp16, dp8, dp16, dp8);

            android.widget.TextView subtitle = new android.widget.TextView(this);
            subtitle.setText("Share your QR code or Account ID with your contact.");
            subtitle.setTextSize(13f);
            subtitle.setTextColor(0xFFAAAAAA);
            subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
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

            android.widget.TextView idLabel = new android.widget.TextView(this);
            idLabel.setText(myUserId);
            idLabel.setTextSize(15f);
            idLabel.setTypeface(android.graphics.Typeface.MONOSPACE);
            idLabel.setTextColor(0xFFE6EDF3);
            idLabel.setGravity(Gravity.CENTER_HORIZONTAL);
            idLabel.setLetterSpacing(0.06f);
            idLabel.setPadding(0, dp8, 0, 0);
            container.addView(idLabel);

            new AlertDialog.Builder(this)
                    .setTitle("Your QR Code")
                    .setView(container)
                    .setPositiveButton("Done", null)
                    .setNeutralButton("Share", (d, w) -> shareMyId())
                    .setNegativeButton("Copy ID", (d, w) -> copyMyId())
                    .show();

        } catch (WriterException e) {
            Toast.makeText(this, "Could not generate QR code.", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Camera QR scan ────────────────────────────────────────────────────────

    private void launchScanner() {
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt("Scan your contact's DuoShield QR code");
        options.setBeepEnabled(false);
        options.setBarcodeImageEnabled(false);
        options.setOrientationLocked(false);
        scanLauncher.launch(options);
    }

    private void onScanResult(ScanIntentResult result) {
        if (result == null || result.getContents() == null) return;
        String scanned = result.getContents().trim();
        if (etPartnerUserId != null) etPartnerUserId.setText(scanned);
        TabLayout tabLayout = findViewById(R.id.tabLayout);
        if (tabLayout != null && tabLayout.getTabAt(0) != null) {
            tabLayout.selectTab(tabLayout.getTabAt(0));
        }
        startAddContact(scanned);
    }

    // ── Gallery QR scan ───────────────────────────────────────────────────────

    private void launchGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        galleryLauncher.launch(intent);
    }

    private void onGalleryResult(ActivityResult result) {
        if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
        Uri imageUri = result.getData().getData();
        if (imageUri == null) return;

        showLoading(true);
        setInputsEnabled(false);

        new Thread(() -> {
            try {
                String decoded = decodeQrFromUri(imageUri);
                runOnUiThread(() -> {
                    showLoading(false);
                    setInputsEnabled(true);
                    if (decoded == null || decoded.isEmpty()) {
                        Toast.makeText(this, "No QR code found in the selected image.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (etPartnerUserId != null) etPartnerUserId.setText(decoded);
                    TabLayout tabLayout = findViewById(R.id.tabLayout);
                    if (tabLayout != null && tabLayout.getTabAt(0) != null) {
                        tabLayout.selectTab(tabLayout.getTabAt(0));
                    }
                    startAddContact(decoded.trim());
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    showLoading(false);
                    setInputsEnabled(true);
                    Toast.makeText(this, "Could not read QR from image. Try a clearer photo.",
                            Toast.LENGTH_SHORT).show();
                });
            }
        }, "qr-gallery-decode").start();
    }

    /** Decodes a QR code from a gallery image URI using ZXing. */
    private String decodeQrFromUri(Uri uri) throws Exception {
        InputStream is = getContentResolver().openInputStream(uri);
        if (is == null) throw new Exception("Cannot open image");
        Bitmap bitmap = BitmapFactory.decodeStream(is);
        is.close();
        if (bitmap == null) throw new Exception("Cannot decode image");

        int   width  = bitmap.getWidth();
        int   height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        bitmap.recycle();

        RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
        BinaryBitmap       binary = new BinaryBitmap(new HybridBinarizer(source));
        Result             qr     = new QRCodeReader().decode(binary);
        return qr.getText();
    }

    // ── Contact creation ──────────────────────────────────────────────────────

    private String readPartnerInput() {
        if (etPartnerUserId == null || etPartnerUserId.getText() == null) return "";
        return etPartnerUserId.getText().toString().trim();
    }

    private void startAddContact(String partnerId) {
        showLoading(true);
        setInputsEnabled(false);

        contactManager.addContact(partnerId, new ContactManager.ContactCallback() {
            @Override
            public void onAdded(String chatId, String partnerUid, String partnerDisplayName) {
                runOnUiThread(() -> openChat(chatId, partnerUid));
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    showLoading(false);
                    setInputsEnabled(true);
                    Toast.makeText(AddContactActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void openChat(String chatId, String partnerUid) {
        Intent i = new Intent(this, ChatMediaActivity.class);
        i.putExtra("conversation_id", chatId);
        i.putExtra("partner_uid", partnerUid);
        startActivity(i);
        finish();
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void showLoading(boolean show) {
        if (progressPairing != null)
            progressPairing.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void setInputsEnabled(boolean enabled) {
        if (btnPair         != null) btnPair.setEnabled(enabled);
        if (btnScanQr       != null) btnScanQr.setEnabled(enabled);
        if (btnGallery      != null) btnGallery.setEnabled(enabled);
        if (etPartnerUserId != null) etPartnerUserId.setEnabled(enabled);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
