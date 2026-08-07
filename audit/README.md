# DuoShield Security Audit

This folder is the **single source of truth** for the DuoShield security assessment.
It is written so that any security engineer can pick up the audit **cold** — with no
prior conversation context — and continue exactly where the previous session stopped.

---

## START HERE — the audit is COMPLETE

**All ten sessions are done. Read [`SESSION-10-SYNTHESIS.md`](./SESSION-10-SYNTHESIS.md) first — it is
the final report** and it contains the aggregate findings, the eight cross-cutting themes, the
regression verdict on the prior review, the advertised-guarantees scorecard, and the prioritized
remediation roadmap.

**Audit total: 4 Critical / 28 High / 28 Medium / 34 Low / 23 Informational — 117 findings.**

### The two things to act on before anything else

1. **`S08-C1`** — the Firebase **Admin service-account private key** is packaged into every published
   release APK (`release.yml` writes it into `app/src/main/assets/`, which R8 never touches). Admin SDK
   credentials bypass Firestore rules entirely and can mint a token for any uid. **This invalidates the
   controls Sessions 01–06 audited.** `SC-02` adds the B2 key pair and `WORKER_SECRET` to the same
   exposure.
2. **`S07-C1`** — `/mintToken` accepts a value the client publishes publicly as proof of account
   ownership, so any account can be taken over from its Account ID alone, **without the seed phrase**.

Everything else is secondary: an attacker does not need it. See `SESSION-10-SYNTHESIS.md` §8 for the
five P0 items, starting with credential revocation.

### Reading order

1. **Final report:** [`SESSION-10-SYNTHESIS.md`](./SESSION-10-SYNTHESIS.md).
2. **Context, if you need it:** `SESSION-00-RECON.md` → `ARCHITECTURE.md` → `ATTACK_SURFACE.md` →
   `AUDIT_PROGRESS.md`.
3. **Per-surface detail:** `SESSION-01-FIRESTORE.md` … `SESSION-09-SUPPLY-CHAIN-CI.md`, each with
   `path:line` anchors, exploit paths and fixes.
4. **Internalize the threat model below.** Only server / Worker / Firestore-rule enforcement counts as
   a control; client-side checks never do. Theme A in the final report is the list of six shipped
   features that violate this.

### If you are resuming work

Do **remediation verification**, not a new discovery session. Work `SESSION-10-SYNTHESIS.md` §8 P0
first, and verify each fix against source rather than trusting commit titles — §4 of that report
documents four prior-review items where the fix was claimed, partial, or introduced a new defect.
`SESSION-10-SYNTHESIS.md` §9 lists what the audit could not determine (console-side IAM scope, branch
protection, App Check state, deployed-vs-committed drift, and the fact that `firestore-tests/` was
never actually run) — resolve those before finalizing any severity.

**Caveat, now resolved:** `docs/SECURITY_REVIEW_2026-08-04.md` items were carried as *"claimed fixed —
re-verify."* Session 10 completed that re-verification: **8 fixed, 4 partial, 11 open**, with two of
its "Verified solid" entries proven wrong. Use §4 of the synthesis report, not the original document's
status.

---

DuoShield is an end-to-end-encrypted Android messenger with a client-heavy design:
all cryptography happens on-device and the server tier is intended to be a
"zero-knowledge" relay that stores only ciphertext and metadata. That design shifts
the security burden onto **trust-boundary enforcement**: because the client is
assumed fully compromised, every guarantee must be enforced by the server, the
Cloudflare Worker, or the Firestore rules — never by the app.

## Threat model (assume all of the following)

- The Android client is fully compromised and every client-side check is bypassable.
- Attackers can reverse-engineer the APK and extract any compiled-in constant.
- Attackers can intercept, replay, and modify every network request.
- Attackers may already hold one or more authenticated accounts.
- Attackers will automate API abuse.
- The server and cloud services must **never** trust client input.

Everything server-authoritative (Firestore rules, `server/index.js`, `worker/src/index.js`)
is therefore in scope as a control. The on-device crypto is in scope for
confidentiality/integrity of message content, but is explicitly a lower priority than
the trust boundaries, because a compromised client can already read its own plaintext.

## Documents in this folder

| File | Purpose |
|---|---|
| [`README.md`](./README.md) | This overview + how to use the audit set. |
| [`ARCHITECTURE.md`](./ARCHITECTURE.md) | Components, trust boundaries, auth/authz flow, data flow, external services. |
| [`ATTACK_SURFACE.md`](./ATTACK_SURFACE.md) | Every identified attack surface, entry point, and control, with file:line anchors. |
| [`AUDIT_PROGRESS.md`](./AUDIT_PROGRESS.md) | Session ledger with per-session severity counts, audit order and rationale, and the running result notes. |
| [`SESSION-00-RECON.md`](./SESSION-00-RECON.md) | The full reconnaissance report. Risk ranking + the 10-session plan. |
| `SESSION-01-FIRESTORE.md` … `SESSION-09-SUPPLY-CHAIN-CI.md` | The nine per-surface assessment reports. Findings with `path:line`, exploit path, severity and fix. |
| [**`SESSION-10-SYNTHESIS.md`**](./SESSION-10-SYNTHESIS.md) | **The final report.** Aggregate findings, the four Criticals, eight cross-cutting themes, regression pass over the prior review, advertised-guarantees scorecard, prioritized remediation roadmap, audit limitations. **Start here.** |
| [`UX_REVIEW.md`](./UX_REVIEW.md) | Separate UX review — not part of the security assessment. |

## Current status

**Phase: COMPLETE.** All ten planned sessions are done and the final report is written.

- Repository fully mapped (architecture, trust boundaries, attack surface).
- All ten sessions of the plan in `SESSION-00-RECON.md` executed; 117 findings recorded.
- The prior review at `docs/SECURITY_REVIEW_2026-08-04.md` has been fully re-verified against live
  source — **8 fixed, 4 partial, 11 open** (`SESSION-10-SYNTHESIS.md` §4). Its status is no longer an
  open question.
- **Headline result:** the cryptography is correct; the authorization placed around it is not. Six of
  ten advertised guarantees do not hold, driven mostly by controls that live on the client or trust a
  client-writable document as an identity source.

## Conventions used

- Every finding cites `path:line` and states which trust boundary it breaks.
- "The client validates X" is **never** a control. Only server/Worker/Firestore-rule
  enforcement counts.
- Severity labels: **Critical / High / Medium / Low / Info**, each with a concrete exploit path
  aligned to the threat model, and a proposed fix.
- Exploitability against the threat model is preferred over theoretical concern.
- Session 09 labels its findings `SC-01 … SC-12` rather than `S09-*`; Session 10 preserves that.
