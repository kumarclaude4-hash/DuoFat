---
name: DuoShield PBKDF2 PIN format
description: How app PIN and duress PIN are stored after the HANDOFF_v2 fix
---

**Format:** `"<16-byte-hex-salt>:<32-byte-hex-hash>"`
- Algorithm: PBKDF2WithHmacSHA256
- Iterations: 310,000
- Output length: 256 bits

**Why:** Old code used SHA-256 without salt/iterations — trivially rainbow-table attacked.

**Breaking change:** Existing users stored as plain 64-char hex will NOT match the new format (no `:` separator). They will be locked out and must reset their PIN. This is intentional for a security app.

**How to apply:** PinManager and DuressManager both use this format. Any new PIN-like feature must match. Comparisons must use `constantTimeEquals` to prevent timing attacks.
