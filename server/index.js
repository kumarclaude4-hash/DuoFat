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

// ── Waitlist request-access rate limit (separate from mintToken's IP bucket) ──
const WAITLIST_IP_WINDOW_MS = 15 * 60 * 1000;
const WAITLIST_IP_MAX_HITS  = 5;
const waitlistIpHits = new Map();

setInterval(() => {
  const cutoff = Date.now() - WAITLIST_IP_WINDOW_MS;
  for (const [ip, rec] of waitlistIpHits) {
    if (rec.windowStart < cutoff) waitlistIpHits.delete(ip);
  }
}, 30 * 60 * 1000);

function checkWaitlistIpRateLimit(ip) {
  const now = Date.now();
  const rec = waitlistIpHits.get(ip);
  if (!rec || now - rec.windowStart >= WAITLIST_IP_WINDOW_MS) {
    waitlistIpHits.set(ip, { count: 1, windowStart: now });
    return true;
  }
  if (rec.count >= WAITLIST_IP_MAX_HITS) return false;
  rec.count++;
  return true;
}

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

// ── Per-UID authenticated-endpoint rate limiter ───────────────────────────────
// Prevents an authenticated user from flooding B2 presign/delete or other
// server-mediated endpoints.  Each endpoint has its own per-minute bucket.
const AUTH_RATE_WINDOW_MS = 60_000;
const AUTH_RATE_LIMITS = {
  b2PresignedPut:  30,   // 30 PUT presigns / min per user
  b2PresignedGet:  60,   // 60 GET presigns / min per user
  b2Delete:        10,   // 10 deletes / min per user
  createChat:      10,   // 10 chat creations / min per user
  migrateUid:       2,   //  2 migrations / min per user
  turnCredentials: 20,   // 20 TURN fetches / min per user
  removeGroupMember: 20, // 20 removals / min per user
  linkPreview:     30,   // 30 link previews / min per user
};
const authRateLimits = new Map(); // uid → { counts: {ep: n}, windowStart }

function checkAuthRateLimit(uid, endpoint) {
  const now   = Date.now();
  const limit = AUTH_RATE_LIMITS[endpoint] || 30;
  const rec   = authRateLimits.get(uid);
  if (!rec || now - rec.windowStart >= AUTH_RATE_WINDOW_MS) {
    authRateLimits.set(uid, { counts: { [endpoint]: 1 }, windowStart: now });
    return true;
  }
  const cur = rec.counts[endpoint] || 0;
  if (cur >= limit) return false;
  rec.counts[endpoint] = cur + 1;
  return true;
}

// Purge stale auth rate-limit entries every 5 minutes.
setInterval(() => {
  const cutoff = Date.now() - AUTH_RATE_WINDOW_MS;
  for (const [uid, rec] of authRateLimits) {
    if (rec.windowStart < cutoff) authRateLimits.delete(uid);
  }
}, 5 * 60 * 1000);

// ── Admin panel auth ──────────────────────────────────────────────────────────
// Gates /admin/api/* (waitlist approval, account-lock unfreeze). A single
// operator-held token (ADMIN_TOKEN env var), never shipped in the APK. The
// static /admin page itself carries no data — only the API calls it makes
// need the token — so serving the HTML shell without auth leaks nothing.
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || "";
const ADMIN_IP_WINDOW_MS = 15 * 60 * 1000; // 15 minutes
const ADMIN_IP_MAX_FAILS = 10;
const adminIpFails = new Map(); // ip → { count, windowStart }

setInterval(() => {
  const cutoff = Date.now() - ADMIN_IP_WINDOW_MS;
  for (const [ip, rec] of adminIpFails) {
    if (rec.windowStart < cutoff) adminIpFails.delete(ip);
  }
}, 30 * 60 * 1000);

function adminIpLocked(ip) {
  const rec = adminIpFails.get(ip);
  if (!rec) return false;
  if (Date.now() - rec.windowStart >= ADMIN_IP_WINDOW_MS) return false;
  return rec.count >= ADMIN_IP_MAX_FAILS;
}

function recordAdminAuthFailure(ip) {
  const now = Date.now();
  const rec = adminIpFails.get(ip);
  if (!rec || now - rec.windowStart >= ADMIN_IP_WINDOW_MS) {
    adminIpFails.set(ip, { count: 1, windowStart: now });
  } else {
    rec.count++;
  }
}

// Constant-time comparison so token-guessing can't be timed byte-by-byte.
function safeTokenEqual(a, b) {
  const bufA = Buffer.from(String(a));
  const bufB = Buffer.from(String(b));
  if (bufA.length !== bufB.length) return false;
  return crypto.timingSafeEqual(bufA, bufB);
}

// Returns true and lets the caller proceed, or writes a 401/429/503 response
// and returns false. Every admin/api route must call this first.
function requireAdminAuth(req, res) {
  const ip = getClientIp(req);
  if (adminIpLocked(ip)) {
    res.writeHead(429, { "Content-Type": "text/plain" });
    res.end("Too many failed attempts — wait 15 min and retry");
    return false;
  }
  if (!ADMIN_TOKEN) {
    console.error("admin auth: ADMIN_TOKEN is not configured on the server");
    res.writeHead(503, { "Content-Type": "text/plain" });
    res.end("Admin panel not configured");
    return false;
  }
  const supplied = req.headers["x-admin-token"] || "";
  if (!supplied || !safeTokenEqual(supplied, ADMIN_TOKEN)) {
    recordAdminAuthFailure(ip);
    res.writeHead(401, { "Content-Type": "text/plain" });
    res.end("Invalid admin token");
    return false;
  }
  return true;
}

// ── Global unhandled-rejection / exception guards ─────────────────────────────
// Prevents a single async exception from crashing the process.
process.on("unhandledRejection", (reason) => {
  console.error("Unhandled promise rejection:", reason instanceof Error ? reason.message : reason);
});
process.on("uncaughtException", (err) => {
  console.error("Uncaught exception:", err.message, "\n", err.stack);
});

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

// ── SSRF guard helpers for /linkPreview ───────────────────────────────────────
// Block private/loopback addresses and cloud metadata endpoints. Applied both
// to the initial user-supplied URL and to every redirect hop (see
// fetchFollowingSafeRedirects below) — checking only the first URL would let
// a malicious server redirect the fetch to an internal address afterwards.
function isBlockedPreviewHost(hostname) {
  const host = hostname.toLowerCase();
  return /^(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|\[?::1\]?)/.test(host) ||
    host === "metadata.google.internal" ||
    host === "169.254.169.254" ||
    host.endsWith(".internal") ||
    host.endsWith(".local");
}

// Fetches targetUrl, manually validating and following redirects (instead of
// `redirect: "follow"`) so each hop is re-checked against isBlockedPreviewHost
// before it is fetched. Throws on a blocked/invalid hop or too many redirects.
async function fetchFollowingSafeRedirects(targetUrl, { headers, timeoutMs, maxRedirects = 5 }) {
  let current = targetUrl;
  for (let hop = 0; hop <= maxRedirects; hop++) {
    const parsed = new URL(current);
    if (!["http:", "https:"].includes(parsed.protocol) || isBlockedPreviewHost(parsed.hostname)) {
      throw new Error(`Blocked redirect target: ${parsed.hostname}`);
    }
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), timeoutMs);
    let response;
    try {
      response = await fetch(current, { headers, signal: ctrl.signal, redirect: "manual" });
    } finally {
      clearTimeout(t);
    }
    if ([301, 302, 303, 307, 308].includes(response.status)) {
      const location = response.headers.get("location");
      if (!location) throw new Error("Redirect with no Location header");
      current = new URL(location, current).toString();
      continue;
    }
    return { response, finalUrl: current };
  }
  throw new Error("Too many redirects");
}

// ── Request body size limit ───────────────────────────────────────────────────
// Prevents DoS via oversized request bodies. 64 KB is plenty for all valid
// JSON payloads; media goes to B2 directly via presigned URLs, never here.
const MAX_BODY_BYTES = 64 * 1024; // 64 KB

function readBody(req, res) {
  return new Promise((resolve, reject) => {
    let body = "";
    let bytes = 0;
    req.on("data", (chunk) => {
      bytes += chunk.length;
      if (bytes > MAX_BODY_BYTES) {
        res.writeHead(413, { "Content-Type": "text/plain" });
        res.end("Request body too large");
        req.destroy();
        reject(new Error("body_too_large"));
        return;
      }
      body += chunk;
    });
    req.on("end", () => resolve(body));
    req.on("error", reject);
  });
}

// Callback-style counterpart to readBody(), for handlers written in the
// on("data")/on("end") style (several routes below need to run
// requireAdminAuth or other checks before body parsing, so they never
// migrated to the Promise-based helper above). Enforces the same
// MAX_BODY_BYTES cap: the naive `body += chunk` pattern this replaces has
// no size limit of its own — it only inherited protection from the
// declared Content-Length pre-check up in the request handler, which a
// chunked-encoding request (no Content-Length header) bypasses entirely.
// Calls onComplete(body) only when the body stayed within the limit;
// otherwise the 413 response is already sent and onComplete is not called.
function collectBody(req, res, onComplete) {
  let body = "";
  let tooLarge = false;
  req.on("data", (chunk) => {
    if (tooLarge) return;
    body += chunk;
    if (body.length > MAX_BODY_BYTES) {
      tooLarge = true;
      res.writeHead(413, { "Content-Type": "text/plain" });
      res.end("Request body too large");
      req.destroy();
    }
  });
  req.on("end", () => {
    if (tooLarge) return;
    onComplete(body);
  });
}

// ── Admin panel HTML shell ─────────────────────────────────────────────────────
// Self-contained page (no build step, no external assets) served at GET /admin.
// Prompts for the operator token once, keeps it in memory only (never
// persisted to localStorage/cookies), and sends it as `x-admin-token` on
// every fetch to /admin/api/*. All rendered values go through textContent,
// never innerHTML, so nothing from Firestore can execute as markup.
const ADMIN_PAGE_HTML = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>DuoShield Admin</title>
<meta name="robots" content="noindex, nofollow">
<style>
  :root { color-scheme: dark; }
  body { font-family: -apple-system, system-ui, sans-serif; background: #0b0f14; color: #e6edf3; margin: 0; padding: 24px; }
  h1 { font-size: 18px; margin: 0 0 4px; }
  .sub { color: #8b98a5; font-size: 13px; margin-bottom: 20px; }
  .gate { max-width: 360px; margin: 80px auto; text-align: center; }
  .gate input { width: 100%; box-sizing: border-box; padding: 10px 12px; font-size: 14px; border-radius: 6px; border: 1px solid #30363d; background: #161b22; color: #e6edf3; margin-top: 12px; }
  .gate button { width: 100%; margin-top: 12px; padding: 10px; border-radius: 6px; border: none; background: #2f81f7; color: white; font-size: 14px; cursor: pointer; }
  #app { display: none; }
  section { margin-bottom: 32px; }
  table { width: 100%; border-collapse: collapse; font-size: 13px; }
  th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #21262d; }
  th { color: #8b98a5; font-weight: 500; }
  button.action { padding: 5px 12px; border-radius: 5px; border: 1px solid #30363d; background: #21262d; color: #e6edf3; cursor: pointer; font-size: 12px; }
  button.action:hover { background: #30363d; }
  button.danger { border-color: #f85149; color: #f85149; }
  .empty { color: #8b98a5; font-size: 13px; padding: 12px 0; }
  .err { color: #f85149; font-size: 13px; margin-top: 8px; }
  .toast { position: fixed; bottom: 20px; right: 20px; background: #161b22; border: 1px solid #30363d; padding: 10px 16px; border-radius: 6px; font-size: 13px; }
  .mono { font-family: ui-monospace, SFMono-Regular, monospace; font-size: 12px; }
  .refresh { float: right; }
</style>
</head>
<body>

  <div class="gate" id="gate">
    <h1>DuoShield Admin</h1>
    <div class="sub">Enter the operator token to continue</div>
    <input type="password" id="tokenInput" placeholder="Admin token" autofocus>
    <button id="unlockBtn">Unlock</button>
    <div class="err" id="gateErr"></div>
  </div>

  <div id="app">
    <h1>DuoShield Admin</h1>
    <div class="sub">Waitlist approval &amp; account unfreeze</div>

    <section>
      <h2>Pending waitlist requests <button class="action refresh" onclick="loadWaitlist()">Refresh</button></h2>
      <table>
        <thead><tr><th>Request ID</th><th>Requested</th><th></th></tr></thead>
        <tbody id="waitlistBody"></tbody>
      </table>
      <div class="empty" id="waitlistEmpty" style="display:none">No pending requests.</div>
    </section>

    <section>
      <h2>Locked accounts <button class="action refresh" onclick="loadLocked()">Refresh</button></h2>
      <table>
        <thead><tr><th>UID</th><th>Locked at</th><th></th></tr></thead>
        <tbody id="lockedBody"></tbody>
      </table>
      <div class="empty" id="lockedEmpty" style="display:none">No locked accounts.</div>
    </section>

    <section>
      <h2>Duress PIN enrollment <button class="action refresh" onclick="loadDuressEnrolled()">Refresh</button></h2>
      <div style="display:flex;gap:8px;margin-bottom:12px;align-items:center;">
        <input id="duressUidInput" type="text" placeholder="Search by account UID" style="flex:1;padding:8px 10px;font-size:13px;border-radius:5px;border:1px solid #30363d;background:#161b22;color:#e6edf3;font-family:ui-monospace,monospace;" onkeydown="if(event.key==='Enter')searchDuressAccount();">
        <button class="action" onclick="searchDuressAccount()">Search</button>
      </div>
      <div id="duressSearchResult" style="display:none;margin-bottom:16px;padding:12px 14px;border:1px solid #30363d;border-radius:6px;background:#161b22;">
        <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;">
          <div>
            <div class="mono" id="duressSearchUid" style="font-size:13px;"></div>
            <div class="sub" id="duressSearchStatus" style="margin:4px 0 0;"></div>
          </div>
          <button class="action" id="duressSearchAction"></button>
        </div>
      </div>
      <div class="empty" id="duressSearchEmpty" style="display:none">No account found for that UID.</div>
      <table>
        <thead><tr><th>UID</th><th>Enrolled at</th><th></th></tr></thead>
        <tbody id="duressBody"></tbody>
      </table>
      <div class="empty" id="duressEmpty" style="display:none">No accounts enrolled.</div>
    </section>

    <section>
      <h2>Audit log <button class="action refresh" onclick="loadAuditLog()">Refresh</button></h2>
      <table>
        <thead><tr><th>Action</th><th>Target</th><th>Admin IP</th><th>When</th></tr></thead>
        <tbody id="auditBody"></tbody>
      </table>
      <div class="empty" id="auditEmpty" style="display:none">No audit entries yet.</div>
    </section>

    <div id="inactivityBanner" style="display:none;position:fixed;top:0;left:0;right:0;background:#f85149;color:#fff;text-align:center;padding:10px 16px;font-size:13px;z-index:999;">
      Session will expire due to inactivity — <span id="inactivityCountdown">60</span>s remaining.
    </div>
  </div>

<script>
let TOKEN = "";

function unlock() {
  const input = document.getElementById("tokenInput");
  const btn   = document.getElementById("unlockBtn");
  // Try .value first; fall back to defaultValue for browsers where autofill
  // populates the visual but not the live .value until the user interacts.
  const t = (input.value || input.defaultValue || "").trim();
  const errEl = document.getElementById("gateErr");
  if (!t) {
    errEl.textContent = "Tap the token field first, then press Unlock.";
    input.focus();
    return;
  }
  errEl.textContent = "";
  btn.disabled = true;
  btn.textContent = "Unlocking…";
  TOKEN = t;
  // Show the app immediately — if the token is wrong every panel's 401
  // handler will push the user back to the gate automatically.
  showApp();
  btn.disabled = false;
  btn.textContent = "Unlock";
  loadWaitlist();
  loadLocked();
  loadDuressEnrolled();
  loadAuditLog();
}

function toast(msg) {
  const el = document.createElement("div");
  el.className = "toast";
  el.textContent = msg;
  document.body.appendChild(el);
  setTimeout(() => el.remove(), 3000);
}

async function api(path, opts) {
  const res = await fetch(path, Object.assign({}, opts, {
    headers: Object.assign({ "x-admin-token": TOKEN, "Content-Type": "application/json" }, (opts && opts.headers) || {}),
  }));
  if (res.status === 401) {
    document.getElementById("app").style.display = "none";
    document.getElementById("gate").style.display = "block";
    document.getElementById("gateErr").textContent = "Invalid token.";
    throw new Error("unauthorized");
  }
  if (!res.ok) throw new Error(await res.text());
  const ct = res.headers.get("content-type") || "";
  return ct.includes("application/json") ? res.json() : null;
}

function showApp() {
  document.getElementById("gate").style.display = "none";
  document.getElementById("app").style.display = "block";
  resetInactivityTimer();
}

async function loadWaitlist() {
  try {
    const data = await api("/admin/api/waitlist");
    showApp();
    const body = document.getElementById("waitlistBody");
    body.innerHTML = "";
    document.getElementById("waitlistEmpty").style.display = data.requests.length ? "none" : "block";
    for (const r of data.requests) {
      const tr = document.createElement("tr");

      const idTd = document.createElement("td");
      idTd.className = "mono";
      idTd.textContent = r.requestId;
      tr.appendChild(idTd);

      const dateTd = document.createElement("td");
      dateTd.textContent = r.createdAt ? new Date(r.createdAt).toLocaleString() : "—";
      tr.appendChild(dateTd);

      const actionTd = document.createElement("td");
      const btn = document.createElement("button");
      btn.className = "action";
      btn.textContent = "Approve";
      btn.onclick = () => approve(r.requestId, btn);
      actionTd.appendChild(btn);
      tr.appendChild(actionTd);

      body.appendChild(tr);
    }
  } catch (e) {
    if (e.message !== "unauthorized") toast("Failed to load waitlist: " + e.message);
  }
}

async function approve(requestId, btn) {
  btn.disabled = true;
  btn.textContent = "Approving…";
  try {
    await api("/admin/api/waitlist/approve", { method: "POST", body: JSON.stringify({ requestId }) });
    toast("Approved " + requestId.slice(0, 8) + "…");
    loadWaitlist();
    loadAuditLog();
  } catch (e) {
    if (e.message !== "unauthorized") { toast("Approve failed: " + e.message); btn.disabled = false; btn.textContent = "Approve"; }
  }
}

async function loadLocked() {
  try {
    const data = await api("/admin/api/locked");
    showApp();
    const body = document.getElementById("lockedBody");
    body.innerHTML = "";
    document.getElementById("lockedEmpty").style.display = data.accounts.length ? "none" : "block";
    for (const a of data.accounts) {
      const tr = document.createElement("tr");

      const idTd = document.createElement("td");
      idTd.className = "mono";
      idTd.textContent = a.uid;
      tr.appendChild(idTd);

      const dateTd = document.createElement("td");
      dateTd.textContent = a.lockedAt ? new Date(a.lockedAt).toLocaleString() : "—";
      tr.appendChild(dateTd);

      const actionTd = document.createElement("td");
      const btn = document.createElement("button");
      btn.className = "action danger";
      btn.textContent = "Unfreeze";
      btn.onclick = () => unfreeze(a.uid, btn);
      actionTd.appendChild(btn);
      tr.appendChild(actionTd);

      body.appendChild(tr);
    }
  } catch (e) {
    if (e.message !== "unauthorized") toast("Failed to load locked accounts: " + e.message);
  }
}

async function unfreeze(uid, btn) {
  if (!confirm("Unfreeze account " + uid + "? This lets the app sign in again.")) return;
  btn.disabled = true;
  btn.textContent = "Unfreezing…";
  try {
    await api("/admin/api/locked/unfreeze", { method: "POST", body: JSON.stringify({ uid }) });
    toast("Unfroze " + uid);
    loadLocked();
    loadAuditLog();
  } catch (e) {
    if (e.message !== "unauthorized") { toast("Unfreeze failed: " + e.message); btn.disabled = false; btn.textContent = "Unfreeze"; }
  }
}

async function loadDuressEnrolled() {
  try {
    const data = await api("/admin/api/duress/enrolled");
    const body = document.getElementById("duressBody");
    body.innerHTML = "";
    document.getElementById("duressEmpty").style.display = data.accounts.length ? "none" : "block";
    for (const a of data.accounts) {
      const tr = document.createElement("tr");

      const idTd = document.createElement("td");
      idTd.className = "mono";
      idTd.textContent = a.uid;
      tr.appendChild(idTd);

      const dateTd = document.createElement("td");
      dateTd.textContent = a.enrolledAt ? new Date(a.enrolledAt).toLocaleString() : "—";
      tr.appendChild(dateTd);

      const actionTd = document.createElement("td");
      const btn = document.createElement("button");
      btn.className = "action danger";
      btn.textContent = "Revoke";
      btn.onclick = () => revokeDuress(a.uid, btn);
      actionTd.appendChild(btn);
      tr.appendChild(actionTd);

      body.appendChild(tr);
    }
  } catch (e) {
    if (e.message !== "unauthorized") toast("Failed to load duress enrolled: " + e.message);
  }
}

let duressSearchUid = "";

async function searchDuressAccount() {
  const input = document.getElementById("duressUidInput");
  const uid = input.value.trim();
  if (!uid) { toast("Enter a UID first"); return; }
  const resultBox = document.getElementById("duressSearchResult");
  const emptyBox  = document.getElementById("duressSearchEmpty");
  resultBox.style.display = "none";
  emptyBox.style.display  = "none";
  try {
    const data = await api("/admin/api/account/lookup?uid=" + encodeURIComponent(uid));
    if (!data.accountExists) {
      emptyBox.style.display = "block";
      return;
    }
    duressSearchUid = uid;
    document.getElementById("duressSearchUid").textContent = uid;
    document.getElementById("duressSearchStatus").textContent =
      data.duressEligible ? "Duress PIN: enabled" : "Duress PIN: not enabled";
    const btn = document.getElementById("duressSearchAction");
    btn.className = data.duressEligible ? "action danger" : "action";
    btn.textContent = data.duressEligible ? "Disable" : "Enable";
    btn.onclick = data.duressEligible
      ? () => revokeDuress(uid, btn, true)
      : () => enrollDuress(uid, btn);
    resultBox.style.display = "block";
  } catch (e) {
    if (e.message !== "unauthorized") toast("Search failed: " + e.message);
  }
}

async function enrollDuress(uid, btn) {
  if (!uid) { toast("Enter a UID first"); return; }
  if (btn) { btn.disabled = true; btn.textContent = "Enabling…"; }
  try {
    await api("/admin/api/duress/enroll", { method: "POST", body: JSON.stringify({ uid }) });
    toast("Enabled duress PIN for " + uid);
    document.getElementById("duressUidInput").value = "";
    document.getElementById("duressSearchResult").style.display = "none";
    loadDuressEnrolled();
    loadAuditLog();
  } catch (e) {
    if (e.message !== "unauthorized") toast("Enable failed: " + e.message);
    if (btn) { btn.disabled = false; btn.textContent = "Enable"; }
  }
}

async function revokeDuress(uid, btn, fromSearch) {
  if (!confirm("Revoke duress PIN eligibility for " + uid + "?\nThey will lose access to the secondary-PIN feature.")) return;
  const resetLabel = fromSearch ? "Disable" : "Revoke";
  btn.disabled = true;
  btn.textContent = "Revoking…";
  try {
    await api("/admin/api/duress/revoke", { method: "POST", body: JSON.stringify({ uid }) });
    toast("Revoked " + uid);
    if (fromSearch) document.getElementById("duressSearchResult").style.display = "none";
    loadDuressEnrolled();
    loadAuditLog();
  } catch (e) {
    if (e.message !== "unauthorized") { toast("Revoke failed: " + e.message); btn.disabled = false; btn.textContent = resetLabel; }
  }
}

async function loadAuditLog() {
  try {
    const data = await api("/admin/api/auditlog");
    const body = document.getElementById("auditBody");
    body.innerHTML = "";
    document.getElementById("auditEmpty").style.display = data.entries.length ? "none" : "block";
    for (const e of data.entries) {
      const tr = document.createElement("tr");

      const actionTd = document.createElement("td");
      actionTd.textContent = e.action === "waitlist_approved" ? "✅ Waitlist approved"
                           : e.action === "account_unfrozen"  ? "🔓 Account unfrozen"
                           : e.action === "duress_enrolled"   ? "🔐 Duress enrolled"
                           : e.action === "duress_revoked"    ? "❌ Duress revoked"
                           : e.action;
      tr.appendChild(actionTd);

      const targetTd = document.createElement("td");
      targetTd.className = "mono";
      targetTd.textContent = e.requestId ? e.requestId.slice(0, 12) + "…" : (e.uid || "—");
      tr.appendChild(targetTd);

      const ipTd = document.createElement("td");
      ipTd.className = "mono";
      ipTd.textContent = e.adminIp || "—";
      tr.appendChild(ipTd);

      const dateTd = document.createElement("td");
      dateTd.textContent = e.at ? new Date(e.at).toLocaleString() : "—";
      tr.appendChild(dateTd);

      body.appendChild(tr);
    }
  } catch (e) {
    if (e.message !== "unauthorized") toast("Failed to load audit log: " + e.message);
  }
}

// ── Inactivity auto-logout (10 minutes) ──────────────────────────────────────
// Starts counting down once the session is unlocked. Any mouse, keyboard, or
// touch event resets the timer. A 60-second warning banner appears before logout.
const INACTIVITY_TIMEOUT_MS  = 10 * 60 * 1000; // 10 min
const INACTIVITY_WARNING_MS  = 60 * 1000;       // warn 60 s before
let inactivityTimer  = null;
let countdownTimer   = null;
let countdownSeconds = 60;

function resetInactivityTimer() {
  clearTimeout(inactivityTimer);
  clearInterval(countdownTimer);
  document.getElementById("inactivityBanner").style.display = "none";
  inactivityTimer = setTimeout(startInactivityWarning, INACTIVITY_TIMEOUT_MS - INACTIVITY_WARNING_MS);
}

function startInactivityWarning() {
  countdownSeconds = 60;
  const banner = document.getElementById("inactivityBanner");
  banner.style.display = "block";
  document.getElementById("inactivityCountdown").textContent = countdownSeconds;
  countdownTimer = setInterval(() => {
    countdownSeconds--;
    document.getElementById("inactivityCountdown").textContent = countdownSeconds;
    if (countdownSeconds <= 0) {
      clearInterval(countdownTimer);
      forceLogout();
    }
  }, 1000);
}

function forceLogout() {
  TOKEN = "";
  clearTimeout(inactivityTimer);
  clearInterval(countdownTimer);
  document.getElementById("inactivityBanner").style.display = "none";
  document.getElementById("app").style.display = "none";
  document.getElementById("gate").style.display = "block";
  document.getElementById("gateErr").textContent = "Session expired due to inactivity.";
  document.getElementById("tokenInput").value = "";
}

["mousemove", "mousedown", "keydown", "touchstart", "scroll"].forEach((evt) => {
  document.addEventListener(evt, () => {
    if (TOKEN) resetInactivityTimer();
  }, { passive: true });
});

document.getElementById("unlockBtn").addEventListener("click", unlock);
document.getElementById("tokenInput").addEventListener("keydown", (e) => {
  if (e.key === "Enter") unlock();
});
</script>
</body>
</html>
`;

// ── Health + status + mintToken HTTP server ───────────────────────────────────
http.createServer((req, res) => {

  // Reject oversized bodies before any routing (DoS guard).
  // Content-Length may be absent (chunked), so also enforce via readBody().
  const declaredLength = parseInt(req.headers["content-length"] || "0", 10);
  if (declaredLength > MAX_BODY_BYTES) {
    res.writeHead(413, { "Content-Type": "text/plain" });
    res.end("Request body too large");
    return;
  }

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
    collectBody(req, res, async (body) => {
      try {
        // ── IP rate limit (checked before parsing body) ──────────────────────
        const clientIp = getClientIp(req);
        if (!checkIpRateLimit(clientIp)) {
          console.warn(`mintToken: IP rate limit hit ip=${clientIp}`);
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Too many requests from this IP — wait 15 min and retry");
          return;
        }

        const { userId, identityPubKeyHex, waitlistRequestId } = JSON.parse(body);
        if (!userId || typeof userId !== "string" ||
            !identityPubKeyHex || typeof identityPubKeyHex !== "string") {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid userId / identityPubKeyHex");
          return;
        }

        // ── Per-userId cooldown (prevents rapid re-auth from same account) ───
        // Set the cooldown timestamp synchronously, before the first `await`
        // below, not after the token is minted. Setting it post-mint left a
        // window where two concurrent requests for the same userId could both
        // read the old timestamp and both pass the check before either write
        // landed, bypassing the 60s limit entirely.
        const now  = Date.now();
        const last = mintCooldown.get(userId) || 0;
        if (now - last < 60_000) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Too many requests — wait 60 s and retry");
          return;
        }
        mintCooldown.set(userId, now);

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
            // New account — invite-only. Require an approved, not-yet-used
            // waitlist request and consume it atomically alongside the
            // identity claim so a token can never mint two accounts.
            if (!waitlistRequestId || typeof waitlistRequestId !== "string" ||
                !/^[0-9a-f]{32}$/.test(waitlistRequestId)) {
              throw Object.assign(new Error("Access request required"), { status: 403 });
            }
            const waitlistRef = db.collection("waitlist").doc(waitlistRequestId);
            const waitlistSnap = await tx.get(waitlistRef);
            if (!waitlistSnap.exists || waitlistSnap.data().status !== "approved") {
              throw Object.assign(new Error("Access request not approved"), { status: 403 });
            }

            tx.update(waitlistRef, {
              status:       "used",
              usedByUserId: userId,
              usedAt:       FieldValue.serverTimestamp(),
            });

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

        console.log(`mintToken: issued token for userId=${userId} newAccount=${isNewAccount}`);

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ token }));
      } catch (e) {
        if (e.status === 403) {
          // Thrown from inside the Firestore transaction: either a key mismatch
          // (F2 fix) or a missing/unapproved waitlist request for a new account.
          console.warn(`mintToken: 403 (${e.message}) for userId=${JSON.parse(body || "{}").userId}`);
          res.writeHead(403, { "Content-Type": "text/plain" });
          res.end(e.message);
        } else {
          console.error("mintToken error:", e.message);
          res.writeHead(500, { "Content-Type": "text/plain" });
          res.end("Internal server error");
        }
      }
    });
    return;
  }

  // ── POST /requestAccess ──────────────────────────────────────────────────────
  //
  // Body: none required.
  //
  // Account creation is invite-only. A fresh install that wants a NEW account
  // calls this first to get a request token, which sits in Firestore as
  // "pending" until the operator manually approves it (Firebase console /
  // admin script — never from the app). The client polls GET /waitlistStatus
  // with the token and only proceeds to actual account creation once approved.
  // Restoring an EXISTING account never touches this endpoint.
  if (req.method === "POST" && req.url === "/requestAccess") {
    (async () => {
      try {
        const clientIp = getClientIp(req);
        if (!checkWaitlistIpRateLimit(clientIp)) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Too many requests from this IP — wait 15 min and retry");
          return;
        }

        // Drain the (empty) body so the connection closes cleanly.
        await readBody(req, res).catch(() => "");

        const requestId = crypto.randomBytes(16).toString("hex");
        await db.collection("waitlist").doc(requestId).set({
          status:    "pending",
          createdAt: FieldValue.serverTimestamp(),
        });

        console.log(`requestAccess: new waitlist entry requestId=${requestId}`);
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ requestId }));
      } catch (e) {
        console.error("requestAccess error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Internal server error");
      }
    })();
    return;
  }

  // ── GET /waitlistStatus?requestId=... ────────────────────────────────────────
  //
  // Returns { status: "pending" | "approved" | "used" | "not_found" }.
  // No auth required (the requestId itself is an unguessable 128-bit token,
  // and it reveals nothing beyond one account's own pending/approved state).
  if (req.method === "GET" && (req.url || "").split("?")[0] === "/waitlistStatus") {
    (async () => {
      try {
        const clientIp = getClientIp(req);
        if (!checkWaitlistIpRateLimit(clientIp)) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Too many requests from this IP — wait 15 min and retry");
          return;
        }

        const requestUrl = new URL(req.url, "http://localhost");
        const requestId = requestUrl.searchParams.get("requestId") || "";
        if (!/^[0-9a-f]{32}$/.test(requestId)) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid requestId");
          return;
        }

        const snap = await db.collection("waitlist").doc(requestId).get();
        const status = snap.exists ? snap.data().status : "not_found";

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ status }));
      } catch (e) {
        console.error("waitlistStatus error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Internal server error");
      }
    })();
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
  //   2. Copy backups/{oldUid} and its direct subcollections → new UID
  //   3. Rewrite chat participants: replace oldUid with newUid
  //   4. Rewrite group members:    replace oldUid with newUid
  //   5. Mark identities/{userId} as migrated only after all required work succeeds
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

        if (!checkAuthRateLimit(decodedToken.uid, "migrateUid")) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Rate limit exceeded — slow down and retry");
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

        const results = {
          chatsMigrated: 0,
          groupsMigrated: 0,
          userDocCopied: false,
          backupDocsCopied: 0,
        };

        // 1. Copy users/{oldUid} → users/{userId}. The old document is not
        // deleted: a failed later step must leave the migration retryable without
        // destroying the legacy account's visible profile.
        const oldUserSnap = await db.collection("users").doc(oldUid).get();
        if (oldUserSnap.exists) {
          const data = oldUserSnap.data();
          if (data) {
            await db.collection("users").doc(userId).set(data);
            results.userDocCopied = true;
          }
        }

        // 2. Copy all backup content. Restore reads under the deterministic UID, so
        // missing this step makes an otherwise valid recovery phrase appear empty.
        const oldBackupRef = db.collection("backups").doc(oldUid);
        const newBackupRef = db.collection("backups").doc(userId);
        const oldBackupSnap = await oldBackupRef.get();
        if (oldBackupSnap.exists) {
          await newBackupRef.set(oldBackupSnap.data(), { merge: true });
          results.backupDocsCopied++;
        }
        for (const subcollection of ["messages", "contacts", "groups"]) {
          const snap = await oldBackupRef.collection(subcollection).get();
          for (const doc of snap.docs) {
            await newBackupRef.collection(subcollection).doc(doc.id).set(doc.data());
            results.backupDocsCopied++;
          }
        }

        // 3. Rewrite chat participants arrays.
        const chatsSnap = await db.collection("chats")
          .where("participants", "array-contains", oldUid).get();
        for (const chatDoc of chatsSnap.docs) {
          await chatDoc.ref.update({
            participants: FieldValue.arrayRemove(oldUid),
          });
          await chatDoc.ref.update({
            participants: FieldValue.arrayUnion(userId),
          });
          results.chatsMigrated++;
        }

        // 4. Rewrite group members arrays.
        const groupsSnap = await db.collection("groups")
          .where("members", "array-contains", oldUid).get();
        for (const groupDoc of groupsSnap.docs) {
          await groupDoc.ref.update({
            members: FieldValue.arrayRemove(oldUid),
          });
          await groupDoc.ref.update({
            members: FieldValue.arrayUnion(userId),
          });
          results.groupsMigrated++;
        }

        // The identity UID is the migration completion marker. It is intentionally
        // written last so a retry remains authorized after any failed copy/patch.
        await idDoc.ref.update({
          uid: userId,
          migratedAt: FieldValue.serverTimestamp(),
        });

        // Clean up only after the completion marker is written. Backups are copied
        // rather than moved so an interrupted cleanup can never hide history from
        // a restore retry.
        if (oldUserSnap.exists) {
          await db.collection("users").doc(oldUid).delete();
        }

        console.log(
          `migrateUid: userId=${userId} oldUid=${oldUid} chats=${results.chatsMigrated} `
          + `groups=${results.groupsMigrated} backupDocs=${results.backupDocsCopied}`
        );

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

        if (!checkAuthRateLimit(decodedToken.uid, "createChat")) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Rate limit exceeded — slow down and retry");
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
        let turnUid;
        try {
          turnUid = (await admin.auth().verifyIdToken(idToken)).uid;
        } catch (authErr) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Invalid or expired token");
          return;
        }

        if (!checkAuthRateLimit(turnUid, "turnCredentials")) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Rate limit exceeded — slow down and retry");
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
    collectBody(req, res, async (body) => {
      try {
        const tok = (req.headers["authorization"] || "").replace(/^Bearer\s+/, "").trim();
        if (!tok) { res.writeHead(401); res.end("Unauthorized"); return; }
        let lpUid;
        try { lpUid = (await admin.auth().verifyIdToken(tok)).uid; }
        catch { res.writeHead(401); res.end("Invalid token"); return; }
        if (!checkAuthRateLimit(lpUid, "linkPreview")) {
          res.writeHead(429); res.end("Rate limit exceeded — retry in 60 s"); return;
        }

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
        if (isBlockedPreviewHost(parsed.hostname)) {
          res.writeHead(403); res.end("Forbidden address"); return;
        }

        try {
          // SSRF guard, continued: `redirect: "follow"` would let a
          // malicious server 302 the fetch to an internal address
          // (127.0.0.1, a cloud metadata IP, etc.) after the initial host
          // already passed the check above — Node's fetch does not re-run
          // caller validation on redirect hops. Follow redirects manually
          // instead, so every hop's host is checked before it's fetched.
          const { response: r, finalUrl } = await fetchFollowingSafeRedirects(targetUrl, {
            headers: { "User-Agent": "Mozilla/5.0 (compatible; DuoShield/1.0)" },
            timeoutMs: 6000,
          });
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
        } catch (fetchErr) {
          console.warn("/linkPreview fetch failed:", fetchErr.message);
          res.writeHead(200, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ url: targetUrl, domain: parsed.hostname.replace(/^www\./, "") }));
        }
      } catch (e) {
        console.error("/linkPreview error:", e.message);
        res.writeHead(500); res.end("Internal server error");
      }
    });
    return;
  }

  // ── /removeGroupMember — admin removes member + revokes key (F3) ─────────
  if (req.method === "POST" && req.url === "/removeGroupMember") {
    collectBody(req, res, async (body) => {
      try {
        const tok = (req.headers["authorization"] || "").replace(/^Bearer\s+/, "").trim();
        if (!tok) { res.writeHead(401); res.end("Unauthorized"); return; }
        let callerUid;
        try { callerUid = (await admin.auth().verifyIdToken(tok)).uid; }
        catch { res.writeHead(401); res.end("Invalid token"); return; }
        if (!checkAuthRateLimit(callerUid, "removeGroupMember")) {
          res.writeHead(429); res.end("Rate limit exceeded — retry in 60 s"); return;
        }
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
        res.writeHead(500); res.end("Internal server error");
      }
    });
    return;
  }

  // ── /requestLockNonce ─────────────────────────────────────────────────────
  //
  // Issues a single-use, uid-bound, 24-hour nonce for AccountLockWorker to
  // consume later via /duress-lock. Called by DuressManager on the background
  // thread before sign-out — while the Firebase session is still live — so
  // the nonce is obtained with a proper per-user verifiable credential (ID
  // token) rather than a static APK-embedded secret.
  //
  // Storing a nonce (random 32-byte hex string) in WorkManager's input data
  // is safe: unlike a Firebase ID token, a nonce has no intrinsic auth power.
  // It is bound server-side to the uid that requested it, so it cannot be used
  // to lock any other account. It is single-use — consumed and deleted on the
  // first successful /duress-lock call — so a leaked nonce cannot replay.
  //
  if (req.method === "POST" && req.url === "/requestLockNonce") {
    req.on("data", () => {}); // body unused
    req.on("end", async () => {
      try {
        const authHeader = req.headers["authorization"] || "";
        const idToken = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : null;
        if (!idToken) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Missing Authorization header");
          return;
        }

        let uid;
        try {
          uid = (await admin.auth().verifyIdToken(idToken)).uid;
        } catch (_) {
          res.writeHead(401, { "Content-Type": "text/plain" });
          res.end("Invalid or expired token");
          return;
        }

        if (!checkAuthRateLimit(uid, "requestLockNonce")) {
          res.writeHead(429, { "Content-Type": "text/plain" });
          res.end("Rate limit exceeded");
          return;
        }

        // Generate a 32-byte random nonce and store it in Firestore with a 24-hour
        // expiry and the authenticated uid. Using Admin SDK so Firestore rules never
        // block these writes (the collection is deny-all for clients).
        const nonce = crypto.randomBytes(32).toString("hex");
        const expiresAt = new Date(Date.now() + 24 * 60 * 60 * 1000);
        await db.collection("_duressNonces").doc(nonce).set({ uid, expiresAt });

        console.log(`[requestLockNonce] nonce issued for uid=${uid}`);
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ nonce }));
      } catch (e) {
        console.error("[requestLockNonce] error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Internal server error");
      }
    });
    return;
  }

  // ── /duress-lock ──────────────────────────────────────────────────────────
  //
  // Writes accountLock/{uid}.locked = true via the Admin SDK. Called by
  // AccountLockWorker when the synchronous in-app lock write failed (offline
  // at trigger time) and connectivity has since been restored.
  //
  // Auth: single-use nonce issued by /requestLockNonce while the user was
  // still signed in. The nonce is bound to a specific uid server-side, so it
  // cannot be used to lock any other account. A static APK-embedded secret
  // (WORKER_SECRET) is explicitly NOT used here — it would let anyone who
  // reverse-engineered the APK lock arbitrary accounts.
  //
  if (req.method === "POST" && req.url === "/duress-lock") {
    collectBody(req, res, async (body) => {
      try {
        let parsed;
        try { parsed = JSON.parse(body); } catch (_) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Invalid JSON");
          return;
        }

        const { nonce } = parsed;
        if (typeof nonce !== "string" || nonce.length !== 64) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid nonce");
          return;
        }

        // Look up + consume the nonce and write the lock atomically in a single
        // transaction. This used to be a get() followed by a separate batch()
        // write, leaving a window where two concurrent requests carrying the
        // same nonce could both read it as valid before either deleted it.
        // Admin SDK bypasses Firestore rules either way.
        let uid;
        try {
          uid = await db.runTransaction(async (tx) => {
            const nonceRef  = db.collection("_duressNonces").doc(nonce);
            const nonceSnap = await tx.get(nonceRef);
            if (!nonceSnap.exists) {
              // Unknown nonce: already consumed, never issued, or corrupted.
              throw Object.assign(new Error("Invalid or already-consumed nonce"), { status: 403 });
            }
            const { uid: nonceUid, expiresAt } = nonceSnap.data();
            if (!nonceUid || new Date() > new Date(expiresAt.toDate ? expiresAt.toDate() : expiresAt)) {
              // Expired — delete to clean up and signal the client not to retry.
              tx.delete(nonceRef);
              throw Object.assign(new Error("Nonce expired"), { status: 401 });
            }
            tx.set(
              db.collection("accountLock").doc(nonceUid),
              { locked: true, lockedAt: admin.firestore.FieldValue.serverTimestamp() },
              { merge: true }
            );
            tx.delete(nonceRef); // single-use: consumed
            return nonceUid;
          });
        } catch (txErr) {
          if (txErr.status) {
            res.writeHead(txErr.status, { "Content-Type": "text/plain" });
            res.end(txErr.message);
            return;
          }
          throw txErr; // unexpected Firestore error — fall through to outer catch
        }

        console.log(`[duress-lock] accountLock written for uid=${uid}`);
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ locked: true }));
      } catch (e) {
        console.error("[duress-lock] error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Internal server error");
      }
    });
    return;
  }

  // ── GET /admin ────────────────────────────────────────────────────────────
  //
  // Static HTML/JS shell for the operator admin panel — no server data is
  // embedded in the page itself, only the fetch calls it makes to
  // /admin/api/* carry the token, so serving this without auth is safe.
  if (req.method === "GET" && req.url === "/admin") {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    res.end(ADMIN_PAGE_HTML);
    return;
  }

  // ── GET /admin/api/waitlist ───────────────────────────────────────────────
  //
  // Auth: x-admin-token header. Returns pending waitlist requests, newest
  // first, so the operator can see who's asking for access.
  if (req.method === "GET" && req.url === "/admin/api/waitlist") {
    (async () => {
      if (!requireAdminAuth(req, res)) return;
      try {
        const snap = await db.collection("waitlist")
          .where("status", "==", "pending")
          .orderBy("createdAt", "desc")
          .limit(200)
          .get();
        const requests = snap.docs.map((d) => {
          const data = d.data();
          const createdAt = data.createdAt && data.createdAt.toDate ? data.createdAt.toDate().toISOString() : null;
          return { requestId: d.id, createdAt };
        });
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ requests }));
      } catch (e) {
        console.error("admin/api/waitlist error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Server error: " + e.message);
      }
    })();
    return;
  }

  // ── POST /admin/api/waitlist/approve ──────────────────────────────────────
  //
  // Body: { requestId }. Auth: x-admin-token header.
  // Flips a pending waitlist doc to status: "approved" so the requester's
  // next /waitlistStatus poll lets them proceed to account creation.
  if (req.method === "POST" && req.url === "/admin/api/waitlist/approve") {
    let body = "";
    req.on("data", (chunk) => { body += chunk; });
    req.on("end", async () => {
      if (!requireAdminAuth(req, res)) return;
      try {
        let parsed;
        try { parsed = JSON.parse(body); } catch (_) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Invalid JSON");
          return;
        }
        const { requestId } = parsed;
        if (typeof requestId !== "string" || !/^[0-9a-f]{32}$/.test(requestId)) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid requestId");
          return;
        }
        const ref = db.collection("waitlist").doc(requestId);
        const snap = await ref.get();
        if (!snap.exists) {
          res.writeHead(404, { "Content-Type": "text/plain" });
          res.end("Request not found");
          return;
        }
        if (snap.data().status !== "pending") {
          res.writeHead(409, { "Content-Type": "text/plain" });
          res.end(`Request is already "${snap.data().status}", not pending`);
          return;
        }
        await ref.update({ status: "approved", approvedAt: FieldValue.serverTimestamp() });
        console.log(`[admin] waitlist request approved: requestId=${requestId}`);

        // Audit log — non-fatal; never block the response on this write
        db.collection("adminAuditLog").add({
          action:    "waitlist_approved",
          requestId,
          adminIp:   getClientIp(req),
          at:        FieldValue.serverTimestamp(),
        }).catch((auditErr) => console.warn("[admin] audit log write failed:", auditErr.message));

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true }));
      } catch (e) {
        console.error("admin/api/waitlist/approve error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Server error: " + e.message);
      }
    });
    return;
  }

  // ── GET /admin/api/locked ─────────────────────────────────────────────────
  //
  // Auth: x-admin-token header. Returns currently-locked accounts so the
  // operator can see who's frozen and pick one to unfreeze.
  if (req.method === "GET" && req.url === "/admin/api/locked") {
    (async () => {
      if (!requireAdminAuth(req, res)) return;
      try {
        const snap = await db.collection("accountLock")
          .where("locked", "==", true)
          .get();
        const accounts = snap.docs.map((d) => {
          const data = d.data();
          const lockedAt = data.lockedAt && data.lockedAt.toDate ? data.lockedAt.toDate().toISOString() : null;
          return { uid: d.id, lockedAt };
        });
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ accounts }));
      } catch (e) {
        console.error("admin/api/locked error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Server error: " + e.message);
      }
    })();
    return;
  }

  // ── POST /admin/api/locked/unfreeze ───────────────────────────────────────
  //
  // Body: { uid }. Auth: x-admin-token header.
  // Deletes the accountLock/{uid} doc — the only way this doc can ever be
  // removed, per firestore.rules (clients get `allow delete: if false`).
  if (req.method === "POST" && req.url === "/admin/api/locked/unfreeze") {
    let body = "";
    req.on("data", (chunk) => { body += chunk; });
    req.on("end", async () => {
      if (!requireAdminAuth(req, res)) return;
      try {
        let parsed;
        try { parsed = JSON.parse(body); } catch (_) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Invalid JSON");
          return;
        }
        const { uid } = parsed;
        if (typeof uid !== "string" || uid.length < 1 || uid.length > 128) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid uid");
          return;
        }
        const ref = db.collection("accountLock").doc(uid);
        const snap = await ref.get();
        if (!snap.exists) {
          res.writeHead(404, { "Content-Type": "text/plain" });
          res.end("No lock found for this uid");
          return;
        }
        await ref.delete();
        console.log(`[admin] account unfrozen: uid=${uid}`);

        // Audit log — non-fatal; never block the response on this write
        db.collection("adminAuditLog").add({
          action:  "account_unfrozen",
          uid,
          adminIp: getClientIp(req),
          at:      FieldValue.serverTimestamp(),
        }).catch((auditErr) => console.warn("[admin] audit log write failed:", auditErr.message));

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true }));
      } catch (e) {
        console.error("admin/api/locked/unfreeze error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Server error: " + e.message);
      }
    });
    return;
  }

  // ── GET /admin/api/duress/enrolled ───────────────────────────────────────
  //
  // Auth: x-admin-token header. Returns all accounts currently enrolled for
  // duress-PIN eligibility (duressEligibility/{uid}.eligible == true).
  if (req.method === "GET" && req.url === "/admin/api/duress/enrolled") {
    (async () => {
      if (!requireAdminAuth(req, res)) return;
      try {
        const snap = await db.collection("duressEligibility")
          .where("eligible", "==", true)
          .get();
        const accounts = snap.docs.map((d) => {
          const data = d.data();
          const enrolledAt = data.enrolledAt && data.enrolledAt.toDate
            ? data.enrolledAt.toDate().toISOString() : null;
          return { uid: d.id, enrolledAt };
        });
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ accounts }));
      } catch (e) {
        console.error("admin/api/duress/enrolled error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Server error: " + e.message);
      }
    })();
    return;
  }

  // ── GET /admin/api/account/lookup?uid=... ────────────────────────────────
  //
  // Auth: x-admin-token header. Looks up whether an account with this UID
  // actually exists (identities/{uid}) and its current duress-PIN eligibility
  // status. Used by the admin panel's "search by UID" step before enabling —
  // enrollment must never be granted blind to a UID that isn't a real account.
  if (req.method === "GET" && req.url.startsWith("/admin/api/account/lookup")) {
    (async () => {
      if (!requireAdminAuth(req, res)) return;
      try {
        const requestUrl = new URL(req.url, "http://localhost");
        const uid = requestUrl.searchParams.get("uid") || "";
        if (!uid || uid.length > 128) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid uid");
          return;
        }
        const [identitySnap, eligibilitySnap] = await Promise.all([
          db.collection("identities").doc(uid).get(),
          db.collection("duressEligibility").doc(uid).get(),
        ]);
        const accountExists  = identitySnap.exists;
        const duressEligible = accountExists && eligibilitySnap.exists && eligibilitySnap.data().eligible === true;
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ uid, accountExists, duressEligible }));
      } catch (e) {
        console.error("admin/api/account/lookup error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Server error: " + e.message);
      }
    })();
    return;
  }

  // ── POST /admin/api/duress/enroll ─────────────────────────────────────────
  //
  // Body: { uid }. Auth: x-admin-token header.
  // Creates or updates duressEligibility/{uid} with eligible:true so the app
  // shows the secondary-PIN setup UI for that account on next eligibility check.
  // Requires the UID to correspond to a real account (identities/{uid}) —
  // enrollment is never granted blind to an unverified/nonexistent UID.
  if (req.method === "POST" && req.url === "/admin/api/duress/enroll") {
    let body = "";
    req.on("data", (chunk) => { body += chunk; });
    req.on("end", async () => {
      if (!requireAdminAuth(req, res)) return;
      try {
        let parsed;
        try { parsed = JSON.parse(body); } catch (_) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Invalid JSON");
          return;
        }
        const { uid } = parsed;
        if (typeof uid !== "string" || uid.length < 1 || uid.length > 128) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid uid");
          return;
        }
        const identitySnap = await db.collection("identities").doc(uid).get();
        if (!identitySnap.exists) {
          res.writeHead(404, { "Content-Type": "text/plain" });
          res.end("No account found for this uid");
          return;
        }
        await db.collection("duressEligibility").doc(uid).set({
          eligible:   true,
          enrolledAt: FieldValue.serverTimestamp(),
        }, { merge: true });
        console.log(`[admin] duress enrollment granted: uid=${uid}`);

        db.collection("adminAuditLog").add({
          action:  "duress_enrolled",
          uid,
          adminIp: getClientIp(req),
          at:      FieldValue.serverTimestamp(),
        }).catch((auditErr) => console.warn("[admin] audit log write failed:", auditErr.message));

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true }));
      } catch (e) {
        console.error("admin/api/duress/enroll error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Server error: " + e.message);
      }
    });
    return;
  }

  // ── POST /admin/api/duress/revoke ─────────────────────────────────────────
  //
  // Body: { uid }. Auth: x-admin-token header.
  // Sets eligible:false on duressEligibility/{uid} — the client's cached flag
  // is updated on the next eligibility refresh (sign-in or foreground).
  if (req.method === "POST" && req.url === "/admin/api/duress/revoke") {
    let body = "";
    req.on("data", (chunk) => { body += chunk; });
    req.on("end", async () => {
      if (!requireAdminAuth(req, res)) return;
      try {
        let parsed;
        try { parsed = JSON.parse(body); } catch (_) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Invalid JSON");
          return;
        }
        const { uid } = parsed;
        if (typeof uid !== "string" || uid.length < 1 || uid.length > 128) {
          res.writeHead(400, { "Content-Type": "text/plain" });
          res.end("Missing or invalid uid");
          return;
        }
        const ref = db.collection("duressEligibility").doc(uid);
        const snap = await ref.get();
        if (!snap.exists) {
          res.writeHead(404, { "Content-Type": "text/plain" });
          res.end("No eligibility record found for this uid");
          return;
        }
        await ref.update({ eligible: false, revokedAt: FieldValue.serverTimestamp() });
        console.log(`[admin] duress enrollment revoked: uid=${uid}`);

        db.collection("adminAuditLog").add({
          action:  "duress_revoked",
          uid,
          adminIp: getClientIp(req),
          at:      FieldValue.serverTimestamp(),
        }).catch((auditErr) => console.warn("[admin] audit log write failed:", auditErr.message));

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true }));
      } catch (e) {
        console.error("admin/api/duress/revoke error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Server error: " + e.message);
      }
    });
    return;
  }

  // ── GET /admin/api/auditlog ───────────────────────────────────────────────
  //
  // Auth: x-admin-token header. Returns the 100 most-recent admin actions
  // (waitlist approvals + account unfreezes) so the operator has a tamper-
  // evident record of who did what and when.
  if (req.method === "GET" && req.url === "/admin/api/auditlog") {
    (async () => {
      if (!requireAdminAuth(req, res)) return;
      try {
        const snap = await db.collection("adminAuditLog")
          .orderBy("at", "desc")
          .limit(100)
          .get();
        const entries = snap.docs.map((d) => {
          const data = d.data();
          const at = data.at && data.at.toDate ? data.at.toDate().toISOString() : null;
          return { id: d.id, action: data.action, requestId: data.requestId || null, uid: data.uid || null, adminIp: data.adminIp || null, at };
        });
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ entries }));
      } catch (e) {
        console.error("admin/api/auditlog error:", e.message);
        res.writeHead(500, { "Content-Type": "text/plain" });
        res.end("Server error: " + e.message);
      }
    })();
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
