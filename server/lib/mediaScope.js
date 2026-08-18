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
  NOT_OWNER: "not-avatar-owner",
};

const SCOPE_ALLOW = {
  CHAT: "chat-participant",
  GROUP: "group-member",
  AVATAR_OWNER: "avatar-owner",
  AVATAR_READER: "avatar-reader",
};

// ── Avatar keys ──────────────────────────────────────────────────────────────
// Profile photos are stored as `avatars/<ownerUid>_<millis>.jpg` (see
// SettingsActivity.uploadProfilePhoto). That is a TWO-segment key with an
// `avatars` prefix, so it never matched the `<media|voice>/<scopeId>/<file>`
// allow-list on either tier and every profile-photo upload died at
// /mediaToken with `400 Invalid key format` — the user-visible
// "Upload failed: mediaToken denied [400]: Invalid key format".
//
// Avatars are not conversation-scoped, so the chat/group membership test above
// is the wrong question for them: the middle segment of an avatar key is a UID,
// not a chatId, and asking "is the caller in chats/<uid>?" would deny the owner
// of the photo. They get their own decision instead:
//
//   • write / delete — owner only. The UID is embedded in the key, so this is a
//     direct comparison against the verified caller and an attacker cannot mint
//     a token that overwrites or deletes somebody else's profile photo.
//   • read — any authenticated user. This matches the existing exposure of the
//     photo itself: `photoUrl` lives on `users/{uid}`, which firestore.rules
//     makes readable by every signed-in user, and it is additionally propagated
//     onto conversation documents. Restricting the bytes harder than the
//     pointer would break partner avatars in chat lists without withholding
//     anything, so read stays open to signed-in callers only — never anonymous.
const AVATAR_KEY_FORMAT = /^avatars\/[A-Za-z0-9-]{8,64}_\d{10,17}\.jpg$/;

/** Ops that mutate the stored object, and so require ownership. */
const AVATAR_WRITE_OPS = new Set(["write", "delete"]);

/**
 * Extracts the owning UID from an avatar key, or null when `key` is not a
 * well-formed avatar key. Splits on the LAST underscore so that the timestamp
 * suffix is removed even though a UID may itself contain no underscore — the
 * format regex already guarantees the tail is all digits.
 */
function avatarOwnerFromKey(key) {
  if (typeof key !== "string" || !AVATAR_KEY_FORMAT.test(key)) return null;
  const name = key.slice("avatars/".length, -".jpg".length);
  const cut = name.lastIndexOf("_");
  if (cut <= 0) return null;
  return name.slice(0, cut);
}

/** True when `key` addresses a profile photo rather than conversation media. */
function isAvatarKey(key) {
  return typeof key === "string" && AVATAR_KEY_FORMAT.test(key);
}

/**
 * Decides whether `uid` may mint a token for an avatar object.
 *
 * Pure like {@link decideScopeAccess} — no Firestore I/O — because ownership is
 * fully determined by the key itself, which is exactly what makes this cheap
 * enough to check without a lookup.
 *
 * @returns {{allowed: boolean, reason: string}}
 */
function decideAvatarAccess({ uid, key, op } = {}) {
  if (typeof uid !== "string" || uid === "") {
    return { allowed: false, reason: SCOPE_DENY.BAD_INPUT };
  }
  const owner = avatarOwnerFromKey(key);
  if (!owner) return { allowed: false, reason: SCOPE_DENY.BAD_INPUT };

  if (AVATAR_WRITE_OPS.has(op)) {
    return owner === uid
      ? { allowed: true, reason: SCOPE_ALLOW.AVATAR_OWNER }
      : { allowed: false, reason: SCOPE_DENY.NOT_OWNER };
  }
  if (op === "read") {
    return { allowed: true, reason: SCOPE_ALLOW.AVATAR_READER };
  }
  // Unknown verb: fail closed rather than defaulting to the permissive branch.
  return { allowed: false, reason: SCOPE_DENY.BAD_INPUT };
}

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

module.exports = {
  decideScopeAccess,
  decideAvatarAccess,
  avatarOwnerFromKey,
  isAvatarKey,
  AVATAR_KEY_FORMAT,
  SCOPE_ALLOW,
  SCOPE_DENY,
};
