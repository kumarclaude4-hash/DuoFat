package com.duoshield.app.contacts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link AccountIdValidator} (S08-L1).
 *
 * <p>Runs on the JVM, no Android dependency — same pattern as
 * {@code YouTubeUrlParserTest}. Covers the shape {@code AddContactActivity.handleDeepLink()}
 * must now enforce on attacker-controlled deep-link paths (it previously trusted any
 * non-empty path unconditionally), and the shape {@code tryPasteFromClipboard()} already
 * enforced before this fix (kept passing to confirm both entry points now share identical
 * behavior via this class).
 */
public class AccountIdValidatorTest {

    private static final String VALID = "K3MNP-Q8RXA-7BC";

    // ── Accepted forms ───────────────────────────────────────────────────────

    @Test
    public void canonicalShapeIsAccepted() {
        assertEquals(VALID, AccountIdValidator.canonicalizeOrNull(VALID));
        assertTrue(AccountIdValidator.isValid(VALID));
    }

    @Test
    public void lowerCaseIsCanonicalizedToUpperCase() {
        assertEquals(VALID, AccountIdValidator.canonicalizeOrNull(VALID.toLowerCase()));
    }

    @Test
    public void surroundingWhitespaceIsTrimmed() {
        assertEquals(VALID, AccountIdValidator.canonicalizeOrNull("  " + VALID + "\n"));
    }

    // ── Rejected forms — the deep-link fail-open cases this fix closes ───────

    @Test
    public void nullIsRejected() {
        assertNull(AccountIdValidator.canonicalizeOrNull(null));
        assertTrue(!AccountIdValidator.isValid(null));
    }

    @Test
    public void emptyStringIsRejected() {
        assertNull(AccountIdValidator.canonicalizeOrNull(""));
    }

    @Test
    public void arbitraryDeepLinkPathIsRejected() {
        // What handleDeepLink() used to accept unconditionally: any non-empty path
        // segment from an attacker-controlled duoshield://add/<path> intent.
        assertNull(AccountIdValidator.canonicalizeOrNull("javascript:alert(1)"));
        assertNull(AccountIdValidator.canonicalizeOrNull("../../etc/passwd"));
        assertNull(AccountIdValidator.canonicalizeOrNull("not-an-account-id"));
        assertNull(AccountIdValidator.canonicalizeOrNull("<script>"));
    }

    @Test
    public void ambiguousCharactersAreRejected() {
        // 0/O and 1/I/L are deliberately excluded from the base32 alphabet.
        assertNull(AccountIdValidator.canonicalizeOrNull("K3MN0-Q8RXA-7BC"));
        assertNull(AccountIdValidator.canonicalizeOrNull("K3MNO-Q8RXA-7BC".replace('O', '0')));
        assertNull(AccountIdValidator.canonicalizeOrNull("K3MN1-Q8RXA-7BC"));
        assertNull(AccountIdValidator.canonicalizeOrNull("K3MNL-Q8RXA-7BC"));
        assertNull(AccountIdValidator.canonicalizeOrNull("K3MNI-Q8RXA-7BC"));
        assertNull(AccountIdValidator.canonicalizeOrNull("K3MNO-Q8RXA-7BC"));
    }

    @Test
    public void wrongGroupLengthsAreRejected() {
        assertNull(AccountIdValidator.canonicalizeOrNull("K3MN-Q8RXA-7BC"));   // 4-5-3
        assertNull(AccountIdValidator.canonicalizeOrNull("K3MNP-Q8RXA-7BCD")); // 5-5-4
        assertNull(AccountIdValidator.canonicalizeOrNull("K3MNPQ8RXA7BC"));    // no dashes
    }

    @Test
    public void oversizedInputIsRejected() {
        StringBuilder sb = new StringBuilder(VALID);
        for (int i = 0; i < 500; i++) sb.append('A');
        assertNull(AccountIdValidator.canonicalizeOrNull(sb.toString()));
    }
}
