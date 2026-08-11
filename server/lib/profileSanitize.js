"use strict";

// Pure, unit-testable decision helpers used by /migrateUid and /createChat.
//
// S02-H1: /migrateUid used to `.set()` the entire `users/{oldUid}` document
// verbatim onto `users/{userId}` via the Admin SDK, which bypasses Firestore
// rules entirely. `sanitizeMigratedUserFields` replays only the same
// allow-listed, type/size-bounded fields the `users/{uid}` write rule itself
// permits (see `firestore.rules`), so a legacy or otherwise-tainted document
// can never smuggle an unexpected field across a migration.
//
// S02-L2: /createChat wrote `myDisplayName`/`partnerDisplayName` straight from
// the request body with no type check or length bound. `isValidDisplayName`
// enforces the same bound as the `users/{uid}.displayName` rule (<=200 chars,
// must be a non-empty string) before either name is persisted to a chat doc
// the other participant reads.

const MAX_DISPLAY_NAME_LEN = 200;
const MAX_FCM_TOKEN_LEN = 4096;
const MAX_PLATFORM_LEN = 32;
const MAX_PHOTO_URL_LEN = 2048;

/**
 * Reduce an arbitrary `users/{uid}` document to only the fields the
 * `users/{uid}` Firestore write rule allow-lists, each re-validated for
 * type and size. Unknown fields, wrong types, or oversized values are
 * silently dropped rather than copied.
 *
 * @param {unknown} data
 * @returns {Record<string, string>}
 */
function sanitizeMigratedUserFields(data) {
  const safe = {};
  if (!data || typeof data !== "object") return safe;

  if (typeof data.displayName === "string" && data.displayName.length > 0 &&
      data.displayName.length <= MAX_DISPLAY_NAME_LEN) {
    safe.displayName = data.displayName;
  }
  if (typeof data.fcmToken === "string" && data.fcmToken.length > 0 &&
      data.fcmToken.length <= MAX_FCM_TOKEN_LEN) {
    safe.fcmToken = data.fcmToken;
  }
  if (typeof data.platform === "string" && data.platform.length > 0 &&
      data.platform.length <= MAX_PLATFORM_LEN) {
    safe.platform = data.platform;
  }
  if (typeof data.photoUrl === "string" && data.photoUrl.length > 0 &&
      data.photoUrl.length <= MAX_PHOTO_URL_LEN) {
    safe.photoUrl = data.photoUrl;
  }
  return safe;
}

/**
 * Validate a display name before it is persisted to a chat document that
 * the other participant can read.
 *
 * @param {unknown} value
 * @returns {boolean}
 */
function isValidDisplayName(value) {
  return typeof value === "string" && value.length > 0 && value.length <= MAX_DISPLAY_NAME_LEN;
}

module.exports = {
  sanitizeMigratedUserFields,
  isValidDisplayName,
  MAX_DISPLAY_NAME_LEN,
  MAX_FCM_TOKEN_LEN,
  MAX_PLATFORM_LEN,
  MAX_PHOTO_URL_LEN,
};
