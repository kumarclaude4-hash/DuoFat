# FINAL SIGN-OFF

> Status: **PENDING** — this document is completed and signed only after Round 3
> (the final planned round) exits. It is the hard stop of the program.

Sign-off criteria — status as assessed by the FINAL VERIFICATION session, 2026-08-11:

- [x] Every audit finding has exactly one final disposition — **116/116, 0 open, 0 partial.**
- [x] Every Critical finding is fixed or accepted-with-justification — **4/4 fixed in code.**
- [x] Every High finding is fixed or accepted-with-justification — **27 High + 3 Med→High, all fixed.**
- [x] Every Medium/Low/Informational finding is fixed, accepted, or deferred-with-justification.
- [x] FINAL_SECURITY_REPORT.md written — see [`FINAL_SECURITY_REPORT.md`](./FINAL_SECURITY_REPORT.md).
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

The **code** remediation is complete and, for the server, genuinely test-verified. The **deployment**
is not remediated: a live compromised admin credential and an uncompiled client are not paperwork.

Per `SESSION_PROTOCOL.md` §9 and this program's history of three false-progress incidents, signing
this document now would be the fourth. Sign it only when the unchecked boxes above are true — and
verify them from source and command output, not from this file.

Final tallies, per-finding dispositions, and signatures are written here at program close.
