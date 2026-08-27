package com.duoshield.app.util;

import android.content.Context;
import android.widget.ImageView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.duoshield.app.R;

public class GlideHelper {

    /**
     * Avatars stored as a "b2:" path need an authenticated SigV4 download (the
     * bucket rejects plain unauthenticated GETs — see
     * {@link com.duoshield.app.util.B2StorageHelper#loadAvatarBytes}); anything
     * else (a legacy plain URL) is handed to Glide directly for backward compat.
     */
    private static void loadPrivateOrPlain(Context ctx, String pathOrUrl, ImageView iv,
                                            boolean circle) {
        final String requestKey = pathOrUrl;
        Glide.with(ctx).clear(iv);
        iv.setTag(requestKey);
        iv.setImageResource(R.drawable.ic_person);
        if (pathOrUrl == null || pathOrUrl.isEmpty()) return;
        if (com.duoshield.app.util.B2StorageHelper.isB2Path(pathOrUrl)) {
            com.duoshield.app.util.B2StorageHelper.loadAvatarBytes(pathOrUrl,
                new com.duoshield.app.util.B2StorageHelper.MediaCallback() {
                    @Override public void onLoaded(byte[] bytes) {
                        if (!requestKey.equals(iv.getTag())) return;
                        com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> req =
                            Glide.with(ctx).load(bytes)
                                .placeholder(R.drawable.ic_person)
                                .error(R.drawable.ic_person);
                        if (circle) req.transform(new CircleCrop());
                        req.into(iv);
                    }
                    @Override public void onError(Exception e) {
                        if (requestKey.equals(iv.getTag())) iv.setImageResource(R.drawable.ic_person);
                    }
                });
        } else {
            if (!requestKey.equals(iv.getTag())) return;
            com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> req =
                Glide.with(ctx).load(pathOrUrl)
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person);
            if (circle) req.transform(new CircleCrop());
            req.into(iv);
        }
    }

    public static void loadAvatar(Context ctx, String url, ImageView iv) {
        loadPrivateOrPlain(ctx, url, iv, true);
    }

    public static void loadMedia(Context ctx, String url, ImageView iv) {
        Glide.with(ctx).load(url)
            .placeholder(android.R.drawable.ic_menu_gallery)
            .centerCrop()
            .into(iv);
    }

    public static void loadToolbar(Context ctx, String url, ImageView iv) {
        loadPrivateOrPlain(ctx, url, iv, true);
    }
}
