package com.duoshield.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S3-19 regression coverage for S08-I1: {@code proguard-rules.pro} must no
 * longer keep {@code com.duoshield.app.crypto.**} / {@code
 * com.duoshield.app.security.**} in full ({@code -keep .. { *; }} +
 * {@code -keepclassmembers .. { *; }}) — the app's own key-management,
 * PIN/lock, and E2E-crypto packages are exactly the classes an attacker doing
 * static analysis on the APK most wants left unobfuscated with dead code
 * intact, and the audit found no reflection/JNI/Gson entry point in either
 * package that would require it.
 *
 * <p>Plain-text/regex read of the actual rules file on disk — no R8/Gradle
 * invocation, mirroring {@code S319ManifestTest}'s plain-JVM approach for the
 * other S3-19 findings. Not compiled/run in this environment (no JDK) —
 * hand-verified against the live file instead.
 */
public class ProguardRulesNarrowingTest {

    @Test
    public void doesNotBlanketKeepAppCryptoPackage() throws IOException {
        String rules = readRules();
        assertFalse(
                "proguard-rules.pro must not blanket-keep com.duoshield.app.crypto.** "
                        + "(S08-I1) — that disables shrinking/obfuscation for the app's own "
                        + "E2E-crypto classes with no reflection/JNI reason found",
                hasActiveKeepMembersRuleFor(rules, "com.duoshield.app.crypto.**"));
    }

    @Test
    public void doesNotBlanketKeepAppSecurityPackage() throws IOException {
        String rules = readRules();
        assertFalse(
                "proguard-rules.pro must not blanket-keep com.duoshield.app.security.** "
                        + "(S08-I1) — that disables shrinking/obfuscation for the app's own "
                        + "PIN/lock/duress classes with no reflection/JNI reason found",
                hasActiveKeepMembersRuleFor(rules, "com.duoshield.app.security.**"));
    }

    @Test
    public void workManagerConstructorKeepRule_stillCoversAppWorkers() throws IOException {
        // The narrowing relies on AccountLockWorker (security) and
        // SignedPreKeyRotationWorker (crypto.signal) staying covered by the
        // generic Worker/ListenableWorker keep rules instead of a
        // package-specific one. If either generic rule regresses, WorkManager's
        // reflective instantiation of those two classes would silently break
        // in a release build — assert both are still present.
        String rules = readRules();
        assertTrue("must still keep classes extending androidx.work.Worker",
                rules.contains("-keep class * extends androidx.work.Worker"));
        assertTrue("must still keep the (Context, WorkerParameters) constructor for "
                        + "ListenableWorker subclasses — this is what makes WorkManager's "
                        + "reflective instantiation of AccountLockWorker/"
                        + "SignedPreKeyRotationWorker safe without a package-specific rule",
                rules.contains("androidx.work.ListenableWorker")
                        && rules.contains("public <init>(android.content.Context, androidx.work.WorkerParameters);"));
    }

    @Test
    public void appDataModelsPackage_stillKeptForRoomAndFirestoreReflection() throws IOException {
        // Sanity check that the narrowing was scoped to crypto/security only —
        // com.duoshield.app.models.** (used by Room + Firestore's reflective
        // field mapping) must remain fully kept.
        String rules = readRules();
        assertTrue("com.duoshield.app.models.** must remain a full keep — Room/Firestore "
                        + "reflectively map its fields",
                hasActiveKeepMembersRuleFor(rules, "com.duoshield.app.models.**"));
    }

    /**
     * True if {@code rules} contains an active (non-comment) {@code -keep} or
     * {@code -keepclassmembers} line naming the literal package/pattern
     * {@code target} followed by a {@code { *; }} member wildcard — the exact
     * blanket-keep shape S08-I1 removed for crypto/security. Lines beginning
     * with {@code #} (comments) are skipped, so mentioning the removed rule in
     * an explanatory comment (as the current file's S08-I1 rationale block
     * does) does not itself trigger a match.
     */
    private static boolean hasActiveKeepMembersRuleFor(String rules, String target) {
        String escapedTarget = Pattern.quote(target);
        Pattern linePattern = Pattern.compile(
                "^\\s*-keep(?:classmembers)?\\s+class\\s+" + escapedTarget + "\\s*\\{\\s*\\*;\\s*}",
                Pattern.MULTILINE);
        Matcher m = linePattern.matcher(rules);
        while (m.find()) {
            int lineStart = rules.lastIndexOf('\n', m.start()) + 1;
            if (!rules.substring(lineStart, m.start()).trim().startsWith("#")) {
                return true;
            }
        }
        return false;
    }

    private static String readRules() throws IOException {
        File f = locate("proguard-rules.pro", "app/proguard-rules.pro");
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static File locate(String fromModuleDir, String fromRepoRoot) {
        File dir = new File(".").getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++) {
            File moduleCandidate = new File(dir, fromModuleDir);
            if (moduleCandidate.isFile()) return moduleCandidate;
            File rootCandidate = new File(dir, fromRepoRoot);
            if (rootCandidate.isFile()) return rootCandidate;
            dir = dir.getParentFile();
        }
        fail("Could not locate " + fromRepoRoot + " from working dir "
                + new File(".").getAbsolutePath());
        return null; // unreachable
    }
}
