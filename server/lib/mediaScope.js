"use strict";

// Media-token scope authorization (S03-H1 / SEC-A01 remediation).
//
// ── The defect this closes ───────────────────────────────────────────────────
// A media object key is `<media|voice>/<scopeId>/<uuid>.<ext>`, so the middle
// segment names the conversation the object belongs to. /mediaToken decided
// authorization by asking "is the caller in chats/{scopeId}.participants OR in
// groups/{scopeId}.members?" — accepting *either* collection as proof.
//
// firestore.rules lets any authenticated user create groups/{anyId} as long as
// they list themselves in `members`:
//
//     match /groups/{groupId} {
//       allow create: if request.auth != null
//                     && request.auth.uid in request.resource.data.members;
//
// There is no constraint on the document ID. 1:1 chat IDs are deterministic
// (SHA-256 over the two sorted UIDs — see the `calls` rule's comment in
// firestore.rules), so an attacker who can compute a victim conversation's
// chatId could create a *shadow* `groups/{thatChatId}` naming only themselves,
// and the OR above would then authorize a `read` or `delete` media token for a
// conversation they have nothing to do with.
//
// ── The invariant enforced here ──────────────────────────────────────────────
// A scopeId must resolve to exactly ONE conversation. A scopeId that names both
// a chat and a group is not a legitimate state that any client flow produces —
// chat IDs are content-derived hashes and group IDs are random — so the overlap
// itself is the attack signature, and the only safe response is to deny.
//
// Deciding this from already-fetched documents (rather than doing I/O here)
// keeps the rule pure and unit-testable without a Firestore emulator, which is
// how it earns a real test rather than an asserted one.

/** Reasons a decision was reached. Stable strings — logs and tests match on them. */
const SCOPE_DENY = {
  BAD_INPUT: "bad-input",
  NOT_FOUND: "scope-not-found",
  AMBIGUOUS: "scope-ambiguous",
  NOT_MEMBER: "not-a-member",
  MALFORMED_GROUP: "group-missing-creator",
};

const SCOPE_ALLOW = {
  CHAT: "chat-participant",
  GROUP: "group-member",
};

/**
 * Decides whether `uid` may mint a media token for a scope, given the two
 * documents that scope could name.
 *
 * Both document arguments use the shape `{ exists, data }`, mirroring the
 * subset of the Firestore DocumentSnapshot API this needs (`data` may be a
 * plain object or a function returning one, so real snapshots pass straight
 * through).
 *
 * @returns {{allowed: boolean, reason: string}}
 */
function decideScopeAccess({ uid, chatDoc, groupDoc } = {}) {
  if (typeof uid !== "string" || uid === "") {
    return { allowed: false, reason: SCOPE_DENY.BAD_INPUT };
  }

  const chat = readDoc(chatDoc);
  const group = readDoc(groupDoc);

  // Fail closed on ambiguity BEFORE any membership test. Order matters: if the
  // membership checks ran first, an attacker who is a legitimate member of the
  // shadow group they created would be allowed by the group branch before the
  // collision was ever noticed.
  if (chat && group) {
    return { allowed: false, reason: SCOPE_DENY.AMBIGUOUS };
  }

  if (chat) {
    const participants = chat.participants;
    if (Array.isArray(participants) && participants.includes(uid)) {
      return { allowed: true, reason: SCOPE_ALLOW.CHAT };
    }
    return { allowed: false, reason: SCOPE_DENY.NOT_MEMBER };
  }

  if (group) {
    // `createdBy` is immutable per firestore.rules and every legitimate group
    // carries it. Requiring it costs nothing and rejects the minimal
    // `{members:[self]}` document a squatter would write, independently of the
    // ambiguity check above — defense in depth for the case where a future
    // rules change reintroduces ID squatting.
    const createdBy = group.createdBy;
    const members = group.members;
    if (typeof createdBy !== "string" || createdBy === "") {
      return { allowed: false, reason: SCOPE_DENY.MALFORMED_GROUP };
    }
    if (!Array.isArray(members) || !members.includes(createdBy)) {
      return { allowed: false, reason: SCOPE_DENY.MALFORMED_GROUP };
    }
    if (members.includes(uid)) {
      return { allowed: true, reason: SCOPE_ALLOW.GROUP };
    }
    return { allowed: false, reason: SCOPE_DENY.NOT_MEMBER };
  }

  return { allowed: false, reason: SCOPE_DENY.NOT_FOUND };
}

/**
 * Normalizes a snapshot-ish value to its data object, or null when the document
 * does not exist. A document that exists with no readable data is treated as
 * non-existent rather than as an empty object, so it can never satisfy a
 * membership test.
 */
function readDoc(doc) {
  if (!doc || !doc.exists) return null;
  const data = typeof doc.data === "function" ? doc.data() : doc.data;
  if (!data || typeof data !== "object") return null;
  return data;
}

module.exports = { decideScopeAccess, SCOPE_ALLOW, SCOPE_DENY };
