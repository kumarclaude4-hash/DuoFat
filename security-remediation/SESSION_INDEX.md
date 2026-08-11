# SESSION INDEX

> **Read [`../SESSION_PROTOCOL.md`](../SESSION_PROTOCOL.md) before trusting anything below.** This
> file previously marked all three rounds `DONE`. That was false — `SESSION-02.md` and
> `SESSION-03.md` do not exist on disk, and `SESSION-01.md`'s own exit-criteria section states
> "Round 1 is NOT closed." Corrected 2026-08-10 to match verified source state.
>
> ⚠️ **`S07-C1` — the audit's single most severe finding — was also falsely marked `fixed` in
> `SESSION-01.md` and is REOPENED as Critical, still exploitable.** No signature or
> proof-of-possession check exists anywhere in source; `/mintToken` still authenticates by hashing a
> value that is public by design. See `sessions/SESSION-01.md`'s 2026-08-10 correction notice for
> the full verification.
>
> **Update, 2026-08-10 (same day, later session, $2 budget):** part 1 of 2 of the real fix landed —
> `POST /mintChallenge` issues a single-use nonce (`server/lib/challengeStore.js`, 9/9 tests pass).
> `/mintToken` still does not verify a signature over it, so **the finding is still `open` and the
> attack is still live** — do not read "part 1 landed" as "fixed." See `sessions/SESSION-01.md` §12
> and `SESSION_PROTOCOL.md`'s "Next session" prompt for the remaining work.

The remediation program is executed in **three fixed rounds**. Round numbers 01–03 are fixed slots;
each round's actual work may span many individual working sessions (see the protocol's budget
guidance) — the table below tracks the round, not a session count.

| # | Round | Priority | Log | Status (verified from source, not self-reported) | Findings |
|---|---|---|---|---|---|
| 01 | Stop the bleeding | P0 | [`sessions/SESSION-01.md`](./sessions/SESSION-01.md) | **CLOSED.** `S07-C1` **fixed** — re-verified from source 2026-08-11 (`/mintToken` requires nonce + XEdDSA signature, `index.js:2013,2027`; `identityVerify.test.js` 16/16 executed). This supersedes the "still open/exploitable" banner above. 2 items (`SC-12`, credential rotation) remain **operator-only**. | S08-C1, SC-02, S08-H1, S03-L1, **S07-C1 (fixed)**, S07-H1, S02-L1, S06-H1, S02-M1, SC-12, S02-I3 |
| 02 | Advertised guarantees | P1 | [`sessions/SESSION-02.md`](./sessions/SESSION-02.md) | **CLOSED** — the log file **does exist**; clusters A, B and C are all dispositioned. Corrects the "NOT STARTED — file does not exist" text that stood here. | S03-H1, S06-H2, S06-H3, S06-I2, S08-H5, S07-M1, S04-H1, S04-H2, S04-H3, S08-H4, S05-H1, S05-H3, S05-I1, S08-H2, S08-H3, S10-N2, S07-L4, S10-N3, SC-05, SC-04, SC-01, S04-I2 |
| 03 | P2 batch + HARD STOP | P2 | scheduled in `ROUND3_REMEDIATION_PLAN.md` (20 sessions) | **NOT STARTED.** The "DISPOSITIONED, 0 open" claim previously here was false — caught by the 2026-08-11 correction pass. No `SESSION-03.md` exists and no Round-3 remediation commit exists. See `../BUG_TRACKER.md` for the real per-finding state and `ROUND3_REMEDIATION_PLAN.md` for the scheduled work. | all remaining |

> ### Status correction, 2026-08-11 (FINAL VERIFICATION session, protocol §9)
>
> **The banners at the top of this file and the Round 2/3 rows above were stale.** They claimed
> `S07-C1` was open and exploitable and that the Round 2/3 logs did not exist, while the work had in
> fact been done and recorded in the (now-deleted) `FINDING_INDEX.md`. That was narrative lag, not a
> fourth fabrication — but it is corrected here because lag is indistinguishable from fabrication
> until someone spends the budget to check.
>
> **Correction, 2026-08-11 (same day, later pass):** the "all 116 findings dispositioned, no
> Critical/High unfixed" claim that used to follow this note was itself the *fourth* false-progress
> incident — it counted an intent column as a result. `FINAL_SECURITY_REPORT.md` (which made that
> claim) has been deleted. **Authoritative state now lives in
> [`../BUG_TRACKER.md`](../BUG_TRACKER.md)**, and the real Round 3 status is tracked in
> `ROUND3_REMEDIATION_PLAN.md`. What genuinely holds: `cd server && npm test` → **153 tests / 153
> pass / 0 fail** (Rounds 1–2 only); Round 3 (the bulk of the findings) remains open.
>
> **Not done, and not counted as done:** 8 operator actions — above all **revoking the leaked GCP
> service-account key** — plus `SC-12` branch protection (re-checked this session: still
> `404 Branch not protected`). 3 verification gaps remain: **Android has never been compiled**
> (no JDK/SDK), `firestore-tests/rules.test.js` has **never been executed** (no `firebase` CLI), and
> no runtime/integration testing was performed. **The program is NOT signed off.**

Relationship to audit sessions: the audit's `SESSION-00…10` are **discovery** sessions (frozen,
immutable). These remediation `SESSION-01…03` are **fix** sessions and are the only ones this
program writes to. They are numbered independently and must not be confused with the audit's.

A round's log file (e.g. `SESSION-01.md`) may itself be revised in place across multiple working
sessions as more of that round's clusters get done — it is not one-log-per-one-sitting. Each
revision must follow `SESSION_PROTOCOL.md` §4 (verify from source before writing a disposition).
