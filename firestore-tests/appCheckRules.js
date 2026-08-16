/**
 * Shared helpers for loading firestore.rules in the App Check dual-suite setup
 * (S08-H5 item 4d).
 *
 * The emulator used by @firebase/rules-unit-testing v3 cannot mint App Check
 * tokens, so `request.app` is always null there. Item 4a wired
 * `appCheckVerified()` (defined in firestore.rules as `request.app != null`)
 * into the seed-derived recovery/backup + duress `allow` clauses. That leaves
 * the test harness needing to prove two different things that cannot both be
 * shown against the verbatim rules in one emulator run:
 *
 *   Suite A (rules.test.js)   — the auth/ownership/lock logic is unchanged.
 *                               Needs the gate OUT OF THE WAY, so it loads the
 *                               rules with appCheckVerified() forced to `true`.
 *   Suite B (appcheck.test.js)— the gate actually denies when App Check is
 *                               absent. Loads the rules VERBATIM and asserts the
 *                               gated paths fail even with valid auth.
 *
 * Both share this module so the substitution can never drift between them.
 */

const { readFileSync } = require('fs');

/**
 * The exact body line of appCheckVerified() in firestore.rules. If the rule is
 * refactored, this must be updated in lockstep — hence the assertion below,
 * which fails loudly rather than silently loading un-stubbed rules (which would
 * make Suite A fail in a confusing way instead of here with a clear message).
 */
const APP_CHECK_BODY = 'return request.app != null;';

/**
 * Reads firestore.rules and rewrites appCheckVerified()'s body to `return true;`
 * so the gate is a no-op. Used by Suite A only.
 */
function loadRulesWithAppCheckStubbed(rulesPath) {
  const raw = readFileSync(rulesPath, 'utf8');
  if (!raw.includes(APP_CHECK_BODY)) {
    throw new Error(
      'appCheckRules: could not find the expected appCheckVerified() body (' +
      JSON.stringify(APP_CHECK_BODY) + ') in ' + rulesPath + '. If the rule was ' +
      'refactored, update APP_CHECK_BODY in firestore-tests/appCheckRules.js so ' +
      'Suite A keeps stubbing App Check instead of silently testing the live gate.'
    );
  }
  return raw.replace(APP_CHECK_BODY, 'return true;');
}

/** Reads firestore.rules verbatim (no substitution). Used by Suite B. */
function loadRulesVerbatim(rulesPath) {
  return readFileSync(rulesPath, 'utf8');
}

module.exports = {
  APP_CHECK_BODY,
  loadRulesWithAppCheckStubbed,
  loadRulesVerbatim,
};
