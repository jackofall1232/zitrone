# Redeploying the relay server (relay.sublemonable.com)

The website + Android APK are deployed independently of the **relay server** (the
Go/Fiber process behind `relay.sublemonable.com`). A relay redeploy is a separate,
manual step — and it is easy to forget, which is exactly how attachments broke:
the live relay was left on a **pre-0.7.0-beta build that predates the attachment
(blob) feature entirely**, so every attachment fetch 404s (the `/api/v1/blobs`
and `/api/v1/blobs/redeem` routes don't exist there) and uploads >512 KiB 413
(the old global body limit). Text messaging is unaffected, which is why it went
unnoticed.

## When you MUST redeploy the relay
- After ANY change under `server/` that you want live (new routes, limits, fixes).
- Specifically now: to ship the attachment blob store (routes + raised body limit)
  that the 0.7.x Android app already depends on.

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
- `BLOB_MAX_BYTES` (default 8 MiB) and `BLOB_TTL_HOURS` (default 72) — leave unset
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
```
It probes (unauthenticated, mutates nothing) that the blob routes are present
(401, not 404), that a ~1 MB upload is not 413'd, and that the guard returns the
current-build `payload_too_large` signature. Exit 0 = the deployed build has
attachments; non-zero = still stale / misconfigured. Run it as the last step of
every relay redeploy.
