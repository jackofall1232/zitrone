# Redeploying the relay server (relay.sublemonable.com)

The website + Android APK are deployed independently of the **relay server** (the
Go/Fiber process behind `relay.sublemonable.com`). A relay redeploy is a separate,
manual step — and it is easy to forget. Two live outages already came from this:

1. **Attachments (0.7.x):** the live relay was left on a **pre-0.7.0-beta build**
   that predates the attachment (blob) feature entirely, so every attachment
   fetch 404s (the `/api/v1/blobs` and `/api/v1/blobs/redeem` routes don't exist
   there) and uploads >512 KiB 413 (the old global body limit). Text messaging
   was unaffected, which is why it went unnoticed.
2. **Lemon drops / QR dead drops (0.8.x):** the live relay was left on a build
   that had blobs but **not** `/api/v1/qr-drops` (pre–PR #3). Clients still
   sealed locally and solved the PoW, then deposit returned a Fiber router 404
   `{"error":"error"}`. The UI used to collapse that into "Couldn't seal the
   drop — try again.", masking the real diagnosis. Schema note: a current
   server applies `qr_drops` on boot via the embedded `schema.sql`.

## When you MUST redeploy the relay
- After ANY change under `server/` that you want live (new routes, limits, fixes).
- After shipping clients that call new routes (blobs, qr-drops, …) — **before**
  or **with** the client release, not days later.
- Specifically: attachment blob store (0.7+) and lemon-drop deposit/fetch/burn
  (0.8+ / PR #3) both require a redeploy or every client call is a silent 404.

## Redeploy steps (on the relay box)
The relay runs via Docker Compose (`docker-compose.yml`, plus the `-f
docker-compose.tor.yml` / `-f docker-compose.i2p.yml` overlays if Tor/I2P are on).

1. On the relay box, pull the intended release commit:
   ```bash
   cd /path/to/zitrone && git fetch origin && git checkout main && git pull --ff-only
   ```
   (Or check out the exact release tag you are shipping.)
2. Rebuild and restart just the server (Postgres + data volumes are untouched):
   ```bash
   docker compose [-f docker-compose.yml -f docker-compose.tor.yml -f docker-compose.i2p.yml] up -d --build server
   ```
3. Confirm the container is healthy and on the new build:
   ```bash
   docker compose ps
   curl -s localhost:8443/healthz
   ```

## Config to verify at redeploy (closes the silent-404 / 413 classes)
- `BLOB_MAX_BYTES` (default 8 MiB) and `BLOB_TTL_HOURS` (default 168 / 1 week
  unfetched fallback; successful redeem is fetch-and-burn) — leave unset
  to take the defaults, or set deliberately. As of the hardening fix these are
  clamped to positive defaults if misconfigured `≤0` (a `≤0` TTL would make every
  blob fetch 404 while uploads still return 201 — a silent, trust-breaking failure).
- **Reverse proxy body limit.** The relay accepts blob uploads up to
  `BlobBodyLimit` (~11.3 MB with the default cap). If a proxy fronts the relay
  (see `SELF_HOSTING.md`), its body cap must be **≥12 MB** or it will 413 large
  attachments even with a correct relay:
  - Caddy: `request_body { max_size 12MB }` (stock Caddy has NO default cap — only
    add this if you also cap elsewhere; the documented bare `reverse_proxy` is fine).
  - nginx: `client_max_body_size 12m;` (nginx defaults to **1 MB** — this WILL 413
    attachments >1 MB if left at the default).

## Verify the deployed build (REQUIRED — do not skip)
From anywhere with network access to the relay:
```bash
scripts/verify-relay-build.sh https://relay.sublemonable.com
# local compose (this box):
scripts/verify-relay-build.sh http://localhost:8443
```
It probes (unauthenticated, mutates nothing) that:

- blob routes are present (401, not 404), a ~1 MB upload is not 413'd, and the
  body-limit guard returns the current-build `payload_too_large` signature;
- **lemon-drop** `POST /api/v1/qr-drops`, `/qr-drops/fetch`, and `/qr-drops/burn`
  reach their handlers (400 validation), and are **not** Fiber router 404s with
  generic `{"error":"error"}` (that signature means a pre–PR #3 build).

Exit 0 = the deployed build has attachments **and** lemon drops; non-zero =
still stale / misconfigured. Run it as the last step of every relay redeploy.

### Symptom → cause cheatsheet
| Client symptom | Likely relay state |
| --- | --- |
| Attachments 404 / large upload 413 | Pre-blob build or proxy body cap |
| "Couldn't seal/create the drop" / deposit fails after PoW spinner | Missing `/api/v1/qr-drops` (pre-0.8 / pre-PR #3) |
| Honest UI: "stale server build" / "doesn't support QR drops" | Same — clients 0.8.3+ name it explicitly |
