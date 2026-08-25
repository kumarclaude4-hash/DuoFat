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
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;

import java.util.ArrayList;

/**
 * Full-screen media preview before sending.
 *
 * <p>The same preview is used for one photo/video and for a multi-selection.
 * Keeping the caption on this screen is important: it makes the caption part of
 * the media message rather than a separate text message.</p>
 */
public class MediaSendPreviewActivity extends AppCompatActivity {

    public static final String EXTRA_URI     = "uri";
    public static final String EXTRA_URIS    = "uris";
    public static final String EXTRA_MEDIA_TYPE = "media_type";
    public static final String EXTRA_CAPTION = "caption";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_media_send_preview);

        Intent incoming = getIntent();
        String uriStr = incoming.getStringExtra(EXTRA_URI);
        ArrayList<String> uriStrings = incoming.getStringArrayListExtra(EXTRA_URIS);
        if (uriStrings == null || uriStrings.isEmpty()) {
            uriStrings = new ArrayList<>();
            if (uriStr != null) uriStrings.add(uriStr);
        }
        if (uriStrings.isEmpty()) { finish(); return; }

        String mediaType = incoming.getStringExtra(EXTRA_MEDIA_TYPE);
        if (mediaType == null || mediaType.isEmpty()) mediaType = "image";

        ImageView previewImage = findViewById(R.id.previewImage);
        EditText  captionInput = findViewById(R.id.captionInput);
        LinearLayout thumbnailContainer = findViewById(R.id.thumbnailContainer);
        ImageButton btnClose   = findViewById(R.id.btnClose);
        FrameLayout btnSendWrap = findViewById(R.id.btnSend).getParent() instanceof FrameLayout
                ? (FrameLayout) findViewById(R.id.btnSend).getParent()
                : null;
        View btnSend = findViewById(R.id.btnSend);

        captionInput.setHintTextColor(0x88FFFFFF);

        final ArrayList<String> selectedUris = uriStrings;
        final String selectedMediaType = mediaType;
        showPreview(previewImage, Uri.parse(selectedUris.get(0)), selectedMediaType);
        populateThumbnails(thumbnailContainer, previewImage, selectedUris, selectedMediaType);

        btnClose.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });

        View.OnClickListener sendListener = v -> {
            // Validate again at the final send boundary, before the calling activity
            // starts thumbnailing, encryption, or upload work. A single oversized
            // video is allowed through because its caller routes it to VideoTranscoder;
            // album items cannot be transcoded independently and are rejected by index.
            for (int i = 0; i < selectedUris.size(); i++) {
                long bytes = MediaLimits.sizeOf(this, Uri.parse(selectedUris.get(i)));
                boolean singleVideoCandidate = selectedUris.size() == 1
                        && "video".equals(selectedMediaType);
                if (MediaLimits.isOversize(bytes) && !singleVideoCandidate) {
                    String label = selectedUris.size() > 1
                            ? "Item " + (i + 1) : "This file";
                    Toast.makeText(this,
                            MediaLimits.tooLargeMessage(bytes, label),
                            Toast.LENGTH_LONG).show();
                    return;
                }
            }

            String caption = captionInput.getText() != null
                    ? captionInput.getText().toString().trim() : "";
            Intent result = new Intent();
            result.putExtra(EXTRA_URI, selectedUris.get(0));
            result.putStringArrayListExtra(EXTRA_URIS, selectedUris);
            result.putExtra(EXTRA_MEDIA_TYPE, selectedMediaType);
            result.putExtra(EXTRA_CAPTION, caption);
            setResult(RESULT_OK, result);
            finish();
        };

        btnSend.setOnClickListener(sendListener);
        if (btnSendWrap != null) btnSendWrap.setOnClickListener(sendListener);
    }

    private void populateThumbnails(LinearLayout container, ImageView preview,
                                    ArrayList<String> uris, String mediaType) {
        if (container == null) return;
        container.removeAllViews();
        if (uris.size() < 2) {
            container.setVisibility(View.GONE);
            return;
        }
        container.setVisibility(View.VISIBLE);
        for (int i = 0; i < uris.size(); i++) {
            final Uri itemUri = Uri.parse(uris.get(i));
            ImageView thumb = new ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(58), dp(58));
            lp.setMargins(dp(4), 0, dp(4), 0);
            thumb.setLayoutParams(lp);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setPadding(dp(2), dp(2), dp(2), dp(2));
            thumb.setBackgroundResource(i == 0
                    ? R.drawable.bg_media_rounded : R.drawable.bg_caption_input);
            showPreview(thumb, itemUri, mediaType);
            thumb.setOnClickListener(v -> showPreview(preview, itemUri, mediaType));
            container.addView(thumb);
        }
    }

    private void showPreview(ImageView target, Uri uri, String mediaType) {
        if ("video".equals(mediaType)) {
            // Glide's video decoder extracts the first frame without blocking
            // the preview screen on a MediaMetadataRetriever call.
            Glide.with(this).asBitmap().load(uri)
                    .placeholder(R.drawable.bg_media_rounded)
                    .error(R.drawable.bg_media_rounded)
                    .centerCrop().into(target);
        } else {
            Glide.with(this).load(uri).centerCrop().into(target);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
