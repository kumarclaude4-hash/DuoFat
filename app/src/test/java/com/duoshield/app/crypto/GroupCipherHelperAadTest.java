package com.duoshield.app.crypto;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Pure JUnit tests for {@link GroupCipherHelper}'s S07-H3 fix: binding
 * (groupId, senderUid, messageId) into AES-GCM associated data so a group
 * ciphertext only decrypts under the exact context it was created for,
 * rather than that binding living solely in Firestore security rules.
 *
 * <p>Runs under Robolectric. GroupCipherHelper's only Android dependency is
 * android.util.Base64, but that is load-bearing here rather than incidental: the
 * module sets {@code testOptions.unitTests.returnDefaultValues true}, under which
 * the android.jar stub's {@code Base64.decode()} returns null instead of throwing.
 * A null key array makes {@code SecretKeySpec} reject it with "Missing argument"
 * before any AEAD assertion runs, so every AAD case below fails for a reason that
 * has nothing to do with the crypto under test. Robolectric supplies the real AOSP
 * Base64, which is what makes these assertions meaningful.
 *
 * Covers:
 *  1. encrypt/decrypt round-trip with no AAD (legacy overloads unchanged).
 *  2. encrypt/decrypt round-trip with matching AAD.
 *  3. Decryption fails when the AAD's groupId differs (replay into another group).
 *  4. Decryption fails when the AAD's senderUid differs (misattribution).
 *  5. Decryption fails when the AAD's messageId differs (splice onto another slot).
 *  6. Decryption fails when AAD is supplied at read time for a ciphertext that
 *     was encrypted with none (and vice versa).
 *  7. buildAad is deterministic and sensitive to every field.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 33)
public class GroupCipherHelperAadTest {

    private static String testGroupKey() {
        return GroupCipherHelper.generateGroupKey();
    }

    @Test
    public void legacyNoAadOverloads_stillRoundTrip() throws Exception {
        String key   = testGroupKey();
        String text  = "hello group, no AAD";
        String blob  = GroupCipherHelper.encrypt(text, key);
        assertEquals("legacy no-AAD round trip must still work",
                text, GroupCipherHelper.decrypt(blob, key));
    }

    @Test
    public void matchingAad_roundTrips() throws Exception {
        String key = testGroupKey();
        byte[] aad = GroupCipherHelper.buildAad("group-1", "sender-a", "msg-1");
        String text = "bound to group-1/sender-a/msg-1";

        String blob = GroupCipherHelper.encrypt(text, key, aad);
        assertEquals("decryption with the identical AAD must recover the original text",
                text, GroupCipherHelper.decrypt(blob, key, aad));
    }

    @Test
    public void mismatchedGroupId_failsDecryption() throws Exception {
        String key = testGroupKey();
        byte[] writeAad = GroupCipherHelper.buildAad("group-1", "sender-a", "msg-1");
        byte[] readAad  = GroupCipherHelper.buildAad("group-2", "sender-a", "msg-1"); // replayed into another group
        String blob = GroupCipherHelper.encrypt("secret", key, writeAad);
        try {
            GroupCipherHelper.decrypt(blob, key, readAad);
            fail("Decrypting with a different groupId in the AAD must fail the GCM tag check "
                    + "(this is exactly the cross-group replay S07-H3 targets)");
        } catch (Exception expected) {
            // pass — AEAD authentication failure
        }
    }

    @Test
    public void mismatchedSenderUid_failsDecryption() throws Exception {
        String key = testGroupKey();
        byte[] writeAad = GroupCipherHelper.buildAad("group-1", "sender-a", "msg-1");
        byte[] readAad  = GroupCipherHelper.buildAad("group-1", "sender-b", "msg-1"); // misattributed sender
        String blob = GroupCipherHelper.encrypt("secret", key, writeAad);
        try {
            GroupCipherHelper.decrypt(blob, key, readAad);
            fail("Decrypting with a different senderUid in the AAD must fail the GCM tag check "
                    + "(this is exactly the misattribution S07-H3 targets)");
        } catch (Exception expected) {
            // pass — AEAD authentication failure
        }
    }

    @Test
    public void mismatchedMessageId_failsDecryption() throws Exception {
        String key = testGroupKey();
        byte[] writeAad = GroupCipherHelper.buildAad("group-1", "sender-a", "msg-1");
        byte[] readAad  = GroupCipherHelper.buildAad("group-1", "sender-a", "msg-2"); // spliced onto another slot
        String blob = GroupCipherHelper.encrypt("secret", key, writeAad);
        try {
            GroupCipherHelper.decrypt(blob, key, readAad);
            fail("Decrypting with a different messageId in the AAD must fail the GCM tag check "
                    + "(this is exactly the message-splice S07-H3 targets)");
        } catch (Exception expected) {
            // pass — AEAD authentication failure
        }
    }

    @Test
    public void aadPresenceMismatch_failsDecryption() throws Exception {
        String key = testGroupKey();
        byte[] aad = GroupCipherHelper.buildAad("group-1", "sender-a", "msg-1");

        // Written WITH aad, read with NONE.
        String blobWithAad = GroupCipherHelper.encrypt("secret", key, aad);
        try {
            GroupCipherHelper.decrypt(blobWithAad, key); // no AAD supplied
            fail("Decrypting with no AAD a ciphertext that was written with AAD must fail");
        } catch (Exception expected) {
            // pass
        }

        // Written WITHOUT aad, read WITH one.
        String blobNoAad = GroupCipherHelper.encrypt("secret", key);
        try {
            GroupCipherHelper.decrypt(blobNoAad, key, aad);
            fail("Decrypting with AAD a ciphertext that was written without one must fail");
        } catch (Exception expected) {
            // pass
        }
    }

    @Test
    public void buildAad_isDeterministic() {
        byte[] a1 = GroupCipherHelper.buildAad("group-1", "sender-a", "msg-1");
        byte[] a2 = GroupCipherHelper.buildAad("group-1", "sender-a", "msg-1");
        assertArrayEquals("buildAad must be deterministic for identical inputs", a1, a2);
    }

    @Test
    public void buildAad_differsWhenAnyFieldChanges() {
        byte[] base = GroupCipherHelper.buildAad("group-1", "sender-a", "msg-1");
        assertFalse("changing groupId must change the AAD bytes",
                java.util.Arrays.equals(base,
                        GroupCipherHelper.buildAad("group-2", "sender-a", "msg-1")));
        assertFalse("changing senderUid must change the AAD bytes",
                java.util.Arrays.equals(base,
                        GroupCipherHelper.buildAad("group-1", "sender-b", "msg-1")));
        assertFalse("changing messageId must change the AAD bytes",
                java.util.Arrays.equals(base,
                        GroupCipherHelper.buildAad("group-1", "sender-a", "msg-2")));
    }

    @Test
    public void generateGroupKey_produces32ByteKey() {
        String keyB64 = GroupCipherHelper.generateGroupKey();
        assertNotNull(keyB64);
        // Base64 (no padding stripped, no wrap) of 32 raw bytes is 44 chars,
        // possibly with trailing '=' padding depending on encoder flags used
        // elsewhere — assert decoded length precisely instead of the string form.
        byte[] decoded = android.util.Base64.decode(keyB64, android.util.Base64.NO_WRAP);
        assertEquals("group key must be 32 bytes (AES-256)", 32, decoded.length);
    }
}
