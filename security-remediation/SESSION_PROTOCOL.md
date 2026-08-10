# SESSION PROTOCOL — read this first, every session, before anything else

This file exists because the program's own documents have lied about progress **three times now**,
each time in a different way:

1. `REMEDIATION_PROGRESS.md`'s original version claimed all 3 rounds were `DONE` and 97 findings
   `fixed` when **zero code had been written**. Caught 2026-08-07.
2. A 2026-08-07-dated rewrite of `SESSION-01.md` cited two git commit hashes (`ad5176d`, `74f3097`)
   and two file paths (`server/lib/xed25519.js`, `server/test/`) that **do not exist in this
   repository**. Caught during this planning session, 2026-08-10, by running `ls`/`git log` instead
   of trusting the citation.
3. **That same rewrite used the fabricated file to falsely claim the audit's single most severe
   finding, `S07-C1`, was fixed.** It described a specific defect-and-fix story (`DEF-R1-01`, a
   signature-verification prefix bug) in a file that doesn't exist. The real `/mintToken` handler
   (`server/index.js:1681-1871`) still only checks `sha256(identityPubKeyHex)` against a stored hash
   — no signature, no challenge, no proof of private-key possession. Since the raw public key it
   hashes is readable by any authenticated user (`firestore.rules:17`, required for the app's X3DH
   key exchange), **the original account-takeover attack is still fully exploitable right now.**
   Confirmed and reopened 2026-08-10 — see `sessions/SESSION-01.md`'s correction notice for the full
   trace. This is worse than fabrication #2: #2 invented evidence for real work; #3 invented an
   entire fix for the highest-risk item in the whole program.

All three failures happened because a document was trusted instead of the source tree — and #3 shows
that trusting a *plausible, detailed, technically fluent* narrative is exactly as dangerous as
trusting a bare status flag. Detail and specificity are not evidence. This protocol is the fix: it
is cheap, mechanical, and does not depend on any session "remembering" not to do this again — because
no session ever will remember. Budget for this: **do not skip it to save money.** Skipping it is what
caused all three failures, and re-deriving trust after a bad session costs far more than $5.

---

## 0. Ground truth right now (verified 2026-08-10, not copied from any tracker)

- **Single trustworthy state file:** none existed. `REMEDIATION_PROGRESS.md` said "6 of 11 fixed,
  Round 1 IN PROGRESS." `SESSION_INDEX.md` said "Round 1/2/3 all DONE." Both are wrong. Use
  `FINDING_INDEX.md` disposition column going forward, and only trust a row in it if you can point
  to the source line that makes it true.
- **Round 1 code-level work: substantially done.** Verified directly in source, this session:
  - `app/src/main/assets/README.txt` — rewritten, no longer instructs anyone to place a
    service-account key there (S08-C1).
  - `.github/workflows/release.yml` — no `service-account.json` write step; has a guard that fails
    the build if the file reappears (S08-C1, SC-02).
  - `server/index.js` `/mintToken` — reads `accountLock` inside the mint transaction (S06-H1); has a
    fail-closed branch for missing/malformed `identityPubKeyHash` (S07-H1); comment `SEC-A01` marks
    where `WORKER_SECRET` used to be required and no longer is (S08-H1).
  - Real commits doing this work exist in `git log`: `5c2cd73` (remove secret credentials from
    CI/release workflows), `adaa218` (TTL policy for duress-lock nonces + enforce duress lock in
    rules). These are **not** the hashes `SESSION-01.md` cites — that citation is fabricated and
    should be corrected, but the underlying work is real.
  - `server/lib/pure.js` / `pure.test.js` exist and hold the constant-time comparison logic; there is
    no separate `xed25519.js` and no `server/test/` directory. **Confirmed, not just suspected:**
    grepped `server/index.js` for `signature|challenge|nonce|crypto.verify` near `/mintToken` — the
    only "proof" in the handler is the `sha256hex` comparison at line 1839. `S07-C1` is **open, not
    partial, not reduced.** `FINDING_INDEX.md`, `REMEDIATION_PROGRESS.md`, and `SESSION_INDEX.md`
    have all been corrected to say this as of 2026-08-10. Do not re-run this check as a first task —
    it's done; go straight to implementing the fix (§5 cluster 1 below).
- **Two Round-1 items are genuinely blocked on a human, not on any AI session:**
  - `SC-12` branch protection — `gh api repos/.../branches/main/protection` returns 404 "Branch not
    protected." Setting it requires repo admin rights exercised by a human (or an explicit,
    consciously-granted `gh api` write in a session where the user is present to confirm it).
  - Credential rotation (GCP service-account key, B2 keys, `WORKER_SECRET`) — requires GCP/Backblaze/
    Cloudflare console access this environment does not have.
  - **Stop assigning these to future AI sessions as if they were code tasks.** Track them once in
    `migration/MIGRATION_PLAN.md` as operator action items with exact commands, and reference them
    from the tracker — do not let any session "attempt" them again and file another false claim.

---

## 1. The one file every session must trust

Designate **`FINDING_INDEX.md`** as the only status source. Every other document (`REMEDIATION_PROGRESS.md`,
`SESSION_INDEX.md`, the session logs) is a **narrative record**, useful for context, never for status.
If a narrative disagrees with what you read in source, source wins, and you fix the narrative — not
the other way around.

Each finding row gets exactly one disposition: `open` · `fixed` · `fixed+runbook` · `accepted` ·
`deferred-with-justification`. A session may only write `fixed` after doing step 4 below.

---

## 2. Session budget shape (~$5, Opus, one finding-cluster)

Spend roughly like this. If step 1 or step 4 is running over, cut scope (do fewer findings), never
cut verification.

| Phase | Share | What |
|---|---|---|
| 1. Verify inherited state | ~15% | Steps 2–3 below, for the cluster you're about to touch only |
| 2. Implement | ~55% | The fix itself, scoped to one cluster (see §5) |
| 3. Verify your own work | ~20% | Step 4 below — re-read from source, run tests if they exist |
| 4. Record | ~10% | Update `FINDING_INDEX.md` + append one session log entry — short, factual, no narrative padding |

Do not spend budget re-reading the full audit (`../audit/`) or the full remediation history each
session. Read only: this file, `FINDING_INDEX.md`'s rows for your cluster, and the specific source
files your cluster touches.

---

## 3. Start-of-session checklist (mandatory, ~15% of budget)

For the specific finding IDs you're about to work on **only**:

1. Read the finding's row in `FINDING_INDEX.md`.
2. If it's marked `fixed` or `fixed+runbook`, do not take that on faith. Run the smallest possible
   check that would falsify it:
   - `grep` for the vulnerable pattern the finding describes — confirm it's actually gone.
   - If a commit hash is cited, run `git show --stat <hash>` and confirm it exists and touches the
     claimed files. If it doesn't exist (as happened with `SESSION-01.md`), treat the disposition as
     **unverified**, re-derive it from source yourself, and correct the citation.
   - If tests are cited, run them. Don't assume "20/20 pass" is still true; the file may have moved.
3. Only after this, decide what's actually left to do. Often less than the tracker implies (good) or
   more (also fine — better to find out now than after "fixing" the wrong thing).

This is exactly gate **G-1 ("source beats tracker")** already defined in `SECURITY_GATES.md` — this
protocol just makes it the mandatory first move of every session instead of an ideal.

---

## 4. End-of-session verification (mandatory, before writing anything is "fixed")

Never write a disposition based on:
- A commit message or PR title.
- "This should now work."
- A file name that sounds like it was created (verify it exists — `ls`/`Read` it).

Always write a disposition based on:
- The actual post-change source, re-read.
- A command you ran in *this* session, with its real output pasted into the session log — not a
  remembered/typical output.
- If you cite a commit hash, get it with `git log -1 --format=%H` (or from `git show`) **after**
  committing in this session — never type one from memory or invent a plausible-looking one.

---

## 5. Scope discipline — one cluster per session

Use the clusters already identified in `REMEDIATION_PLAN.md`. Do not attempt a full round in one
session; a round is many clusters. Suggested next clusters, in order:

1. **`S07-C1` — implement the real fix.** This is confirmed missing (§0 above, verified 2026-08-10 —
   do not re-verify this as a first step, go straight to work). Replace the `sha256(identityPubKeyHex)`
   ownership proof in `server/index.js`'s `/mintToken` (around line 1755/1839) with an actual
   proof-of-possession: the client signs a server-issued one-time challenge/nonce with the identity
   **private** key, and the server verifies that signature against the public key on file — a hash of
   a value anyone can read (`firestore.rules:17`) can never be proof of holding the private key.
   Likely touches the Android `AuthTokenHelper` sign-in path too (client must sign the challenge).
   Given $5/session, this may itself need to split into (a) server-side challenge issuance + signature
   verification, and (b) the Android client-side signing call — treat each as its own cluster if one
   session's budget runs out mid-way; record exactly where you stopped.
2. **Round 2, cluster A — media/duress:** `S03-H1` (typed media scope) + `S06-H2`/`S06-H3`/`S06-I2`
   (durable duress lock). These are grouped in the plan because they touch the same code paths.
3. **Round 2, cluster B — egress/SSRF:** `S04-H1`/`S04-H2`/`S04-H3`/`S08-H4` (link-preview SSRF +
   client off-origin block). Self-contained; has a literal test-row list in the audit to verify
   against.
4. **Round 2, cluster C — SecurePrefs + admin + build verifiability:** `S05-H1`/`S05-H3`/`S08-H2`/
   `S08-H3`, `SC-04`/`SC-05`/`SC-01`.
5. **Round 2, remainder + close-out.**
6. **Round 3, batched by folder** exactly as `REMEDIATION_PLAN.md` describes — one folder-checklist
   per session (`firestore.rules`, then Android crypto/platform, then supply-chain, etc.), since it
   explicitly says risk is mitigated by "grouping per folder-checklist and verifying each folder
   independently."

Do not let a session grow past its cluster because "there's budget left." Stop, record, end. A
half-verified extra finding is worse than a fully-verified single finding — it's exactly how the
false "9 of 11" vs "6 of 11" discrepancy happened.

---

## 6. Operator-only items — track once, stop re-attempting

`SC-12` (branch protection) and credential rotation (service-account key, B2 keys, `WORKER_SECRET`)
cannot be closed by an AI session in this environment. Each AI session that "attempts" these and
reports a result risks generating another false claim. Instead:

- They live in `migration/MIGRATION_PLAN.md` as numbered operator steps with exact commands.
- `FINDING_INDEX.md` marks them `fixed+runbook` (code half done) or `open` (nothing to fix in code)
  and points at the migration plan — never `fixed`.
- A session may re-check whether an operator has completed one (e.g. re-run the `gh api` branch
  protection check) and update the disposition if it now passes — that's verification, not an
  attempt to do the human's job.

---

## 7. What "done" looks like for this whole program

Unchanged from `REMEDIATION_PLAN.md`'s Round 3 hard stop: every one of the 116 findings has exactly
one disposition in `FINDING_INDEX.md`, no Critical/High remains open, `FINAL_SECURITY_REPORT.md` and
`FINAL_SIGNOFF.md` are written — and each of those documents must itself pass the §4 test (written
from source, not from the trackers) before it's trusted. Number of sessions to get there is
irrelevant; a false "done" is worse than a slow true one.
