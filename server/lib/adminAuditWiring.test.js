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
