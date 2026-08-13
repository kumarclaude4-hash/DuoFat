package com.duoshield.app;

import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory;

/**
 * Debug-variant implementation. {@code firebase-appcheck-debug} is a
 * {@code debugImplementation}-only dependency (see app/build.gradle), so this
 * class — and its import of {@link DebugAppCheckProviderFactory} — must live
 * in the {@code debug} source set, never in {@code main}. A release-variant
 * twin of this same class (with a no-op body) lives in
 * app/src/release/java/com/duoshield/app/AppCheckDebugProvider.java so
 * {@link DuoShieldApp} can call this unconditionally without a compile-time
 * reference to a debug-only class ever reaching {@code compileReleaseJavaWithJavac}.
 */
final class AppCheckDebugProvider {

    private AppCheckDebugProvider() {}

    /** Installs the Debug App Check provider factory. Debug builds only. */
    static void install(FirebaseAppCheck appCheck) {
        appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance());
    }
}
