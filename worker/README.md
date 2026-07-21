# DuoShield Storage Worker

Cloudflare Worker providing tiered media storage for DuoShield.

```
Android app
    │  HTTPS  (PUT / GET / DELETE)
    │  Authorization: Bearer <WORKER_SECRET>
    ▼
Cloudflare Worker  ← this directory
    │
    ├── R2 bucket "duoshield-hot"   (days 0–30, fast, free CF egress)
    │
    └── B2 bucket "duoshield-cold"  (permanent — no auto-expiry)
         └── Deleted only when the app explicitly sends DELETE
```

All blobs are **AES-256-GCM encrypted on the Android device** before upload.
The Worker only ever sees ciphertext — it never touches plaintext.

---

## Setup (one-time)

### 1. Prerequisites

```bash
node -v          # 18+
npm install -g wrangler
wrangler login
```

### 2. Create Backblaze B2 bucket

1. Log in to [Backblaze B2](https://secure.backblaze.com/b2_buckets.htm)
2. Create bucket **`duoshield-cold`** — set to **Private**
3. Create an application key with Read + Write for that bucket
4. Note your endpoint (e.g. `https://s3.eu-central-003.backblazeb2.com`) and region

### 3. Create the R2 bucket

```bash
npx wrangler r2 bucket create duoshield-hot
```

### 4. Create the KV namespace (rate limiting + storage counters)

```bash
npx wrangler kv namespace create RATE_KV
# Copy the "id" value and paste it into wrangler.jsonc → kv_namespaces[0].id
```

### 5. Edit wrangler.jsonc

Update these values:
- `kv_namespaces[0].id` → your KV namespace ID from step 4
- `vars.B2_ENDPOINT` → your B2 endpoint
- `vars.B2_BUCKET`   → your B2 bucket name (e.g. `duoshield-cold`)
- `vars.B2_REGION`   → your B2 region (e.g. `eu-central-003`)

### 6. Set secrets

```bash
npx wrangler secret put B2_ACCESS_KEY_ID      # paste your B2 keyID
npx wrangler secret put B2_SECRET_ACCESS_KEY  # paste your B2 applicationKey
npx wrangler secret put WORKER_SECRET         # shared token for the Android app
```

**`WORKER_SECRET`** is a random string you generate (e.g. `openssl rand -hex 32`).
Every request from the Android app must include `Authorization: Bearer <WORKER_SECRET>`.
Without it the Worker returns 401.

### 7. Install dependencies and deploy

```bash
cd worker
npm install
npx wrangler deploy
```

Your Worker URL will be:
```
https://duoshield-storage.<your-subdomain>.workers.dev
```

### 8. Wire the Worker URL and secret into the Android app

Add to `local.properties`:
```
worker.url=https://duoshield-storage.<your-subdomain>.workers.dev
worker.secret=<your WORKER_SECRET value>
```

Then rebuild the APK. `B2StorageHelper` automatically routes all storage calls
through the Worker when `WORKER_URL` is non-empty.

---

## Testing

```bash
TOKEN="your-worker-secret"

# Upload a test file
curl -X PUT "https://duoshield-storage.<sub>.workers.dev/test/hello.bin" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/octet-stream" \
  -H "Content-Length: 5" \
  -H "X-Client-ID: test-user" \
  --data-binary "hello"

# Download it back
curl "https://duoshield-storage.<sub>.workers.dev/test/hello.bin" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Client-ID: test-user"

# Delete it
curl -X DELETE "https://duoshield-storage.<sub>.workers.dev/test/hello.bin" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Client-ID: test-user"

# Check storage stats (admin)
curl "https://duoshield-storage.<sub>.workers.dev/stats" \
  -H "Authorization: Bearer $TOKEN"

# Trigger the cron manually (local dev only)
npx wrangler dev --test-scheduled
curl "http://localhost:8787/__scheduled?cron=0+2+*+*+*"
```

---

## Free-tier limits

| Resource              | Free limit        | Guard                              |
|-----------------------|-------------------|------------------------------------|
| R2 storage            | 10 GB             | Hard cap at 9.5 GB (95%)          |
| R2 Class A ops (PUT)  | 1 M/month         | No explicit guard — monitor usage  |
| R2 Class B ops (GET)  | 10 M/month        | No explicit guard — monitor usage  |
| B2 storage            | 10 GB             | Tracked via nightly list; informational only |
| B2 → CF egress        | Free (Bandwidth Alliance) | —                        |
| Worker requests       | 100 K/day         | Hard gate at 90 K/day (90%)       |
| KV reads              | 100 K/day         | ~2 reads/request; comfortable     |
| KV writes             | 1 K/day           | Minimised via sampling + in-memory rate limiting |

**Estimated monthly cost for ~50 users: $0.**

> **Note on KV writes:** The free tier allows 1,000 KV writes/day. The Worker
> minimises this by using sampled request counting (~0.1 writes/request) and
> in-memory rate limiting (0 KV writes). Storage counter writes happen only on
> PUT/DELETE (~1 write each). For Durable Objects-grade global consistency on
> rate limiting, upgrade to Workers Paid ($5/month).

---

## Data lifecycle

| Age      | Location | Accessible       | How it leaves                         |
|----------|----------|------------------|---------------------------------------|
| 0–30 days | R2 (hot) | ✅ Fast          | Daily cron moves to B2 after 30 days  |
| 31+ days  | B2 (cold) | ✅ Slightly slower | App sends explicit DELETE request    |
| Deleted   | Neither  | ❌               | Removed from both tiers by DELETE     |

**B2 has no automatic expiry.** Files remain in cold storage until the application
explicitly deletes them (disappearing-message sweep, manual delete, etc.).
The nightly cron reconciles B2 storage bytes via `ListObjectsV2` but never
auto-deletes anything from B2.

---

## Security

- **Authentication:** All data-plane and stats endpoints require
  `Authorization: Bearer <WORKER_SECRET>`. Health check (`/health`) is public.
- **Encryption:** All file content is AES-256-GCM encrypted client-side before
  upload. The Worker and both storage tiers only ever see ciphertext.
- **Rate limiting:** 120 req/min per `X-Client-ID` enforced per edge isolate
  (advisory — not globally consistent without Durable Objects).
- **Storage cap:** Uploads rejected when R2 exceeds 9.5 GB. Actual stored size
  is verified via R2 HEAD after upload (client `Content-Length` is not trusted).
