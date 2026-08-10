"use strict";

// ── Egress guard: SSRF decisions for server-initiated outbound fetches ────────
//
// Findings addressed: S04-H1 (SSRF predicate never resolves DNS and misses
// IPv6/alternate literal forms), S04-H2 (unbounded response body).
//
// Why this file exists as a SEPARATE, PURE module:
//   `lib/pure.js:isBlockedPreviewHost()` is a single string-prefix regex. It is
//   not wrong so much as radically incomplete, and every gap below is reachable
//   from the user-supplied `/linkPreview` URL:
//
//     - `http://0.0.0.0/`            → 0.0.0.0/8 was not matched at all; on
//                                      Linux this reaches localhost services.
//     - `http://2130706433/`         → decimal encoding of 127.0.0.1. Node's
//                                      fetch/`new URL()` normalise this to
//                                      127.0.0.1 when connecting, but the
//                                      regex sees the literal string "2130706433".
//     - `http://0x7f.0x0.0x0.0x1/`   → hex encoding, same story. Also octal
//                                      (`0177.0.0.1`) and short form (`127.1`).
//     - `http://[fd00::1]/`          → unique-local (fc00::/7): not matched.
//     - `http://[fe80::1]/`          → IPv6 link-local: not matched.
//     - `http://[::ffff:127.0.0.1]/` → IPv4-mapped loopback: not matched.
//     - `http://169.254.169.253/`    → the old check equality-matched only
//                                      169.254.169.254, but the whole
//                                      169.254/16 link-local range is unsafe
//                                      (AWS also serves credentials on .253).
//     - `http://internal.example.com/` resolving to 10.0.0.5 → the old check
//                                      inspects the NAME ONLY and never
//                                      resolves it, so any attacker-controlled
//                                      DNS record defeated it completely. This
//                                      was the single largest gap.
//
// The decision logic here is pure and synchronous so it can carry real unit
// tests (`egressGuard.test.js`) rather than asserted ones. DNS resolution is
// inherently I/O, so it lives in the thin async wrapper at the bottom
// (`resolveAndCheckHost`), which delegates every address it gets back to the
// pure `isBlockedIpAddress()`. That split is deliberate: the part that decides
// is testable, the part that does I/O holds no policy.
//
// This module ADDS to the existing check rather than replacing it — callers run
// both, per SESSION_PROTOCOL §8 ("Add, don't replace, existing checks").

const dns = require("node:dns").promises;

// ── IPv4 literal parsing (all four encodings inet_aton accepts) ───────────────
//
// A hostname is only an IPv4 literal if EVERY dot-separated part is numeric in
// one of these bases. `inet_aton()` — and therefore the OS resolver the fetch
// ultimately uses — accepts 1, 2, 3 or 4 parts, where a short form packs the
// remaining bytes into the last part (127.1 == 127.0.0.1).
//
// Returns a 32-bit unsigned integer, or null if `host` is not an IPv4 literal.
function parseIpv4Literal(host) {
  const parts = String(host).split(".");
  if (parts.length === 0 || parts.length > 4) return null;

  const values = [];
  for (const part of parts) {
    if (part === "") return null;
    let value;
    if (/^0[xX][0-9a-fA-F]+$/.test(part)) {
      value = parseInt(part.slice(2), 16);        // hex:   0x7f
    } else if (/^0[0-7]+$/.test(part)) {
      value = parseInt(part.slice(1), 8);         // octal: 0177
    } else if (/^[0-9]+$/.test(part)) {
      value = parseInt(part, 10);                 // decimal
    } else {
      return null;                                // not numeric → it's a name
    }
    if (!Number.isFinite(value) || value < 0) return null;
    values.push(value);
  }

  // Every part except the last must fit in one byte; the last absorbs the rest.
  const last = values[values.length - 1];
  const leading = values.slice(0, -1);
  if (leading.some((v) => v > 0xff)) return null;

  const remainingBytes = 4 - leading.length;
  const lastMax = remainingBytes >= 4 ? 0xffffffff : Math.pow(256, remainingBytes) - 1;
  if (last > lastMax) return null;

  let result = 0;
  for (const v of leading) result = (result << 8) | v;
  // Shift the accumulated leading bytes up by the width the last part occupies.
  result = remainingBytes >= 4 ? 0 : result * Math.pow(256, remainingBytes);
  return (result + last) >>> 0;
}

// Blocked IPv4 space, as [network, prefixLength] pairs. Anything that is not
// globally routable unicast is refused: the server has no legitimate reason to
// fetch a link preview from any of it.
const BLOCKED_IPV4_CIDRS = [
  ["0.0.0.0", 8],        // "this network" — 0.0.0.0 reaches localhost on Linux
  ["10.0.0.0", 8],       // RFC 1918 private
  ["100.64.0.0", 10],    // RFC 6598 carrier-grade NAT
  ["127.0.0.0", 8],      // loopback
  ["169.254.0.0", 16],   // link-local — includes ALL cloud metadata IPs
  ["172.16.0.0", 12],    // RFC 1918 private
  ["192.0.0.0", 24],     // IETF protocol assignments
  ["192.0.2.0", 24],     // TEST-NET-1
  ["192.168.0.0", 16],   // RFC 1918 private
  ["198.18.0.0", 15],    // benchmarking
  ["198.51.100.0", 24],  // TEST-NET-2
  ["203.0.113.0", 24],   // TEST-NET-3
  ["224.0.0.0", 4],      // multicast
  ["240.0.0.0", 4],      // reserved (includes 255.255.255.255 broadcast)
];

function ipv4ToInt(dotted) {
  const [a, b, c, d] = dotted.split(".").map(Number);
  return (((a << 24) | (b << 16) | (c << 8) | d) >>> 0);
}

const BLOCKED_IPV4_RANGES = BLOCKED_IPV4_CIDRS.map(([network, prefix]) => {
  // `>>> 0` keeps the mask unsigned; a /0 mask would overflow the shift, but no
  // /0 appears in the table above.
  const mask = prefix === 0 ? 0 : ((0xffffffff << (32 - prefix)) >>> 0);
  // `>>> 0` on the masked network is REQUIRED, not cosmetic: JS `&` coerces both
  // operands to *signed* int32, so any network at or above 128.0.0.0 (192.168/16,
  // 224/4, 240/4, 198.18/15, …) comes back negative and would never compare equal
  // to the unsigned value classifyIpv4Int() computes. Omitting it silently
  // un-blocked half this table — caught by the "old predicate's catches still
  // hold" test in egressGuard.test.js.
  return { network: ((ipv4ToInt(network) & mask) >>> 0), mask, cidr: `${network}/${prefix}` };
});

function classifyIpv4Int(value) {
  for (const range of BLOCKED_IPV4_RANGES) {
    if ((value & range.mask) >>> 0 === range.network) {
      return { blocked: true, reason: `IPv4 ${range.cidr} is not globally routable` };
    }
  }
  return { blocked: false, reason: "" };
}

// ── IPv6 literal parsing ──────────────────────────────────────────────────────
//
// Accepts an optionally bracketed address. WHATWG `new URL().hostname` KEEPS the
// brackets for IPv6 (`new URL("http://[::1]/").hostname === "[::1]"`), which is
// exactly the detail the old regex tried to paper over with `\[?::1\]?`, so
// strip them explicitly here.
//
// Returns an array of 8 16-bit groups, or null if not an IPv6 literal.
function parseIpv6Literal(host) {
  let text = String(host).trim();
  if (text.startsWith("[") && text.endsWith("]")) text = text.slice(1, -1);
  // Drop a zone index (fe80::1%eth0) — it carries no addressing information.
  const zone = text.indexOf("%");
  if (zone >= 0) text = text.slice(0, zone);
  if (!text.includes(":")) return null;

  // A trailing IPv4 form (::ffff:127.0.0.1, ::127.0.0.1) contributes the final
  // two groups. Rewrite it into equivalent HEX TEXT ("::ffff:7f00:1") rather
  // than carrying the two groups separately: an earlier version of this function
  // appended a placeholder group and produced a 9-group result, which made
  // `::ffff:127.0.0.1` parse as invalid and therefore silently UNBLOCKED
  // IPv4-mapped loopback — the exact bypass this finding is about. Normalising
  // to text keeps a single parsing path for every form.
  const lastColon = text.lastIndexOf(":");
  const afterLastColon = text.slice(lastColon + 1);
  if (afterLastColon.includes(".")) {
    // Only a true dotted quad is legal here, so parse strictly rather than with
    // parseIpv4Literal()'s permissive inet_aton forms.
    const octets = afterLastColon.split(".");
    if (octets.length !== 4) return null;
    const nums = octets.map((o) => (/^[0-9]{1,3}$/.test(o) ? Number(o) : NaN));
    if (nums.some((n) => !Number.isInteger(n) || n > 255)) return null;
    const high = ((nums[0] << 8) | nums[1]).toString(16);
    const low = ((nums[2] << 8) | nums[3]).toString(16);
    text = `${text.slice(0, lastColon + 1)}${high}:${low}`;
  }

  const doubleColon = text.indexOf("::");
  if (doubleColon !== text.lastIndexOf("::")) return null; // at most one "::"

  const expand = (segment) =>
    segment === "" ? [] : segment.split(":").map((g) => (/^[0-9a-fA-F]{1,4}$/.test(g) ? parseInt(g, 16) : NaN));

  let groups;
  if (doubleColon >= 0) {
    const head = expand(text.slice(0, doubleColon));
    const rest = expand(text.slice(doubleColon + 2));
    if (head.length + rest.length >= 8) return null; // "::" must cover >= 1 group
    const zeros = new Array(8 - head.length - rest.length).fill(0);
    groups = [...head, ...zeros, ...rest];
  } else {
    groups = expand(text);
  }

  if (groups.length !== 8 || groups.some((g) => !Number.isInteger(g))) return null;
  return groups;
}

function classifyIpv6Groups(groups) {
  const [g0, g1] = groups;
  const isZero = groups.every((g) => g === 0);
  if (isZero) return { blocked: true, reason: "IPv6 unspecified address (::)" };
  if (groups.slice(0, 7).every((g) => g === 0) && groups[7] === 1) {
    return { blocked: true, reason: "IPv6 loopback (::1)" };
  }
  // IPv4-mapped (::ffff:a.b.c.d) and IPv4-compatible (::a.b.c.d): the kernel
  // connects to the embedded IPv4 address, so apply the IPv4 policy to it.
  const firstFiveZero = groups.slice(0, 5).every((g) => g === 0);
  if (firstFiveZero && (groups[5] === 0xffff || groups[5] === 0)) {
    const embedded = (((groups[6] << 16) | groups[7]) >>> 0);
    const verdict = classifyIpv4Int(embedded);
    if (verdict.blocked) {
      return { blocked: true, reason: `IPv6-embedded ${verdict.reason}` };
    }
  }
  if ((g0 & 0xfe00) === 0xfc00) return { blocked: true, reason: "IPv6 unique-local (fc00::/7)" };
  if ((g0 & 0xffc0) === 0xfe80) return { blocked: true, reason: "IPv6 link-local (fe80::/10)" };
  if ((g0 & 0xff00) === 0xff00) return { blocked: true, reason: "IPv6 multicast (ff00::/8)" };
  if (g0 === 0x0064 && g1 === 0xff9b) return { blocked: true, reason: "IPv6 NAT64 (64:ff9b::/96)" };
  if (g0 === 0x2001 && g1 === 0x0db8) return { blocked: true, reason: "IPv6 documentation (2001:db8::/32)" };
  return { blocked: false, reason: "" };
}

/**
 * Pure verdict on a single IP address string (v4 or v6, brackets optional).
 * Non-addresses return `{ blocked: false, isIpLiteral: false }` — a NAME is not
 * safe by virtue of not being a literal, it simply has to be resolved first.
 */
function isBlockedIpAddress(address) {
  const text = String(address || "").trim();
  if (!text) return { blocked: true, isIpLiteral: false, reason: "empty host" };

  const v6 = parseIpv6Literal(text);
  if (v6) {
    const verdict = classifyIpv6Groups(v6);
    return { blocked: verdict.blocked, isIpLiteral: true, reason: verdict.reason };
  }
  const v4 = parseIpv4Literal(text);
  if (v4 !== null) {
    const verdict = classifyIpv4Int(v4);
    return { blocked: verdict.blocked, isIpLiteral: true, reason: verdict.reason };
  }
  return { blocked: false, isIpLiteral: false, reason: "" };
}

// Hostnames that must never be fetched regardless of what they resolve to.
// `.internal`/`.local`/`.localhost` are reserved or mDNS-only; the metadata
// names are the classic SSRF credential targets.
const BLOCKED_HOST_SUFFIXES = [".internal", ".local", ".localhost", ".home.arpa"];
const BLOCKED_HOST_NAMES = new Set([
  "localhost",
  "metadata",
  "metadata.google.internal",
  "metadata.goog",
  "instance-data",
]);

function isBlockedHostName(hostname) {
  const host = String(hostname || "").toLowerCase().replace(/\.$/, "");
  if (!host) return { blocked: true, reason: "empty host" };
  if (BLOCKED_HOST_NAMES.has(host)) return { blocked: true, reason: `reserved hostname "${host}"` };
  for (const suffix of BLOCKED_HOST_SUFFIXES) {
    if (host.endsWith(suffix)) return { blocked: true, reason: `reserved TLD "${suffix}"` };
  }
  return { blocked: false, reason: "" };
}

/**
 * Full PURE (no DNS) verdict on a candidate preview URL.
 *
 * Returns `{ ok: true, url, hostname, isIpLiteral }` or `{ ok: false, reason }`.
 * A name-based host that passes here still MUST go through
 * `resolveAndCheckHost()` before the socket is opened — this function
 * deliberately cannot see where a name points.
 */
function evaluatePreviewTarget(rawUrl) {
  let parsed;
  try {
    parsed = new URL(String(rawUrl));
  } catch {
    return { ok: false, reason: "malformed URL" };
  }
  if (!["http:", "https:"].includes(parsed.protocol)) {
    return { ok: false, reason: `scheme "${parsed.protocol}" not allowed` };
  }
  // Credentials in the URL are a phishing/confusion vector and are never needed
  // for a public page fetch.
  if (parsed.username || parsed.password) {
    return { ok: false, reason: "embedded credentials not allowed" };
  }
  const nameVerdict = isBlockedHostName(parsed.hostname);
  if (nameVerdict.blocked) return { ok: false, reason: nameVerdict.reason };

  const ipVerdict = isBlockedIpAddress(parsed.hostname);
  if (ipVerdict.blocked) return { ok: false, reason: ipVerdict.reason };

  return {
    ok: true,
    url: parsed.href,
    hostname: parsed.hostname,
    isIpLiteral: ipVerdict.isIpLiteral,
  };
}

// ── Response size policy (S04-H2) ─────────────────────────────────────────────
//
// `await response.text()` buffers the ENTIRE body before any `.slice()` can run,
// so a malicious host advertising a small page and then streaming gigabytes will
// exhaust the heap and kill the process. Two gates, both required: reject an
// oversized declared Content-Length up front (cheap, no bytes read), and count
// bytes while streaming in case the header lied or was absent.

const MAX_PREVIEW_HTML_BYTES = 512 * 1024;  // 512 KB — og: tags live in <head>
const MAX_PREVIEW_IMAGE_BYTES = 2 * 1024 * 1024; // 2 MB — a preview thumbnail

/**
 * Pure decision on a declared Content-Length header.
 * An absent/unparseable header is NOT a rejection — it just means the streaming
 * counter is the only defence, which is why that counter is mandatory.
 */
function contentLengthExceeds(headerValue, maxBytes) {
  if (headerValue === null || headerValue === undefined || headerValue === "") return false;
  const declared = Number(String(headerValue).trim());
  if (!Number.isFinite(declared) || declared < 0) return false;
  return declared > maxBytes;
}

/**
 * Reads a fetch Response body, aborting once `maxBytes` have been seen.
 * Returns `{ truncated, bytes, buffer }`. Never accumulates more than
 * `maxBytes` (+ one chunk) in memory, which is the property S04-H2 needs.
 */
async function readCappedBody(response, maxBytes) {
  if (contentLengthExceeds(response.headers.get("content-length"), maxBytes)) {
    throw new Error("response too large (declared Content-Length exceeds cap)");
  }
  if (!response.body) return { truncated: false, bytes: 0, buffer: Buffer.alloc(0) };

  const chunks = [];
  let bytes = 0;
  let truncated = false;
  const reader = response.body.getReader();
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      bytes += value.byteLength;
      chunks.push(Buffer.from(value));
      if (bytes >= maxBytes) {
        truncated = true;
        break; // stop reading; `finally` cancels the stream so the socket closes
      }
    }
  } finally {
    try { await reader.cancel(); } catch { /* already closed */ }
  }
  return { truncated, bytes, buffer: Buffer.concat(chunks).subarray(0, maxBytes) };
}

// ── DNS-aware check (the S04-H1 gap the old predicate could not close) ────────

/**
 * Resolves `hostname` and refuses it if ANY returned address is non-public.
 *
 * "Any" rather than "the first" is deliberate: a host with both a public A
 * record and a private one would otherwise be a coin flip.
 *
 * Returns `{ ok: true, addresses }` or `{ ok: false, reason }`.
 *
 * KNOWN RESIDUAL RISK (recorded in RISK_REGISTER.md, not silently ignored):
 * this is a check-then-connect sequence, so a DNS rebinding attack with a
 * sub-second TTL can still return a public address here and a private one to
 * the connect() that follows. Fully closing that requires pinning the socket to
 * the validated address (a custom agent/lookup hook). This function shrinks the
 * window from "permanently open" to "requires a rebinding race"; it does not
 * claim to eliminate it.
 */
async function resolveAndCheckHost(hostname, lookup = dns.lookup) {
  const literal = isBlockedIpAddress(hostname);
  if (literal.blocked) return { ok: false, reason: literal.reason };
  if (literal.isIpLiteral) return { ok: true, addresses: [String(hostname).replace(/^\[|\]$/g, "")] };

  let records;
  try {
    records = await lookup(hostname, { all: true });
  } catch (e) {
    return { ok: false, reason: `DNS resolution failed: ${e.code || e.message}` };
  }
  if (!Array.isArray(records) || records.length === 0) {
    return { ok: false, reason: "DNS returned no addresses" };
  }
  for (const record of records) {
    const verdict = isBlockedIpAddress(record.address);
    if (verdict.blocked) {
      return { ok: false, reason: `resolves to blocked address: ${verdict.reason}` };
    }
  }
  return { ok: true, addresses: records.map((r) => r.address) };
}

module.exports = {
  parseIpv4Literal,
  parseIpv6Literal,
  isBlockedIpAddress,
  isBlockedHostName,
  evaluatePreviewTarget,
  contentLengthExceeds,
  readCappedBody,
  resolveAndCheckHost,
  MAX_PREVIEW_HTML_BYTES,
  MAX_PREVIEW_IMAGE_BYTES,
};
