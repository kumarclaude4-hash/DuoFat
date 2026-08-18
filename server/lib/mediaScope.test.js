"use strict";

// Unit tests for media-token scope authorization (S03-H1 remediation).
// Run with: npm test   (inside server/) — uses the Node built-in test runner.

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  decideScopeAccess,
  decideAvatarAccess,
  avatarOwnerFromKey,
  isAvatarKey,
  SCOPE_ALLOW,
  SCOPE_DENY,
} = require("./mediaScope");

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

// ── Avatar keys (SEC-A01 / profile-photo "Invalid key format" fix) ───────────
// Profile photos are stored as `avatars/<ownerUid>_<millis>.jpg` — a two-segment
// key that never matched the conversation-media allow-list, so every upload died
// at /mediaToken with "400 Invalid key format". These assert the key shape the
// client (SettingsActivity.uploadProfilePhoto) actually produces is recognised,
// and that ownership is decided from the key alone with no Firestore I/O.

// A representative real key: a 28-char Firebase UID + 13-digit millis timestamp.
const AVATAR_OWNER = "aBcDeF1234567890AbCdEf123456";
const AVATAR_KEY = `avatars/${AVATAR_OWNER}_1787064081340.jpg`;

test("isAvatarKey accepts the key shape the client uploads", () => {
  assert.equal(isAvatarKey(AVATAR_KEY), true);
  // Shortest/longest UID bounds (8..64) and the 10..17 digit timestamp bounds.
  assert.equal(isAvatarKey("avatars/12345678_1000000000.jpg"), true);
  assert.equal(isAvatarKey(`avatars/${"a".repeat(64)}_99999999999999999.jpg`), true);
});

test("isAvatarKey rejects conversation-media keys and malformed input", () => {
  // A well-formed media key must NOT be mistaken for an avatar (and vice versa),
  // so the two families keep their separate authorization questions.
  assert.equal(isAvatarKey("media/chat0123456789abcd/photo.jpg"), false);
  assert.equal(isAvatarKey("voice/chat0123456789abcd/clip.m4a"), false);
  // Avatars are JPEG-only — no smuggling a video extension through this branch.
  assert.equal(isAvatarKey(`avatars/${AVATAR_OWNER}_1787064081340.mp4`), false);
  // Path traversal, missing timestamp, wrong prefix, non-string.
  assert.equal(isAvatarKey("avatars/../etc/passwd"), false);
  assert.equal(isAvatarKey(`avatars/${AVATAR_OWNER}.jpg`), false);
  assert.equal(isAvatarKey("avatar/x_1787064081340.jpg"), false);
  assert.equal(isAvatarKey(null), false);
  assert.equal(isAvatarKey(42), false);
});

test("avatarOwnerFromKey extracts the owning UID, null on a non-avatar key", () => {
  assert.equal(avatarOwnerFromKey(AVATAR_KEY), AVATAR_OWNER);
  assert.equal(avatarOwnerFromKey("media/chat0123456789abcd/photo.jpg"), null);
  assert.equal(avatarOwnerFromKey("not a key"), null);
  assert.equal(avatarOwnerFromKey(undefined), null);
});

test("avatar write is allowed for the owner embedded in the key", () => {
  const decision = decideAvatarAccess({ uid: AVATAR_OWNER, key: AVATAR_KEY, op: "write" });
  assert.equal(decision.allowed, true);
  assert.equal(decision.reason, SCOPE_ALLOW.AVATAR_OWNER);
});

test("avatar write/delete is denied for anyone who is not the owner", () => {
  for (const op of ["write", "delete"]) {
    const decision = decideAvatarAccess({ uid: ATTACKER, key: AVATAR_KEY, op });
    assert.equal(decision.allowed, false, `op=${op}`);
    assert.equal(decision.reason, SCOPE_DENY.NOT_OWNER, `op=${op}`);
  }
});

test("avatar delete is allowed for the owner", () => {
  const decision = decideAvatarAccess({ uid: AVATAR_OWNER, key: AVATAR_KEY, op: "delete" });
  assert.equal(decision.allowed, true);
  assert.equal(decision.reason, SCOPE_ALLOW.AVATAR_OWNER);
});

test("avatar read is allowed for any authenticated caller (partner avatars)", () => {
  // Matches the existing exposure of photoUrl on users/{uid}: any signed-in
  // user may read the bytes, so chat-list partner avatars load.
  const decision = decideAvatarAccess({ uid: ATTACKER, key: AVATAR_KEY, op: "read" });
  assert.equal(decision.allowed, true);
  assert.equal(decision.reason, SCOPE_ALLOW.AVATAR_READER);
});

test("avatar access fails closed on empty uid, bad key, or unknown verb", () => {
  // Missing/empty uid — never authorize an anonymous caller.
  for (const uid of [undefined, null, "", 0, {}]) {
    const decision = decideAvatarAccess({ uid, key: AVATAR_KEY, op: "read" });
    assert.equal(decision.allowed, false, `uid=${JSON.stringify(uid)}`);
    assert.equal(decision.reason, SCOPE_DENY.BAD_INPUT, `uid=${JSON.stringify(uid)}`);
  }
  // A key that is not a well-formed avatar key is bad input, not a denial to reason about.
  assert.deepEqual(
    decideAvatarAccess({ uid: AVATAR_OWNER, key: "media/chat0123456789abcd/x.jpg", op: "write" }),
    { allowed: false, reason: SCOPE_DENY.BAD_INPUT }
  );
  // Unknown verb must not fall through to the permissive read branch.
  assert.deepEqual(
    decideAvatarAccess({ uid: AVATAR_OWNER, key: AVATAR_KEY, op: "list" }),
    { allowed: false, reason: SCOPE_DENY.BAD_INPUT }
  );
  // Called with nothing at all, denies rather than throwing.
  assert.equal(decideAvatarAccess().allowed, false);
});
