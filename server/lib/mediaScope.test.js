"use strict";

// Unit tests for media-token scope authorization (S03-H1 remediation).
// Run with: npm test   (inside server/) — uses the Node built-in test runner.

const test = require("node:test");
const assert = require("node:assert/strict");
const { decideScopeAccess, SCOPE_ALLOW, SCOPE_DENY } = require("./mediaScope");

const VICTIM = "uid-victim";
const PEER = "uid-peer";
const ATTACKER = "uid-attacker";

/** Snapshot stub with `data` as a function, matching Firestore's real API. */
const doc = (data) => ({ exists: true, data: () => data });
const missing = { exists: false, data: () => undefined };

const chatOf = (...participants) => doc({ participants });
const groupOf = (createdBy, ...members) => doc({ createdBy, members });

// ── The attack this finding is about ─────────────────────────────────────────

test("S03-H1: shadow group shadowing a real chat is denied, not allowed", () => {
  // The victim's legitimate 1:1 conversation.
  const chatDoc = chatOf(VICTIM, PEER);
  // The attacker created groups/{sameChatId} naming only themselves.
  const groupDoc = groupOf(ATTACKER, ATTACKER);

  const decision = decideScopeAccess({ uid: ATTACKER, chatDoc, groupDoc });

  assert.equal(decision.allowed, false);
  assert.equal(decision.reason, SCOPE_DENY.AMBIGUOUS);
});

test("S03-H1: a shadow group denies even a genuine chat participant (fail closed)", () => {
  // Ambiguity is unresolvable, so the victim is denied too. Denying a real
  // participant is an availability cost; allowing the attacker is a
  // confidentiality breach. This asserts we chose the former deliberately.
  const decision = decideScopeAccess({
    uid: VICTIM,
    chatDoc: chatOf(VICTIM, PEER),
    groupDoc: groupOf(ATTACKER, ATTACKER),
  });

  assert.equal(decision.allowed, false);
  assert.equal(decision.reason, SCOPE_DENY.AMBIGUOUS);
});

test("S03-H1: ambiguity is rejected before membership, even if attacker is in both", () => {
  const decision = decideScopeAccess({
    uid: ATTACKER,
    chatDoc: chatOf(VICTIM, ATTACKER),
    groupDoc: groupOf(ATTACKER, ATTACKER),
  });

  assert.equal(decision.allowed, false);
  assert.equal(decision.reason, SCOPE_DENY.AMBIGUOUS);
});

test("S03-H1: squatted group with no createdBy is rejected", () => {
  // The minimal document firestore.rules' create allows: {members:[self]}.
  const decision = decideScopeAccess({
    uid: ATTACKER,
    chatDoc: missing,
    groupDoc: doc({ members: [ATTACKER] }),
  });

  assert.equal(decision.allowed, false);
  assert.equal(decision.reason, SCOPE_DENY.MALFORMED_GROUP);
});

test("S03-H1: group whose createdBy is not a member is rejected", () => {
  const decision = decideScopeAccess({
    uid: ATTACKER,
    chatDoc: missing,
    groupDoc: doc({ createdBy: VICTIM, members: [ATTACKER] }),
  });

  assert.equal(decision.allowed, false);
  assert.equal(decision.reason, SCOPE_DENY.MALFORMED_GROUP);
});

// ── Legitimate access still works ────────────────────────────────────────────

test("chat participant is allowed when no group shadows the id", () => {
  const decision = decideScopeAccess({
    uid: VICTIM,
    chatDoc: chatOf(VICTIM, PEER),
    groupDoc: missing,
  });

  assert.equal(decision.allowed, true);
  assert.equal(decision.reason, SCOPE_ALLOW.CHAT);
});

test("group member is allowed for a well-formed group", () => {
  const decision = decideScopeAccess({
    uid: PEER,
    chatDoc: missing,
    groupDoc: groupOf(VICTIM, VICTIM, PEER),
  });

  assert.equal(decision.allowed, true);
  assert.equal(decision.reason, SCOPE_ALLOW.GROUP);
});

test("group creator is allowed for their own group", () => {
  const decision = decideScopeAccess({
    uid: VICTIM,
    chatDoc: missing,
    groupDoc: groupOf(VICTIM, VICTIM, PEER),
  });

  assert.equal(decision.allowed, true);
  assert.equal(decision.reason, SCOPE_ALLOW.GROUP);
});

// ── Non-membership and malformed input ───────────────────────────────────────

test("non-participant of an existing chat is denied", () => {
  const decision = decideScopeAccess({
    uid: ATTACKER,
    chatDoc: chatOf(VICTIM, PEER),
    groupDoc: missing,
  });

  assert.equal(decision.allowed, false);
  assert.equal(decision.reason, SCOPE_DENY.NOT_MEMBER);
});

test("non-member of an existing group is denied", () => {
  const decision = decideScopeAccess({
    uid: ATTACKER,
    chatDoc: missing,
    groupDoc: groupOf(VICTIM, VICTIM, PEER),
  });

  assert.equal(decision.allowed, false);
  assert.equal(decision.reason, SCOPE_DENY.NOT_MEMBER);
});

test("unknown scope is denied", () => {
  const decision = decideScopeAccess({ uid: VICTIM, chatDoc: missing, groupDoc: missing });

  assert.equal(decision.allowed, false);
  assert.equal(decision.reason, SCOPE_DENY.NOT_FOUND);
});

test("missing or empty uid is denied", () => {
  for (const uid of [undefined, null, "", 0, {}]) {
    const decision = decideScopeAccess({
      uid,
      chatDoc: chatOf(VICTIM, PEER),
      groupDoc: missing,
    });
    assert.equal(decision.allowed, false, `uid=${JSON.stringify(uid)}`);
    assert.equal(decision.reason, SCOPE_DENY.BAD_INPUT);
  }
});

test("called with no arguments at all, denies rather than throwing", () => {
  const decision = decideScopeAccess();
  assert.equal(decision.allowed, false);
  assert.equal(decision.reason, SCOPE_DENY.BAD_INPUT);
});

test("participants field of the wrong type is denied, not coerced", () => {
  // A string containing the uid must not satisfy an includes() test.
  const decision = decideScopeAccess({
    uid: VICTIM,
    chatDoc: doc({ participants: VICTIM }),
    groupDoc: missing,
  });

  assert.equal(decision.allowed, false);
  assert.equal(decision.reason, SCOPE_DENY.NOT_MEMBER);
});

test("existing document with no readable data cannot authorize", () => {
  const decision = decideScopeAccess({
    uid: VICTIM,
    chatDoc: { exists: true, data: () => undefined },
    groupDoc: missing,
  });

  assert.equal(decision.allowed, false);
  assert.equal(decision.reason, SCOPE_DENY.NOT_FOUND);
});

test("plain-object data (not a function) is also supported", () => {
  const decision = decideScopeAccess({
    uid: VICTIM,
    chatDoc: { exists: true, data: { participants: [VICTIM, PEER] } },
    groupDoc: missing,
  });

  assert.equal(decision.allowed, true);
  assert.equal(decision.reason, SCOPE_ALLOW.CHAT);
});
