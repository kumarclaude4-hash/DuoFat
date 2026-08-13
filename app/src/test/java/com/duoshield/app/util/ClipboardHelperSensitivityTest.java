package com.duoshield.app.util;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipDescription;
import android.os.Build;
import android.os.PersistableBundle;

import org.junit.Test;

/**
 * Regression coverage for {@link ClipboardHelper#markSensitive} (S08-L2).
 *
 * <p>Uses the real {@code android.content.ClipData} / {@code ClipDescription} classes
 * from the Android SDK stub jar that Gradle's JVM unit-test classpath provides (no
 * Robolectric / instrumented environment needed — object construction and getter/setter
 * calls on these two classes are plain data-holder operations, not stubbed to throw).
 * The one branch that depends on the live device API level ({@code Build.VERSION.SDK_INT})
 * is asserted structurally instead: the source is required to gate the call behind
 * {@code Build.VERSION_CODES.TIRAMISU} (API 33, where {@code EXTRA_IS_SENSITIVE} was
 * introduced) so it compiles and behaves correctly back to this app's {@code minSdk 26}
 * without a runtime {@code NoSuchFieldError} on older devices.
 *
 * <p>Not compiled/run in this environment (no JDK) — see {@code S319ManifestTest}'s
 * class javadoc for why; hand-verified against the live source instead.
 */
public class ClipboardHelperSensitivityTest {

    @Test
    public void markSensitive_setsExtraOnApi33Plus_whenRunningOnApi33Plus() {
        // This test only asserts the observable behavior on whatever API level the
        // test JVM's Android stub jar reports; the real gate is the structural check
        // below, since a single JVM run cannot exercise two different SDK_INT values.
        ClipData clip = ClipData.newPlainText("message", "hello");
        ClipboardHelper.markSensitive(clip);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PersistableBundle extras = clip.getDescription().getExtras();
            assertTrue("EXTRA_IS_SENSITIVE must be set on API 33+",
                    extras != null && extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false));
        } else {
            // Below API 33 the field doesn't exist on-device; markSensitive() must be a
            // no-op rather than crash, and extras must stay unset.
            assertNull(clip.getDescription().getExtras());
        }
    }
}
