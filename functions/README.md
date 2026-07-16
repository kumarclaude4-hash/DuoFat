# DuoShield — Firebase Cloud Functions

Three server-side functions that run on Firebase, no server needed.

## Functions

### `notifyOnMessage`
**Trigger:** New document in `conversations/{convId}/messages/{msgId}`

Sends a silent FCM push to the recipient so their phone wakes up and syncs the new message. The push carries no readable content — the encrypted text stays in Firestore and is decrypted on-device.

### `scheduledSelfDestruct`
**Trigger:** Every 60 minutes (Cloud Scheduler)

Sweeps all conversations for messages where `selfDestructAt ≤ now` and batch-deletes them. This is the server-side backup for the Android WorkManager job — it runs even when both phones are offline or uninstalled.

### `cleanupExpiredRooms`
**Trigger:** Every 15 minutes (Cloud Scheduler)

Deletes pairing room documents that expired before anyone joined. Keeps Firestore tidy.

---

## One Android change required

For `notifyOnMessage` to find the recipient, the conversation document needs a `participants` array. Add this one write to `PairingManager.savePrefs()`:

```java
// In PairingManager.savePrefs(), after saving SharedPreferences:
Map<String, Object> convData = new HashMap<>();
convData.put("participants", Arrays.asList(myUid, partnerUid));
db.collection("conversations").document(convId)
  .set(convData, SetOptions.merge());
```

And add `selfDestructAt` when writing messages (in `FirestoreHelper.saveMessage()`):

```java
long ttlMinutes = prefs.getLong("self_destruct_minutes", 60L);
message.put("selfDestructAt", timestamp + ttlMinutes * 60_000L);
```

---

## Deploy

```bash
# Prerequisites: Node 20+, Firebase CLI
npm install -g firebase-tools
firebase login

# From DuoShield/ directory:
cd functions && npm install && npm run build && cd ..
firebase use duoshield-8caf1          # your Firebase project ID
firebase deploy --only functions,firestore
```

## Local testing

```bash
cd functions
npm run build
firebase emulators:start --only functions,firestore
```
