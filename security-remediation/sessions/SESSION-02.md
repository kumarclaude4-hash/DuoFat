# REMEDIATION SESSION 02 — Round 2: Client Trust Boundary, Egress, Admin Accountability

**Status: NOT STARTED — plan only.** Maps to `REMEDIATION_PLAN.md` Round 2.
**Blocked by:** Round 1 must close first (see `DEPENDENCY_GRAPH.md` §3). R2 assumes the server
identity boundary (`S07-C1`) is already fixed and secrets are already out of the client build
(`S08-C1`, `SC-02`), because several R2 fixes are only meaningful once those hold.

> **ID integrity note.** A previous version of this file addressed `S03-C1`, `S04-C1`, `S07-C1`
> (as a crypto-misuse item) and `SC-1`/`SC-3`. `S03-C1` and `S04-C1` **do not exist** in the audit,
> `S07-C1` is a Round-1 server-identity finding rather than a crypto item, and `SC-1`/`SC-3` were
> malformed spellings. It also used wildcards (`S06-M-series`, `S05-H-series`) that did not resolve
> to a definite finding set. Rewritten 2026-08-07 against `MASTER_CHECKLIST.md`.

## Objectives

1. Stop the client from being a trusted component: no plaintext key fallback, no secret residue in
   caches or logs, screenshot protection that cannot be silently disabled app-wide.
2. Close the server-side egress surface (`/linkPreview`) against SSRF and IP-leak abuse.
3. Make duress enforcement complete — it must survive offline use and leave no working residue.
4. Give admin actions a durable audit trail and a brute-force ceiling.
5. Make releases verifiable and stop the release workflow from destroying release history.

## Exact findings addressed — 21 total (1 Critical, 15 High, 1 Medium, 2 Low, 2 Informational)

### Supply chain / build integrity
| ID | Sev | Summary |
|---|---|---|
| `SC-01` | Critical | Vendored libsignal JAR not reproducible / unhashed / unvalidated |
| `SC-04` | High | Release APKs unverifiable — no checksums, no provenance |
| `SC-05` | High | Release workflow deletes all releases and tags |

### Client trust boundary (Android)
| ID | Sev | Summary |
|---|---|---|
| `S08-H5` | High | SecurePrefs plaintext fallback holds keys + SQLCipher passphrase |
| `S07-M1` | High (gov.) | Duplicate of `S08-H5`; closed by the same change |
| `S08-H2` | High | `FLAG_SECURE` cleared app-wide |
| `S08-H3` | High | Plaintext media persists in cache |
| `S08-H4` | High | Link-preview image fetched by client directly from sender host |
| `S10-N2` | Low | Peer UID written to release logcat |

### Egress / SSRF
| ID | Sev | Summary |
|---|---|---|
| `S04-H1` | High | SSRF predicate never resolves DNS, misses IPv6 forms |
| `S04-H2` | High | `/linkPreview` unbounded body read, no timeout |
| `S04-H3` | High | `og:image` beacon leaks recipient IP + read time |
| `S04-I2` | Info | Dead B2 presign surface still wired to live credentials |

### Duress completeness
| ID | Sev | Summary |
|---|---|---|
| `S06-H2` | High | Duress wipe leaves WorkManager residue |
| `S06-H3` | High | Offline duress trigger never locks the account |
| `S06-I2` | Info | Wipe step 1a cannot distinguish success from failure |

### Admin accountability
| ID | Sev | Summary |
|---|---|---|
| `S05-H1` | High | `ADMIN_TOKEN` has no entropy floor and no brute-force ceiling |
| `S05-H3` | High | Admin actions not durably audited |
| `S05-I1` | Info | Operator secrets undocumented |

### Media scope
| ID | Sev | Summary |
|---|---|---|
| `S03-H1` | High | Media scope confusion via client-created `groups/{chatId}` |
| `S10-N3` | Low | Deleted media survives B2 cold tier on race |

## Folders / files expected to change

- `app/src/main/java/com/duoshield/app/data/SecurePrefs*` — fail-closed keystore path
- `app/src/main/java/com/duoshield/app/**` — `FLAG_SECURE` scoping, media cache lifecycle,
  link-preview fetch path, release-log redaction
- `app/src/main/java/com/duoshield/app/duress/**` + WorkManager wipe workers
- `server/lib/pure.js` — `isBlockedPreviewHost` / address-family predicate
- `server/index.js` — `/linkPreview` limits + timeout, admin audit sink, admin token gate
- `firestore.rules` — `groups/{chatId}` creation constraint for `S03-H1`
- `.github/workflows/release.yml` — checksums/provenance, remove destructive delete step
- Gradle vendored-dependency verification for `SC-01`

## Root-cause analysis

The unifying defect is that the **client is treated as a trusted participant**. Keys fall back to
plaintext when the keystore errors (`S08-H5`), media decrypted for display is never reclaimed
(`S08-H3`), and the client performs its own outbound fetch to a sender-controlled host
(`S08-H4`/`S04-H3`), which converts a rendering step into an IP-disclosure oracle.

Independently, the server's egress guard is a **string predicate rather than a resolution-time
check** (`S04-H1`), so any name that resolves to a private address defeats it. Duress is
**client-authoritative and connectivity-dependent** (`S06-H3`), so the exact adversary it exists to
defeat can defeat it by taking the device offline. Admin actions are authenticated by a
**single shared bearer secret with no entropy floor, no rate ceiling, and no durable log**
(`S05-H1`/`S05-H3`), so the privileged path is neither hardened nor reconstructable after the fact.

## Implementation plan (dependency order)

1. **`S04-H1` first** — it is the R2 internal prerequisite (`DEPENDENCY_GRAPH.md` §2). Replace the
   string predicate with resolve-then-validate over every returned address, rejecting private,
   loopback, link-local, ULA, and IPv4-mapped IPv6 ranges; pin the validated address for the actual
   connection so the name cannot be re-resolved between check and use.
2. **`S04-H2`** — hard byte cap on the response body, wall-clock timeout, capped redirect count with
   revalidation of every hop.
3. **`S04-H3` + `S08-H4`** — proxy preview images through the server so the recipient never contacts
   the sender's host. Fixing these before the client cache work avoids touching the same call path twice.
4. **`S08-H5` / `S07-M1`** — remove the plaintext fallback; a keystore failure must deny, not degrade.
5. **`S08-H2`, `S08-H3`, `S10-N2`** — scope `FLAG_SECURE` to sensitive surfaces, bound the decrypted
   media cache to its viewing lifetime, strip identifiers from release logs.
6. **`S06-H3`, `S06-H2`, `S06-I2`** — queue the duress lock durably so it applies offline and
   reconciles on reconnect; cancel and clear WorkManager residue; make step 1a report a real outcome.
7. **`S05-H1`, `S05-H3`, `S05-I1`** — entropy floor enforced at startup, attempt ceiling on the admin
   path, append-only audit record per admin action, operator secret inventory documented.
8. **`S03-H1`** — constrain `groups/{chatId}` creation so a client cannot mint a group document that
   widens its own media scope.
9. **`SC-01`, `SC-04`, `SC-05`** — pin and verify the vendored JAR by digest, publish checksums and
   provenance, and delete the destructive release/tag cleanup step.

## Tests to run

- SSRF suite: DNS rebinding, `169.254.169.254`, `::1`, `::ffff:127.0.0.1`, ULA `fc00::/7`,
  redirect-to-private, oversized body, slow-loris timeout.
- SecurePrefs: simulated keystore failure must deny, and must never write plaintext.
- Duress: airplane-mode trigger → lock applied and reconciled on reconnect; no WorkManager job survives.
- Admin: low-entropy token rejected at startup; attempt ceiling trips; every action produces one audit row.
- Rules: a client cannot create a `groups/{chatId}` that grants itself media scope.

## Evidence to collect — `evidence/after/round-2/`

Diffs per finding, SSRF transcript, keystore-failure log, offline-duress trace, admin audit sample,
rules test output, release-workflow diff, JAR digest record. Index in `evidence/notes/README.md`.

## Validation steps

Per `validation/VALIDATION_PLAN.md`. Each of the 21 findings needs a source citation, and each of the
21 needs a passing test or an explicit, written statement of why no test applies.

## Regression checks

Legitimate members still download media; normal link previews still resolve; legitimate admin
operations still succeed; non-duress accounts unaffected; release workflow still publishes a
working APK. Round-1 guarantees `SG-01`…`SG-04` must be re-run and still pass.

## Exit criteria

- All 21 findings verified from source, with tests where applicable.
- No plaintext key fallback and no secret in the client build.
- Egress guard validated at resolution time, not by string shape.
- Duress effective offline.
- Every admin action durably audited.
- `SG-05`…`SG-09` in `REGRESSION_MATRIX.md` pass; R1 guarantees still pass.

## Findings explicitly NOT touched this round

The 11 Round-1 findings (must already be closed) and all 84 Round-3 findings — Firestore rules
hardening beyond `S03-H1`, quota and limiter work, remaining supply-chain items, and every
Medium/Low/Informational disposition not listed above. R2 does not assign terminal dispositions to
Round-3 findings.
