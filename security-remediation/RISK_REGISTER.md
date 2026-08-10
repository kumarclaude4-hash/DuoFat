# RISK REGISTER

Tracks residual risk for every finding that ends as `accepted` or
`deferred-with-justification`, plus program-level risks. Findings ending as
`fixed` are recorded in FINDING_INDEX/MASTER_CHECKLIST and need no entry here
unless residual risk remains.

Policy: Critical/High findings may only be `fixed` or `accepted-with-justification`
(never deferred). Medium/Low/Informational may be fixed, accepted, or deferred.

## Program-level risks

| ID | Risk | Likelihood | Impact | Mitigation | Owner | Status |
|----|------|-----------|--------|------------|-------|--------|
| PR-1 | Rotation of keystore/service account done late, leaving exposure window | Med | Critical | Rotate first in Round 1 before any deploy (MIGRATION §6) | Release eng | Open→tracked |
| PR-2 | Feature-flagged enforcement accidentally left in dual-accept | Med | High | Round 3 exit criteria requires legacy usage = 0 and flag flipped | Backend | Open→tracked |
| PR-3 | History rewrite breaks forks/clones | Low | Med | Communicate; rotate so history is worthless even if not rewritten | Release eng | Open→tracked |

## Finding-level residual risk (populated as dispositions are set)

| Finding | Severity | Disposition | Residual risk | Justification / acceptance | Revisit trigger |
|---------|----------|-------------|---------------|----------------------------|-----------------|
| S03-H1 | High | fixed (R2) | **The fix converts a confidentiality breach into an availability DoS.** `/mediaToken` now denies when a scopeId resolves to both a chat and a group. Because `firestore.rules` still lets any authenticated user create `groups/{arbitraryId}` (the S01-L1 gap), an attacker who can compute a victim's deterministic 1:1 `chatId` can create a shadow group for it and permanently deny *both legitimate participants* all media read/write/delete for that conversation. No data is exposed; the conversation's media becomes inaccessible. | Deliberate trade: denying a real participant costs availability, allowing the attacker costs confidentiality of another user's media. Fail-closed is correct at this layer and the ambiguity is unforgeable evidence of tampering, so it is logged at `warn` as an active-attack signal. The clean elimination is at the rules layer — constraining `groups` create so an attacker cannot squat an ID at all — which is owned by **S01-L1** (`groups` create doesn't validate `createdBy`), scheduled R3. Deliberately not fixed here to avoid editing a finding owned by another cluster/round. | Close when S01-L1 lands a rule that prevents ID squatting (e.g. `createdBy == request.auth.uid` plus a server-generated//namespaced group ID); then re-assess whether the ambiguity branch is still reachable. Also revisit if a `warn`-level `scope-ambiguous` log is ever observed in production — that is an attack in progress, not noise. |
| S06-H3 | High | fixed (R2) | Two residuals. (1) **Single load-bearing call site:** the offline duress lock works only because `BaseActivity.onStart()` calls `maintainLockCredential()`. This method previously had *zero* callers and the failure was completely silent — no crash, no log, no test — so a future refactor can silently re-break it. (2) **First-run / never-online-since-install window:** if a duress code is triggered before any warm nonce has ever been parked, no credential exists, the intent cannot be drained, and the account stays unlocked; the code records this and logs `account believed UNLOCKED` rather than pretending success. | (1) Cannot be closed by an assertion in code — an Android instrumentation test is required, and there is no JDK/Android SDK in this environment (BLOCKED), so it is documented in-code as load-bearing instead of being test-enforced. (2) Inherent: `/duress-lock` authenticates with the nonce itself and `/requestLockNonce` needs the Firebase session that the wipe destroys, so a process that never obtained a nonce can never obtain one for that uid again. Preserving the honest "believed unlocked" record (S06-L4/S06-I2) is the compensating control. | (1) Add an instrumentation/Robolectric test asserting a warm nonce is parked after a foreground start, as soon as an Android build environment exists — this is the intended permanent fix. (2) Revisit if a server-side push-initiated lock (not dependent on a client-held nonce) is introduced, which would remove the window entirely. |

_No Critical or High finding will be entered as `deferred`. Any Critical/High
accepted here must carry an explicit written business/technical justification and
a compensating control._
