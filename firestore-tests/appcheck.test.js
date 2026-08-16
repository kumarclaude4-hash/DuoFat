/**
 * DuoShield — App Check enforcement suite (Suite B, S08-H5 item 4d)
 *
 * Loads the REAL firestore.rules verbatim — appCheckVerified() is NOT stubbed
 * here (that is Suite A / rules.test.js). @firebase/rules-unit-testing v3 cannot
 * mint App Check tokens, so `request.app` is always null in this emulator. That
 * is exactly the condition this suite exploits: every gated path must DENY even
 * when the caller is the correctly-authenticated owner and every non-App-Check
 * precondition (ownership, not-locked, eligibility) is satisfied.
 *
 * If any of these assertions flips to a success, the App Check gate on that path
 * was removed or bypassed. A green Suite A alone would not catch that, because
 * Suite A forces the gate open.
 */

const {
  initializeTestEnvironment,
  assertFails,
} = require('@firebase/rules-unit-testing');
const { resolve } = require('path');
const { loadRulesVerbatim } = require('./appCheckRules');

const PROJECT_ID = 'duoshield-appcheck-test';
const RULES_PATH = resolve(__dirname, '../firestore.rules');

let testEnv;

function asUser(uid) {
  return testEnv.authenticatedContext(uid).firestore();
}

async function seed(path, data) {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().doc(path).set(data);
  });
}

beforeAll(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules: loadRulesVerbatim(RULES_PATH),
      host: '127.0.0.1',
      port: 8080,
    },
  });
});

afterAll(async () => {
  await testEnv.cleanup();
});

afterEach(async () => {
  await testEnv.clearFirestore();
});

// ─────────────────────────────────────────────────────────────────────────────
// Every gated path must DENY the authenticated owner because request.app is null.
// ─────────────────────────────────────────────────────────────────────────────

describe('App Check gate — recovery/{uid}', () => {
  test('owner read denied without App Check', async () => {
    await seed('recovery/alice', { blob: 'x' });
    await assertFails(asUser('alice').doc('recovery/alice').get());
  });

  test('owner write denied without App Check', async () => {
    await assertFails(asUser('alice').doc('recovery/alice').set({ blob: 'y' }));
  });
});

describe('App Check gate — backups/{userId} (+ subcollections)', () => {
  // No accountLock doc is seeded, so accountNotLocked() is satisfied; the ONLY
  // remaining reason these can fail is the missing App Check token.
  test('meta read denied without App Check', async () => {
    await seed('backups/alice', { lastBackupTs: 1, count: 0 });
    await assertFails(asUser('alice').doc('backups/alice').get());
  });

  test('meta create denied without App Check', async () => {
    await assertFails(
      asUser('alice').doc('backups/alice').set({ lastBackupTs: 1, count: 0 })
    );
  });

  test('messages read denied without App Check', async () => {
    await seed('backups/alice/messages/m1', { ciphertext: 'z' });
    await assertFails(asUser('alice').doc('backups/alice/messages/m1').get());
  });

  test('messages create denied without App Check', async () => {
    await assertFails(
      asUser('alice').doc('backups/alice/messages/m1').set({ ciphertext: 'z' })
    );
  });

  test('contacts read denied without App Check', async () => {
    await seed('backups/alice/contacts/c1', { name: 'Bob' });
    await assertFails(asUser('alice').doc('backups/alice/contacts/c1').get());
  });

  test('groups read denied without App Check', async () => {
    await seed('backups/alice/groups/g1', { name: 'Group' });
    await assertFails(asUser('alice').doc('backups/alice/groups/g1').get());
  });
});

describe('App Check gate — duressEligibility/{accountId}', () => {
  test('owner read denied without App Check', async () => {
    await seed('duressEligibility/alice', { eligible: true });
    await assertFails(asUser('alice').doc('duressEligibility/alice').get());
  });
});

describe('App Check gate — accountLock/{accountId}', () => {
  test('owner read denied without App Check', async () => {
    await seed('accountLock/alice', { locked: true });
    await assertFails(asUser('alice').doc('accountLock/alice').get());
  });

  test('create denied without App Check even when eligible', async () => {
    // Eligibility satisfied — the only remaining gate is App Check.
    await seed('duressEligibility/alice', { eligible: true });
    await assertFails(
      asUser('alice').doc('accountLock/alice').set({ locked: true })
    );
  });
});

describe('App Check gate — users/{uid}/devices/{deviceId}', () => {
  test('owner read denied without App Check', async () => {
    await seed('users/alice/devices/dev1', {
      fcmToken: 'tok', platform: 'android', createdAt: 1, updatedAt: 1,
    });
    await assertFails(asUser('alice').doc('users/alice/devices/dev1').get());
  });

  test('owner create denied without App Check', async () => {
    await assertFails(
      asUser('alice').doc('users/alice/devices/dev1').set({
        fcmToken: 'tok', platform: 'android', createdAt: 1, updatedAt: 1,
      })
    );
  });
});
