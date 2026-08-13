package com.duoshield.app.contacts;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Canonical DuoShield Account ID format (S08-L1).
 *
 * <p>Account IDs look like {@code K3MNP-Q8RXA-7BC}: three groups (5-5-3) drawn from an
 * unambiguous base32 alphabet that excludes the characters people routinely confuse when
 * transcribing by hand or reading off a screen ({@code 0/O}, {@code 1/I/L}).
 *
 * <h3>Why this exists as its own class (S08-L1)</h3>
 * Before this fix, the canonical format regex existed in exactly one place —
 * {@code AddContactActivity.tryPasteFromClipboard()} — and gated only the clipboard
 * auto-paste path. {@code AddContactActivity.handleDeepLink()} took the opposite,
 * fail-open stance: it read {@code getIntent().getData().getPath()} — attacker-controlled
 * input from any app or web page that can fire an {@code ACTION_VIEW} intent at
 * {@code duoshield://add/<path>} — stripped the leading slash, and dropped whatever
 * string remained straight into the "Enter Account ID" field with zero validation, as
 * long as it was non-empty. A hostile deep link could not directly authenticate as
 * another user (the string still has to resolve via {@code identities/{userId}.uid} in
 * {@code ContactManager}), but it could stage the field with confusing/oversized/
 * non-canonical text, defeat the "this looks like a real Account ID" visual review the
 * clipboard path already enforces, or wedge in control characters / excessive length
 * ahead of a UI widget that assumed the clipboard-validated shape.
 *
 * <p>Extracting the format into a single, Android-independent, unit-testable class lets
 * both entry points (deep link, clipboard) enforce the identical canonical shape instead
 * of one being fail-open, and gives future entry points (e.g. a future universal link
 * handler) one obvious place to call.
 */
public final class AccountIdValidator {

    private AccountIdValidator() {}

    /** Five, dash, five, dash, three — unambiguous base32 alphabet only. */
    private static final Pattern ACCOUNT_ID_PATTERN =
            Pattern.compile("[23456789A-HJ-NP-Z]{5}-[23456789A-HJ-NP-Z]{5}-[23456789A-HJ-NP-Z]{3}");

    /**
     * Canonicalizes {@code raw} (trims surrounding whitespace, upper-cases) and returns
     * the canonical form only if the result matches the exact Account ID shape.
     *
     * @return the canonicalized Account ID, or {@code null} if {@code raw} is null, blank,
     *         or does not match the canonical shape after canonicalization.
     */
    public static String canonicalizeOrNull(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        return ACCOUNT_ID_PATTERN.matcher(s).matches() ? s : null;
    }

    /** True if {@code raw} canonicalizes to a valid Account ID. */
    public static boolean isValid(String raw) {
        return canonicalizeOrNull(raw) != null;
    }
}
