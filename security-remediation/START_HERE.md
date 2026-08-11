# START HERE

**You said: "head to this folder, open this md, and start working." This is that md.**
Do exactly what is below, in order. Do not improvise a different plan. Do not declare anything
`fixed` without running the verification named for it.

---

## What this program is

Rounds 1–2 of the security remediation are code-complete and server-test-verified (server suite
**153/153**). **Round 3 — 103 open findings — is not implemented.** Those 103 findings are scheduled
across **20 sessions** in [`ROUND3_REMEDIATION_PLAN.md`](./ROUND3_REMEDIATION_PLAN.md). One session =
**fix + verify + document** for one scheduled batch. `FINAL_SIGNOFF.md` is **PENDING** and stays that
way until every session and both catch-up gates (S3-15b, S3-19b) have actually run.

---

## Chain state — the ONE line that says what to do next

<!-- Update ONLY this block at the end of each session. It is the single source of truth for "next". -->

```
NEXT SESSION: S3-01  (Get secrets out of the APK and CI — P0)
LAST DONE:    none (Round 3 not started; Rounds 1–2 complete)
BLOCKED GATES PENDING: S3-15b (RULES emulator), S3-19b (Android build) — need operator toolchains
```

If `NEXT SESSION` above is `S3-20 complete`, do **not** start coding — go to the sign-off gate at the
bottom of the plan and verify operator items instead.

---

## Do this, in order

1. **Read the rules of engagement.** Open [`SESSION_PROTOCOL.md`](./SESSION_PROTOCOL.md) and read it
   in full. It is binding: source beats tracker, ≤4 tasks/session, never fabricate a PASS, never make
   `/mintToken` auth fields optional.
2. **Open the plan.** In [`ROUND3_REMEDIATION_PLAN.md`](./ROUND3_REMEDIATION_PLAN.md), find the
   session named in `NEXT SESSION` above. That session's finding IDs, code targets, verification lane,
   and exit criteria are your entire scope. **Do not pull work forward from a later session.**
3. **Recover state (SESSION_PROTOCOL §3 + §7).** Run:
   - `git status --short` and `git log -3 --oneline --stat`
   - For each finding ID in this session, read its row in [`FINDING_INDEX.md`](./FINDING_INDEX.md) and
     run the smallest check that would falsify its current status (`grep` the vulnerable pattern; if a
     commit is cited, `git show --stat <hash>`). If source already satisfies it, record that and skip.
4. **Implement the smallest fix** for this session's findings only. Keep existing guards; add beside
   them. Honor the standing invariants in the plan (especially: `nonce`/`signatureHex` stay mandatory).
5. **Verify in this session's lane** (the plan names it):
   - `SRV` → `cd server && npm test` (must stay green; add cases for the findings).
   - `WORKER` → `node --check` + worker unit tests.
   - `RULES` → write emulator tests now, but the run is **BLOCKED** here → land as
     `partial — RULES verification BLOCKED`; promotion happens in **S3-15b**.
   - `AND` → write/adjust code + tests now, run is **BLOCKED** here → land as
     `partial — AND verification BLOCKED`; promotion happens in **S3-19b**.
   - `CI` → YAML lint + `git`/`gh` inspection + hash recompute; actual CI run is operator.
   - **If a toolchain is missing, mark BLOCKED and fall back to source review. Never invent a PASS.**
6. **Document + checkpoint** (mandatory before stopping):
   - Update each finding's `Status`/`Verify` in [`FINDING_INDEX.md`](./FINDING_INDEX.md) with real
     evidence (a command you ran this session, or a commit hash from `git log -1 --format=%H` **after**
     committing). Only write `fixed` if the lane's verification actually ran and passed.
   - Write `sessions/SESSION-<n>.md` with the end-of-session record block (format in
     `SESSION_PROTOCOL.md §7`).
   - Commit; confirm `git status` is clean.
   - **Update the Chain state block above**: set `NEXT SESSION` to the following session, set
     `LAST DONE` to the one you just finished.
7. **Stop.** One session = one scheduled batch. Do not start the next session because budget remains.

---

## Operator-only items (do not fake; only re-verify)

These cannot be completed by a session in this environment — revoke the leaked GCP service-account
key, rotate `WORKER_SECRET`/B2/admin creds, enable `SC-12` branch protection, Firestore TTL, App
Check enable, SBOM, and the Android build+release. A session may **re-check** whether an operator has
done one (e.g. `gh api repos/kumarclaude4-hash/DuoFatass/branches/main/protection`) and update the
disposition from live evidence — but must never report an operator action as done without that
evidence. Tracked in the plan's sign-off gate and `SESSION_PROTOCOL.md §6`.

---

## Reading order summary

`START_HERE.md` (this file, for `NEXT SESSION`) → `SESSION_PROTOCOL.md` (rules) →
`ROUND3_REMEDIATION_PLAN.md` (the session's scope) → `FINDING_INDEX.md` (per-row truth) →
previous `sessions/SESSION-*.md` (inherited evidence). Then work.
