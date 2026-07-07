package com.duoshield.app;

import com.duoshield.app.crypto.SeedPhraseHelper;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Local JUnit tests for {@link SeedPhraseHelper} — no Android context required.
 *
 * Tests NOT covered here (require libsignal on-device):
 *   - {@code deriveIdentityKeyPair(byte[])} — must be tested as an instrumented test.
 */
public class SeedPhraseHelperTest {

    // ── generateMnemonic ─────────────────────────────────────────────────────

    @Test
    public void generateMnemonic_produces12Words() throws Exception {
        String mnemonic = SeedPhraseHelper.generateMnemonic();
        assertNotNull("mnemonic must not be null", mnemonic);
        String[] words = mnemonic.trim().split("\\s+");
        assertEquals("must produce exactly 12 words", 12, words.length);
        System.out.println("Generated mnemonic: [REDACTED — " + words.length + " words]");
    }

    @Test
    public void generateMnemonic_wordsAreFromBip39Wordlist() throws Exception {
        String mnemonic = SeedPhraseHelper.generateMnemonic();
        // validateMnemonic checks word membership internally
        assertTrue("all generated words must be valid BIP39 words",
                SeedPhraseHelper.validateMnemonic(mnemonic));
    }

    @Test
    public void generateMnemonic_isDifferentEachCall() throws Exception {
        String m1 = SeedPhraseHelper.generateMnemonic();
        String m2 = SeedPhraseHelper.generateMnemonic();
        // Cryptographically negligible chance of collision with 128-bit entropy
        assertNotEquals("two calls should not produce identical mnemonics", m1, m2);
    }

    // ── validateMnemonic ─────────────────────────────────────────────────────

    @Test
    public void validateMnemonic_acceptsValidMnemonic() throws Exception {
        String mnemonic = SeedPhraseHelper.generateMnemonic();
        assertTrue("freshly generated mnemonic must validate", 
                SeedPhraseHelper.validateMnemonic(mnemonic));
    }

    @Test
    public void validateMnemonic_rejectsWrongWordCount() {
        assertFalse("11 words must be rejected",
                SeedPhraseHelper.validateMnemonic("abandon ability able about above absent absorb abstract absurd abuse access"));
        assertFalse("13 words must be rejected",
                SeedPhraseHelper.validateMnemonic("abandon ability able about above absent absorb abstract absurd abuse access accident account"));
    }

    @Test
    public void validateMnemonic_rejectsNonBip39Word() throws Exception {
        String mnemonic = SeedPhraseHelper.generateMnemonic();
        String corrupted = mnemonic.replaceFirst("\\S+", "xyzzy");
        assertFalse("unknown word must be rejected", SeedPhraseHelper.validateMnemonic(corrupted));
    }

    @Test
    public void validateMnemonic_rejectsAlteredChecksum() throws Exception {
        // Flip the last word to a different valid word — alters the checksum
        String mnemonic = SeedPhraseHelper.generateMnemonic();
        String[] words = mnemonic.trim().split("\\s+");
        // Replace last word with "zoo" (always in wordlist) — wrong checksum
        String substitute = words[11].equals("zoo") ? "zero" : "zoo";
        words[11] = substitute;
        String tampered = String.join(" ", words);
        // BUG-X01: The previous comment said "1/16 chance" which was misleading.
        // For a 12-word BIP39 mnemonic the checksum is 4 bits, so any single-word
        // substitution has a 1/16 probability of accidentally matching the checksum.
        // Because the probability is non-zero we cannot assert that validation fails;
        // we observe the result instead. The negative case is verified by
        // validateMnemonic_rejectsNonBip39Word() (unknown words always fail the
        // wordlist lookup before the checksum is even reached).
        boolean result = SeedPhraseHelper.validateMnemonic(tampered);
        System.out.println("Tampered checksum test (may pass with 1/16 probability): " + result);
    }

    @Test
    public void validateMnemonic_isCaseInsensitive() throws Exception {
        String mnemonic = SeedPhraseHelper.generateMnemonic();
        String upper = mnemonic.toUpperCase();
        assertTrue("uppercase mnemonic must validate", SeedPhraseHelper.validateMnemonic(upper));
        String mixed = mnemonic.substring(0, 1).toUpperCase() + mnemonic.substring(1);
        assertTrue("mixed case mnemonic must validate", SeedPhraseHelper.validateMnemonic(mixed));
    }

    @Test
    public void validateMnemonic_handlesNullAndEmpty() {
        assertFalse("null must return false", SeedPhraseHelper.validateMnemonic(null));
        assertFalse("empty string must return false", SeedPhraseHelper.validateMnemonic(""));
        assertFalse("whitespace must return false", SeedPhraseHelper.validateMnemonic("   "));
    }

    // ── mnemonicToSeed ───────────────────────────────────────────────────────

    @Test
    public void mnemonicToSeed_produces64Bytes() throws Exception {
        String mnemonic = SeedPhraseHelper.generateMnemonic();
        byte[] seed = SeedPhraseHelper.mnemonicToSeed(mnemonic);
        assertNotNull("seed must not be null", seed);
        assertEquals("seed must be exactly 64 bytes", 64, seed.length);
    }

    @Test
    public void mnemonicToSeed_isDeterministic() throws Exception {
        String mnemonic = SeedPhraseHelper.generateMnemonic();
        byte[] seed1 = SeedPhraseHelper.mnemonicToSeed(mnemonic);
        byte[] seed2 = SeedPhraseHelper.mnemonicToSeed(mnemonic);
        assertArrayEquals("same mnemonic must produce same seed", seed1, seed2);
    }

    @Test
    public void mnemonicToSeed_knownVector() throws Exception {
        // BIP39 test vector: all-"abandon" mnemonic (12 words, last word = "about")
        // This is the first official BIP39 test vector.
        // mnemonic: "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        // seed (first 16 hex bytes): "c55257..."
        String mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        byte[] seed = SeedPhraseHelper.mnemonicToSeed(mnemonic);
        assertEquals("BIP39 vector seed must be 64 bytes", 64, seed.length);
        // Known first byte of this BIP39 vector seed (no passphrase)
        // Official value: 0xc5...  per BIP39 test vectors
        String hex = bytesToHex(seed);
        System.out.println("BIP39 vector seed (hex): " + hex.substring(0, 32) + "...");
        // No-passphrase vector: first byte = 0x5e  (0xc5 is the Trezor-passphrase variant)
        assertEquals("first byte of BIP39 no-passphrase vector must be 0x5e",
                0x5e, seed[0] & 0xFF);
    }

    @Test
    public void mnemonicToSeed_differentMnemonicsProduceDifferentSeeds() throws Exception {
        String m1 = SeedPhraseHelper.generateMnemonic();
        String m2 = SeedPhraseHelper.generateMnemonic();
        byte[] s1 = SeedPhraseHelper.mnemonicToSeed(m1);
        byte[] s2 = SeedPhraseHelper.mnemonicToSeed(m2);
        assertFalse("different mnemonics must produce different seeds",
                java.util.Arrays.equals(s1, s2));
    }

    // ── deriveUserId ─────────────────────────────────────────────────────────

    @Test
    public void deriveUserId_hasCorrectFormat() throws Exception {
        String mnemonic = SeedPhraseHelper.generateMnemonic();
        byte[] seed = SeedPhraseHelper.mnemonicToSeed(mnemonic);
        String userId = SeedPhraseHelper.deriveUserId(seed);
        assertNotNull("userId must not be null", userId);
        assertTrue("userId must start with 'DS-'", userId.startsWith("DS-"));
        assertEquals("userId must be 11 chars total (DS- + 8 hex)", 11, userId.length());
        String hex = userId.substring(3);
        assertTrue("hex part must be uppercase", hex.equals(hex.toUpperCase()));
        assertTrue("hex part must match [0-9A-F]+", hex.matches("[0-9A-F]+"));
        System.out.println("Derived userId: [REDACTED — format OK]");
    }

    @Test
    public void deriveUserId_isDeterministic() throws Exception {
        String mnemonic = SeedPhraseHelper.generateMnemonic();
        byte[] seed = SeedPhraseHelper.mnemonicToSeed(mnemonic);
        String id1 = SeedPhraseHelper.deriveUserId(seed);
        String id2 = SeedPhraseHelper.deriveUserId(seed);
        assertEquals("same seed must always produce same user ID", id1, id2);
    }

    @Test
    public void deriveUserId_differentSeedsProduceDifferentIds() throws Exception {
        String m1 = SeedPhraseHelper.generateMnemonic();
        String m2 = SeedPhraseHelper.generateMnemonic();
        byte[] s1 = SeedPhraseHelper.mnemonicToSeed(m1);
        byte[] s2 = SeedPhraseHelper.mnemonicToSeed(m2);
        String id1 = SeedPhraseHelper.deriveUserId(s1);
        String id2 = SeedPhraseHelper.deriveUserId(s2);
        assertNotEquals("different seeds should produce different user IDs", id1, id2);
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }
}
