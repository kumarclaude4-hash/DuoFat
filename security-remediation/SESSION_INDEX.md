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
| 03 | P2 batch + HARD STOP | P2 | scheduled in `ROUND3_REMEDIATION_PLAN.md` (20 sessions); per-session logs `sessions/SESSION-S3-*.md` | **IN PROGRESS — 8 of 20 sessions touched** (was "NOT STARTED"; the earlier "DISPOSITIONED, 0 open" claim was the false one caught 2026-08-11). Done: **S3-01** (APK/CI secrets), **S3-02** (supply-chain integrity + release provenance — SC-01/04/05 fixed), **S3-03** (dependency pinning & scanning — SC-06/07/08/09/10 fixed; **SC-03 partial**, hash population BLOCKED on Gradle+Android SDK+network), **S3-04** (Firestore cross-user write protection — S01-H1/H2/H3 **partial**, RULES verify BLOCKED → S3-15b; commit 812813d — chain state was not advanced when this landed, corrected in S3-05), **S3-05** (Firestore field validation & abuse caps — S01-M1/M2/M3/M4/L1/L2 **partial**, RULES verify BLOCKED → S3-15b; commit 3070b0b + test corrections in ec8a919; see `sessions/SESSION-S3-05.md`), **S3-06** (Server auth & identity — S02-H1/S02-L2 **fixed** with new code, `server/lib/profileSanitize.js` + 9 tests, commit 269d165; S02-M1 and S02-L1/S07-H1 **fixed**, re-verified already-fixed from source at pre-existing commit 5c2cd73; lane SRV ran for real — `npm test` 146/147 pass, 1 pre-existing unrelated failure; see `sessions/SESSION-S3-06.md`), **S3-07** (Server limits, memory growth, IP keying — **all 5 findings fixed** across three sub-sessions: S02-L3/S02-L4/S04-L1/S04-M3 fixed first, commit 000ed14; S04-M1 IPv6 /64 keying fixed next, commit 959d869; S04-L3 admin lockout durable store fixed last, commit fe9559a, backed by Upstash Redis with an in-memory fail-safe fallback — see `sessions/SESSION-S3-07.md`, including its correction note on an earlier stale "S04-M1 deferred" claim), **S3-08** (Server egress, TURN, public endpoints — **partial, 3 of 4 plan-scoped findings closed**: S04-M2 TURN credential TTL clamp + daily aggregate cap + outbound timeout **fixed**, commit 8f6b206; S04-L2/S06-L2 `/duress-lock` auth+rate-limit **re-verified fixed from source**, no new code; S04-I3 preview provenance **partial by design** — provenance labeling fixed, failure-indistinguishability deferred pending an Android-side change; S04-I1 `/status`+`/` unauthenticated **not attempted**, remains `Open` — lane SRV ran `npm test` 185/186 pass, 1 pre-existing unrelated failure; see `sessions/SESSION-S3-08.md`). **Next: S3-09** (Duress server enforcement), consistent with `START_HERE.md`'s chain state. All remaining findings still `open`; `../BUG_TRACKER.md` holds per-finding truth. | all remaining |

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
>
> ### Progress update, 2026-08-11 (Round 3 execution — see `START_HERE.md` chain state)
>
> Round 3 implementation has since **begun**: sessions **S3-01 through S3-07 are complete** (Round 03
> row above; `sessions/SESSION-S3-*.md`). `S3-07` was initially only 3 of its 5 findings fixed under
> an explicit user-set session budget (`S04-M1`/`S04-L3` deliberately deferred, not blocked by a
> toolchain), but both deferred findings were fixed in follow-on sub-sessions before this row was
> finalized — see `sessions/SESSION-S3-07.md` for the full three-sub-session record, including a
> correction note where that file itself had briefly lagged behind the actual fix state. The "Round 3
> was never implemented / remains open" statements above are the frozen 2026-08-11 verification-pass
> record and are **superseded for those sessions only** — the other 12-13 sessions and both catch-up
> gates (S3-15b RULES, S3-19b Android) are still open, and `SC-03` is **partial** (Gradle
> dependency-verification scaffold committed; hash population BLOCKED on toolchain), same as
> S3-04/S3-05 (RULES lane, verify BLOCKED — no `java`/`firebase` CLI this session), not fixed. S3-06
> and S3-07 are the first Round-3 sessions whose lane (`SRV`) had no toolchain blocker, so their
> fixed findings are recorded `fixed`, not `partial`. For "what's next," trust `START_HERE.md`'s
> `NEXT SESSION` line (currently **S3-08**) and `../BUG_TRACKER.md` for per-finding truth. The
> program is still **NOT signed off**.

Relationship to audit sessions: the audit's `SESSION-00…10` are **discovery** sessions (frozen,
immutable). These remediation `SESSION-01…03` are **fix** sessions and are the only ones this
program writes to. They are numbered independently and must not be confused with the audit's.

A round's log file (e.g. `SESSION-01.md`) may itself be revised in place across multiple working
sessions as more of that round's clusters get done — it is not one-log-per-one-sitting. Each
revision must follow `SESSION_PROTOCOL.md` §4 (verify from source before writing a disposition).
