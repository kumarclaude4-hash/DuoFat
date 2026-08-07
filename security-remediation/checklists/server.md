# CHECKLIST — server (backend / token mint)

- **Purpose:** Server-authoritative auth, token minting, capability decisions.
- **Trust responsibility:** ROOT of trust for authorization. Must be fail-closed.
- **Secrets exposure risk:** Service account, signing/seed material, HMAC keys.
- **Dependency relationships:** Consumed by Android client; reads lock/duress state; gates media/admin.
- **Security responsibilities:** Proof-of-seed verification; deny-by-default; account lock + duress enforcement; replay resistance.
- **Files in scope:** Cloud Functions token-mint handler, lock/duress store, challenge issuance.
- **Findings affecting folder:** S02-C1 (UID-only mint), S02-H1 (fail-open), S02-H2 (no lock), S06-C1 (server duress), S02 M/L.
- **Required changes (R1):** Require proof-of-seed bound to UID; fail-closed on any error; consult lock+duress; deny locked/duress.
- **Verification steps:** Unit tests for deny paths (no proof, bad proof, exception, locked, duress) and allow path; replay/nonce test.
- **Regression checks:** Enrolled client still mints in dual-accept window.
