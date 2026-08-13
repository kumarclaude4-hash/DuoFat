package com.duoshield.app;

import com.google.firebase.appcheck.FirebaseAppCheck;

/**
 * Release-variant twin of app/src/debug/java/com/duoshield/app/AppCheckDebugProvider.java.
 * {@code firebase-appcheck-debug} is a {@code debugImplementation}-only
 * dependency (see app/build.gradle) and is never on the release classpath, so
 * this variant is a deliberate no-op: release builds only ever install the
 * Play Integrity provider factory (see {@link DuoShieldApp#onCreate()}).
 */
final class AppCheckDebugProvider {

    private AppCheckDebugProvider() {}

    /** No-op in release builds — the Debug App Check provider is debug-only. */
    static void install(FirebaseAppCheck appCheck) {
        // Intentionally empty.
    }
}
