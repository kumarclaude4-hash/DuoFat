/**
 * DuoShield — Firestore Security Rules Test Suite
 *
 * Run with: npm test  (requires Firebase Emulator — see README)
 *
 * Uses @firebase/rules-unit-testing v3 which handles emulator start/stop.
 */

const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require('@firebase/rules-unit-testing');
const { readFileSync } = require('fs');
const { resolve } = require('path');

// ── helpers ──────────────────────────────────────────────────────────────────

const PROJECT_ID = 'duoshield-test';
const RULES_PATH = resolve(__dirname, '../firestore.rules');

let testEnv;

/** Shorthand: get a Firestore handle as a specific authenticated user. */
function asUser(uid) {
  return testEnv.authenticatedContext(uid).firestore();
}

/** Shorthand: get a Firestore handle with no authentication. */
function asAnon() {
  return testEnv.unauthenticatedContext().firestore();
}

// ── setup / teardown ──────────────────────────────────────────────────────────

beforeAll(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules: readFileSync(RULES_PATH, 'utf8'),
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

// ── seed helpers ──────────────────────────────────────────────────────────────

/** Write a document bypassing rules (Admin SDK path via withSecurityRulesDisabled). */
async function seed(path, data) {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().doc(path).set(data);
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// USERS
// ─────────────────────────────────────────────────────────────────────────────

describe('/users/{uid}', () => {
  beforeEach(async () => {
    await seed('users/alice', { displayName: 'Alice', fcmToken: 'tok_alice' });
  });

  test('any signed-in user can read any user doc', async () => {
    await assertSucceeds(asUser('bob').doc('users/alice').get());
  });

  test('unauthenticated user cannot read', async () => {
    await assertFails(asAnon().doc('users/alice').get());
  });

  test('owner can write their own doc', async () => {
    await assertSucceeds(
      asUser('alice').doc('users/alice').set({ displayName: 'Alice2' })
    );
  });

  test('non-owner cannot write another user doc', async () => {
    await assertFails(
      asUser('bob').doc('users/alice').set({ displayName: 'Hacked' })
    );
  });

  test('unauthenticated cannot write', async () => {
    await assertFails(
      asAnon().doc('users/alice').set({ displayName: 'X' })
    );
  });
});

describe('/users/{uid}/public_keys/{doc}', () => {
  beforeEach(async () => {
    await seed('users/alice/public_keys/bundle', {
      identityKey: 'ik_alice',
      preKeys: [{ id: 1, key: 'pk1' }],
    });
  });

  test('any signed-in user can read a key bundle', async () => {
    await assertSucceeds(
      asUser('bob').doc('users/alice/public_keys/bundle').get()
    );
  });

  test('unauthenticated cannot read key bundle', async () => {
    await assertFails(asAnon().doc('users/alice/public_keys/bundle').get());
  });

  test('owner can create their key bundle', async () => {
    await assertSucceeds(
      asUser('carol').doc('users/carol/public_keys/bundle').set({
        identityKey: 'ik_carol',
        preKeys: [],
      })
    );
  });

  test('non-owner cannot create key bundle', async () => {
    await assertFails(
      asUser('bob').doc('users/carol/public_keys/bundle').set({
        identityKey: 'ik_hijack',
        preKeys: [],
      })
    );
  });

  test('any signed-in user can consume a one-time pre-key (oneTimePreKeys + updatedAt only)', async () => {
    await assertSucceeds(
      asUser('bob').doc('users/alice/public_keys/bundle').update({
        oneTimePreKeys: [],
        updatedAt: 2000,
      })
    );
  });

  test('non-owner cannot overwrite identityKey via cross-user update (F19)', async () => {
    await assertFails(
      asUser('bob').doc('users/alice/public_keys/bundle').update({
        identityKey: 'hijacked_ik',
      })
    );
  });

  test('non-owner cannot smuggle identityKey alongside oneTimePreKeys in the same update (F19)', async () => {
    await assertFails(
      asUser('bob').doc('users/alice/public_keys/bundle').update({
        oneTimePreKeys: [],
        identityKey: 'hijacked_ik',
      })
    );
  });

  test('owner can still update identityKey/signedPreKey on their own bundle', async () => {
    await assertSucceeds(
      asUser('alice').doc('users/alice/public_keys/bundle').update({
        identityKey: 'rotated_ik',
      })
    );
  });

  test('owner can delete their key bundle', async () => {
    await assertSucceeds(
      asUser('alice').doc('users/alice/public_keys/bundle').delete()
    );
  });

  test('non-owner cannot delete key bundle', async () => {
    await assertFails(
      asUser('bob').doc('users/alice/public_keys/bundle').delete()
    );
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// CHATS
// ─────────────────────────────────────────────────────────────────────────────

describe('/chats/{chatId}', () => {
  const CHAT_ID = 'chat_ab';

  beforeEach(async () => {
    await seed(`chats/${CHAT_ID}`, {
      participants: ['alice', 'bob'],
      lastMessage: 'hey',
    });
  });

  test('participant can read the chat', async () => {
    await assertSucceeds(asUser('alice').doc(`chats/${CHAT_ID}`).get());
  });

  test('non-participant cannot read the chat', async () => {
    await assertFails(asUser('eve').doc(`chats/${CHAT_ID}`).get());
  });

  test('unauthenticated cannot read', async () => {
    await assertFails(asAnon().doc(`chats/${CHAT_ID}`).get());
  });

  test('client cannot create a chat (server-only via Admin SDK)', async () => {
    // The create rule is intentionally `if false` — chat docs are created exclusively
    // by the server's /createChat endpoint using the Admin SDK (F6 fix).
    await assertFails(
      asUser('alice').doc('chats/chat_ac').set({
        participants: ['alice', 'carol'],
      })
    );
  });

  test('user cannot create a chat without themselves', async () => {
    await assertFails(
      asUser('alice').doc('chats/chat_bc').set({
        participants: ['bob', 'carol'],
      })
    );
  });

  test('participant can update the chat (e.g. lastMessage)', async () => {
    await assertSucceeds(
      asUser('bob').doc(`chats/${CHAT_ID}`).update({ lastMessage: 'hi' })
    );
  });

  test('non-participant cannot update', async () => {
    await assertFails(
      asUser('eve').doc(`chats/${CHAT_ID}`).update({ lastMessage: 'owned' })
    );
  });
});

describe('/chats/{chatId}/messages/{msgId}', () => {
  const CHAT_ID = 'chat_ab';
  const MSG_ID  = 'msg_1';

  beforeEach(async () => {
    await seed(`chats/${CHAT_ID}`, { participants: ['alice', 'bob'] });
    await seed(`chats/${CHAT_ID}/messages/${MSG_ID}`, {
      sender: 'alice',
      text: 'hello',
      timestamp: 1000,
    });
  });

  test('participant can read messages', async () => {
    await assertSucceeds(
      asUser('alice').doc(`chats/${CHAT_ID}/messages/${MSG_ID}`).get()
    );
  });

  test('non-participant cannot read messages', async () => {
    await assertFails(
      asUser('eve').doc(`chats/${CHAT_ID}/messages/${MSG_ID}`).get()
    );
  });

  test('participant can write a message', async () => {
    await assertSucceeds(
      asUser('bob').doc(`chats/${CHAT_ID}/messages/msg_2`).set({
        sender: 'bob',
        text: 'hey',
        timestamp: 2000,
        isEncrypted: true,  // required by rules (F19/F21 fix)
      })
    );
  });

  test('non-participant cannot write a message', async () => {
    await assertFails(
      asUser('eve').doc(`chats/${CHAT_ID}/messages/msg_evil`).set({
        sender: 'eve',
        text: 'injection',
        timestamp: 3000,
      })
    );
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// GROUPS
// ─────────────────────────────────────────────────────────────────────────────

describe('/groups/{groupId}', () => {
  const GROUP_ID = 'group_1';

  beforeEach(async () => {
    await seed(`groups/${GROUP_ID}`, {
      members: ['alice', 'bob'],
      createdBy: 'alice',
      name: 'Test Group',
    });
  });

  test('member can read the group', async () => {
    await assertSucceeds(asUser('alice').doc(`groups/${GROUP_ID}`).get());
  });

  test('non-member cannot read the group', async () => {
    await assertFails(asUser('eve').doc(`groups/${GROUP_ID}`).get());
  });

  test('user can create a group they are in', async () => {
    await assertSucceeds(
      asUser('carol').doc('groups/group_2').set({
        members: ['carol', 'dave'],
        createdBy: 'carol',
        name: 'New Group',
      })
    );
  });

  test('user cannot create group without themselves in members', async () => {
    await assertFails(
      asUser('eve').doc('groups/group_3').set({
        members: ['alice', 'bob'],
        createdBy: 'alice',
        name: 'Hijack',
      })
    );
  });

  test('member can update the group', async () => {
    await assertSucceeds(
      asUser('bob').doc(`groups/${GROUP_ID}`).update({ name: 'Renamed' })
    );
  });

  test('member cannot change createdBy on the group (F27 escalation guard)', async () => {
    await assertFails(
      asUser('bob').doc(`groups/${GROUP_ID}`).update({ createdBy: 'bob' })
    );
  });

  test('member can update other fields as long as createdBy is unchanged', async () => {
    await assertSucceeds(
      asUser('bob').doc(`groups/${GROUP_ID}`).update({
        name: 'Renamed Again',
        createdBy: 'alice',
      })
    );
  });

  test('creator can delete the group', async () => {
    await assertSucceeds(asUser('alice').doc(`groups/${GROUP_ID}`).delete());
  });

  test('non-creator member cannot delete the group', async () => {
    await assertFails(asUser('bob').doc(`groups/${GROUP_ID}`).delete());
  });
});

describe('/groups/{groupId}/messages/{msgId}', () => {
  const GROUP_ID = 'group_1';

  beforeEach(async () => {
    await seed(`groups/${GROUP_ID}`, {
      members: ['alice', 'bob'],
      createdBy: 'alice',
    });
    await seed(`groups/${GROUP_ID}/messages/msg_1`, {
      sender: 'alice',
      text: 'hi group',
    });
  });

  test('member can read group messages', async () => {
    await assertSucceeds(
      asUser('bob').doc(`groups/${GROUP_ID}/messages/msg_1`).get()
    );
  });

  test('non-member cannot read group messages', async () => {
    await assertFails(
      asUser('eve').doc(`groups/${GROUP_ID}/messages/msg_1`).get()
    );
  });

  test('member can write a group message', async () => {
    await assertSucceeds(
      asUser('bob').doc(`groups/${GROUP_ID}/messages/msg_2`).set({
        sender: 'bob',
        text: 'reply',
        isEncrypted: true,  // required by rules (F28 fix)
      })
    );
  });
});

describe('/groups/{groupId}/keys/{memberUid}', () => {
  const GROUP_ID = 'group_1';

  beforeEach(async () => {
    await seed(`groups/${GROUP_ID}`, {
      members: ['alice', 'bob'],
      createdBy: 'alice',
    });
    await seed(`groups/${GROUP_ID}/keys/bob`, { encryptedKey: 'enc_key_bob' });
  });

  test('member can read their own key', async () => {
    await assertSucceeds(
      asUser('bob').doc(`groups/${GROUP_ID}/keys/bob`).get()
    );
  });

  test('member cannot read another member key', async () => {
    await assertFails(
      asUser('alice').doc(`groups/${GROUP_ID}/keys/bob`).get()
    );
  });

  test('group creator can write a key for any member (key distribution)', async () => {
    await assertSucceeds(
      asUser('alice').doc(`groups/${GROUP_ID}/keys/bob`).set({
        encryptedKey: 'new_key',
      })
    );
  });

  test('non-creator member cannot write another member key slot (F27)', async () => {
    await assertFails(
      asUser('bob').doc(`groups/${GROUP_ID}/keys/alice`).set({
        encryptedKey: 'substituted_key',
      })
    );
  });

  test('non-creator member cannot write even their own key slot (F27)', async () => {
    await assertFails(
      asUser('bob').doc(`groups/${GROUP_ID}/keys/bob`).set({
        encryptedKey: 'self_write_still_denied',
      })
    );
  });

  test('non-member cannot write a key', async () => {
    await assertFails(
      asUser('eve').doc(`groups/${GROUP_ID}/keys/alice`).set({
        encryptedKey: 'steal',
      })
    );
  });

  test('member cannot escalate to creator by rewriting createdBy then writing a key (F27 two-step bypass)', async () => {
    await assertFails(
      asUser('bob').doc(`groups/${GROUP_ID}`).update({ createdBy: 'bob' })
    );
    await assertFails(
      asUser('bob').doc(`groups/${GROUP_ID}/keys/alice`).set({
        encryptedKey: 'escalated_key',
      })
    );
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// CALLS
// ─────────────────────────────────────────────────────────────────────────────

describe('/calls/{callId}', () => {
  const CALL_ID = 'call_1';
  // The calls create rule (F6 fix) checks that the caller and callee are both
  // participants in an existing chat doc.  Seed that chat so the test passes.
  const CHAT_ID_AB    = 'chat_alice_bob';
  const CHAT_ID_AC    = 'chat_alice_carol';

  beforeEach(async () => {
    await seed(`chats/${CHAT_ID_AB}`, { participants: ['alice', 'bob']   });
    await seed(`chats/${CHAT_ID_AC}`, { participants: ['alice', 'carol'] });
    // Seed the pre-existing call used by read/update tests.
    await seed(`calls/${CALL_ID}`, {
      callerId: 'alice',
      calleeId: 'bob',
      chatId:   CHAT_ID_AB,
      status:   'ringing',
    });
  });

  test('caller can create a call doc', async () => {
    // Rules require: callerId == auth.uid, chatId exists, both parties in chat.
    await assertSucceeds(
      asUser('alice').doc('calls/call_2').set({
        callerId: 'alice',
        calleeId: 'carol',
        chatId:   CHAT_ID_AC,
        status:   'ringing',
      })
    );
  });

  test('cannot create a call with a different callerId', async () => {
    await assertFails(
      asUser('alice').doc('calls/call_3').set({
        callerId: 'bob',
        calleeId: 'carol',
        chatId:   CHAT_ID_AC,
        status:   'ringing',
      })
    );
  });

  test('caller can read call doc', async () => {
    await assertSucceeds(asUser('alice').doc(`calls/${CALL_ID}`).get());
  });

  test('callee can read call doc', async () => {
    await assertSucceeds(asUser('bob').doc(`calls/${CALL_ID}`).get());
  });

  test('outsider cannot read call doc', async () => {
    await assertFails(asUser('eve').doc(`calls/${CALL_ID}`).get());
  });

  test('participant can update call doc (accept/reject)', async () => {
    await assertSucceeds(
      asUser('bob').doc(`calls/${CALL_ID}`).update({ status: 'accepted' })
    );
  });
});

describe('/calls/{callId}/callerCandidates', () => {
  const CALL_ID = 'call_1';

  beforeEach(async () => {
    await seed(`calls/${CALL_ID}`, {
      callerId: 'alice',
      calleeId: 'bob',
      status: 'ringing',
    });
  });

  test('caller can write ICE candidates', async () => {
    await assertSucceeds(
      asUser('alice').doc(`calls/${CALL_ID}/callerCandidates/cand_1`).set({
        candidate: 'candidate:...',
      })
    );
  });

  test('callee can read caller ICE candidates', async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await ctx.firestore().doc(`calls/${CALL_ID}/callerCandidates/cand_1`)
        .set({ candidate: 'candidate:...' });
    });
    await assertSucceeds(
      asUser('bob').doc(`calls/${CALL_ID}/callerCandidates/cand_1`).get()
    );
  });

  test('outsider cannot read or write ICE candidates', async () => {
    await assertFails(
      asUser('eve').doc(`calls/${CALL_ID}/callerCandidates/cand_1`).set({
        candidate: 'candidate:inject',
      })
    );
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// IDENTITIES
// ─────────────────────────────────────────────────────────────────────────────

describe('/identities/{userId}', () => {
  const USER_ID = 'ABCDE-FGHIJ-KLM';

  beforeEach(async () => {
    await seed(`identities/${USER_ID}`, {
      uid: USER_ID,
      identityPubKeyHash: 'hash_abc',
    });
  });

  test('any signed-in user can read an identity (contact lookup)', async () => {
    await assertSucceeds(asUser('bob').doc(`identities/${USER_ID}`).get());
  });

  test('unauthenticated cannot read identity', async () => {
    await assertFails(asAnon().doc(`identities/${USER_ID}`).get());
  });

  test('owner can update their identity without changing its public-key hash', async () => {
    await assertSucceeds(
      asUser(USER_ID).doc(`identities/${USER_ID}`).update({ label: 'legacy' })
    );
  });

  test('cannot write identity with a different uid field (identity hijack)', async () => {
    await assertFails(
      asUser(USER_ID).doc(`identities/${USER_ID}`).set({
        uid: 'bob',
        identityPubKeyHash: 'hash_abc',
      })
    );
  });

  test('cannot overwrite someone else identity even with correct uid field mismatch', async () => {
    await assertFails(
      asUser('eve').doc(`identities/${USER_ID}`).set({
        uid: USER_ID,
        identityPubKeyHash: 'eve_hijack',
      })
    );
  });

  test('cannot overwrite another deterministic identity path (recovery takeover)', async () => {
    await assertFails(
      asUser('eve').doc(`identities/${USER_ID}`).set({
        uid: USER_ID,
        identityPubKeyHash: 'eve_hash',
      })
    );
  });

  test('owner cannot replace the bound public-key hash', async () => {
    await assertFails(
      asUser(USER_ID).doc(`identities/${USER_ID}`).update({
        identityPubKeyHash: 'different_key',
      })
    );
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// RECOVERY
// ─────────────────────────────────────────────────────────────────────────────

describe('/recovery/{uid}', () => {
  beforeEach(async () => {
    await seed('recovery/alice', { blob: 'encrypted_recovery_blob' });
  });

  test('owner can read their recovery blob', async () => {
    await assertSucceeds(asUser('alice').doc('recovery/alice').get());
  });

  test('other user cannot read recovery blob', async () => {
    await assertFails(asUser('bob').doc('recovery/alice').get());
  });

  test('owner can write their recovery blob', async () => {
    await assertSucceeds(
      asUser('alice').doc('recovery/alice').set({ blob: 'updated_blob' })
    );
  });

  test('other user cannot write recovery blob', async () => {
    await assertFails(
      asUser('bob').doc('recovery/alice').set({ blob: 'stolen' })
    );
  });

  test('unauthenticated cannot read or write', async () => {
    await assertFails(asAnon().doc('recovery/alice').get());
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// BACKUPS
// ─────────────────────────────────────────────────────────────────────────────

describe('/backups/{userId}', () => {
  beforeEach(async () => {
    await seed('backups/alice', { lastBackupTs: 1000, count: 5 });
  });

  test('owner can read backup meta', async () => {
    await assertSucceeds(asUser('alice').doc('backups/alice').get());
  });

  test('other user cannot read backup', async () => {
    await assertFails(asUser('bob').doc('backups/alice').get());
  });

  test('owner can create backup meta', async () => {
    await assertSucceeds(
      asUser('carol').doc('backups/carol').set({ lastBackupTs: 2000, count: 0 })
    );
  });

  test('backup meta delete is always denied', async () => {
    await assertFails(asUser('alice').doc('backups/alice').delete());
  });
});

describe('/backups/{userId}/messages/{msgId}', () => {
  beforeEach(async () => {
    await seed('backups/alice', { lastBackupTs: 1000 });
    await seed('backups/alice/messages/msg_1', {
      ciphertext: 'enc_msg',
      isDeleted: false,
    });
  });

  test('owner can read backup messages', async () => {
    await assertSucceeds(
      asUser('alice').doc('backups/alice/messages/msg_1').get()
    );
  });

  test('other user cannot read backup messages', async () => {
    await assertFails(
      asUser('bob').doc('backups/alice/messages/msg_1').get()
    );
  });

  test('owner can create a backup message', async () => {
    await assertSucceeds(
      asUser('alice').doc('backups/alice/messages/msg_2').set({
        ciphertext: 'enc_msg_2',
        isDeleted: false,
      })
    );
  });

  test('backup message delete is always denied (use isDeleted:true)', async () => {
    await assertFails(
      asUser('alice').doc('backups/alice/messages/msg_1').delete()
    );
  });
});

describe('/backups/{userId}/contacts/{contactId}', () => {
  beforeEach(async () => {
    await seed('backups/alice', { lastBackupTs: 1000 });
    await seed('backups/alice/contacts/contact_1', {
      displayName: 'Bob',
      conversationId: 'chat_ab',
    });
  });

  test('owner can read backup contacts', async () => {
    await assertSucceeds(
      asUser('alice').doc('backups/alice/contacts/contact_1').get()
    );
  });

  test('other user cannot read backup contacts', async () => {
    await assertFails(
      asUser('bob').doc('backups/alice/contacts/contact_1').get()
    );
  });

  test('backup contact delete is always denied', async () => {
    await assertFails(
      asUser('alice').doc('backups/alice/contacts/contact_1').delete()
    );
  });
});

describe('/backups/{userId}/groups/{groupId}', () => {
  beforeEach(async () => {
    await seed('backups/alice', { lastBackupTs: 1000 });
    await seed('backups/alice/groups/group_1', { id: 'group_1', name: 'Encrypted group' });
  });

  test('owner can read their group backup', async () => {
    await assertSucceeds(asUser('alice').doc('backups/alice/groups/group_1').get());
  });

  test('other user cannot read a group backup', async () => {
    await assertFails(asUser('bob').doc('backups/alice/groups/group_1').get());
  });

  test('owner can write their group backup', async () => {
    await assertSucceeds(
      asUser('alice').doc('backups/alice/groups/group_2').set({ id: 'group_2', name: 'Encrypted group' })
    );
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// BACKUP LOGS
// ─────────────────────────────────────────────────────────────────────────────

describe('/backup_logs/{logId}', () => {
  test('owner can create a backup log entry', async () => {
    await assertSucceeds(
      asUser('alice').doc('backup_logs/log_1').set({
        uid: 'alice',
        event: 'backup_complete',
        ts: 1000,
      })
    );
  });

  test('cannot create a log entry with a different uid', async () => {
    await assertFails(
      asUser('alice').doc('backup_logs/log_2').set({
        uid: 'bob',
        event: 'backup_complete',
        ts: 1000,
      })
    );
  });

  test('nobody can read backup logs', async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await ctx.firestore().doc('backup_logs/log_seed').set({
        uid: 'alice',
        event: 'backup_complete',
      });
    });
    await assertFails(asUser('alice').doc('backup_logs/log_seed').get());
  });

  test('nobody can delete backup logs', async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await ctx.firestore().doc('backup_logs/log_seed').set({ uid: 'alice' });
    });
    await assertFails(asUser('alice').doc('backup_logs/log_seed').delete());
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// SERVER HEALTH (internal only)
// ─────────────────────────────────────────────────────────────────────────────

describe('/_server_health/{doc}', () => {
  test('no client can read server health docs', async () => {
    await assertFails(asUser('alice').doc('_server_health/status').get());
  });

  test('no client can write server health docs', async () => {
    await assertFails(
      asUser('alice').doc('_server_health/status').set({ ok: true })
    );
  });

  test('unauthenticated cannot read or write either', async () => {
    await assertFails(asAnon().doc('_server_health/status').get());
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// ACCOUNT LOCK  (Issue 1 — one-way latch enforcement)
// ─────────────────────────────────────────────────────────────────────────────

describe('/accountLock/{accountId}', () => {
  test('owner can read their own lock doc', async () => {
    await seed('accountLock/alice', { locked: true });
    await assertSucceeds(asUser('alice').doc('accountLock/alice').get());
  });

  test('stranger cannot read another account\'s lock doc', async () => {
    await seed('accountLock/alice', { locked: true });
    await assertFails(asUser('bob').doc('accountLock/alice').get());
  });

  test('unauthenticated cannot read lock doc', async () => {
    await seed('accountLock/alice', { locked: true });
    await assertFails(asAnon().doc('accountLock/alice').get());
  });

  test('owner can create a lock doc with locked=true', async () => {
    await assertSucceeds(
      asUser('alice').doc('accountLock/alice').set({ locked: true, lockedAt: new Date() })
    );
  });

  test('owner CANNOT create a lock doc with locked=false', async () => {
    await assertFails(
      asUser('alice').doc('accountLock/alice').set({ locked: false })
    );
  });

  test('owner CANNOT create a lock doc without the locked field', async () => {
    await assertFails(
      asUser('alice').doc('accountLock/alice').set({ lockedAt: new Date() })
    );
  });

  test('owner can update an existing lock doc keeping locked=true', async () => {
    await seed('accountLock/alice', { locked: true });
    await assertSucceeds(
      asUser('alice').doc('accountLock/alice').update({ locked: true, lockedAt: new Date() })
    );
  });

  test('owner CANNOT update doc to set locked=false (one-way latch)', async () => {
    await seed('accountLock/alice', { locked: true });
    await assertFails(
      asUser('alice').doc('accountLock/alice').update({ locked: false })
    );
  });

  test('owner CANNOT delete lock doc', async () => {
    await seed('accountLock/alice', { locked: true });
    await assertFails(asUser('alice').doc('accountLock/alice').delete());
  });

  test('stranger CANNOT write another account\'s lock doc', async () => {
    await assertFails(
      asUser('bob').doc('accountLock/alice').set({ locked: true })
    );
  });

  test('unauthenticated CANNOT write lock doc', async () => {
    await assertFails(
      asAnon().doc('accountLock/alice').set({ locked: true })
    );
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// DURESS ELIGIBILITY  (admin-only writes, owner-only read)
// ─────────────────────────────────────────────────────────────────────────────

describe('/duressEligibility/{accountId}', () => {
  beforeEach(async () => {
    await seed('duressEligibility/alice', { eligible: true });
  });

  test('owner can read their own eligibility doc', async () => {
    await assertSucceeds(asUser('alice').doc('duressEligibility/alice').get());
  });

  test('stranger cannot read another account\'s eligibility doc', async () => {
    await assertFails(asUser('bob').doc('duressEligibility/alice').get());
  });

  test('unauthenticated cannot read eligibility doc', async () => {
    await assertFails(asAnon().doc('duressEligibility/alice').get());
  });

  test('owner CANNOT write their own eligibility doc', async () => {
    await assertFails(
      asUser('alice').doc('duressEligibility/alice').set({ eligible: true })
    );
  });

  test('owner CANNOT update their own eligibility doc', async () => {
    await assertFails(
      asUser('alice').doc('duressEligibility/alice').update({ eligible: false })
    );
  });

  test('owner CANNOT delete their eligibility doc', async () => {
    await assertFails(asUser('alice').doc('duressEligibility/alice').delete());
  });

  test('stranger CANNOT write any eligibility doc', async () => {
    await assertFails(
      asUser('bob').doc('duressEligibility/carol').set({ eligible: true })
    );
  });

  test('unauthenticated CANNOT write any eligibility doc', async () => {
    await assertFails(
      asAnon().doc('duressEligibility/alice').set({ eligible: true })
    );
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// WAITLIST  (server-managed: no client reads or writes)
// ─────────────────────────────────────────────────────────────────────────────

describe('/waitlist/{requestId}', () => {
  beforeEach(async () => {
    await seed('waitlist/req_001', { userId: 'alice', approved: false });
  });

  test('authenticated user CANNOT read waitlist docs', async () => {
    await assertFails(asUser('alice').doc('waitlist/req_001').get());
  });

  test('unauthenticated CANNOT read waitlist docs', async () => {
    await assertFails(asAnon().doc('waitlist/req_001').get());
  });

  test('authenticated user CANNOT create waitlist docs', async () => {
    await assertFails(
      asUser('alice').doc('waitlist/req_002').set({ userId: 'alice' })
    );
  });

  test('authenticated user CANNOT update waitlist docs', async () => {
    await assertFails(
      asUser('alice').doc('waitlist/req_001').update({ approved: true })
    );
  });

  test('authenticated user CANNOT delete waitlist docs', async () => {
    await assertFails(asUser('alice').doc('waitlist/req_001').delete());
  });

  test('unauthenticated CANNOT write waitlist docs', async () => {
    await assertFails(
      asAnon().doc('waitlist/req_003').set({ userId: 'anon' })
    );
  });
});
