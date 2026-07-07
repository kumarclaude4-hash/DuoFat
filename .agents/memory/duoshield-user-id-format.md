---
name: DuoShield User ID format
description: The account ID format changed from hex DS-XXXXXXXXXXXXXXXX to Base32 XXXXX-XXXXX-XXX
---

# Account ID format (Base32 custom alphabet)

**Rule:** All new accounts get IDs in the form `XXXXX-XXXXX-XXX` (13 base-32 digits + 2 dashes).

**Alphabet:** `23456789ABCDEFGHJKLMNPQRSTUVWXYZ` (32 chars, no O/I/L/0/1 ambiguity).

**Why:** Old format (`DS-A1B2C3D4E5F6A7B8`) was hex, visually ambiguous, and had an unnecessary prefix. New format is URL-safe, unambiguous, and user-readable.

**Derivation:** SHA-256(seed) → first 8 bytes → BigInteger → divide by 32 thirteen times → formatted string. Same collision resistance (64 bits / ~1.8×10¹⁹ IDs).

**How to apply:**
- `SeedPhraseHelper.deriveUserId(byte[] seed)` produces this format.
- `RestoreFromSeedActivity` validates via `equalsIgnoreCase` — works transparently with any format.
- Clipboard auto-paste in `AddContactActivity` matches with regex: `[23456789A-HJ-NP-Z]{5}-[23456789A-HJ-NP-Z]{5}-[23456789A-HJ-NP-Z]{3}`
- Existing users with DS- format IDs will need to re-generate (expected; dev environment).
