# ============================================================
# DuoShield ProGuard Rules
# ============================================================

# ── R8 full-mode optimisations ───────────────────────────────
# Run 5 optimisation passes (default is 1) and allow R8 to
# widen member visibility where it saves code — safe because
# the app is self-contained and never published as a library.
-optimizationpasses 5
-allowaccessmodification

# ── App crypto & security classes — never obfuscate ─────────
-keep class com.duoshield.app.crypto.**        { *; }
-keepclassmembers class com.duoshield.app.crypto.**    { *; }
-keep class com.duoshield.app.security.**      { *; }
-keepclassmembers class com.duoshield.app.security.**  { *; }

# ── Data models — required by Room and Firestore reflection ─
-keep class com.duoshield.app.models.**        { *; }
-keepclassmembers class com.duoshield.app.models.**    { *; }

# ── Firebase — all subpackages ───────────────────────────────
-keep class com.google.firebase.**             { *; }
-keepclassmembers class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# ── Google Auth Library (FCM OAuth2 token exchange) ─────────
-keep class com.google.auth.**                 { *; }
-keepclassmembers class com.google.auth.**     { *; }
-dontwarn com.google.auth.**

# ── Java crypto & security providers ────────────────────────
-keep class javax.crypto.**                    { *; }
-keep class javax.crypto.spec.**               { *; }
-keep class java.security.**                   { *; }
-keep class java.security.spec.**              { *; }
-dontwarn javax.crypto.**
-dontwarn java.security.**

# ── Room — preserve DAO + entity annotations ────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *            { *; }
-keep @androidx.room.Dao class *               { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}

# ── WorkManager ──────────────────────────────────────────────
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ── Firebase Messaging Service ───────────────────────────────
-keep class * extends com.google.firebase.messaging.FirebaseMessagingService

# ── Biometric library ────────────────────────────────────────
-keep class androidx.biometric.**              { *; }
-dontwarn androidx.biometric.**

# ── Glide ────────────────────────────────────────────────────
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-dontwarn com.bumptech.glide.**

# ── Suppress warnings from google-auth transitive deps ───────
-dontwarn com.google.api.**
-dontwarn io.grpc.**
-dontwarn org.apache.http.**
-dontwarn com.google.android.gms.**

# ── PhotoView (JitPack) ──────────────────────────────────────
-keep class com.github.chrisbanes.photoview.** { *; }
-dontwarn com.github.chrisbanes.photoview.**

# ── Security Crypto (EncryptedSharedPreferences) ─────────────
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# ── Signal Protocol (libsignal-android) ─────────────────────
# The native Rust .so is loaded by name; class names must not be obfuscated.
-keep class org.signal.libsignal.**            { *; }
-keepclassmembers class org.signal.libsignal.** { *; }
-dontwarn org.signal.libsignal.**

# ── SQLCipher — full-database encryption ─────────────────────
# SupportFactory is instantiated reflectively by Room; class and member names
# must be preserved or the database will fail to open with a ClassNotFoundException.
-keep class net.sqlcipher.** { *; }
-keepclassmembers class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**
-keep class net.zetetic.** { *; }
-dontwarn net.zetetic.**

# ── ZXing — QR code scanning & generation ────────────────────
-keep class com.google.zxing.** { *; }
-keepclassmembers class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
-keepclassmembers class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**
-dontwarn com.journeyapps.barcodescanner.**

# ── WebRTC (stream-webrtc-android / org.webrtc) ──────────────────────
# JNI layer maps native method names to Java class/method names at
# runtime.  Obfuscating any org.webrtc class causes UnsatisfiedLinkError
# the first time a WebRTC API is called (both architectures, release only).
-keep class org.webrtc.**                  { *; }
-keepclassmembers class org.webrtc.**      { *; }
-dontwarn org.webrtc.**

# ── Media3 / ExoPlayer ───────────────────────────────────────────────
# ExoPlayer loads video/audio decoders and renderers reflectively.
# Obfuscating androidx.media3 classes causes ClassNotFoundExceptions
# during playback initialisation on release builds.
-keep class androidx.media3.**             { *; }
-keepclassmembers class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── OkHttp & Okio ────────────────────────────────────────────────────
-keep class okhttp3.**                     { *; }
-keep class okio.**                        { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# ── Keep source file names for crash reporting ───────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
