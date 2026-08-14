"use strict";
const crypto = require("node:crypto");
const { createAdminSessionStore } = require("./lib/adminSessionStore");
const pure = require("./lib/pure");

const ADMIN_TOKEN = "a-very-strong-admin-token-9f8e7d6c5b4a3210deadbeef";

// mirror index.js secret derivation
const ADMIN_SESSION_SECRET = Buffer.from(
  crypto.hkdfSync("sha256", Buffer.from(ADMIN_TOKEN, "utf8"), Buffer.alloc(0), "duoshield-admin-session-v1", 32)
);

const store = createAdminSessionStore({
  idleTtlMs: 30 * 60 * 1000,
  absoluteTtlMs: 8 * 60 * 60 * 1000,
  secret: ADMIN_SESSION_SECRET,
});

// A long Android in-app webview UA (>200 chars is common)
const UA_LOGIN = "Mozilla/5.0 (Linux; Android 14; 23021RAA2Y Build/UKQ1.230917.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.6099.230 Mobile Safari/537.36 Instagram 300.0.0.0.0 Android (34/14; 440dpi; 1080x2210; Xiaomi; 23021RAA2Y; sunstone; mt6789; en_US; 500000000)";

// index.js createAdminSession: create({ userAgent: req.headers["user-agent"] || "" })
const token = store.create({ userAgent: UA_LOGIN });
console.log("[repro] token len", token.length, "uaLen", UA_LOGIN.length);

// Simulate cookie round trip through adminSessionCookie encode + getCookie decode
const cookieHeader = `duoshield_admin_session=${encodeURIComponent(token)}; Path=/admin`;
const roundTripped = pure.getCookie(cookieHeader, "duoshield_admin_session");
console.log("[repro] cookie roundtrip identical:", roundTripped === token);

// GET /admin render check: validate refresh:false
const r1 = store.validate(roundTripped, { userAgent: UA_LOGIN }, { refresh: false });
console.log("[repro] GET /admin (refresh:false):", JSON.stringify(r1));

// first /admin/api/* call: validate refresh:true (default)
const r2 = store.validate(roundTripped, { userAgent: UA_LOGIN });
console.log("[repro] /admin/api/* (refresh:true):", JSON.stringify(r2));

// Now simulate the SECOND-INSTANCE / RESTART scenario: fresh store, same secret, no local record
const store2 = createAdminSessionStore({
  idleTtlMs: 30 * 60 * 1000,
  absoluteTtlMs: 8 * 60 * 60 * 1000,
  secret: ADMIN_SESSION_SECRET,
});
const r3 = store2.validate(roundTripped, { userAgent: UA_LOGIN }, { refresh: false });
console.log("[repro] restart GET /admin (refresh:false):", JSON.stringify(r3));
const r4 = store2.validate(roundTripped, { userAgent: UA_LOGIN });
console.log("[repro] restart /admin/api/* (refresh:true):", JSON.stringify(r4));

// Scenario: DIFFERENT signing secret between instances (e.g. ADMIN_SESSION_SECRET
// set on one instance but derived on another, or ADMIN_TOKEN differs)
const store3 = createAdminSessionStore({
  idleTtlMs: 30 * 60 * 1000,
  absoluteTtlMs: 8 * 60 * 60 * 1000,
  secret: crypto.randomBytes(32),
});
const r5 = store3.validate(roundTripped, { userAgent: UA_LOGIN });
console.log("[repro] different-secret instance:", JSON.stringify(r5));
