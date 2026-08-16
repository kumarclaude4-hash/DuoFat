package com.duoshield.app.util;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipDescription;
import android.os.Build;
import android.os.PersistableBundle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Regression coverage for {@link ClipboardHelper#markSensitive} (S08-L2).
 *
 * <p>Runs under Robolectric, pinned to {@code sdk = 33} (Tiramisu — the level that
 * introduced {@code ClipDescription.EXTRA_IS_SENSITIVE}), so the API 33+ branch is the
 * one actually exercised. The android.jar stub cannot serve this test: object
 * construction on {@code ClipData} is not a plain data-holder operation there, and under
 * this module's {@code returnDefaultValues true} the stubbed
 * {@code ClipData.newPlainText()} simply returns null, so the test NPE'd on its own
 * fixture before reaching {@code markSensitive}. Robolectric supplies real
 * {@code ClipData} / {@code ClipDescription} / {@code PersistableBundle} behavior.
 *
 * <p>The complementary pre-33 branch is covered by {@link #markSensitive_isNoOpBelowApi33}
 * via a second {@code @Config} — one JVM run cannot otherwise exercise two SDK_INT values.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 33)
public class ClipboardHelperSensitivityTest {

    @Test
    public void markSensitive_setsExtraOnApi33Plus() {
        // Robolectric pins SDK_INT to 33 for this class, so this is the real API 33+
        // branch rather than whatever the host stub happened to report.
        assertTrue("fixture precondition: this test must run on API 33+",
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU);

        ClipData clip = ClipData.newPlainText("message", "hello");
        ClipboardHelper.markSensitive(clip);

        PersistableBundle extras = clip.getDescription().getExtras();
        assertNotNull("markSensitive must attach an extras bundle on API 33+", extras);
        assertTrue("EXTRA_IS_SENSITIVE must be set on API 33+",
                extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false));
    }

    /**
     * Below API 33 {@code EXTRA_IS_SENSITIVE} does not exist on-device, so
     * {@code markSensitive()} must degrade to a no-op instead of throwing
     * {@code NoSuchFieldError} — this app ships {@code minSdk 26}.
     */
    @Test
    @Config(manifest = Config.NONE, sdk = 26)
    public void markSensitive_isNoOpBelowApi33() {
        assertTrue("fixture precondition: this test must run below API 33",
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU);

        ClipData clip = ClipData.newPlainText("message", "hello");
        ClipboardHelper.markSensitive(clip); // must not throw

        assertNull("extras must stay unset below API 33",
                clip.getDescription().getExtras());
    }
}
