package com.duoshield.app.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** In-app updater for signed APKs published by the DuoFat GitHub release workflow. */
public final class AppUpdateHelper {
    private static final String RELEASES_API =
            "https://api.github.com/repos/kumarclaude4-hash/DuoFatass/releases/latest";
    private static final String PROVIDER_SUFFIX = ".provider";
    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private AppUpdateHelper() { }

    public static String getVersionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName;
        } catch (PackageManager.NameNotFoundException e) { return "unknown"; }
    }

    public static int getVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) return (int) pi.getLongVersionCode();
            return pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) { return 0; }
    }

    public static final class ReleaseInfo {
        public final String versionName;
        public final String apkName;
        public final String apkUrl;
        public final String sha256;
        public final String releaseUrl;

        private ReleaseInfo(String versionName, String apkName, String apkUrl,
                            String sha256, String releaseUrl) {
            this.versionName = versionName;
            this.apkName = apkName;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
            this.releaseUrl = releaseUrl;
        }
    }

    public interface CheckCallback {
        void onUpdateAvailable(ReleaseInfo info);
        void onUpToDate(String currentVersion);
        void onError(String message);
    }

    public interface DownloadCallback {
        void onProgress(int percent);
        void onDownloaded(File apk);
        void onError(String message);
    }

    public interface InstallCallback {
        void onPermissionRequired();
        void onStarted();
        void onError(String message);
    }

    public static void checkForUpdate(Context context, CheckCallback callback) {
        Context app = context.getApplicationContext();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        worker.execute(() -> {
            try {
                String json = readText(new URL(RELEASES_API));
                JSONObject release = new JSONObject(json);
                String tag = release.optString("tag_name", "").trim();
                String remoteVersion = tag.startsWith("v") ? tag.substring(1) : tag;
                if (remoteVersion.isEmpty()) throw new Exception("Release version is missing");
                String currentVersion = getVersionName(app);
                if (compareVersions(remoteVersion, currentVersion) <= 0) {
                    post(() -> callback.onUpToDate(currentVersion));
                    return;
                }

                JSONArray assets = release.optJSONArray("assets");
                if (assets == null) throw new Exception("Release has no downloadable assets");
                String preferred = preferredApkName();
                JSONObject apk = null;
                JSONObject sums = null;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String name = asset.optString("name", "");
                    if (name.endsWith(".apk")) {
                        if (preferred.equals(name)) apk = asset;
                    } else if ("SHA256SUMS".equals(name)) {
                        sums = asset;
                    }
                }
                if (preferred.isEmpty() || apk == null || sums == null) {
                    throw new Exception("Release does not contain a compatible APK and checksum");
                }

                String apkName = apk.optString("name", "");
                String apkUrl = apk.optString("browser_download_url", "");
                String sumsUrl = sums.optString("browser_download_url", "");
                String checksumText = readText(new URL(sumsUrl));
                String checksum = checksumFor(checksumText, apkName);
                if (checksum == null) throw new Exception("APK checksum is missing from release");
                String releaseUrl = release.optString("html_url", "");
                ReleaseInfo info = new ReleaseInfo(remoteVersion, apkName, apkUrl,
                        checksum, releaseUrl);
                post(() -> callback.onUpdateAvailable(info));
            } catch (Exception e) {
                post(() -> callback.onError(safeMessage(e, "Could not check for updates")));
            } finally {
                worker.shutdown();
            }
        });
    }

    public static void download(Context context, ReleaseInfo info, DownloadCallback callback) {
        Context app = context.getApplicationContext();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        worker.execute(() -> {
            File out = null;
            try {
                if (info == null || !info.apkUrl.startsWith("https://")) {
                    throw new Exception("Invalid release download URL");
                }
                File external = app.getExternalFilesDir("updates");
                if (external == null) throw new Exception("Update storage is unavailable");
                File dir = new File(external, "");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("Cannot create update directory");
                out = new File(dir, "duoshield-" + info.versionName.replaceAll("[^A-Za-z0-9._-]", "_") + ".apk");
                HttpURLConnection conn = open(new URL(info.apkUrl));
                int total = conn.getContentLength();
                int read = 0;
                try (InputStream in = new BufferedInputStream(conn.getInputStream());
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buffer = new byte[32 * 1024];
                    int n;
                    int last = -1;
                    while ((n = in.read(buffer)) != -1) {
                        fos.write(buffer, 0, n);
                        read += n;
                        int percent = total > 0 ? Math.min(99, (read * 100) / total) : -1;
                        if (percent != last) {
                            last = percent;
                            int progress = percent;
                            post(() -> callback.onProgress(progress));
                        }
                    }
                } finally {
                    conn.disconnect();
                }
                if (!info.sha256.equalsIgnoreCase(sha256(out))) {
                    if (out.exists()) out.delete();
                    throw new Exception("Downloaded APK checksum does not match the release");
                }
                post(() -> callback.onProgress(100));
                File result = out;
                post(() -> callback.onDownloaded(result));
            } catch (Exception e) {
                if (out != null && out.exists()) out.delete();
                post(() -> callback.onError(safeMessage(e, "Update download failed")));
            } finally {
                worker.shutdown();
            }
        });
    }

    public static boolean canInstallPackages(Context context) {
        return Build.VERSION.SDK_INT < 26
                || context.getPackageManager().canRequestPackageInstalls();
    }

    public static void openInstallPermissionSettings(Activity activity) {
        if (Build.VERSION.SDK_INT >= 26) {
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        }
    }

    public static void install(Activity activity, File apk, InstallCallback callback) {
        try {
            if (apk == null || !apk.isFile() || apk.length() == 0) {
                throw new Exception("Downloaded update is unavailable");
            }
            PackageInfo archive = activity.getPackageManager()
                    .getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (archive == null || !activity.getPackageName().equals(archive.packageName)) {
                throw new Exception("Downloaded update is not a DuoShield package");
            }
            if (!canInstallPackages(activity)) {
                callback.onPermissionRequired();
                return;
            }
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + PROVIDER_SUFFIX, apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            callback.onStarted();
        } catch (Exception e) {
            callback.onError(safeMessage(e, "Could not open Android installer"));
        }
    }

    private static String preferredApkName() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return "app-arm64-v8a-release.apk";
            if ("armeabi-v7a".equals(abi)) return "app-armeabi-v7a-release.apk";
        }
        return "";
    }

    private static int compareVersions(String a, String b) {
        String[] aa = a.split("[.-]");
        String[] bb = b.split("[.-]");
        int n = Math.max(aa.length, bb.length);
        for (int i = 0; i < n; i++) {
            int av = i < aa.length ? numberPrefix(aa[i]) : 0;
            int bv = i < bb.length ? numberPrefix(bb[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int numberPrefix(String value) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < value.length() && Character.isDigit(value.charAt(i)); i++) {
            digits.append(value.charAt(i));
        }
        if (digits.length() == 0) return 0;
        try { return Integer.parseInt(digits.toString()); }
        catch (NumberFormatException ignored) { return Integer.MAX_VALUE; }
    }

    private static String checksumFor(String sums, String apkName) {
        for (String line : sums.split("\\R")) {
            String trimmed = line.trim();
            if ((trimmed.endsWith("  " + apkName) || trimmed.endsWith(" *" + apkName))
                    && trimmed.length() >= 64) {
                String hash = trimmed.substring(0, 64);
                if (hash.matches("[A-Fa-f0-9]{64}")) return hash;
            }
        }
        return null;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder out = new StringBuilder();
        for (byte b : digest.digest()) out.append(String.format(Locale.US, "%02x", b));
        return out.toString();
    }

    private static String readText(URL url) throws Exception {
        HttpURLConnection conn = open(url);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
            return out.toString();
        } finally { conn.disconnect(); }
    }

    private static HttpURLConnection open(URL url) throws Exception {
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new Exception("HTTPS is required");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "DuoShield-InApp-Updater");
        conn.connect();
        if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) {
            throw new Exception("Release server returned HTTP " + conn.getResponseCode());
        }
        return conn;
    }

    private static void post(Runnable action) {
        new Handler(Looper.getMainLooper()).post(action);
    }

    private static String safeMessage(Exception e, String fallback) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? fallback : message;
    }
}
