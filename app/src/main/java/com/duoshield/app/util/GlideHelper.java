package com.duoshield.app.util;

import android.content.Context;
import android.widget.ImageView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.duoshield.app.R;

public class GlideHelper {

    public static void loadAvatar(Context ctx, String url, ImageView iv) {
        Glide.with(ctx).load(url)
            .placeholder(R.drawable.ic_person)
            .error(R.drawable.ic_person)
            .transform(new CircleCrop())
            .into(iv);
    }

    public static void loadMedia(Context ctx, String url, ImageView iv) {
        Glide.with(ctx).load(url)
            .placeholder(android.R.drawable.ic_menu_gallery)
            .centerCrop()
            .into(iv);
    }

    public static void loadToolbar(Context ctx, String url, ImageView iv) {
        Glide.with(ctx).load(url)
            .placeholder(R.drawable.ic_person)
            .error(R.drawable.ic_person)
            .circleCrop()
            .into(iv);
    }
}
