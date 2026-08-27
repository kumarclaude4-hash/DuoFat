package com.duoshield.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * S3-19 regression coverage for S08-M1 and S08-M2.
 *
 * <p>Plain-JVM XML parse (no Android SDK / Robolectric) against the actual
 * manifest and file_paths.xml on disk, mirroring LockScreenManifestTest's
 * approach for S08-L4. Not compiled/run in this environment (no JDK) — the
 * assertions were hand-verified against the live files with
 * {@code xmllint --xpath} / {@code grep} before this test was written.
 *
 * <p>{@code AndroidManifest.xml} declares {@code xmlns:android} at its root
 * and every attribute in it is {@code android:}-prefixed, so its test method
 * below parses namespace-aware and reads attributes via
 * {@code getAttributeNS}, matching {@code LockScreenManifestTest}.
 * {@code file_paths.xml} is different on both counts: it declares no
 * {@code xmlns:android} at all, and — matching Android's own documented
 * FileProvider meta-data format — its {@code name}/{@code path} attributes
 * are unprefixed (FileProvider's own parser reads them with no namespace).
 * That test method therefore parses namespace-unaware and reads attributes
 * by their literal unprefixed name, e.g. {@code getAttribute("name")}.
 */
public class S319ManifestTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    /**
     * S08-M1: android:allowNativeHeapPointerTagging="false" must be gone from
     * the <application> tag — its absence re-enables the default (true),
     * restoring pointer-tagging as a memory-safety mitigation for libsignal,
     * WebRTC, and SQLCipher's native code.
     */
    @Test
    public void applicationTag_hasNoHeapPointerTaggingOverride() throws Exception {
        Document doc = parseNamespaceAware(locate("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml"));
        NodeList applications = doc.getElementsByTagName("application");
        assertEquals("expected exactly one <application> tag", 1, applications.getLength());
        Element application = (Element) applications.item(0);
        assertFalse(
                "android:allowNativeHeapPointerTagging must not be set (removing it "
                        + "restores the default of true)",
                application.hasAttributeNS(ANDROID_NS, "allowNativeHeapPointerTagging"));
    }

    /**
     * S08-M2: file_paths.xml may expose only the two narrowly scoped roots
     * used by the app: shared/ for media sharing and updates/ under the app's
     * external-files directory for the verified APK installer handoff. It must
     * not declare either external-storage root or an unscoped "." root.
     */
    @Test
    public void filePaths_declaresOnlyScopedSharedCacheRoot() throws Exception {
        Document doc = parseNamespaceUnaware(
                locate("src/main/res/xml/file_paths.xml", "app/src/main/res/xml/file_paths.xml"));
        Element root = doc.getDocumentElement();
        assertEquals("paths", root.getTagName());

        NodeList children = root.getChildNodes();
        int elementCount = 0;
        Element sharedPath = null;
        Element updatesPath = null;
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                elementCount++;
                Element path = (Element) child;
                if ("shared".equals(path.getAttribute("name"))) sharedPath = path;
                if ("updates".equals(path.getAttribute("name"))) updatesPath = path;
            }
        }

        assertEquals("expected exactly two declared path roots", 2, elementCount);
        assertNotNull(sharedPath);
        assertEquals("cache-path", sharedPath.getTagName());
        assertEquals("shared/", sharedPath.getAttribute("path"));
        assertNotNull(updatesPath);
        assertEquals("external-files-path", updatesPath.getTagName());
        assertEquals("updates/", updatesPath.getAttribute("path"));

        // Explicitly reject the external-cache and unscoped files roots.
        assertEquals(0, root.getElementsByTagName("external-cache-path").getLength());
        assertEquals(0, root.getElementsByTagName("files-path").getLength());
    }

    // ── file lookup / parsing ────────────────────────────────────────────

    /**
     * Gradle's JVM unit-test working directory is normally the module dir
     * ({@code app/}), but this walks upward defensively so the test is not
     * brittle to whichever directory a test runner happens to invoke it
     * from (same defensive pattern as LockScreenManifestTest).
     */
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

    private static Document parseNamespaceAware(File file)
            throws IOException, ParserConfigurationException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(file);
    }

    private static Document parseNamespaceUnaware(File file)
            throws IOException, ParserConfigurationException, SAXException {
        // Namespace-unaware on purpose: file_paths.xml declares no
        // xmlns:android at all, and its name/path attributes are plain
        // (unprefixed) per FileProvider's documented meta-data format, so
        // there is no namespace to resolve here in the first place.
        // Attributes are read by their literal unprefixed name below (e.g.
        // getAttribute("name")).
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        return factory.newDocumentBuilder().parse(file);
    }
}
