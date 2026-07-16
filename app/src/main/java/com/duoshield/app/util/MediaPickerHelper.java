package com.duoshield.app.util;

import android.net.Uri;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.FragmentActivity;

public class MediaPickerHelper {

    public interface OnPickedListener { void onPicked(Uri uri, String type); }

    private final ActivityResultLauncher<String> imageLauncher;
    private final ActivityResultLauncher<String> videoLauncher;

    public MediaPickerHelper(FragmentActivity activity, OnPickedListener listener) {
        imageLauncher = activity.registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> { if (uri != null) listener.onPicked(uri, "image"); });
        videoLauncher = activity.registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> { if (uri != null) listener.onPicked(uri, "video"); });
    }

    public void pickImage() { imageLauncher.launch("image/*"); }
    public void pickVideo() { videoLauncher.launch("video/*"); }
}
