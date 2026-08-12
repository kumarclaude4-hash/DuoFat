package com.duoshield.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.IOException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;

/**
 * S08-L4 regression coverage: the lock screen must never surface an unlocked
 * activity's content in the system recents list, and must not stack a second
 * instance on top of itself.
 *
 * <p>This is a plain XML-parse test (no Android SDK / Robolectric /
 * instrumentation needed) so it can run in this environment even while a real
 * device/emulator check is blocked (S3-19b). It reads the manifest straight
 * off disk and asserts on {@code <activity android:name=".LockScreenActivity">}
 * having both:
 * <ul>
 *   <li>{@code android:excludeFromRecents="true"} — the underlying fix for
 *       S08-L4 (manifest previously had neither this nor {@code noHistory}).</li>
 *   <li>{@code android:launchMode="singleTask"} — backs the
 *       {@code BaseActivity.lockScreenActive} guard so a second
 *       {@code FLAG_ACTIVITY_NEW_TASK} start (BaseActivity.onStart /
 *       onShakeToLock) cannot create a stacked duplicate instance.</li>
 * </ul>
 */
public class LockScreenManifestTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    @Test
    public void lockScreenActivity_isExcludedFromRecentsAndSingleTask() throws Exception {
        Element activityEl = findActivityElement("LockScreenActivity");
        assertNotNull("Could not find <activity> for LockScreenActivity in AndroidManifest.xml",
                activityEl);

        String excludeFromRecents = activityEl.getAttributeNS(ANDROID_NS, "excludeFromRecents");
        assertEquals("LockScreenActivity must set android:excludeFromRecents=\"true\" "
                        + "(S08-L4) so an unlocked screen never appears in the task switcher "
                        + "snapshot behind/around the lock screen.",
                "true", excludeFromRecents);

        String launchMode = activityEl.getAttributeNS(ANDROID_NS, "launchMode");
        assertEquals("LockScreenActivity must set android:launchMode=\"singleTask\" (S08-L4) "
                        + "so it cannot be stacked as a duplicate instance.",
                "singleTask", launchMode);
    }

    // ── manifest lookup ──────────────────────────────────────────────────

    private static Element findActivityElement(String simpleActivityName)
            throws IOException, ParserConfigurationException, SAXException {
        Document doc = parseManifest();
        NodeList activities = doc.getElementsByTagName("activity");
        for (int i = 0; i < activities.getLength(); i++) {
            Node node = activities.item(i);
            if (!(node instanceof Element)) continue;
            Element el = (Element) node;
            String name = el.getAttributeNS(ANDROID_NS, "name");
            if (name != null && (name.equals("." + simpleActivityName) || name.endsWith("." + simpleActivityName))) {
                return el;
            }
        }
        return null;
    }

    private static Document parseManifest()
            throws IOException, ParserConfigurationException, SAXException {
        File manifest = locateManifest();
        if (manifest == null) {
            fail("Could not locate AndroidManifest.xml from working dir "
                    + new File(".").getAbsolutePath());
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(manifest);
    }

    /**
     * Gradle's JVM unit-test working directory is normally the module dir
     * ({@code app/}), but this walks upward defensively so the test is not
     * brittle to whichever directory a test runner happens to invoke it from.
     */
    private static File locateManifest() {
        File dir = new File(".").getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++) {
            File candidate = new File(dir, "src/main/AndroidManifest.xml");
            if (candidate.isFile()) return candidate;
            File appCandidate = new File(dir, "app/src/main/AndroidManifest.xml");
            if (appCandidate.isFile()) return appCandidate;
            dir = dir.getParentFile();
        }
        return null;
    }
}
