"use strict";

// ── S05-H3 wiring test ────────────────────────────────────────────────────────
//
// WHAT THIS IS, HONESTLY: a source-level structural test, not a behavioural one.
// It reads `index.js` as TEXT and asserts that the admin audit sink is actually
// called from each authentication branch. It does NOT execute those branches, so
// it cannot prove a row reaches Firestore.
//
// WHY IT EXISTS ANYWAY: the defect it guards against is exactly the one this
// program keeps producing — a fix that exists, reads correctly, and is described
// in a comment as load-bearing, but has NO CALLERS. Cluster A found
// `maintainLockCredential()` dead. Cluster B's first pass then added
// `auditAdminEvent()` with a comment claiming "login success, login failure,
// lockout and logout" were covered, while wiring it into `requireAdminAuth()`
// ONLY — so the four highest-value events in that sentence were still silently
// unrecorded. A behavioural test needs Firebase Admin credentials and a live
// Firestore; this needs neither, and it fails the moment someone adds an auth
// branch without an audit call. That trade is worth taking. Per SESSION_PROTOCOL
// §8: "'The function exists' is not evidence; wiring is."
//
// A real behavioural test of the audit write remains BLOCKED in this environment
// (no Firestore emulator / no service-account credentials) and is recorded as
// such rather than asserted.

const { test } = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");

const SERVER_SOURCE = fs.readFileSync(path.join(__dirname, "..", "index.js"), "utf8");

// Every admin auth event that must leave a durable trace. The action strings are
// asserted literally so a rename that breaks a forensic query fails here.
const REQUIRED_AUDIT_ACTIONS = [
  "admin_api_blocked_locked_out", // API hit while IP is locked out
  "admin_api_unauthorized",       // API hit with no valid token or session
  "admin_login_blocked_locked_out",
  "admin_login_unconfigured",     // login attempted with no ADMIN_TOKEN deployed
  "admin_login_failed",
  "admin_login_succeeded",
  "admin_logout",
];

test("every admin auth event is wired to the durable audit sink", () => {
  for (const action of REQUIRED_AUDIT_ACTIONS) {
    assert.ok(
      SERVER_SOURCE.includes(`auditAdminEvent("${action}"`),
      `no auditAdminEvent("${action}") call site in index.js — this event would ` +
      `exist only in Render's rolling console logs (S05-H3)`
    );
  }
});

test("the audit sink is defined, and called more than once", () => {
  assert.ok(
    /function auditAdminEvent\s*\(/.test(SERVER_SOURCE),
    "auditAdminEvent() is not defined in index.js"
  );
  // A definition plus one call is the shape the incomplete first pass had.
  const callCount = (SERVER_SOURCE.match(/auditAdminEvent\("/g) || []).length;
  assert.ok(
    callCount >= REQUIRED_AUDIT_ACTIONS.length,
    `expected at least ${REQUIRED_AUDIT_ACTIONS.length} audit call sites, found ${callCount}`
  );
});

test("the login handler audits both outcomes, not just failure", () => {
  // Auditing only failures is a common half-fix: "nobody failed" is not the same
  // as "nobody got in", and only the success row answers the second question.
  const loginHandlerStart = SERVER_SOURCE.indexOf('requestPath === "/admin/login"');
  assert.ok(loginHandlerStart > 0, "could not locate the POST /admin/login handler");
  // Bound the slice to the handler that follows, so a match from elsewhere in
  // this 4000-line file cannot make the assertion pass spuriously.
  const loginHandlerEnd = SERVER_SOURCE.indexOf('requestPath === "/admin/logout"', loginHandlerStart);
  assert.ok(loginHandlerEnd > loginHandlerStart, "could not locate the POST /admin/logout handler");
  const loginHandler = SERVER_SOURCE.slice(loginHandlerStart, loginHandlerEnd);

  assert.ok(
    loginHandler.includes('auditAdminEvent("admin_login_succeeded"'),
    "successful admin logins are not audited"
  );
  assert.ok(
    loginHandler.includes('auditAdminEvent("admin_login_failed"'),
    "failed admin logins are not audited"
  );
});

test("the audit log is never written the live admin token", () => {
  // A durable copy of a live credential would turn the audit trail into a second
  // place to steal it from. The failure row records the supplied LENGTH only.
  const failureCall = SERVER_SOURCE.slice(
    SERVER_SOURCE.indexOf('auditAdminEvent("admin_login_failed"')
  ).slice(0, 400);
  assert.ok(
    !/\bADMIN_TOKEN\b/.test(failureCall),
    "the failed-login audit row must not include ADMIN_TOKEN"
  );
  assert.ok(
    !/suppliedToken:\s*supplied\b|token:\s*supplied\b/.test(failureCall),
    "the failed-login audit row must not include the supplied secret itself"
  );
  assert.ok(
    failureCall.includes("suppliedLength"),
    "the failed-login audit row should record the supplied length for triage"
  );
});

test("S05-M1: the audit sink pseudonymises the operator IP, never writes it raw", () => {
  // adminAuditLog has no TTL and no retention policy (unlike Render's rolling
  // console logs), so a raw IP written here is a permanent, resolvable,
  // directly-identifying record — worse than the console logging SEC-L01 was
  // written to fix, in the collection meant to be its more careful cousin.
  const sinkStart = SERVER_SOURCE.indexOf("function auditAdminEvent");
  assert.ok(sinkStart > 0, "could not locate auditAdminEvent()");
  const sinkEnd = SERVER_SOURCE.indexOf("\n}", sinkStart);
  const sinkBody = SERVER_SOURCE.slice(sinkStart, sinkEnd);

  assert.ok(
    /adminIp:\s*ipTag\(getClientIp\(req\)\)/.test(sinkBody),
    "auditAdminEvent must write ipTag(getClientIp(req)), not the raw IP, as adminIp"
  );
  // The regression this guards against: `adminIp: getClientIp(req)` with no
  // ipTag() wrapper anywhere in the sink body.
  assert.ok(
    !/adminIp:\s*getClientIp\(req\)/.test(sinkBody),
    "auditAdminEvent must not write the raw client IP as adminIp"
  );
});

test("S05-M1: no admin action writes adminAuditLog directly, bypassing the pseudonymising sink", () => {
  // The regression this guards against: auditAdminEvent() existed and
  // correctly wrapped adminIp in ipTag(), but four call sites (waitlist
  // approve/deny, account unfreeze, duress enroll/revoke) still called
  // `db.collection("adminAuditLog").add(...)` directly with a raw
  // `adminIp: getClientIp(req)` — the sink was correct, but most of its
  // callers weren't using it. "The function exists and is correct" is not
  // evidence that every write site goes through it.
  const allWrites = SERVER_SOURCE.match(/db\.collection\("adminAuditLog"\)\.add\(/g) || [];
  // Exactly one is legitimate: the sink's own definition. Any more means some
  // call site is writing directly instead of going through auditAdminEvent().
  assert.strictEqual(
    allWrites.length,
    1,
    `expected exactly 1 db.collection("adminAuditLog").add(...) call site (auditAdminEvent()'s own ` +
    `definition), found ${allWrites.length} — every OTHER admin action must route through ` +
    `auditAdminEvent() so adminIp is always pseudonymised, not write directly`
  );
});

test("S05-M1: duress enrollment/revocation audit rows never carry the raw uid", () => {
  // The finding's own "most sensitive" callout: `action: "duress_enrolled",
  // uid: <raw uid>` in a permanent, no-TTL collection is a durable record of
  // exactly which account has the duress feature enabled — the single most
  // dangerous fact in this system to disclose to a coercive adversary who
  // later reaches this collection.
  for (const action of ["duress_enrolled", "duress_revoked"]) {
    const callStart = SERVER_SOURCE.indexOf(`auditAdminEvent("${action}"`);
    assert.ok(callStart > 0, `no auditAdminEvent("${action}") call site found`);
    const call = SERVER_SOURCE.slice(callStart, callStart + 200);
    assert.ok(
      /uid:\s*uidTag\(uid\)/.test(call),
      `auditAdminEvent("${action}", ...) must pass uid: uidTag(uid), not the raw uid`
    );
  }
});

test("S05-M1: waitlist requestId is never logged to stdout in the clear", () => {
  assert.ok(
    !/console\.(log|warn|error)\(`[^`]*requestId=\$\{requestId\}/.test(SERVER_SOURCE),
    "a console log line interpolates the raw requestId — use reqTag(requestId) instead"
  );
});

// ── S05-H2 wiring test ────────────────────────────────────────────────────────
//
// Same "wiring, not existence" caveat as above: this proves the deny route,
// admin-auth gate, status transition, and audit call are all present and in
// the right order, as text. It does not prove a request reaches Firestore
// (BLOCKED here — no emulator/credentials).

test("S05-H2: POST /admin/api/waitlist/deny exists, is admin-gated, and sets status: denied", () => {
  const routeStart = SERVER_SOURCE.indexOf('req.url === "/admin/api/waitlist/deny"');
  assert.ok(routeStart > 0, "no POST /admin/api/waitlist/deny route found — the deny path is missing");

  // Bound the slice to this handler only, so matches from /approve above it
  // (or /admin/api/locked below it) cannot make these assertions pass
  // spuriously.
  const nextRouteStart = SERVER_SOURCE.indexOf('req.url === "/admin/api/locked"', routeStart);
  assert.ok(nextRouteStart > routeStart, "could not bound the deny handler");
  const handler = SERVER_SOURCE.slice(routeStart, nextRouteStart);

  assert.ok(
    handler.includes("requireAdminAuth(req, res)"),
    "the deny endpoint must be gated by requireAdminAuth, same as every other /admin/api route"
  );
  assert.ok(
    /status\s*!==\s*"pending"/.test(handler),
    "deny must reject a requestId that is not currently pending (no re-denying approved/denied docs)"
  );
  assert.ok(
    /status:\s*"denied"/.test(handler),
    "deny must set status: \"denied\" — the finding's fix explicitly asks for a denied status " +
    "distinct from pending/approved/used"
  );
  assert.ok(
    handler.includes('auditAdminEvent("waitlist_denied"'),
    "a denial must leave a durable audit trail, same as approval"
  );
});

test("adminAuditLog is server-only in firestore.rules", () => {
  // The audit trail is worthless if a client can read or rewrite it.
  const rules = fs.readFileSync(path.join(__dirname, "..", "..", "firestore.rules"), "utf8");
  const match = rules.match(/match\s+\/adminAuditLog\/\{[^}]*\}\s*\{([^}]*)\}/);
  assert.ok(match, "no adminAuditLog rule found in firestore.rules");
  assert.match(
    match[1],
    /allow\s+read,\s*write:\s*if\s+false/,
    "adminAuditLog must deny all client reads and writes"
  );
});
