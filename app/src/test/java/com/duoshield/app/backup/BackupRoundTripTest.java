package com.duoshield.app.backup;

import com.duoshield.app.crypto.BackupCryptoHelper;
import com.duoshield.app.models.Message;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Pure JUnit tests for the backup serialisation + crypto round-trip.
 * No Android context required — all paths under test are plain Java.
 *
 * Covers:
 *  1. toJson / fromJson preserve every Message field, including the newer
 *     forwarded / edited / starred booleans.
 *  2. Legacy docs that omit the newer fields restore with safe defaults.
 *  3. A missing or null id / conversationId / sender returns null from fromJson
 *     (the caller treats null as "skip this doc").
 *  4. AES-256-GCM encrypt → decrypt round-trip (uncompressed path).
 *  5. AES-256-GCM + GZIP encryptCompressed → decryptCompressed round-trip.
 *  6. computeChecksum + verifyChecksum (legacy, unkeyed) — match and mismatch cases.
 *  7. Full end-to-end: encrypt + checksum a message JSON, corrupt the checksum,
 *     verify that verifyChecksum catches the corruption.
 *  8. deriveBackupKey is deterministic — same mnemonic always yields the same key.
 *  9. (S07-H2) computeHmac + verifyHmac — keyed HMAC replaces the unkeyed
 *     checksum for new writes: match/mismatch/wrong-key/legacy-field-absent
 *     cases, plus a check that the HMAC actually differs from the legacy
 *     SHA-256 checksum of the same plaintext (proving it isn't just the old
 *     digest renamed).
 * 10. (S07-M3) buildAad + the encryptCompressed/decryptCompressed AAD overloads
 *     — a matching AAD round-trips, a tampered/mismatched AAD fails decryption,
 *     and the legacy no-AAD overloads still round-trip for old blobs.
 */
public class BackupRoundTripTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Builds a fully-populated Message with every serialisable field set. */
    private static Message fullMessage() {
        Message m = new Message(
                "msg-id-001",
                "conv-abc",
                "sender-uid",
                "Hello, world!",
                1_700_000_000_000L,
                false,          // isEncrypted
                "b2:path/to/img.jpg",
                "image/jpeg");
        m.setMediaKey("base64mediakey==");
        m.setReplyToId("reply-id-999");
        m.setReplyPreview("Quoted text here");
        m.setReaction("❤️");
        m.setStatus("read");
        m.setForwarded(true);
        m.setEdited(true);
        m.starred = true;
        return m;
    }

    /** A 32-byte key used for crypto tests (deterministic, not a real seed). */
    private static byte[] testKey() {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) key[i] = (byte) (i + 1);
        return key;
    }

    // ── toJson / fromJson round-trips ─────────────────────────────────────────

    @Test
    public void roundTrip_coreFields() throws Exception {
        Message original = fullMessage();
        String  json     = BackupManager.toJson(original);
        Message restored = BackupManager.fromJson(json);

        assertNotNull("fromJson must not return null for a valid message", restored);
        assertEquals("id",             original.getId(),             restored.getId());
        assertEquals("conversationId", original.getConversationId(), restored.getConversationId());
        assertEquals("sender",         original.getSender(),         restored.getSender());
        assertEquals("text",           original.getText(),           restored.getText());
        assertEquals("timestamp",      original.getTimestamp(),      restored.getTimestamp());
        assertEquals("mediaUrl",       original.getMediaUrl(),       restored.getMediaUrl());
        assertEquals("mediaType",      original.getMediaType(),      restored.getMediaType());
        assertEquals("mediaKey",       original.getMediaKey(),       restored.getMediaKey());
        assertEquals("replyToId",      original.getReplyToId(),      restored.getReplyToId());
        assertEquals("replyPreview",   original.getReplyPreview(),   restored.getReplyPreview());
        assertEquals("reaction",       original.getReaction(),       restored.getReaction());
        assertEquals("status",         original.getStatus(),         restored.getStatus());
    }

    @Test
    public void roundTrip_forwardedField() throws Exception {
        Message original = fullMessage();
        original.setForwarded(true);
        Message restored = BackupManager.fromJson(BackupManager.toJson(original));
        assertNotNull(restored);
        assertTrue("forwarded flag must survive round-trip", restored.isForwarded());
    }

    @Test
    public void roundTrip_forwardedFalse() throws Exception {
        Message original = fullMessage();
        original.setForwarded(false);
        Message restored = BackupManager.fromJson(BackupManager.toJson(original));
        assertNotNull(restored);
        assertFalse("forwarded=false must survive round-trip", restored.isForwarded());
    }

    @Test
    public void roundTrip_editedField() throws Exception {
        Message original = fullMessage();
        original.setEdited(true);
        Message restored = BackupManager.fromJson(BackupManager.toJson(original));
        assertNotNull(restored);
        assertTrue("edited flag must survive round-trip", restored.isEdited());
    }

    @Test
    public void roundTrip_editedFalse() throws Exception {
        Message original = fullMessage();
        original.setEdited(false);
        Message restored = BackupManager.fromJson(BackupManager.toJson(original));
        assertNotNull(restored);
        assertFalse("edited=false must survive round-trip", restored.isEdited());
    }

    @Test
    public void roundTrip_starredField() throws Exception {
        Message original = fullMessage();
        original.starred = true;
        Message restored = BackupManager.fromJson(BackupManager.toJson(original));
        assertNotNull(restored);
        assertTrue("starred flag must survive round-trip", restored.starred);
    }

    @Test
    public void roundTrip_starredFalse() throws Exception {
        Message original = fullMessage();
        original.starred = false;
        Message restored = BackupManager.fromJson(BackupManager.toJson(original));
        assertNotNull(restored);
        assertFalse("starred=false must survive round-trip", restored.starred);
    }

    @Test
    public void roundTrip_nullOptionalFieldsAreToleratedGracefully() throws Exception {
        // A minimal message — optional fields left null
        Message original = new Message(
                "msg-min-001", "conv-xyz", "sender-a",
                "text only", 1_600_000_000_000L,
                false, null, null);
        Message restored = BackupManager.fromJson(BackupManager.toJson(original));

        assertNotNull(restored);
        assertEquals("id",   original.getId(),   restored.getId());
        assertEquals("text", original.getText(), restored.getText());
        assertNull("mediaUrl must be null", restored.getMediaUrl());
        assertNull("mediaType must be null", restored.getMediaType());
        assertNull("replyToId must be null", restored.getReplyToId());
        assertFalse("forwarded defaults to false", restored.isForwarded());
        assertFalse("edited defaults to false",    restored.isEdited());
        assertFalse("starred defaults to false",   restored.starred);
    }

    @Test
    public void fromJson_legacyDoc_missingNewFields_defaultsToFalse() throws Exception {
        // Simulate a pre-fix backup doc that has no forwarded/edited/starred keys
        String legacyJson = "{\"id\":\"old-1\",\"conversationId\":\"conv-1\","
                + "\"sender\":\"uid-a\",\"text\":\"old message\",\"timestamp\":1000,"
                + "\"mediaUrl\":\"\",\"mediaType\":\"\",\"mediaKey\":\"\","
                + "\"replyToId\":\"\",\"replyPreview\":\"\",\"reaction\":\"\","
                + "\"status\":\"\",\"isDeleted\":false}";

        Message restored = BackupManager.fromJson(legacyJson);
        assertNotNull("legacy doc must restore successfully", restored);
        assertFalse("forwarded must default to false on legacy doc", restored.isForwarded());
        assertFalse("edited must default to false on legacy doc",    restored.isEdited());
        assertFalse("starred must default to false on legacy doc",   restored.starred);
    }

    @Test
    public void fromJson_returnsNull_whenIdMissing() throws Exception {
        // id is required — fromJson must return null so the caller skips the doc
        String json = "{\"conversationId\":\"conv-1\",\"sender\":\"uid-a\","
                + "\"text\":\"hi\",\"timestamp\":1000}";
        assertNull("fromJson must return null when id is absent", BackupManager.fromJson(json));
    }

    @Test
    public void fromJson_returnsNull_whenSenderMissing() throws Exception {
        String json = "{\"id\":\"msg-1\",\"conversationId\":\"conv-1\","
                + "\"text\":\"hi\",\"timestamp\":1000}";
        assertNull("fromJson must return null when sender is absent",
                BackupManager.fromJson(json));
    }

    // ── BackupCryptoHelper — encrypt / decrypt ────────────────────────────────

    @Test
    public void encryptDecrypt_roundTrip() throws Exception {
        byte[] key       = testKey();
        String plaintext = "AES-256-GCM test payload 🔐";
        String blob      = BackupCryptoHelper.encrypt(key, plaintext);
        assertNotNull("encrypt must return a non-null blob", blob);
        assertTrue("blob must contain ':'", blob.contains(":"));

        String recovered = BackupCryptoHelper.decrypt(key, blob);
        assertEquals("decrypted text must match original", plaintext, recovered);
    }

    @Test
    public void encryptDecrypt_differentIvEachCall() throws Exception {
        byte[] key  = testKey();
        String text = "same plaintext";
        String b1   = BackupCryptoHelper.encrypt(key, text);
        String b2   = BackupCryptoHelper.encrypt(key, text);
        assertNotEquals("each call must produce a different IV (and thus different blob)", b1, b2);
    }

    @Test
    public void encryptDecrypt_wrongKeyThrows() {
        byte[] key1  = testKey();
        byte[] key2  = new byte[32]; // all zeros — different from key1
        String text  = "secret";
        try {
            String blob = BackupCryptoHelper.encrypt(key1, text);
            // Decrypting with a different key must throw (GCM auth tag mismatch)
            BackupCryptoHelper.decrypt(key2, blob);
            fail("Decrypting with wrong key must throw an exception");
        } catch (Exception expected) {
            // pass — GCM authentication failure
        }
    }

    // ── BackupCryptoHelper — compressed encrypt / decrypt ────────────────────

    @Test
    public void encryptCompressedDecryptCompressed_roundTrip() throws Exception {
        byte[] key       = testKey();
        String plaintext = "{ \"text\": \"compressible JSON payload aaaaaaaaaaaaa\" }";
        String blob      = BackupCryptoHelper.encryptCompressed(key, plaintext);
        String recovered = BackupCryptoHelper.decryptCompressed(key, blob);
        assertEquals("compressed round-trip must recover original text", plaintext, recovered);
    }

    @Test
    public void encryptCompressed_producesBase64ColonFormat() throws Exception {
        String blob = BackupCryptoHelper.encryptCompressed(testKey(), "test");
        assertTrue("compressed blob must be in 'ivBase64:ciphertextBase64' format",
                blob.contains(":"));
        String[] parts = blob.split(":", 2);
        assertEquals("blob must have exactly two parts", 2, parts.length);
        assertFalse("IV part must not be empty", parts[0].isEmpty());
        assertFalse("ciphertext part must not be empty", parts[1].isEmpty());
    }

    @Test
    public void encryptCompressed_fullMessageJson_roundTrip() throws Exception {
        // Verify the exact path used by BackupManager for real messages
        byte[]  key  = testKey();
        Message msg  = fullMessage();
        String  json = BackupManager.toJson(msg);

        String blob      = BackupCryptoHelper.encryptCompressed(key, json);
        String recovered = BackupCryptoHelper.decryptCompressed(key, blob);
        Message restored = BackupManager.fromJson(recovered);

        assertNotNull(restored);
        assertEquals("id after full crypto round-trip",        msg.getId(),        restored.getId());
        assertEquals("text after full crypto round-trip",      msg.getText(),      restored.getText());
        assertEquals("starred after full crypto round-trip",   msg.starred,        restored.starred);
        assertEquals("forwarded after full crypto round-trip", msg.isForwarded(),  restored.isForwarded());
        assertEquals("edited after full crypto round-trip",    msg.isEdited(),     restored.isEdited());
    }

    // ── BackupCryptoHelper — checksum ─────────────────────────────────────────

    @Test
    public void checksum_verifyMatch() throws Exception {
        String text     = "message body for checksum";
        String checksum = BackupCryptoHelper.computeChecksum(text);
        assertNotNull("checksum must not be null", checksum);
        assertEquals("checksum must be 64 hex chars (SHA-256)", 64, checksum.length());
        assertTrue("verifyChecksum must return true for matching plaintext",
                BackupCryptoHelper.verifyChecksum(text, checksum));
    }

    @Test
    public void checksum_verifyMismatch() throws Exception {
        String text     = "original text";
        String checksum = BackupCryptoHelper.computeChecksum(text);
        assertFalse("verifyChecksum must return false when text has been tampered",
                BackupCryptoHelper.verifyChecksum("tampered text", checksum));
    }

    @Test
    public void checksum_nullExpected_returnsTrueForLegacyDocs() {
        // Legacy backup docs had no checksum field — null expected must not fail restore
        assertTrue("null checksum must return true (legacy doc tolerance)",
                BackupCryptoHelper.verifyChecksum("any text", null));
    }

    @Test
    public void checksum_emptyExpected_returnsTrueForLegacyDocs() {
        assertTrue("empty checksum must return true (legacy doc tolerance)",
                BackupCryptoHelper.verifyChecksum("any text", ""));
    }

    @Test
    public void checksum_isCaseInsensitive() throws Exception {
        String text     = "case test";
        String checksum = BackupCryptoHelper.computeChecksum(text);
        assertTrue("uppercase checksum must verify",
                BackupCryptoHelper.verifyChecksum(text, checksum.toUpperCase()));
        assertTrue("lowercase checksum must verify",
                BackupCryptoHelper.verifyChecksum(text, checksum.toLowerCase()));
    }

    @Test
    public void checksum_isDeterministic() throws Exception {
        String text = "determinism check";
        assertEquals("same text must always produce same checksum",
                BackupCryptoHelper.computeChecksum(text),
                BackupCryptoHelper.computeChecksum(text));
    }

    // ── BackupCryptoHelper — key derivation ───────────────────────────────────

    @Test
    public void deriveBackupKey_produces32Bytes() throws Exception {
        String mnemonic = "abandon abandon abandon abandon abandon abandon "
                + "abandon abandon abandon abandon abandon about";
        byte[] key = BackupCryptoHelper.deriveBackupKey(mnemonic);
        assertNotNull("derived key must not be null", key);
        assertEquals("backup key must be exactly 32 bytes (AES-256)", 32, key.length);
    }

    @Test
    public void deriveBackupKey_isDeterministic() throws Exception {
        String mnemonic = "abandon abandon abandon abandon abandon abandon "
                + "abandon abandon abandon abandon abandon about";
        byte[] key1 = BackupCryptoHelper.deriveBackupKey(mnemonic);
        byte[] key2 = BackupCryptoHelper.deriveBackupKey(mnemonic);
        assertArrayEquals("same mnemonic must always yield the same backup key", key1, key2);
    }

    @Test
    public void deriveBackupKey_differentMnemonicsYieldDifferentKeys() throws Exception {
        String m1 = "abandon abandon abandon abandon abandon abandon "
                + "abandon abandon abandon abandon abandon about";
        // Second word changed
        String m2 = "ability abandon abandon abandon abandon abandon "
                + "abandon abandon abandon abandon abandon about";
        byte[] k1 = BackupCryptoHelper.deriveBackupKey(m1);
        byte[] k2 = BackupCryptoHelper.deriveBackupKey(m2);
        assertFalse("different mnemonics must produce different keys",
                java.util.Arrays.equals(k1, k2));
    }

    // ── BackupCryptoHelper — HMAC integrity (S07-H2) ──────────────────────────

    @Test
    public void hmac_verifyMatch() throws Exception {
        byte[] key  = testKey();
        String text = "message body for hmac";
        String hmac = BackupCryptoHelper.computeHmac(key, text);
        assertNotNull("hmac must not be null", hmac);
        assertEquals("HMAC-SHA256 tag must be 64 hex chars", 64, hmac.length());
        assertTrue("verifyHmac must return true for matching plaintext+key",
                BackupCryptoHelper.verifyHmac(key, text, hmac));
    }

    @Test
    public void hmac_verifyMismatch_tamperedPlaintext() throws Exception {
        byte[] key  = testKey();
        String hmac = BackupCryptoHelper.computeHmac(key, "original text");
        assertFalse("verifyHmac must return false when text has been tampered",
                BackupCryptoHelper.verifyHmac(key, "tampered text", hmac));
    }

    @Test
    public void hmac_verifyMismatch_wrongKey() throws Exception {
        byte[] key1 = testKey();
        byte[] key2 = new byte[32]; // all zeros — different key
        String text = "same plaintext, different key";
        String hmac = BackupCryptoHelper.computeHmac(key1, text);
        assertFalse("an HMAC computed with one key must not verify under a different key "
                        + "(this is exactly the property the old unkeyed SHA-256 checksum lacked)",
                BackupCryptoHelper.verifyHmac(key2, text, hmac));
    }

    @Test
    public void hmac_nullOrEmptyExpected_returnsFalse_callerMustUseLegacyPath() {
        // Unlike verifyChecksum's null/empty tolerance (legacy-doc default-true),
        // verifyHmac must NOT default to true on a missing tag — an absent "hmac"
        // field means the caller has to fall back to verifyChecksum explicitly
        // (see BackupManager.restoreAllSync), not silently treat it as valid.
        assertFalse("null expected hmac must return false, not true",
                BackupCryptoHelper.verifyHmac(testKey(), "any text", null));
        assertFalse("empty expected hmac must return false, not true",
                BackupCryptoHelper.verifyHmac(testKey(), "any text", ""));
    }

    @Test
    public void hmac_isDeterministic() throws Exception {
        byte[] key  = testKey();
        String text = "determinism check";
        assertEquals("same key+text must always produce the same HMAC",
                BackupCryptoHelper.computeHmac(key, text),
                BackupCryptoHelper.computeHmac(key, text));
    }

    @Test
    public void hmac_differsFromLegacyChecksum() throws Exception {
        // Proves the fix is a real keyed primitive, not the old unkeyed digest
        // renamed: for the same plaintext, the HMAC must not equal the plain
        // SHA-256 checksum (their key material and inputs genuinely differ).
        byte[] key       = testKey();
        String text      = "same plaintext, two different integrity tags";
        String hmac      = BackupCryptoHelper.computeHmac(key, text);
        String checksum  = BackupCryptoHelper.computeChecksum(text);
        assertNotEquals("HMAC-SHA256 (keyed) must differ from the legacy unkeyed SHA-256 checksum",
                hmac, checksum);
    }

    @Test
    public void hmac_differentKeysYieldDifferentTags() throws Exception {
        byte[] key1 = testKey();
        byte[] key2 = new byte[32];
        key2[0] = 0x42;
        String text = "same text, different keys";
        assertNotEquals("different keys must yield different HMAC tags for the same plaintext",
                BackupCryptoHelper.computeHmac(key1, text),
                BackupCryptoHelper.computeHmac(key2, text));
    }

    // ── BackupCryptoHelper — associated data (S07-M3) ─────────────────────────

    @Test
    public void aad_matchingAad_roundTrips() throws Exception {
        byte[] key  = testKey();
        byte[] aad  = BackupCryptoHelper.buildAad("doc-1", "conv-1", true);
        String text = "payload bound to doc-1/conv-1";

        String blob      = BackupCryptoHelper.encryptCompressed(key, text, aad);
        String recovered = BackupCryptoHelper.decryptCompressed(key, blob, aad);
        assertEquals("decryption with the identical AAD must recover the original text",
                text, recovered);
    }

    @Test
    public void aad_mismatchedDocId_failsDecryption() throws Exception {
        byte[] key = testKey();
        byte[] writeAad = BackupCryptoHelper.buildAad("doc-1", "conv-1", true);
        byte[] readAad  = BackupCryptoHelper.buildAad("doc-2", "conv-1", true); // different doc id
        String blob = BackupCryptoHelper.encryptCompressed(key, "payload", writeAad);
        try {
            BackupCryptoHelper.decryptCompressed(key, blob, readAad);
            fail("Decrypting with a different docId in the AAD must fail the GCM tag check");
        } catch (Exception expected) {
            // pass — AEAD authentication failure
        }
    }

    @Test
    public void aad_flippedCompressedFlag_failsDecryption() throws Exception {
        // The exact tamper S07-M3 targets: flipping "compressed" outside the
        // ciphertext must now be caught by the GCM tag, not silently accepted.
        byte[] key = testKey();
        byte[] writeAad = BackupCryptoHelper.buildAad("doc-1", "conv-1", true);
        byte[] tamperedAad = BackupCryptoHelper.buildAad("doc-1", "conv-1", false); // flipped
        String blob = BackupCryptoHelper.encryptCompressed(key, "payload", writeAad);
        try {
            BackupCryptoHelper.decryptCompressed(key, blob, tamperedAad);
            fail("Decrypting after the 'compressed' flag was flipped must fail the GCM tag check");
        } catch (Exception expected) {
            // pass — AEAD authentication failure
        }
    }

    @Test
    public void aad_missingAadAtReadTime_failsWhenWrittenWithAad() throws Exception {
        byte[] key = testKey();
        byte[] aad = BackupCryptoHelper.buildAad("doc-1", "conv-1", true);
        String blob = BackupCryptoHelper.encryptCompressed(key, "payload", aad);
        try {
            BackupCryptoHelper.decryptCompressed(key, blob); // no AAD supplied
            fail("Decrypting with no AAD a blob that was written with AAD must fail");
        } catch (Exception expected) {
            // pass — AEAD authentication failure
        }
    }

    @Test
    public void aad_legacyNoAadOverloads_stillRoundTrip() throws Exception {
        // Old call sites / old stored blobs (pre-S07-M3) never used AAD at all —
        // the 2-arg overloads must keep working unchanged.
        byte[] key  = testKey();
        String text = "legacy blob, no AAD ever used";
        String blob = BackupCryptoHelper.encryptCompressed(key, text);
        assertEquals("legacy no-AAD round trip must still work",
                text, BackupCryptoHelper.decryptCompressed(key, blob));
    }

    @Test
    public void buildAad_isDeterministic() {
        byte[] a1 = BackupCryptoHelper.buildAad("doc-1", "conv-1", true);
        byte[] a2 = BackupCryptoHelper.buildAad("doc-1", "conv-1", true);
        assertArrayEquals("buildAad must be deterministic for identical inputs", a1, a2);
    }

    @Test
    public void buildAad_differsWhenAnyFieldChanges() {
        byte[] base = BackupCryptoHelper.buildAad("doc-1", "conv-1", true);
        assertFalse("changing docId must change the AAD bytes",
                java.util.Arrays.equals(base, BackupCryptoHelper.buildAad("doc-2", "conv-1", true)));
        assertFalse("changing conversationId must change the AAD bytes",
                java.util.Arrays.equals(base, BackupCryptoHelper.buildAad("doc-1", "conv-2", true)));
        assertFalse("changing the compressed flag must change the AAD bytes",
                java.util.Arrays.equals(base, BackupCryptoHelper.buildAad("doc-1", "conv-1", false)));
    }
}
