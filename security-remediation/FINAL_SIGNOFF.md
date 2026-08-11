# FINAL SIGN-OFF

> Status: **PENDING** — this document is completed and signed only after Round 3
> (the final planned round) exits. It is the hard stop of the program.

Sign-off criteria — status as **re-assessed and corrected 2026-08-11** against
[`../BUG_TRACKER.md`](../BUG_TRACKER.md) (the successor to the deleted `FINDING_INDEX.md`) and source.
The four boxes below were previously checked `[x]`; that was false (see the correction note) and they
are now unchecked. **Updated same day, tracker consolidation pass:** `../BUG_TRACKER.md` now records
real, re-verified-from-source dispositions — **25 fixed, 6 partial, 12 accepted, 73 still open** —
which is real progress over the "0 dispositions" state below, but still far short of sign-off.

- [ ] **Every audit finding has exactly one final disposition.** **NOT MET.** `../BUG_TRACKER.md`
      records 25 `fixed`, 6 `partial`, 12 `accepted`, and **73 still `open`** out of 116. The earlier
      "116/116, 0 open" claim in a now-deleted report was produced by miscounting an intent column,
      which carried no evidentiary weight.
- [ ] **Every Critical finding is fixed or accepted-with-justification.** **PARTIAL.** `S07-C1` is
      genuinely fixed and verified (Round 1). Remaining Criticals depend on Round 3, which is not
      implemented.
- [ ] **Every High finding is fixed or accepted-with-justification.** **NOT MET.** Source shows
      unfixed Highs, e.g. `S01-H1` (cross-user prekey overwrite still allowed by `firestore.rules`)
      and `S02-H1` (`/migrateUid` verbatim copy). These are Round 3 items and Round 3 has no code.
- [ ] **Every Medium/Low/Informational finding is fixed, accepted, or deferred-with-justification.**
      **NOT MET** — the bulk of these are Round 3 (85 of 116 findings are R3) and remain open.
- [x] FINAL_SECURITY_REPORT.md written — see [`FINAL_SECURITY_REPORT.md`](./FINAL_SECURITY_REPORT.md)
      (corrected 2026-08-11 to reflect the true state).
- [ ] **Every fix has source + test evidence.** Source review: yes, all. Executed tests: **server layer
      only** (153/153 pass). The Android layer has **never been compiled** and the Firestore rules
      tests have **never been executed** — no JDK/Gradle/SDK and no `firebase` CLI in the remediation
      environment. Source-reviewed is not test-verified, and this box stays unchecked until a build
      environment proves otherwise.
- [ ] Every trust boundary in TRUST_BOUNDARIES.md is revalidated (verified / accepted).
- [ ] No unreviewed trust-boundary change remains.
- [ ] **Operator actions complete.** 8 outstanding — `FINAL_SECURITY_REPORT.md` §3. Blocking ones:
      the **leaked GCP service-account key is still un-revoked** (it shipped inside published APKs, so
      it must be assumed compromised), remaining credential rotations, and `SC-12` branch protection
      (re-checked 2026-08-11: still `404 Branch not protected`).
- [ ] **Server and APK released together.** `/mintToken` hard-requires `nonce`+`signatureHex`; a
      server-only deploy breaks old clients, and making those fields optional to accommodate them
      would reintroduce the `S07-C1` account takeover.

## Why this is still PENDING

The **code** remediation is **not** complete. Rounds 1–2 are code-complete and, for the server,
genuinely test-verified (153/153); **Round 3 — 85 of the 116 findings — is not implemented** (no R3
commit; source confirms unfixed Highs such as `S01-H1` and `S02-H1`). On top of that, the
**deployment** is not remediated: a live compromised GCP admin credential and an uncompiled Android
client are not paperwork.

Per `SESSION_PROTOCOL.md` §9 and this program's history of false-progress incidents, signing this
document now would repeat that failure. The 2026-08-11 correction pass **withdrew** four boxes that
had been checked on the strength of the intent column rather than source. Sign this only when the
unchecked boxes above are actually true — Round 3 genuinely implemented and verified, operator items
1–5 done — and verify each from source and command output, not from this file.

Final tallies, per-finding dispositions, and signatures are written here at program close.
