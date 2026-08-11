"use strict";

// ── S06-M1 / S05-M2 wiring test ────────────────────────────────────────────────
//
// WHAT THIS IS, HONESTLY: a source-level structural test, not a behavioural one,
// in the same style as `adminAuditWiring.test.js`. It reads `index.js` and
// `firestore.rules` as TEXT and asserts the eligibility gate is actually wired
// where the finding says it must be. It does not execute `/requestLockNonce`
// against a live Firestore, so it cannot prove the query itself resolves
// correctly against real data — that half is BLOCKED here (no emulator/
// credentials) and is recorded as such, not asserted.
//
// WHY IT EXISTS ANYWAY: this fix (server/index.js's `eligSnap` check, plus the
// `duressEligibility` predicate on the `accountLock` create rule) had zero
// regression coverage before this session, even though it is exactly the class
// of defect this program keeps re-finding — a control that reads correctly in
// isolation but has no caller, or that a later refactor could silently drop.
//
// S05-M2 (audit session 05) and S06-M1 (audit session 06, "upgraded" from
// S05-M2 per SESSION-06-DURESS.md:525) are the same underlying finding:
// `duressEligibility` was, before the fix this test guards, enforced nowhere
// server-side — only a cached client boolean hid a UI button. One test suite
// covers both IDs.

const { test } = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");

const SERVER_SOURCE = fs.readFileSync(path.join(__dirname, "..", "index.js"), "utf8");
const RULES_SOURCE = fs.readFileSync(path.join(__dirname, "..", "..", "firestore.rules"), "utf8");

function requestLockNonceHandler() {
  const start = SERVER_SOURCE.indexOf('req.url === "/requestLockNonce"');
  assert.ok(start > 0, "could not locate the POST /requestLockNonce handler");
  // Bound the slice to the next handler so a match from elsewhere in this
  // multi-thousand-line file cannot make an assertion pass spuriously.
  const end = SERVER_SOURCE.indexOf('req.url === "/duress-lock"', start);
  assert.ok(end > start, "could not locate the POST /duress-lock handler (used as the slice boundary)");
  return SERVER_SOURCE.slice(start, end);
}

test("S06-M1: /requestLockNonce consults duressEligibility before issuing a nonce", () => {
  const handler = requestLockNonceHandler();
  assert.ok(
    /db\.collection\("duressEligibility"\)\.doc\(uid\)\.get\(\)/.test(handler),
    "no duressEligibility/{uid} read in the /requestLockNonce handler — eligibility " +
    "would only be a cached client boolean (S06-M1)"
  );
});

test("S06-M1: an ineligible uid is refused before a nonce is generated, not after", () => {
  const handler = requestLockNonceHandler();
  const eligIdx = handler.search(/eligSnap\.exists|duressEligibility/);
  const nonceIdx = handler.indexOf("crypto.randomBytes(32)");
  assert.ok(eligIdx >= 0, "eligibility check not found in /requestLockNonce");
  assert.ok(nonceIdx > 0, "nonce generation not found in /requestLockNonce");
  assert.ok(
    eligIdx < nonceIdx,
    "the eligibility check must run before the nonce is generated — checking " +
    "after would still let an ineligible account observe a nonce"
  );
});

test("S06-M1: ineligible refusal returns 404, not 403 (enumeration resistance)", () => {
  const handler = requestLockNonceHandler();
  const eligBlock = handler.slice(
    handler.indexOf("eligSnap.exists"),
    handler.indexOf("eligSnap.exists") + 400
  );
  assert.ok(
    /writeHead\(404/.test(eligBlock),
    "ineligible /requestLockNonce should respond 404 so a probing account " +
    "cannot distinguish \"refused\" from \"endpoint does not exist\" (S06-M1's " +
    "stated fix — a 403 here would itself disclose the feature)"
  );
});

test("S06-M1 / S05-M2: the accountLock create rule requires duressEligibility.eligible == true", () => {
  const start = RULES_SOURCE.indexOf("match /accountLock/{accountId}");
  assert.ok(start > 0, "no accountLock rule block found in firestore.rules");
  const end = RULES_SOURCE.indexOf("match /_duressNonces", start);
  assert.ok(end > start, "could not bound the accountLock rule block");
  const body = RULES_SOURCE.slice(start, end);

  const createMatch = body.match(/allow create:([\s\S]*?);/);
  assert.ok(createMatch, "no accountLock 'allow create' rule found");
  const createRule = createMatch[1];

  assert.ok(
    /exists\(\/databases\/\$\(database\)\/documents\/duressEligibility\/\$\(accountId\)\)/.test(createRule),
    "accountLock create must check for a duressEligibility/{accountId} doc — " +
    "otherwise a rule bypass would still let any authenticated user create " +
    "their own accountLock doc with no server-side eligibility boundary"
  );
  assert.ok(
    /duressEligibility\/\$\(accountId\)\)\.data\.eligible\s*==\s*true/.test(createRule),
    "accountLock create must require duressEligibility/{accountId}.data.eligible == true, " +
    "not merely that the doc exists"
  );
});

test("duressEligibility stays server-only (Admin SDK) — no client write path", () => {
  const match = RULES_SOURCE.match(/match\s+\/duressEligibility\/\{[^}]*\}\s*\{([^}]*)\}/);
  assert.ok(match, "no duressEligibility rule found in firestore.rules");
  assert.ok(
    /allow\s+write:\s*if\s+false/.test(match[1]),
    "duressEligibility must deny all client writes — eligibility must only be " +
    "grantable via the admin enroll/revoke endpoints (Admin SDK), never by the " +
    "client whose eligibility it is (that would let anyone self-grant)"
  );
});
