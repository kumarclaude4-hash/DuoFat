# DuoShield — Firestore Rules Tests

Unit tests for `firestore.rules` using the Firebase Local Emulator Suite.

## Prerequisites

```bash
npm install -g firebase-tools
firebase setup:emulators:firestore
```

## Run

```bash
# From this directory:
npm install
npm test
```

The tests automatically start the Firestore emulator on port 8080 and tear it
down when finished.  No internet connection or live Firebase project is needed.

## What is covered

| Collection | Tests |
|---|---|
| `users` | read by any user, write by owner only, write denied for non-owner |
| `users/public_keys` | read by any user, create by owner, update by anyone (pre-key consumption), delete by owner only |
| `chats` | create with self in participants, read by participant, denied for outsider, messages follow same rule |
| `groups` | create, read, update by member; delete by creator only; messages by members; keys by member-uid |
| `calls` | create by caller, read/write by participants; ICE candidates by participants only |
| `identities` | read by any signed-in user; write allowed when uid field matches auth UID; denied when uid field is different |
| `recovery` | owner read/write; denied for other users |
| `backups` | owner read/create/update; delete always denied; messages + contacts subcollections same |
| `backup_logs` | owner create; read/update/delete always denied |
| `_server_health` | always denied |
