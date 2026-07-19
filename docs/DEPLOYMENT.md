# Deployment (production relay — CX23)

Production is the box **CX23**. It runs **zitrone's codebase** (the `server`
image is built from `/root/zitrone/server`) on top of infrastructure whose
identity is still named **`sublemonable`**. That mismatch is deliberate. Read
the next section before changing anything named "sublemonable" on production.

---

## IMPORTANT: "sublemonable" naming on production is intentional

Production (CX23) runs zitrone's codebase, but the underlying infrastructure
identity — Docker Compose project name, volume names (`sublemonable_pg-data`,
`sublemonable_tor-data`, `sublemonable_i2p-data`), Postgres database name
(`sublemonable`), the onion address, and the signing keystore — all remain
named/tied to "sublemonable" **on purpose**.

This is **NOT** a leftover from an incomplete migration. It is deliberate
continuity: switching any of these to "zitrone" would create fresh empty
volumes (regenerating onion keys, losing all Postgres data) or break the
signing certificate chain. The code was cut over; the identity/infrastructure
naming was deliberately **NOT** touched.

If you are a future maintainer or AI agent session and you notice
"sublemonable" naming in production configs, docker-compose commands, or
database connections: **do not "fix" this by renaming to zitrone.** Ask before
touching any of: compose project name, `*_pg-data` / `*_tor-data` /
`*_i2p-data` volumes, the `sublemonable` Postgres database, onion keys, or the
release keystore.

### Why renaming is destructive, concretely

- **Volumes are Compose-project-scoped.** Running the stack from `/root/zitrone`
  without `-p sublemonable` makes the project name default to `zitrone`, and
  Compose then creates brand-new empty `zitrone_pg-data` / `zitrone_tor-data` /
  `zitrone_i2p-data` volumes. The existing onion keys and Postgres data live in
  the `sublemonable_*` volumes and would be silently orphaned — i.e. the
  `.onion` address changes and all accounts vanish.
- **The Postgres data lives in a database named `sublemonable`.** zitrone's
  compose defaults to a database named `zitrone`; Postgres will not auto-create
  it on an already-populated volume, so the server would connect to an empty or
  nonexistent DB. Continuity is preserved by an override (see below) that points
  the server's `DATABASE_URL` back at the existing `sublemonable` database.
- **The onion keys and JWT keystore are identity, not code.** Regenerating them
  changes the app's baked-in relay `.onion` and invalidates every existing
  account's tokens.
- **The I2P relay destination key is identity too — and has a filename trap.**
  `docker-compose.i2p.yml` / i2pd `tunnels.conf` references the destination
  keyfile as `zitrone-relay.dat`. The *original* identity in the reattached
  volume is `sublemonable-relay.dat` (B32
  `y5ac5zowrbpz5schj4hq5fme32ranttmkrtbqg3zjnw6k5wogppq.b32.i2p`, baked into
  `.env` as `I2P_EEPSITE_DEST`). If `zitrone-relay.dat` is missing, **i2pd
  silently generates a fresh key and a new B32** — no error. Continuity is
  maintained by keeping `zitrone-relay.dat` byte-identical to
  `sublemonable-relay.dat`. If you ever see a *new* I2P B32 after a restart,
  this is why; restore it by copying the original key over `zitrone-relay.dat`.

---

## How production is actually run

Always use `-p sublemonable` and the **full** multi-file invocation (base +
tor + i2p + continuity overlay). Never the base file alone — that silently
drops the onion/i2p services.

```sh
cd /root/zitrone
docker compose -p sublemonable \
  -f docker-compose.yml \
  -f docker-compose.tor.yml \
  -f docker-compose.i2p.yml \
  -f docker-compose.continuity.yml \
  up -d
```

- `docker-compose.continuity.yml` is the production-only overlay that pins the
  server + Postgres back to the existing `sublemonable` database. It is **not**
  part of a fresh/self-hosted install (see `docs/SELF_HOSTING.md` for that).
- `.env` (POSTGRES_PASSWORD + the four onion addresses) and
  `server/keys/` (the JWT keystore) are carried over from the old
  sublemonable checkout and are **gitignored** — they live only on CX23.

To rebuild just the relay server after a code change:

```sh
cd /root/zitrone
docker compose -p sublemonable -f docker-compose.yml -f docker-compose.tor.yml \
  -f docker-compose.i2p.yml -f docker-compose.continuity.yml build server
docker compose -p sublemonable -f docker-compose.yml -f docker-compose.tor.yml \
  -f docker-compose.i2p.yml -f docker-compose.continuity.yml up -d
```

---

## Cutover history

**2026-07-19 — sublemonable → zitrone code cutover (CX23).** Production had
been running the `sublemonable` codebase (no `/api/v1/blobs` API), which blocked
0.7.6-beta's reveal-and-burn feature. The relay server was cut over to zitrone's
source with **all infrastructure identity preserved**: same compose project
(`sublemonable`), same volumes, same `sublemonable` Postgres DB (35 accounts
intact), same relay `.onion`
(`fbytdx5ulpxxyabye73xsyymf6qoykujwymy4nwyigg4zp6qd2lmxzad.onion`), same JWT
keystore. No fresh keys, no fresh database. Verified post-cutover:
`/api/v1/blobs` responds (401 auth-required, not 404), relay onion unchanged,
account count unchanged, Tor bootstrapped 100%. See `.l00prite/ledger.md` for
the full record.

One snag was caught and fixed during the cutover: because zitrone's i2pd config
names its keyfile `zitrone-relay.dat` (which did not exist in the volume), i2pd
generated a *fresh* I2P key on first start, changing the B32. This was restored
by copying the original `sublemonable-relay.dat` over `zitrone-relay.dat` (the
throwaway key is kept as `zitrone-relay.dat.cutover-fresh-20260719.bak`), so the
live I2P destination is back to `y5ac5…b32.i2p`, matching `.env`. See the I2P
bullet under "Why renaming is destructive" above.

The onion-site APK update (serving `zitrone-v0.7.6-beta.apk`) is tracked
separately — the signed 0.7.6 APK is built and signature-verified on CX33, not
rebuilt on CX23.
