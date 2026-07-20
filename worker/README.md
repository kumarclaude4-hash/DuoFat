# DuoShield Storage Worker

Cloudflare Worker providing tiered media storage for DuoShield.

```
Android app
    │  plain HTTP (PUT / GET / DELETE)
    ▼
Cloudflare Worker  ← this directory
    │
    ├── R2 bucket "duoshield-hot"   (days 0–30, fast, free CF egress)
    │
    └── B2 bucket "duoshield-cold"  (days 31–180, SigV4 signed by Worker)
         └── Auto-purged after 180 days by daily cron
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

### 4. Create the KV namespace (rate limiting)

```bash
npx wrangler kv namespace create RATE_KV
# Copy the "id" value and paste it into wrangler.jsonc → kv_namespaces[0].id
```

### 5. Edit wrangler.jsonc

Update these values:
- `kv_namespaces[0].id` → your KV namespace ID from step 4
- `vars.B2_ENDPOINT` → your B2 endpoint
- `vars.B2_BUCKET` → your B2 bucket name (e.g. `duoshield-cold`)
- `vars.B2_REGION` → your B2 region (e.g. `eu-central-003`)

### 6. Set B2 credentials as secrets

```bash
npx wrangler secret put B2_ACCESS_KEY_ID
# paste your B2 keyID

npx wrangler secret put B2_SECRET_ACCESS_KEY
# paste your B2 applicationKey
```

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

### 8. Wire the Worker URL into the Android app

Add to `local.properties`:
```
worker.url=https://duoshield-storage.<your-subdomain>.workers.dev
```

Then rebuild the APK. `B2StorageHelper` automatically routes all storage calls
through the Worker when `WORKER_URL` is non-empty — no SigV4 signing on the device.

---

## Testing

```bash
# Upload a test file
curl -X PUT "https://duoshield-storage.<sub>.workers.dev/test/hello.bin" \
  -H "Content-Type: application/octet-stream" \
  -H "X-Client-ID: test-user" \
  --data-binary "hello"

# Download it back
curl "https://duoshield-storage.<sub>.workers.dev/test/hello.bin" \
  -H "X-Client-ID: test-user"

# Delete it
curl -X DELETE "https://duoshield-storage.<sub>.workers.dev/test/hello.bin" \
  -H "X-Client-ID: test-user"

# Trigger the cron manually (local dev only)
npx wrangler dev --test-scheduled
curl "http://localhost:8787/__scheduled?cron=0+2+*+*+*"
```

---

## Free-tier limits (50 users)

| Resource              | Free limit        |
|-----------------------|-------------------|
| R2 storage            | 10 GB/month       |
| R2 Class A ops (PUT)  | 1 M/month         |
| R2 Class B ops (GET)  | 10 M/month        |
| B2 storage            | 10 GB/month       |
| B2 → CF egress        | Free (Bandwidth Alliance) |
| Worker requests       | 100 K/day         |
| KV reads              | 100 K/day         |
| KV writes             | 1 K/day           |

**Estimated monthly cost for 50 users: $0.**

---

## Data lifecycle

| Age         | Location        | Accessible | Action                    |
|-------------|-----------------|------------|---------------------------|
| 0–30 days   | R2 (hot)        | ✅ Fast     | New uploads land here     |
| 31–180 days | B2 (cold)       | ✅ Slightly slower | Daily cron moves here |
| 180+ days   | Deleted         | ❌          | Daily cron purges from B2 |
