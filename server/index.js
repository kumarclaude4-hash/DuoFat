const admin = require("firebase-admin");
const http = require("http");
const crypto = require("crypto");

let serviceAccount;
try {
  const raw = process.env.GOOGLE_APPLICATION_CREDENTIALS_JSON || "{}";
  serviceAccount = JSON.parse(raw);
  if (serviceAccount.private_key) {
    serviceAccount.private_key = serviceAccount.private_key.replace(/\\n/g, "\n");
  }
} catch (e) {
  console.error("Failed to parse GOOGLE_APPLICATION_CREDENTIALS_JSON:", e.message);
  process.exit(1);
}

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
});

const db = admin.firestore();
const messaging = admin.messaging();
const FieldValue = admin.firestore.FieldValue;

const PORT = process.env.PORT || 3000;
const STARTED_AT_MS = Date.now();
const MAX_INITIAL_MESSAGE_AGE_MS = Number(process.env.MAX_INITIAL_MESSAGE_AGE_MS || 5 * 60 * 1000);
const stats = {
  delivered: 0,
  groupDelivered: 0,
  skippedMissingToken: 0,
  skippedOld: 0,
  failed: 0,
  startedAt: new Date(STARTED_AT_MS).toISOString(),
};

console.log("DuoShield push server started.");
console.log(`Initial Firestore snapshot will only process messages from the last ${MAX_INITIAL_MESSAGE_AGE_MS}ms.`);

// ── Startup permission check ──────────────────────────────────────────────────
(async () => {
  try {
    await messaging.send(
      { token: "permission-check-dummy-token", notification: { title: "check" } },
      true
    );
    console.log("✅ FCM permission OK");
  } catch (err) {
    if (err.code === "messaging/invalid-argument" || err.errorInfo?.code === "messaging/invalid-argument") {
      console.log("✅ FCM permission OK (invalid token expected for dry-run)");
    } else if (err.message && err.message.includes("cloudmessaging.messages.create")) {
      console.error("❌ FCM PERMISSION MISSING — add 'Firebase Cloud Messaging API Admin' role to the service account in Google Cloud IAM.");
    } else {
      console.warn("⚠️  FCM check inconclusive:", err.message);
    }
  }

  try {
    const testRef = db.collection("_server_health").doc("startup");
    await testRef.set({ ts: FieldValue.serverTimestamp() });
    await testRef.delete();
    console.log("✅ Firestore write permission OK");
  } catch (err) {
    if (err.message && err.message.includes("PERMISSION_DENIED")) {
      console.error("❌ FIRESTORE WRITE PERMISSION MISSING — add 'Cloud Datastore User' role to the service account in Google Cloud IAM.");
    } else {
      console.warn("⚠️  Firestore write check inconclusive:", err.message);
    }
  }
})();

function messageTimeMs(data) {
  const ts = data.timestamp || data.createdAt || data.serverTimestamp;
  if (ts && typeof ts.toMillis === "function") return ts.toMillis();
  if (typeof ts === "number") return ts;
  if (typeof ts === "string") {
    const parsed = Date.parse(ts);
    if (!Number.isNaN(parsed)) return parsed;
  }
  return STARTED_AT_MS;
}

function shouldSkipOldInitialMessage(change, data) {
  if (messageTimeMs(data) < STARTED_AT_MS - MAX_INITIAL_MESSAGE_AGE_MS) {
    stats.skippedOld++;
    return true;
  }
  return false;
}

function notificationBody(data) {
  if (data.type === "image") return "Sent a photo 🖼";
  if (data.type === "video") return "Sent a video 🎬";
  if (data.type === "voice") return "Sent a voice note 🎙";
  if (data.type === "contact") return "Shared a contact card 📇";
  return "New encrypted message";
}

async function removeInvalidToken(uid, token, err) {
  const code = err.code || err.errorInfo?.code || "";
  if (!code.includes("registration-token-not-registered") && !code.includes("invalid-registration-token")) {
    return;
  }
  try {
    const userRef = db.collection("users").doc(uid);
    const snap = await userRef.get();
    if (snap.data()?.fcmToken === token) {
      await userRef.update({ fcmToken: FieldValue.delete() });
      console.warn(`Removed stale FCM token for ${uid}: ${code}`);
    }
  } catch (cleanupErr) {
    console.warn(`Could not remove stale FCM token for ${uid}:`, cleanupErr.message);
  }
}

async function getSenderName(senderUid) {
  if (!senderUid) return "DuoShield";
  try {
    const snap = await db.collection("users").doc(senderUid).get();
    const name = snap.data()?.displayName;
    return (typeof name === "string" && name.trim()) ? name.trim() : "DuoShield";
  } catch {
    return "DuoShield";
  }
}

async function sendPush({ recipientUid, senderUid, chatId, messageId, type, body }) {
  const userDoc = await db.collection("users").doc(recipientUid).get();
  const fcmToken = userDoc.data()?.fcmToken;
  if (typeof fcmToken !== "string" || fcmToken.trim() === "") {
    stats.skippedMissingToken++;
    console.warn(`No FCM token for recipient=${recipientUid}; cannot push messageId=${messageId}`);
    return false;
  }

  const senderName = await getSenderName(senderUid);

  try {
    // Send DATA-ONLY payload — no `notification` block.
    // With a notification block, Android auto-displays a system notification when
    // the app is in the background AND onMessageReceived fires a second one,
    // producing duplicate (or triplicate) notifications. Data-only ensures
    // onMessageReceived always handles display exactly once.
    await messaging.send({
      token: fcmToken,
      data: {
        type,
        chatId:      chatId      || "",
        messageId:   messageId   || "",
        senderUid:   senderUid   || "",
        senderName:  senderName,
        title:       senderName,
        body,
      },
      android: {
        priority: "high",
      },
    });
    return true;
  } catch (err) {
    stats.failed++;
    console.error(`Push failed for messageId=${messageId} recipient=${recipientUid}:`, err.message);
    await removeInvalidToken(recipientUid, fcmToken, err);
    return false;
  }
}

async function markDelivered(ref, messageId) {
  // Belt-and-suspenders ACK sent right after the push dispatch. If the recipient's
  // device already raced ahead and marked the message "read" (chat was open when
  // the push arrived), an unconditional update would stomp that back down to
  // "delivered" and the sender would never see the real-time read tick. Guard the
  // write in a transaction so it can only move status forward, never backward.
  try {
    await db.runTransaction(async (txn) => {
      const snap = await txn.get(ref);
      const currentStatus = snap.exists ? snap.get("status") : null;
      if (currentStatus === "read" || currentStatus === "delivered") return;
      txn.update(ref, {
        status: "delivered",
        deliveredAt: FieldValue.serverTimestamp(),
      });
    });
  } catch (updateErr) {
    console.warn(`Status update failed for ${messageId} (non-fatal):`, updateErr.message);
  }
}

// ── Single collectionGroup("messages") listener — routes by path prefix ───────
// Combining 1-to-1 and group handling into one listener halves the number of
// Firestore read operations and eliminates the race where two listeners both
// attempt to process the same message document.
db.collectionGroup("messages").onSnapshot(
  async (snapshot) => {
    for (const change of snapshot.docChanges()) {
      if (change.type !== "added") continue;

      const msgDoc = change.doc;
      const data   = msgDoc.data();
      const path   = msgDoc.ref.path;
      if (shouldSkipOldInitialMessage(change, data)) continue;

      const senderUid = data.sender;
      if (!senderUid) continue;

      const messageId = msgDoc.id;

      // ── 1-to-1 chat: chats/{chatId}/messages/{msgId} ──────────────────────
      if (path.startsWith("chats/")) {
        const chatId = path.split("/")[1];
        try {
          const chatDoc = await db.collection("chats").doc(chatId).get();
          if (!chatDoc.exists) continue;

          const participants = chatDoc.data().participants;
          if (!Array.isArray(participants) || participants.length < 2) continue;

          const recipientUid = participants.find((uid) => uid !== senderUid);
          if (!recipientUid) continue;

          const sent = await sendPush({
            recipientUid,
            senderUid,
            chatId,
            messageId,
            type: "new_message",
            body: notificationBody(data),
          });

          if (sent) {
            stats.delivered++;
            console.log(`Push sent: chatId=${chatId} messageId=${messageId} recipient=${recipientUid}`);
            await markDelivered(msgDoc.ref, messageId);
          }
        } catch (err) {
          stats.failed++;
          console.error(`1-to-1 push pipeline failed for ${messageId}:`, err.message);
        }
        continue;
      }

      // ── Group chat: groups/{groupId}/messages/{msgId} ─────────────────────
      if (path.startsWith("groups/")) {
        const groupId = path.split("/")[1];
        try {
          const groupDoc = await db.collection("groups").doc(groupId).get();
          if (!groupDoc.exists) continue;

          const members = groupDoc.data().members;
          if (!Array.isArray(members) || members.length === 0) continue;

          const recipients = members.filter((uid) => uid !== senderUid);
          const results = await Promise.all(
            recipients.map((recipientUid) => sendPush({
              recipientUid,
              senderUid,
              chatId: groupId,
              messageId,
              type: "new_group_message",
              body: notificationBody(data),
            }))
          );

          const sentCount = results.filter(Boolean).length;
          if (sentCount > 0) {
            stats.groupDelivered += sentCount;
            console.log(`Group push sent: groupId=${groupId} messageId=${messageId} recipients=${sentCount}`);
            await markDelivered(msgDoc.ref, messageId);
          }
        } catch (err) {
          stats.failed++;
          console.error(`Group push pipeline failed for ${messageId}:`, err.message);
        }
      }
    }
  },
  (err) => console.error("messages listener error:", err)
);

// ── Calls listener: FCM wakeup for incoming calls ────────────────────────────
// Watches calls/{callId} for new docs with status="ringing" and sends a
// high-priority data FCM push to the callee so the app can ring even when killed.
db.collection("calls").onSnapshot(
  async (snapshot) => {
    for (const change of snapshot.docChanges()) {
      if (change.type !== "added") continue;

      const callDoc = change.doc;
      const data    = callDoc.data();
      const callId  = callDoc.id;

      if (data.status !== "ringing") continue;
      if (shouldSkipOldInitialMessage(change, data)) continue;

      // Skip calls that are already older than the callee-side ring timeout (30 s).
      // This prevents a cold-starting Render instance from sending a call_invite FCM
      // for a call the callee can no longer answer (and avoids phantom ringing on the
      // callee's device long after the caller gave up).
      const RING_TIMEOUT_MS = 30_000;
      const callAgeMs = Date.now() - messageTimeMs(data);
      if (callAgeMs > RING_TIMEOUT_MS) {
        console.log(`Skipping stale call (age=${Math.round(callAgeMs / 1000)}s): callId=${callId}`);
        continue;
      }

      const callerId = data.callerId;
      const calleeId = data.calleeId;
      const isVideo  = data.type === "video";

      if (!calleeId || !callerId) continue;

      try {
        const [callerDoc, calleeDoc] = await Promise.all([
          db.collection("users").doc(callerId).get(),
          db.collection("users").doc(calleeId).get(),
        ]);

        const callerName = callerDoc.data()?.displayName || "DuoShield";
        const fcmToken   = calleeDoc.data()?.fcmToken;

        if (typeof fcmToken !== "string" || fcmToken.trim() === "") {
          console.warn(`No FCM token for callee=${calleeId}; cannot ring callId=${callId}`);
          continue;
        }

        await messaging.send({
          token: fcmToken,
          data: {
            type:       "call_invite",
            callId,
            callerId,
            callerName,
            isVideo:    isVideo ? "true" : "false",
          },
          android: { priority: "high" },
        });
        console.log(`Call invite FCM sent: callId=${callId} callee=${calleeId} video=${isVideo}`);
      } catch (err) {
        console.error(`Call invite FCM failed for callId=${callId}:`, err.message);
      }
    }
  },
  (err) => console.error("calls listener error:", err)
);

// ── Per-userId mint cooldown (in-memory rate limit) ───────────────────────────
// Allows at most one token mint per userId per 60 seconds.
const mintCooldown = new Map();

// ── Per-IP rate limit ─────────────────────────────────────────────────────────
// Max 5 /mintToken attempts per IP in any rolling 15-minute window.
// Render (and most reverse proxies) sets X-Forwarded-For; we take the first
// (leftmost) entry which is the original client IP.
const IP_WINDOW_MS  = 15 * 60 * 1000; // 15 minutes
const IP_MAX_HITS   = 5;
const ipHits = new Map(); // ip → { count, windowStart }

// Purge stale IP entries every 30 minutes so the Map doesn't grow forever.
setInterval(() => {
  const cutoff = Date.now() - IP_WINDOW_MS;
  for (const [ip, rec] of ipHits) {
    if (rec.windowStart < cutoff) ipHits.delete(ip);
  }
}, 30 * 60 * 1000);

function getClientIp(req) {
  const forwarded = req.headers["x-forwarded-for"];
  if (forwarded) return forwarded.split(",")[0].trim();
  return req.socket.remoteAddress || "unknown";
}

function checkIpRateLimit(ip) {
  const now = Date.now();
  const rec = ipHits.get(ip);
  if (!rec || now - rec.windowStart >= IP_WINDOW_MS) {
    ipHits.set(ip, { count: 1, windowStart: now });
    return true; // allowed
  }
  if (rec.count >= IP_MAX_HITS) return false; // blocked
  rec.count++;
  return true; // allowed
}

function sha256hex(hexStr) {
  return crypto.createHash("sha256").update(Buffer.from(hexStr, "hex")).digest("hex");
}

// ── Health + status + mintToken HTTP server ───────────────────────────────────
http.createServer((req, res) => {

  // ── POST /mintToken ─────────────────────────────────────────────────────────
  //
  // Body (JSON): { userId, identityPubKeyHex }
  //
  // Security model (F2 fix applied):
  //   • New accounts: identity slot claimed atomically inside a Firestore transaction
  //     before the token is minted.  First caller wins; concurrent first-claim attempts
  //     for the same userId are serialized by the transaction.
  //   • Existing accounts: sha256(identityPubKeyHex) is re-verified inside the same
  //     transaction.  Mismatch → 403.
  //   • Rate limit: one successful mint per userId per 60 s (in-memory).
  //
  if (req.method === "POST" && req.url === "/mintToken") {
    let body = "";
    req.on("data", (chunk) => { body += chunk; });
    req.on("end", async () => {
      try {
        // ── IP rate limit (checked before parsing body) ──────────────────────
        const clientIp = getClientIp(req);
        if (!checkIpRateLimit(clientIp)) {
          console.warn(`mintToken: IP rate limit hit ip=${clientIp}`);
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Too many requests from this IP — wait 15 min and retry");
          return;
        }

        const { userId, identityPubKeyHex } = JSON.parse(body);
        if (!userId || typeof userId !== "string" ||
            !identityPubKeyHex || typeof identityPubKeyHex !== "string") {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid userId / identityPubKeyHex");
          return;
        }

        // ── Per-userId cooldown (prevents rapid re-auth from same account) ───
        const now  = Date.now();
        const last = mintCooldown.get(userId) || 0;
        if (now - last < 60_000) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Too many requests — wait 60 s and retry");
          return;
        }

        const incomingHash = sha256hex(identityPubKeyHex);

        const idRef = db.collection("identities").doc(userId);

        // F2 fix: claim the identities slot atomically before minting the token.
        // We run a Firestore transaction that either:
        //   a) Creates the doc for a new account (first caller wins; second sees it exists
        //      and verifies the hash — preventing concurrent first-claim races), or
        //   b) Verifies the hash for an existing account.
        // Only after the transaction succeeds do we mint the token, so there is no window
        // between "account doesn't exist" and "doc written".
        let isNewAccount = false;
        await db.runTransaction(async (tx) => {
          const snap = await tx.get(idRef);
          if (!snap.exists) {
            // First claim — atomically write the identity binding
            tx.set(idRef, {
              uid:                userId,
              identityPubKeyHash: incomingHash,
              createdAt:          FieldValue.serverTimestamp(),
            });
            isNewAccount = true;
          } else {
            // Existing account — re-verify hash inside the transaction
            const storedHash = snap.data().identityPubKeyHash;
            if (storedHash && storedHash !== incomingHash) {
              throw Object.assign(new Error("Key mismatch"), { status: 403 });
            }
          }
        });

        // Mint custom token — uid = userId (permanent, seed-derived)
        // Token is minted only after the atomic identity-claim succeeds.
        const token = await admin.auth().createCustomToken(userId);

        mintCooldown.set(userId, now);
        console.log(`mintToken: issued token for userId=${userId} newAccount=${isNewAccount}`);

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ token }));
      } catch (e) {
        if (e.status === 403) {
          // F2 fix: key mismatch thrown from inside the Firestore transaction
          console.warn(`mintToken: key mismatch for userId=${JSON.parse(body || "{}").userId}`);
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end("Key mismatch");
        } else {
          console.error("mintToken error:", e.message);
          res.writeHead(500, { "Content-Type": "text/plain" });
          res.end("Internal server error");
        }
      }
    });
    return;
  }

  // ── POST /migrateUid ─────────────────────────────────────────────────────────
  //
  // Body (JSON): { userId, oldUid }
  // Auth: Firebase ID token in Authorization: Bearer <token> header.
  //
  // Called during account restore when a user's old anonymous Firebase UID
  // differs from their permanent seed-derived userId.  Uses Admin SDK
  // (bypasses Firestore client rules) to:
  //   1. Copy users/{oldUid}  → users/{newUid}  (FCM token, display name, etc.)
  //   2. Delete users/{oldUid}
  //   3. Rewrite chat participants: replace oldUid with newUid
  //   4. Rewrite group members:    replace oldUid with newUid
  //
  // Security model:
  //   • Verifies the Firebase ID token (auth.uid must equal userId).
  //   • Confirms identities/{userId} exists and its stored uid matches oldUid.
  //   • Rate-limited: one call per userId per 60 s.
  //
  if (req.method === "POST" && req.url === "/migrateUid") {
    let body = "";
    req.on("data", (chunk) => { body += chunk; });
    req.on("end", async () => {
      try {
        const authHeader = req.headers["authorization"] || "";
        const idToken = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : null;
        if (!idToken) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Missing Authorization header");
          return;
        }

        let decodedToken;
        try {
          decodedToken = await admin.auth().verifyIdToken(idToken);
        } catch (authErr) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Invalid or expired token");
          return;
        }

        const { userId, oldUid } = JSON.parse(body);
        if (!userId || !oldUid || typeof userId !== "string" || typeof oldUid !== "string") {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid userId / oldUid");
          return;
        }

        // Caller's auth UID must equal the userId they claim to own
        if (decodedToken.uid !== userId) {
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end("Token UID does not match userId");
          return;
        }

        if (userId === oldUid) {
          // Nothing to migrate
          res.writeHead(200, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ migrated: false, reason: "same-uid" }));
          return;
        }

        // Verify identities/{userId} exists and its uid == oldUid
        const idDoc = await db.collection("identities").doc(userId).get();
        if (!idDoc.exists) {
          res.writeHead(404, { "Content-Type": "text/plain" });
          res.end("Identity not found");
          return;
        }
        const storedUid = idDoc.data().uid;

        // Case 1: already migrated — idempotent no-op, do NOT process caller-supplied oldUid.
        if (storedUid === userId) {
          res.writeHead(200, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ migrated: false, reason: "already-migrated" }));
          return;
        }

        // Case 2: the stored UID doesn't match the claimed oldUid — reject.
        // This prevents an authenticated user from rewriting another account's data.
        if (storedUid !== oldUid) {
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end("oldUid does not match identity record");
          return;
        }

        // Only reaches here when storedUid === oldUid — legitimate first-time migration.

        const results = { chatsMigrated: 0, groupsMigrated: 0, userDocCopied: false };

        // 1. Copy users/{oldUid} → users/{userId}
        try {
          const oldUserSnap = await db.collection("users").doc(oldUid).get();
          if (oldUserSnap.exists) {
            const data = oldUserSnap.data();
            if (data) {
              await db.collection("users").doc(userId).set(data);
              await db.collection("users").doc(oldUid).delete();
              results.userDocCopied = true;
            }
          }
        } catch (e) {
          console.warn(`migrateUid: users copy failed (non-fatal): ${e.message}`);
        }

        // 2. Rewrite chat participants arrays
        try {
          const chatsSnap = await db.collection("chats")
            .where("participants", "array-contains", oldUid).get();
          for (const chatDoc of chatsSnap.docs) {
            try {
              await chatDoc.ref.update({
                participants: FieldValue.arrayRemove(oldUid),
              });
              await chatDoc.ref.update({
                participants: FieldValue.arrayUnion(userId),
              });
              results.chatsMigrated++;
            } catch (e) {
              console.warn(`migrateUid: chat patch failed for ${chatDoc.id}: ${e.message}`);
            }
          }
        } catch (e) {
          console.warn(`migrateUid: chats query failed (non-fatal): ${e.message}`);
        }

        // 3. Rewrite group members arrays
        try {
          const groupsSnap = await db.collection("groups")
            .where("members", "array-contains", oldUid).get();
          for (const groupDoc of groupsSnap.docs) {
            try {
              await groupDoc.ref.update({
                members: FieldValue.arrayRemove(oldUid),
              });
              await groupDoc.ref.update({
                members: FieldValue.arrayUnion(userId),
              });
              results.groupsMigrated++;
            } catch (e) {
              console.warn(`migrateUid: group patch failed for ${groupDoc.id}: ${e.message}`);
            }
          }
        } catch (e) {
          console.warn(`migrateUid: groups query failed (non-fatal): ${e.message}`);
        }

        console.log(`migrateUid: userId=${userId} oldUid=${oldUid} chats=${results.chatsMigrated} groups=${results.groupsMigrated}`);

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ migrated: true, ...results }));
      } catch (e) {
        console.error("migrateUid error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Internal server error");
      }
    });
    return;
  }

  // ── POST /createChat ──────────────────────────────────────────────────────────
  //
  // Body (JSON): { myUid, partnerUid, myDisplayName, partnerDisplayName }
  // Auth: Firebase ID token in Authorization: Bearer <token> header.
  //
  // Security model:
  //   • Verifies the token with Firebase Admin SDK (auth.uid must equal myUid).
  //   • Verifies both UIDs exist in identities/{uid} (registered DuoShield accounts).
  //   • Uses set({ merge: true }) so both sides can call this independently and the
  //     result is idempotent (both writes converge on the same chatId doc).
  //   • chatId = SHA-256(lex-smaller uid + "/" + lex-larger uid) — same logic as client.
  //   • Admin SDK bypasses Firestore client rules; the client-side create rule is
  //     set to deny, so only this server path can create chat docs (F6 fix).
  //
  if (req.method === "POST" && req.url === "/createChat") {
    let body = "";
    req.on("data", (chunk) => { body += chunk; });
    req.on("end", async () => {
      try {
        // Verify Firebase ID token from Authorization header
        const authHeader = req.headers["authorization"] || "";
        const idToken = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : null;
        if (!idToken) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Missing Authorization header");
          return;
        }

        let decodedToken;
        try {
          decodedToken = await admin.auth().verifyIdToken(idToken);
        } catch (authErr) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Invalid or expired token");
          return;
        }

        const { myUid, partnerUid, myDisplayName, partnerDisplayName } = JSON.parse(body);
        if (!myUid || !partnerUid || typeof myUid !== "string" || typeof partnerUid !== "string") {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid myUid / partnerUid");
          return;
        }

        // Confirm the authenticated user is who they claim to be
        if (decodedToken.uid !== myUid) {
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end("Token UID does not match myUid");
          return;
        }

        // Verify both accounts exist in identities collection
        const [myIdDoc, partnerIdDoc] = await Promise.all([
          db.collection("identities").doc(myUid).get(),
          db.collection("identities").doc(partnerUid).get(),
        ]);
        if (!myIdDoc.exists) {
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end("Caller not registered");
          return;
        }
        if (!partnerIdDoc.exists) {
          res.writeHead(404, { "Content-Type": "text/plain" });
          res.end("Partner not found");
          return;
        }

        // Compute deterministic chatId (matches ContactManager.buildChatId on Android)
        const sorted = [myUid, partnerUid].sort();
        const chatIdInput = sorted[0] + "/" + sorted[1];
        const chatId = require("crypto").createHash("sha256").update(chatIdInput).digest("hex");

        // Write/merge chat doc (idempotent — same as before, but now through server only)
        const chatDocData = {
          participants: [myUid, partnerUid],
        };
        if (myDisplayName)      chatDocData["partnerName_" + partnerUid] = myDisplayName;
        if (partnerDisplayName) chatDocData["partnerName_" + myUid]      = partnerDisplayName;

        await db.collection("chats").doc(chatId).set(chatDocData, { merge: true });
        console.log(`createChat: chatId=${chatId} participants=[${myUid},${partnerUid}]`);

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ chatId }));
      } catch (e) {
        console.error("createChat error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Internal server error");
      }
    });
    return;
  }

  // ── POST /turnCredentials — returns fresh Cloudflare TURN credentials ───────
  //
  // Requires a valid Firebase ID token in the Authorization header.
  // Calls Cloudflare's generate-credentials API server-side so that
  // TURN_TOKEN_ID and TURN_API_TOKEN never leave the server.
  //
  if (req.method === "POST" && req.url === "/turnCredentials") {
    req.on("data", () => {}); // drain body (unused)
    req.on("end", async () => {
      try {
        // ── Auth ────────────────────────────────────────────────────────────
        const authHeader = req.headers["authorization"] || "";
        const idToken = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : null;
        if (!idToken) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Missing Authorization header");
          return;
        }
        try {
          await admin.auth().verifyIdToken(idToken);
        } catch (authErr) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Invalid or expired token");
          return;
        }

        // ── Cloudflare credentials ───────────────────────────────────────────
        const tokenId  = process.env.TURN_TOKEN_ID  || "";
        const apiToken = process.env.TURN_API_TOKEN || "";
        if (!tokenId || !apiToken) {
          console.error("turnCredentials: TURN_TOKEN_ID or TURN_API_TOKEN not set");
          res.writeHead(503, { "Content-Type": "text/plain" });
          res.end("TURN not configured on server");
          return;
        }

        const cfUrl = `https://rtc.live.cloudflare.com/v1/turn/keys/${tokenId}/credentials/generate`;
        const cfRes = await fetch(cfUrl, {
          method: "POST",
          headers: {
            "Authorization": `Bearer ${apiToken}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({ ttl: 86400 }),
        });

        if (!cfRes.ok) {
          const text = await cfRes.text().catch(() => "");
          console.error(`turnCredentials: Cloudflare returned ${cfRes.status}: ${text}`);
          res.writeHead(502, { "Content-Type": "text/plain" });
          res.end("Cloudflare TURN error");
          return;
        }

        const data = await cfRes.json();
        // data.iceServers = { urls: [...], username: "...", credential: "..." }
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(data.iceServers));
      } catch (e) {
        console.error("turnCredentials error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Internal server error");
      }
    });
    return;
  }

  // Strip query string once for all route checks below
  const parsedUrl = (req.url || "/").split("?")[0];

  // ── GET /health — minimal 200 for UptimeRobot / load-balancer probes ────────
  if (parsedUrl === "/health") {
    res.writeHead(200, { "Content-Type": "text/plain" });
    res.end("OK");
    return;
  }

  // ── GET /status — machine-readable JSON stats ────────────────────────────────
  if (parsedUrl === "/status") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({
      status: "running",
      startedAt: stats.startedAt,
      uptime: Math.floor(process.uptime()) + "s",
      delivered: stats.delivered,
      groupDelivered: stats.groupDelivered,
      skippedMissingToken: stats.skippedMissingToken,
      skippedOld: stats.skippedOld,
      failed: stats.failed,
    }));
    return;
  }

  // ── GET / — live HTML dashboard ───────────────────────────────────────────────
  if ((req.method === "GET" || req.method === "HEAD") && (parsedUrl === "/" || parsedUrl === "")) {
    const uptime  = Math.floor(process.uptime());
    const hours   = Math.floor(uptime / 3600);
    const minutes = Math.floor((uptime % 3600) / 60);
    const seconds = uptime % 60;
    const uptimeStr = `${hours}h ${minutes}m ${seconds}s`;
    const total = stats.delivered + stats.groupDelivered;
    const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8"/>
  <meta name="viewport" content="width=device-width,initial-scale=1"/>
  <meta http-equiv="refresh" content="30"/>
  <title>DuoFat Push Server</title>
  <style>
    *{box-sizing:border-box;margin:0;padding:0}
    body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;
         background:#0a0e1a;color:#e2e8f0;min-height:100vh;
         display:flex;flex-direction:column;align-items:center;
         justify-content:center;padding:24px}
    h1{font-size:1.6rem;font-weight:700;color:#00c9e0;margin-bottom:4px}
    .sub{font-size:.85rem;color:#64748b;margin-bottom:36px}
    .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));
          gap:16px;width:100%;max-width:720px}
    .card{background:#0f1620;border:1px solid #1e293b;border-radius:12px;
          padding:20px 24px}
    .card .label{font-size:.75rem;color:#64748b;text-transform:uppercase;
                 letter-spacing:.08em;margin-bottom:6px}
    .card .value{font-size:2rem;font-weight:700;color:#f8fafc}
    .card .value.green{color:#22c55e}
    .card .value.red{color:#ef4444}
    .card .value.cyan{color:#00c9e0}
    .dot{display:inline-block;width:8px;height:8px;border-radius:50%;
         background:#22c55e;margin-right:6px;animation:pulse 2s infinite}
    @keyframes pulse{0%,100%{opacity:1}50%{opacity:.4}}
    .footer{margin-top:28px;font-size:.75rem;color:#334155}
    a{color:#00c9e0;text-decoration:none}
  </style>
</head>
<body>
  <h1><span class="dot"></span>DuoFat Push Server</h1>
  <p class="sub">Started ${stats.startedAt} &nbsp;·&nbsp; Auto-refreshes every 30 s</p>
  <div class="grid">
    <div class="card">
      <div class="label">Uptime</div>
      <div class="value cyan">${uptimeStr}</div>
    </div>
    <div class="card">
      <div class="label">1-to-1 Delivered</div>
      <div class="value green">${stats.delivered}</div>
    </div>
    <div class="card">
      <div class="label">Group Delivered</div>
      <div class="value green">${stats.groupDelivered}</div>
    </div>
    <div class="card">
      <div class="label">Total Sent</div>
      <div class="value">${total}</div>
    </div>
    <div class="card">
      <div class="label">No Token (skipped)</div>
      <div class="value">${stats.skippedMissingToken}</div>
    </div>
    <div class="card">
      <div class="label">Too Old (skipped)</div>
      <div class="value">${stats.skippedOld}</div>
    </div>
    <div class="card">
      <div class="label">Failed</div>
      <div class="value ${stats.failed > 0 ? "red" : ""}">${stats.failed}</div>
    </div>
  </div>
  <p class="footer">
    JSON: <a href="/status">/status</a> &nbsp;·&nbsp;
    Health: <a href="/health">/health</a>
  </p>
</body>
</html>`;
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    res.end(html);
    return;
  }

  // ── /linkPreview — server-side OG fetch (F12: prevents sender IP leakage) ──
  if (req.method === "POST" && req.url === "/linkPreview") {
    let body = "";
    req.on("data", c => (body += c));
    req.on("end", async () => {
      try {
        const tok = (req.headers["authorization"] || "").replace(/^Bearer\s+/, "").trim();
        if (!tok) { res.writeHead(401); res.end("Unauthorized"); return; }
        try { await admin.auth().verifyIdToken(tok); }
        catch { res.writeHead(401); res.end("Invalid token"); return; }

        let targetUrl;
        try { targetUrl = JSON.parse(body).url; }
        catch { res.writeHead(400); res.end("Bad JSON"); return; }
        if (!targetUrl || typeof targetUrl !== "string") {
          res.writeHead(400); res.end("Missing url"); return;
        }
        let parsed;
        try { parsed = new URL(targetUrl); }
        catch { res.writeHead(400); res.end("Invalid URL"); return; }
        if (!["http:", "https:"].includes(parsed.protocol)) {
          res.writeHead(400); res.end("Invalid URL scheme"); return;
        }
        // SSRF guard — block private/loopback addresses
        const host = parsed.hostname.toLowerCase();
        if (/^(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|\[?::1\]?)/.test(host)) {
          res.writeHead(403); res.end("Private addresses forbidden"); return;
        }

        try {
          const ctrl = new AbortController();
          const t = setTimeout(() => ctrl.abort(), 6000);
          const r = await fetch(targetUrl, {
            headers: { "User-Agent": "Mozilla/5.0 (compatible; DuoShield/1.0)" },
            signal: ctrl.signal, redirect: "follow",
          });
          clearTimeout(t);
          const preview = { url: targetUrl, domain: parsed.hostname.replace(/^www\./, "") };
          if (r.ok && (r.headers.get("content-type") || "").includes("text/html")) {
            const html = (await r.text()).slice(0, 30000);
            const ogT = html.match(/<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']{1,200})["']/i)
                     || html.match(/<meta[^>]+content=["']([^"']{1,200})["'][^>]+property=["']og:title["']/i)
                     || html.match(/<title[^>]*>([^<]{1,200})<\/title>/i);
            const ogI = html.match(/<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']{4,500})["']/i)
                     || html.match(/<meta[^>]+content=["']([^"']{4,500})["'][^>]+property=["']og:image["']/i);
            if (ogT) preview.title    = ogT[1].trim().replace(/\s+/g, " ");
            if (ogI) preview.imageUrl = ogI[1].trim();
          }
          res.writeHead(200, { "Content-Type": "application/json" });
          res.end(JSON.stringify(preview));
        } catch (_) {
          res.writeHead(200, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ url: targetUrl, domain: parsed.hostname.replace(/^www\./, "") }));
        }
      } catch (e) { res.writeHead(500); res.end("Server error: " + e.message); }
    });
    return;
  }

  // ── /b2PresignedPut — generate presigned S3 PUT URL (F9) ─────────────────
  if (req.method === "POST" && req.url === "/b2PresignedPut") {
    let body = "";
    req.on("data", c => (body += c));
    req.on("end", async () => {
      try {
        const tok = (req.headers["authorization"] || "").replace(/^Bearer\s+/, "").trim();
        if (!tok) { res.writeHead(401); res.end("Unauthorized"); return; }
        try { await admin.auth().verifyIdToken(tok); }
        catch { res.writeHead(401); res.end("Invalid token"); return; }
        let objectKey, contentType;
        try { ({ objectKey, contentType } = JSON.parse(body)); contentType = contentType || "application/octet-stream"; }
        catch { res.writeHead(400); res.end("Bad JSON"); return; }
        if (!objectKey || typeof objectKey !== "string" || objectKey.includes("..")) {
          res.writeHead(400); res.end("Invalid objectKey"); return;
        }
        const url = b2PresignUrl("PUT", objectKey, contentType, 900);
        if (!url) { res.writeHead(503); res.end("B2 credentials not configured on server"); return; }
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ url, expiresInSeconds: 900, contentType }));
      } catch (e) { res.writeHead(500); res.end("Server error: " + e.message); }
    });
    return;
  }

  // ── /b2PresignedGet — generate presigned S3 GET URL (F9) ─────────────────
  if (req.method === "POST" && req.url === "/b2PresignedGet") {
    let body = "";
    req.on("data", c => (body += c));
    req.on("end", async () => {
      try {
        const tok = (req.headers["authorization"] || "").replace(/^Bearer\s+/, "").trim();
        if (!tok) { res.writeHead(401); res.end("Unauthorized"); return; }
        try { await admin.auth().verifyIdToken(tok); }
        catch { res.writeHead(401); res.end("Invalid token"); return; }
        let objectKey;
        try { ({ objectKey } = JSON.parse(body)); }
        catch { res.writeHead(400); res.end("Bad JSON"); return; }
        if (!objectKey || typeof objectKey !== "string" || objectKey.includes("..")) {
          res.writeHead(400); res.end("Invalid objectKey"); return;
        }
        const url = b2PresignUrl("GET", objectKey, null, 3600);
        if (!url) { res.writeHead(503); res.end("B2 credentials not configured on server"); return; }
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ url, expiresInSeconds: 3600 }));
      } catch (e) { res.writeHead(500); res.end("Server error: " + e.message); }
    });
    return;
  }

  // ── /b2Delete — server-side B2 object delete (F9) ───────────────────────
  if (req.method === "POST" && req.url === "/b2Delete") {
    let body = "";
    req.on("data", c => (body += c));
    req.on("end", async () => {
      try {
        const tok = (req.headers["authorization"] || "").replace(/^Bearer\s+/, "").trim();
        if (!tok) { res.writeHead(401); res.end("Unauthorized"); return; }
        try { await admin.auth().verifyIdToken(tok); }
        catch { res.writeHead(401); res.end("Invalid token"); return; }
        let objectKey;
        try { ({ objectKey } = JSON.parse(body)); }
        catch { res.writeHead(400); res.end("Bad JSON"); return; }
        if (!objectKey || typeof objectKey !== "string" || objectKey.includes("..")) {
          res.writeHead(400); res.end("Invalid objectKey"); return;
        }
        const kId  = process.env.B2_KEY_ID  || "";
        const kApp = process.env.B2_APPLICATION_KEY || "";
        const bkt  = process.env.B2_BUCKET   || "yyush-duoshield";
        const rgn  = process.env.B2_REGION   || "eu-central-003";
        if (!kId || !kApp) { res.writeHead(503); res.end("B2 credentials not configured"); return; }
        const host = "s3." + rgn + ".backblazeb2.com";
        const now  = new Date();
        const ds   = now.toISOString().slice(0,10).replace(/-/g,"");
        const az   = now.toISOString().replace(/[-:]/g,"").replace(/\.\d{3}/,"");
        const eH   = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        const cs   = ds+"/"+rgn+"/s3/aws4_request";
        const cr   = ["DELETE","/"+bkt+"/"+objectKey,"","host:"+host+"\nx-amz-content-sha256:"+eH+"\nx-amz-date:"+az+"\n","host;x-amz-content-sha256;x-amz-date",eH].join("\n");
        const sts  = ["AWS4-HMAC-SHA256",az,cs,crypto.createHash("sha256").update(cr).digest("hex")].join("\n");
        const sk   = b2HmacKey(kApp,ds,rgn);
        const sig  = crypto.createHmac("sha256",sk).update(sts).digest("hex");
        const auth = "AWS4-HMAC-SHA256 Credential="+kId+"/"+cs+", SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature="+sig;
        const dr = await fetch("https://"+host+"/"+bkt+"/"+objectKey, {
          method:"DELETE", headers:{"Authorization":auth,"x-amz-date":az,"x-amz-content-sha256":eH}
        });
        if (dr.ok || dr.status===204 || dr.status===404) {
          res.writeHead(200,{"Content-Type":"application/json"}); res.end(JSON.stringify({deleted:true}));
        } else { res.writeHead(502); res.end("B2 delete failed: HTTP "+dr.status); }
      } catch (e) { res.writeHead(500); res.end("Server error: "+e.message); }
    });
    return;
  }

  // ── /removeGroupMember — admin removes member + revokes key (F3) ─────────
  if (req.method === "POST" && req.url === "/removeGroupMember") {
    let body = "";
    req.on("data", c => (body += c));
    req.on("end", async () => {
      try {
        const tok = (req.headers["authorization"] || "").replace(/^Bearer\s+/, "").trim();
        if (!tok) { res.writeHead(401); res.end("Unauthorized"); return; }
        let callerUid;
        try { callerUid = (await admin.auth().verifyIdToken(tok)).uid; }
        catch { res.writeHead(401); res.end("Invalid token"); return; }
        let groupId, memberUid;
        try { ({ groupId, memberUid } = JSON.parse(body)); }
        catch { res.writeHead(400); res.end("Bad JSON"); return; }
        if (!groupId || !memberUid) { res.writeHead(400); res.end("Missing groupId or memberUid"); return; }

        const db = admin.firestore();
        const gdoc = await db.collection("groups").doc(groupId).get();
        if (!gdoc.exists) { res.writeHead(404); res.end("Group not found"); return; }
        const gd = gdoc.data();
        if (gd.createdBy !== callerUid) { res.writeHead(403); res.end("Only the group creator can remove members"); return; }
        if (callerUid === memberUid) { res.writeHead(400); res.end("Creator cannot remove themselves"); return; }

        const batch = db.batch();
        batch.update(db.collection("groups").doc(groupId),
          { members: admin.firestore.FieldValue.arrayRemove(memberUid) });
        batch.delete(db.collection("groups").doc(groupId).collection("keys").doc(memberUid));
        await batch.commit();

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ removed: true }));
      } catch (e) {
        console.error("/removeGroupMember error:", e.message);
        res.writeHead(500); res.end("Server error: " + e.message);
      }
    });
    return;
  }

  res.writeHead(404);
  res.end("Not found");

}).listen(PORT, () => console.log(`Push server listening on port ${PORT}`));

// ── B2 SigV4 helpers — used by /b2PresignedPut, /b2PresignedGet, /b2Delete ──

function b2HmacKey(appKey, dateStamp, region) {
  const kDate    = crypto.createHmac("sha256", Buffer.from("AWS4" + appKey)).update(dateStamp).digest();
  const kRegion  = crypto.createHmac("sha256", kDate).update(region).digest();
  const kService = crypto.createHmac("sha256", kRegion).update("s3").digest();
  return crypto.createHmac("sha256", kService).update("aws4_request").digest();
}

function b2PresignUrl(method, objectKey, contentType, ttlSeconds) {
  const kId  = process.env.B2_KEY_ID  || "";
  const kApp = process.env.B2_APPLICATION_KEY || "";
  const bkt  = process.env.B2_BUCKET   || "yyush-duoshield";
  const rgn  = process.env.B2_REGION   || "eu-central-003";
  if (!kId || !kApp) return null;

  const host = "s3." + rgn + ".backblazeb2.com";
  const now  = new Date();
  const ds   = now.toISOString().slice(0, 10).replace(/-/g, "");
  // yyyyMMddTHHmmssZ
  const az   = now.toISOString().replace(/[-:]/g, "").replace(/\.\d{3}/, "");
  const cs   = ds + "/" + rgn + "/s3/aws4_request";
  const cred = kId + "/" + cs;
  const sh   = (method === "PUT" && contentType) ? "content-type;host" : "host";

  const qpRaw = [
    ["X-Amz-Algorithm",    "AWS4-HMAC-SHA256"],
    ["X-Amz-Credential",   cred],
    ["X-Amz-Date",         az],
    ["X-Amz-Expires",      String(ttlSeconds)],
    ["X-Amz-SignedHeaders", sh],
  ];
  // Canonical query string must be sorted by key
  const canonQs = qpRaw.slice().sort((a, b) => a[0].localeCompare(b[0]))
    .map(([k, v]) => encodeURIComponent(k) + "=" + encodeURIComponent(v))
    .join("&");

  const ch = (method === "PUT" && contentType)
    ? "content-type:" + contentType + "\nhost:" + host + "\n"
    : "host:" + host + "\n";

  const cr  = [method, "/" + bkt + "/" + objectKey, canonQs, ch, sh, "UNSIGNED-PAYLOAD"].join("\n");
  const sts = ["AWS4-HMAC-SHA256", az, cs,
    crypto.createHash("sha256").update(cr).digest("hex")].join("\n");
  const sk  = b2HmacKey(kApp, ds, rgn);
  const sig = crypto.createHmac("sha256", sk).update(sts).digest("hex");

  return "https://" + host + "/" + bkt + "/" + objectKey
    + "?" + canonQs + "&X-Amz-Signature=" + sig;
}
