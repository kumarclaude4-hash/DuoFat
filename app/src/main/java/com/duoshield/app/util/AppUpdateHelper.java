package com.duoshield.app.util;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

public class AppUpdateHelper {

    public static String getVersionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager()
                .getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName;
        } catch (PackageManager.NameNotFoundException e) { return "unknown"; }
    }

    public static int getVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager()
                .getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) { return 0; }
    }
}
