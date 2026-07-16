package com.duoshield.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;

/**
 * Full-screen image preview before sending.
 * Receives: "uri" String extra (Uri of the image to preview).
 * Returns:  RESULT_OK with "uri" + "caption" String extras.
 */
public class MediaSendPreviewActivity extends AppCompatActivity {

    public static final String EXTRA_URI     = "uri";
    public static final String EXTRA_CAPTION = "caption";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_media_send_preview);

        String uriStr = getIntent().getStringExtra(EXTRA_URI);
        if (uriStr == null) { finish(); return; }

        Uri uri = Uri.parse(uriStr);

        ImageView previewImage = findViewById(R.id.previewImage);
        EditText  captionInput = findViewById(R.id.captionInput);
        ImageButton btnClose   = findViewById(R.id.btnClose);
        FrameLayout btnSendWrap = findViewById(R.id.btnSend).getParent() instanceof FrameLayout
                ? (FrameLayout) findViewById(R.id.btnSend).getParent()
                : null;
        View btnSend = findViewById(R.id.btnSend);

        captionInput.setHintTextColor(0x88FFFFFF);

        Glide.with(this).load(uri).into(previewImage);

        btnClose.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });

        View.OnClickListener sendListener = v -> {
            String caption = captionInput.getText() != null
                    ? captionInput.getText().toString().trim() : "";
            Intent result = new Intent();
            result.putExtra(EXTRA_URI, uriStr);
            result.putExtra(EXTRA_CAPTION, caption);
            setResult(RESULT_OK, result);
            finish();
        };

        btnSend.setOnClickListener(sendListener);
        if (btnSendWrap != null) btnSendWrap.setOnClickListener(sendListener);
    }
}
