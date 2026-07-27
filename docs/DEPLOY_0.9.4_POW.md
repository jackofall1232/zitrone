# 0.9.4-beta registration PoW — deployment runbook

**Nothing here is flipped yet.** This records the order and the decisions so they are made
now rather than under time pressure at deploy time. Every step needs explicit human approval.

The ordering exists because of one asymmetry: **a 0.9.3 client against an enforcing relay
fails registration immediately.** Burn testing re-registers on every cycle, so if the flag is
flipped early it surfaces at once and looks like a burn regression rather than a deploy
mistake.

## Sequence

| # | Step | State |
|---|---|---|
| 1 | CX23 Part 1 urgent relay fixes (`cx23/urgent-8443-and-ratelimit-interim`) | **done — deployed and verified** |
| 2 | PoW relay code merged + deployed with `REGISTRATION_POW_ENABLED=false` | pending |
| 3 | 0.9.4 client built here and released | pending (client code landed; not cut) |
| 4 | All test devices fresh-installed to 0.9.4 | pending |
| 5 | **Only then** flip `REGISTRATION_POW_ENABLED=true` | pending |

Step 2 is inert against 0.9.3 clients by construction: `Register` only checks a proof when the
flag is on, and the challenge endpoint is served unconditionally (issuing a challenge nobody
uses is harmless). The 0.9.4 client also tolerates a relay that 404s the challenge endpoint,
so steps 2 and 3 are not order-coupled to each other — only both before step 5.

**Do not do step 5 until the Argon2id constants are measured.** They are still
`TODO(pow-calibration, unmeasured)`; see `REGISTRATION_POW_CALIBRATION.md`. The shipped
placeholder `REGISTRATION_ARGON2_DIFFICULTY_BITS=8` is measurably far too high and would make
every registration on a slow device take tens of seconds.

## Decision: merge the CX23 branches normally — do NOT cherry-pick onto the deployed SHA

Both CX23 branches are based on main's tip, so merging one moves the relay's checkout forward
to main, bringing the l00prite reorg and the repo-wide agent config with it. That is fine, and
it is the better of the two options.

**Verified, not assumed:**
- The last commit touching `server/` on main is `2cda83a9`. There are **60 commits** on main
  since it, and **none of them touch `server/`**.
- `docker-compose.yml` declares `build: ./server` — the Docker build context is the `server/`
  directory *only*. Commits outside `server/` therefore cannot affect the built image at all,
  regardless of how many there are.

So moving the checkout to main changes the relay binary's inputs by nothing. The l00prite
files and agent config are inert on the relay: documentation and tool configuration, never
executed by the server process.

**Why not cherry-pick.** Cherry-picking the PoW commit onto the deployed SHA produces a relay
checkout that corresponds to *no commit on main*. "What is deployed?" then stops being
answerable from git, and the next deploy has to reconcile a divergence that only exists
because of this one. The whole point of the four-file compose correction (see
`RELEASING_RELAY.md`) is that silent drift between the recorded state and the real state is
this project's recurring failure mode — deliberately creating a new instance of it to avoid
60 provably irrelevant commits is the wrong trade.

**Precondition HoboJoe must check first:** that the CX23 checkout is clean and actually at the
SHA assumed. If it has local modifications, stop — that is a different problem and pulling on
top of it will compound it. CX33 cannot verify this; it has no access to CX23.

## Deploy commands

Use the **four-file** invocation with `-p sublemonable`. Three files is wrong and would point
the relay at an empty database — see `RELEASING_RELAY.md`, which also carries the post-deploy
continuity check. A healthy container and a 200 from `/healthz` are **not** evidence the data
is there.

```bash
docker compose -p sublemonable \
  -f docker-compose.yml -f docker-compose.tor.yml \
  -f docker-compose.i2p.yml -f docker-compose.continuity.yml \
  up -d --build server
```

## Step 5 preconditions, all of them

- [ ] Argon2id constants measured on a Revvl 6x in battery saver and updated (not placeholders).
      **The measurement channel exists as of the 0.9.4 client:** every solve runs through an
      instrumented recorder that writes per-stage timings, work counts, the parameters used,
      battery-saver state, and foreground/backgrounded state to Settings → Diagnostics (`pow:`
      lines) — on success AND on abort. One registration attempt on the device yields the
      number; read it off the Diagnostics screen.
- [ ] **The relay env pins all four PoW parameters to the values the 0.9.4 client ships**
      (`RegistrationPow.DEFAULT_PARAMS`): the challenge token carries no parameters, so client
      and relay agree by configuration only, and a mismatch on any of the four silently
      rejects every proof once the flag is on. The client currently ships
      `REGISTRATION_HASHCASH_DIFFICULTY=20`, `REGISTRATION_ARGON2_TIME_COST=1`,
      `REGISTRATION_ARGON2_MEMORY_KIB=19456`, `REGISTRATION_ARGON2_DIFFICULTY_BITS=4`.
      **D=4 is a first calibration attempt, not a measured value** — but note the relay
      config's *default* for the difficulty is still the D=8 placeholder, so the env var must
      be set explicitly; do not rely on the default. If the device measurement moves D, client
      and env move in lockstep.
- [ ] `REGISTRATION_CHALLENGE_SECRET` set to a base64 key ≥32 bytes — config fails closed at
      startup without it when the flag is on, which is the desired behaviour, but find that out
      before the flip rather than during it
- [ ] Argon2id verification concurrency bounded (see the calibration doc's finding 1 — unbounded
      concurrency at ~19 MiB per verification is an OOM vector, and relay OOM is a full outage)
- [ ] 0.9.4 released and **every** test device fresh-installed
- [ ] A rollback decided in advance: the flag is env-only, so reverting is a config change and a
      restart, not a redeploy

## Rollback

Set `REGISTRATION_POW_ENABLED=false` and restart the server with the same four-file
invocation. No client change is needed — 0.9.4 clients send a proof the relay simply stops
checking.
