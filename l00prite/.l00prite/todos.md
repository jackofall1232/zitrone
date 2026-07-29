# Zitrone — open TODOs (as of 2026-07-26, 0.9.3-beta shipped: Pucker Burn complete)

> Lives at `l00prite/.l00prite/todos.md` (TRACKED in-repo, new nested layout). The prior 0.8.1-era
> list is archived verbatim at `todos.0.8.1.md`. Deep review detail: `ledger.md` +
> `/root/l00prite/zitrone-vault-ledger.md` (local).

## l00prite scaffolding (this session)
- [x] Migrated zitrone to the new nested `l00prite/` layout (payload under `l00prite/.l00prite/`,
      root pointers + vendor adapters, fully TRACKED). Old flat `.l00prite/` retired (backup at
      `/root/l00prite/zitrone-l00prite-premigration-backup`). Memory repopulated to current state.
- [x] Added the `security-review-loop.md` prompt to `l00prite/.l00prite/prompts/` + the prompt index
      (PR #52 `b8eb652` / PR #53, merged). It drove PR-2's paired-blind loop to clean convergence.


## ✅ CLOSED 2026-07-27 — synthetic relay account surviving account-delete / Pucker Burn is NOT a gate

**Maintainer ruling. This was tracked as a merge gate for U3; it is closed, not deferred.**

**The argument.** After a burn, decoy traffic is pointless (there is no real traffic left to hide)
and real traffic can no longer reach the device (the vault is gone). So a surviving synthetic account
protects nothing and exposes nothing.

**Two things make it airtight rather than merely pragmatic:**

1. **It is strictly dominated by an exposure already disclosed and accepted.**
   `SECURITY_MODEL.md:628` states plainly that *a burn is device-local and does not delete your
   account on the relay.* The REAL account survives a burn. The synthetic one holds strictly less —
   an `accounts` row with an identity public key and nothing else, no message history (envelopes are
   deleted on ack), no linkage (`delivery_receipts` carry only `SHA-256(message_id)`), and no request
   logs by design. If the real account surviving is acceptable, the synthetic one is *a fortiori*.

2. **Post-burn it is unaddressable.** The synthetic account's id lived only inside `TAG_DECOY`, in the
   wiped vault. An adversary holding the burned device cannot name the account, so cannot query it,
   cannot link it to the user, and cannot use it to count vaults. It is not merely inert — it is
   unreachable.

**One documentation consequence, for U6 — not a gate.** The existing disclosure says *your account*
(singular) survives a burn. Once cover traffic ships that becomes *your account and the cover-traffic
account it created for that vault.* One line, and it belongs with U6's `SECURITY_MODEL.md` work
alongside the dead-air disclosure. Same class as the 0.9.3 burn-scope correction, which had to fix
exactly this shape of claim once already.


## 🚚 CX23 TRIP — RUN 2026-07-29. (b), (c), (d) CLOSED; (a) relay half done, client half owed.

Grouped deliberately: each needs the same access and CX33 has none, so batch them rather than paying
the access cost four times. The trip was made on 2026-07-29 directly on CX23.

**Everything below is DEPLOYED on CX23 and PUSHED** — the branches are named per item. Merge order
matters: `cx23/per-client-rate-limit-keying` is STACKED on `cx23/relay-attribution-for-main` (its
config hunk sits on top of `SendRatePerMinute`), so merging (d) into main alone hits a conflict.
`cx23/0.9.4-pow-deploy` is what production runs and is a backup/audit ref — do NOT merge it, it
carries the 0.9.4 PoW deploy commits and duplicates main's own onion flip.

- [ ] **(a) RELAY HALF DONE 2026-07-29 (`8c91809` on `cx23/relay-attribution-for-main`; deployed on
      CX23 as `e25d59a`). CLIENT HALF OWED — deliberately still unchecked, because the relay half
      does NOT fix the user-visible symptom.** Client work is in flight as
      `origin/feat/0.10.1-send-failure-surfacing`, and its wire contract was verified to match the
      deployed relay exactly. **0.10.1 is inert until `8c91809` is merged**, and a redeploy of prod
      from `main` before that reverts attribution to nothing. Original finding follows.
      **(a) `onServerError` SURFACES NOTHING TO THE USER — a LIVE DEFECT IN SHIPPED CODE, not a decoy
      concern.** *(Wording corrected 2026-07-28: the method is no longer literally empty — U3 fix
      round 6 routes `rate_limited` to the cover-traffic yield — but **not one thing here is fixed by
      that**. It is a cover-traffic signal, not error handling, and the user-facing half below is
      untouched and still needs the relay.)*
      **Every server rejection is still silently swallowed.** A rate-limited or otherwise-rejected send leaves the message displayed as
      `SENDING` forever: not marked failed, not retried, no error surfaced. **Users currently have no
      way to know a send failed.** This predates decoy traffic and is worth fixing on its own merits.
      **Fix:** carry the message id on `rate_limited` (and other per-message rejections) so the client
      can attribute and retry. Relay + client.
- [x] **(b) DONE 2026-07-29 — budget RAISED, not exempted** (`8c91809`, deployed). `sendLimit`
      100→**200/min**, now tunable via `SEND_RATE_PER_MINUTE` without a rebuild. Cover frames are
      deliberately NOT exempted: distinguishing them needs either a client-set flag (a client could
      mark everything cover and escape the budget) or a relay-side record of which account is whose
      synthetic peer — a STORED linkage the relay must not hold. Spec §6.2a item 3 sanctions
      "exempting **or raising**". Note (b) and (d) are independent: `sendLimit` keys on the
      authenticated account (`hub.go:174`), so it was never part of the (d) bucket collapse.
      Original finding follows.
      **(b) Cover traffic halves the account's send budget** — decoy-scoped, unlike (a). `sendLimit`
      is charged to the authenticated account, so a covered send costs two permits. **Exempt or raise
      the budget for cover frames.**
      **⚠️ NO LONGER THE ONLY FIX, and the "UNSOUND" ruling is WITHDRAWN (U3 fix round 6,
      2026-07-28).** The client side is now defended: `CoverPressure` sheds cover on the relay's own
      `rate_limited` (routed through `MessagingCoordinator.onServerError`, which used to be empty) and
      on the session's own recent frame rate, so cover contributes at most ~20 frames to any minute
      and at least 60 of the nominal 100 stay free for real sends. The old ruling — *a client assuming
      100/min against a relay configured lower inverts the priority it claims to guarantee* — is
      correct **of a headroom policy**, which must predict the limit; it does not touch a **reactive**
      one, which needs no number at all. This item is now an improvement (cover frames should not cost
      the user's budget at all), not a defect gate. **Does not block U3.**
- [x] **(c) DONE 2026-07-29 — the onion serves `zitrone-v0.10.0-beta.apk`** (was advertising
      v0.8.2-beta). Staged binary verified against the release-cut ledger sha256
      `fa183f30…c877db` BEFORE `SHA256SUMS` was written, not derived from whatever sat in the
      directory. `SHA256SUMS` had also listed a `v0.9.3-beta.apk` that was never staged on that box.
      Both mirrors (public + secret) serve it; 0.7.6/0.8.0/0.8.2 remain downloadable.
      Original finding follows.
      **(c) Onion mirror staging** — the next artefact the onion serves is 0.10.0 (0.9.4 never will;
      see RELEASE STRATEGY). Forward check at publish time, not a stale-APK defect any more.
- [x] **(d) DONE 2026-07-29 (`88078cc` on `cx23/per-client-rate-limit-keying`, deployed) — AND IT
      WAS TEN CALL SITES, NOT ONE.** This is the part worth recording more than the fix: P2 is
      written up as *registration* keying, but `c.IP()` was the limiter key at **register,
      challenge, drops (×2), the relay drop path, QR drops (×3) and blobs (×2)** — all ten collapsed
      to one global bucket behind Caddy, so any single client could exhaust the limit for everyone
      worldwide. On `main` it is **nine** sites (no challenge endpoint there — main has no regpow).
      Route (ii) was taken, plus two things neither route specified:
      - **Trusted-peer gate.** X-Forwarded-For is consulted ONLY when the socket peer (unspoofable)
        is a configured trusted proxy. **Verified empirically on the box:** Caddy reaches the
        container through the published port and arrives as the bridge **gateway 172.18.0.1**, while
        the Tor/I2P sidecars are containers at **172.18.0.x, x≠1**. That distinction is what makes
        it safe. `TRUSTED_PROXY_IPS` takes **EXACT IPs only — CIDRs are rejected**, because
        `172.18.0.0/16` would trust the sidecars and reopen the full bypass.
      - **Keys are HMAC'd under a per-process salt, and this is not incidental.** Drops and QR drops
        are unauthenticated precisely so no sender identity exists anywhere, and blob redeem is
        unauthenticated so a fetch cannot be linked to an account. Keying those on a raw client
        address would hand the relay a stable per-client identifier it does not currently hold — a
        privacy regression traded for a rate-limiting fix. Hashing keeps per-client buckets while
        the limiter holds opaque values that cannot be correlated across restarts.
      Empty `TRUSTED_PROXY_IPS` = pre-existing behaviour, so a stale value is inert — and because
      that degrades INVISIBLY, startup logs the mode (`rate limiting: per-client keying active…`).
      Still does **not** help Tor/I2P (one bucket per sidecar); registration PoW remains the answer
      there. `prekeyLimit` was already keyed on account id and was left alone.
      **Evidence:** vet + full suite + gofmt clean, 5/5 mutations discriminated. NOT measured against
      the live limiter — probing `/api/v1/register` would consume the very bucket at issue.
      Original finding follows.
      **(d) CX23 P2 — non-IP registration keying. NOW UNBLOCKED.** The precondition is answered:
      **Caddy APPENDS `X-Forwarded-For`** (no `header_up` override), so `ProxyHeader` is unsafe as-is.
      Two viable routes: `header_up X-Forwarded-For {remote_host}` in the Caddyfile so Caddy
      overwrites and the header becomes trustworthy, **or** last-hop parsing server-side (take only
      the element Caddy appended). Neither helps Tor/I2P, which collapse via the sidecars regardless —
      registration PoW is the per-client cost there.

## 🗺️ RELEASE STRATEGY — recorded 2026-07-27 (maintainer). Read before planning any unit.

**The "-beta" version labels are a deliberate hedge, not a maturity claim.** Everything shipped so
far is, by the maintainer's own assessment, **alpha**. They were labelled `-beta` from the start so
the project could **flip to a genuine beta at any moment** if a deadline made that necessary — the
vault was uncharted work with no reference implementation anywhere, so its schedule was genuinely
unknowable. The label bought optionality; it was never a statement about readiness.

**The plan, and the explicit anti-scope-creep boundary:**

| Release | Role |
|---|---|
| 0.10.0 | decoy traffic (this unit chain) — **first version that will be served to the onion** |
| 0.11.0 | **the polish round** — UI/UX, and the most detailed such pass the project has had. **THE FINAL ALPHA.** |
| → then | **flip to a TRUE beta: a V1 stable candidate, distributable for real testing** |

**0.9.4 will never be served to the onion.** The next artefact the onion sees is 0.10.0, possibly
0.11.0. This *retires* the "onion mirror serves a stale APK" item as a defect — it is not stale, it
is simply not the artefact being published — but see the note under ONION below for what still needs
checking when 0.10.0 does go out.

**Platforms: Linux and iOS are on the back burner** until after V1 Android testing. Android is the
security reference client and carries the release. Do not open work on the other platforms; that is
the scope creep this boundary exists to prevent.

**⚠️ ONE HONESTY ITEM THIS CREATES, for the maintainer to rule on.** The artefacts are labelled
`-beta` while the project considers itself alpha. Internally that is understood; **externally a
reader takes "beta" as a maturity signal**, and this project's standing rule is that a claim
overstating readiness is a defect regardless of intent. The version strings need not change — but
`README.md` / `AUDIT.md` / release notes should say plainly that these are pre-beta builds, so the
label and the prose do not disagree. It resolves itself at the 0.11.0 flip, when the label becomes
true; the exposure is the window before then. Same class as the four overclaims corrected in
`96982421`, arriving from a different direction.

## ✅ DONE — 0.9.4-beta: REGISTRATION PROOF-OF-WORK.
> **REAL-WORLD REVIEW COMPLETE 2026-07-27 — PASS** (maintainer). The independent branch review that
> 0.9.4 shipped without, recorded at the time as a deliberate call, is now **paid**. 0.9.4 is closed.
> It will **not** be served to the onion; the next onion artefact is 0.10.0 (see RELEASE STRATEGY).

> **STATUS 2026-07-26 (CX33 session).** Client code landed on LOCAL branch
> `feat/0.9.4-registration-pow-client` (4 commits, NOTHING PUSHED, no version bump).
> Suite 585/0 failures, assembleDebug exit 0.
>
> **UPDATE 2026-07-27 (`d6b12587`):** the solve is now WIRED into registration through an
> instrumented recorder — `pow:` lines (per-stage timings, work counts, params used, battery
> saver, foreground/backgrounded) land in the Diagnostics screen on success AND abort, so one
> registration attempt on the Revvl 6x returns the real number without adb or the gradle
> harness. Client ships `DEFAULT_PARAMS` D=4 — a FIRST CALIBRATION ATTEMPT, not a measured
> value; `TODO(pow-calibration)` stands. Relay env must pin all four params at flip time
> (runbook step-5 precondition; relay config default is still the D=8 placeholder). Still
> pending on this track: solve-layer UI wiring (pitcher screen + foreground service are built
> but unwired), independent review of the whole client branch, then the cut.
>
> **UPDATE 2026-07-27 (`3b0719ed`) — solve-layer UI wiring DONE.** The `test-pow-d6b12587`
> cut came back device-tested good (maintainer), and the pitcher is now wired:
> MessagingCoordinator produces `RegistrationPowUiState` (fraction from the solver's sink
> only; 1s ticker owns elapsed/60s-prompt/backgrounded via pure host-tested
> `registrationPowTickState`); SessionUi composes `RegistrationPowScreen` during real account
> creation only. "try later" aborts via stop(); COMPLETE retired at session-up; failed
> attempts drop the overlay instead of freezing a full pitcher. Suite 598/0, assembleDebug
> exit 0. The PoW FOREGROUND SERVICE stays deliberately unbuilt (BACKGROUNDED is lifecycle
> detection; the softened copy doesn't overclaim). Before the cut: `3b0719ed` is NOT in the
> tested binary — the cut build needs a device smoke pass (fresh install → pitcher →
> registered); read back the Revvl 6x `pow:` lines for calibration; independent review of
> the whole branch; relay params pinned at flip.
>
> **BLOCKER CLEARED 2026-07-27 (`2db67d0b`): the Argon2id constants are MEASURED — D=5.**
> The maintainer ran the test cut on the Revvl 6x (battery saver + foreground) and the
> `pow:` lines came back: SHA-256 0.63 MH/s, Argon2id 36.7 ms/eval at 19 MiB/t=1. Calibrated
> on rates, not the lucky 982 ms draw (~0.43× expected work on both stages). The d=20
> pre-stage is ~1.7 s on-device (over half the solve), so the ~3 s floor target applies to
> the WHOLE solve → D=5 (~2.8 s expected in saver, ~5% tail ~8 s, attacker ~0.85 s/account).
> `TODO(pow-calibration)` resolved everywhere; runbook step-5 pin is now
> `REGISTRATION_ARGON2_DIFFICULTY_BITS=5` (relay default is STILL the D=8 placeholder — set
> the env explicitly). Finding recorded: phone pays 16× on SHA-256 vs 1.6× on Argon2id
> relative to the server core; rebalance (d=18 + D+1) is a future candidate, not this cut.
>
> Done: relay-side cost MEASURED across the full m×t sweep (`docs/REGISTRATION_POW_CALIBRATION.md`);
> client solver + challenge fetch + identity-key binding + debug difficulty override;
> cross-implementation agreement between libsodium and Go x/crypto/argon2 VERIFIED by pinned
> vectors (not assumed — a disagreement would silently reject every proof); UI contract +
> functional stub (`ui/components/REGISTRATION_POW_UI_CONTRACT.md`, written to be read cold by
> Fable); deployment runbook + CX23 branch-base decision (`docs/DEPLOY_0.9.4_POW.md`).
>
> Findings that did NOT need the phone: the shipped placeholder
> `REGISTRATION_ARGON2_DIFFICULTY_BITS=8` is far too high (256 expected evals = 5.9 s on a
> 4-core SERVER; likely landing zone D=4–5). The SHA-256 pre-stage does not protect Argon2id
> from a GPU attacker, so the real DoS defence is rate-limited issuance plus a CONCURRENCY
> SEMAPHORE on verification **that does not exist yet** — unbounded concurrency at ~19 MiB per
> verify is an OOM vector. Solve time is geometrically distributed, so UI progress can
> legitimately exceed 100%.
>
> Also on this branch: BurnSetupDialog now qualifies the burn's scope (device-local; the relay
> account survives), which was the 0.9.3 docs correction's open in-app item.
>
> Separate LOCAL branch `docs/four-file-compose-correction` (1 commit): the recorded THREE-file
> compose invocation was WRONG — production needs FOUR files with `-p sublemonable`, or the
> relay comes up on an empty `zitrone` DB while looking healthy.

### Original spec brief (below) — decisions 1–8 remain settled.

**PROBLEM.** `/api/v1/register` is rate-limited 5/hour keyed on `c.IP()`, which resolves to Caddy's
socket address (no `ProxyHeader` configured), so **every clearnet client worldwide shares one global
bucket**. Tor and I2P collapse identically via their sidecars, regardless of exit node. At 2
registrations per user (slot A + slot B) that is **2 users per hour worldwide**. This blocks any
public beta.

IP-keying **cannot** be fixed for overlay transports at all — the sidecar collapse is structural.
Proof-of-work is transport-agnostic, does not depend on network identity, and does not penalise
Tor/I2P users for the transport they chose.

### ⚠️ PREREQUISITE — ANSWERED 2026-07-26. **This is NOT greenfield.**
A complete, shipped, cross-platform hashcash PoW already exists and is reusable:
- **`server/internal/pow/pow.go`** — `Verify(challenge, nonce, difficulty)` +
  `HasLeadingZeroBits`, `NonceBytes = 8`. SHA-256 over `challenge || nonce`, leading-zero-bits
  difficulty, fail-closed on negative difficulty. Has its own `pow_test.go`.
- **Config** `DROP_POW_DIFFICULTY` (`config.go:42,76`), default **20**, clamped non-negative.
- **Call sites** `drops.go:61`, `qrdrops.go:111` — deposit admission control.
- **Android solver** in `crypto/LemonDropCreate.kt` (`POW_DIFFICULTY = 20`, ~1M hashes), plus a
  **TypeScript** implementation (`packages/crypto/src/deaddrop.ts` `DEFAULT_POW_DIFFICULTY`).
- Tor's own onion-service PoW (0.4.8+) is circuit-layer and **not ours** — confirmed, no reusable
  code from there.

**Three consequences for the spec, none of them cosmetic:**
1. The existing scheme **already binds work to a challenge** ("the challenge is the drop ID, binding
   the work to one specific deposit so it cannot be precomputed or replayed across drops"). Settled
   decision 4 (bind proof to the identity key) is the SAME pattern, already proven in production —
   reuse the shape, do not reinvent it.
2. The OPEN QUESTION on a SHA-256 pre-stage is now much cheaper than it looked: the pre-stage would
   be `pow.Verify` verbatim, already written, already tested, already implemented on both clients.
3. **Difficulty 20 ≈ 1M hashes is a real shipped calibration point** for what a phone tolerates on
   this codebase. Start measurement from there rather than from zero.

### SETTLED DESIGN DECISIONS (do not relitigate)
1. **Argon2id, not SHA-256** for the main stage. Already in the app (no new dependency), memory-hard
   so a phone and rented attacker hardware are closer in cost. `p=1` per the locked vault decision,
   for cross-platform determinism. **Parameters WILL DIFFER from vault derivation** — different
   purpose (seconds on a phone, not maximum brute-force resistance). **State this explicitly in
   source so nobody later "harmonises" them.**
2. **Server-issued, HMAC'd, short-lived challenge.** Registration becomes two round-trips: request
   challenge, submit proof. The challenge carries its own timestamp and is HMAC-signed by the
   server, so verification is **stateless** — no challenge table, no state to exhaust.
3. **Cheap-reject before expensive verify.** The relay MUST verify the challenge HMAC and expiry
   BEFORE any Argon2id work. This is the DoS defence: garbage costs microseconds, not memory-hard
   verification. Rate-limit challenge ISSUANCE as the second layer.
4. **Proof binds to the identity key** being registered, so a solved proof cannot be replayed across
   registrations or farmed in bulk ahead of time.
5. **Difficulty floored on the Revvl 6x IN BATTERY SAVER** — the honest worst realistic case.
   **Measure, do not assume:** Android throttles budget SoCs aggressively and registration often
   follows install while the device is still busy. Do NOT tune to a flagship.
6. **No hard fail.** PoW is a computation that completes, just slowly on weak hardware. Failing it
   at a timer discards completed work and gains nothing. User-controlled exit instead.
7. **Debug-build difficulty override**, so burn testing does not cost a PoW wait every cycle.
8. **SHA-256 pre-stage before Argon2id — SETTLED 2026-07-26** (was an open question; closed once the
   prerequisite check showed the primitive already ships). **The verification ladder is:**
   1. **HMAC'd challenge** — verify signature + expiry. Microseconds. Rejects all garbage.
   2. **SHA-256 pre-stage** — `pow.Verify`, the EXISTING production primitive. Also cheap.
   3. **Argon2id** — only for submissions that cleared both.

   **Why it flipped:** the pre-stage was questionable when it meant a new implementation, and is
   clearly worth it when it is reuse of a production-proven primitive already written, tested, and
   implemented on server, Android and TypeScript. **The only cost is protocol surface — which was
   already being paid for the two-round-trip challenge flow regardless.**

   **The gap it closes:** challenge issuance is unauthenticated, so an attacker holding a VALID
   challenge could otherwise force memory-hard Argon2id verification with wrong proofs. With the
   pre-stage, they cannot force memory-hard work without doing real work first. That no longer
   depends on challenge-issuance rate limiting being tuned exactly right — which, given that
   mis-tuned IP-keyed rate limiting is the entire reason this unit exists, is the right place not
   to rely on a limiter.

### UX (settled)
- Progress driven by **actual hash count**, not a spinner. Lemon-squeezing-into-pitcher SVG; pitcher
  fill tracks real progress.
- Primary copy: *"proving your device is real so we don't need your phone number"* — true, and the
  audience is privacy-literate enough to value it.
- Subline: *"you have to squeeze a few lemons to get lemonade."*
  **⚠️ This copy implies seconds, not minutes. It is COUPLED to the difficulty setting — if
  difficulty rises, the copy becomes a small lie.** Re-read it whenever difficulty changes.
- **At 60s:** non-blocking prompt — *"this is taking longer than expected — your device may be in
  battery saver or under heavy load. Try again with the app in the foreground, or plugged in."*
  Options: keep waiting, or try later.
  - **"Keep waiting" MUST NOT restart the work.** The prompt surfaces over a still-running loop.
  - **"Try later" must abort cleanly** — no half-created identity, no consumed challenge, nothing
    the next attempt trips over.
- **Slow path:** foreground service so the user can background the app and be notified on
  completion. Requires a persistent notification (which doubles as progress).
  **⚠️ Disclosure to state, not hide:** this is a NEW persistent-notification surface on an app that
  otherwise has none — "Zitrone is running" in the shade discloses the app is installed and active.
  Acceptable, but say so.
  **⚠️ Also:** battery saver throttles background work HARDER than foreground, so the device where
  this matters most may benefit least. **Measure.**

### REJECTED, with reasons — do not revisit without NEW information
- **Device fingerprint / MAC keying** — client-supplied therefore forgeable; Android returns
  `02:00:00:00:00:00` for MAC since Android 10 so it is unavailable anyway; and a stable device
  identifier would let the relay **correlate slot A and slot B, breaking vault independence**.
- **Range/subnet keying** — meaningless until `ProxyHeader` is fixed (one apparent IP = one range),
  and afterwards CGNAT groups large numbers of unrelated mobile users. Viable only as a loose
  SECOND layer behind per-IP, never instead of it.
- **Clearnet fallback after N PoW failures** — an escape hatch reachable by FAILING the check is the
  check being optional; an attacker fails twice deliberately. Also **deanonymising**: routing a Tor
  user to clearnet because their device is slow sends their real IP at the moment they were most
  trying to avoid it.
- **Easier puzzle on third attempt** — same rule, same reason.
- **"Your device is too old" messaging** — a guess presented as a diagnosis. At 60s the cause is
  unknown (thermal, battery saver, load, or genuinely old hardware). **Never state a verdict you
  cannot back.**
- **RandomX** — enormous overkill for a one-time gate, heavy native dependency.

### STANDING RULE FROM THIS DESIGN (generalise it)
**An escape hatch reachable by failing the check is the check being optional.** The exit must be
gated by something an attacker cannot satisfy.

### OPEN QUESTIONS — decide at spec time, do not assume
- ~~Hybrid SHA-256 pre-stage~~ — **SETTLED, see decision 8 above.** No longer open.
- **Argon2id parameters (memory, iterations) — THE MAIN OPEN SIZING DECISION.** Server verification
  cost is real and scales with them; size for tolerable relay cost at expected volume.
  **Explicitly NOT answered by the prerequisite check:** difficulty 20 calibrates the **SHA-256**
  stage, not the Argon2id one. There is no shipped Argon2id-as-PoW data point in this codebase, and
  the vault's own Argon2id parameters are the wrong reference (different purpose — see decision 1).
  This needs its own measurement on both sides: client cost on a Revvl 6x in battery saver, and
  relay verification cost at expected registration volume.
- **Does slot 0 (burn credential) register with the relay?** — **ANSWERED: NO.** Arming seals slot 0
  in place with the payload staying filler-sized and no DEK written, and a slot-0 match returns
  `Burn` (wipe) rather than opening a session — so it never registers. **Onboarding is 2
  registrations, not 3.** But see the separate finding below, which is the thing that question was
  circling.
- **Consequence for a device that genuinely cannot complete in reasonable time** — is that user
  simply unable to use the app? Belongs in `SECURITY_MODEL.md` alongside the platform-honesty tiers
  as a **known consequence, not a surprise**.

### ⚠️ SEPARATE FINDING, independent of PoW — surfaced while checking the slot-0 question
**A burn does not delete the relay account.** Verified from source: the burn plan never calls the
relay (zero `deleteAccount`/`api.delete` in `runBurnPlan`), which matches the locked Q1 decision
"wipe LOCAL-ONLY (no relay delete)". Locally the account credential IS destroyed —`accountId` lives
in `PREFS_AUTH` (`zitrone_auth.xml`, `AuthStore.KEY_ACCOUNT_ID`), which the burn wipes and the gate
asserts absent.

So after a burn the device is a fresh install, **but the account persists server-side**: its
identity key and prekey bundle remain registered and remain servable to peers, and a contact can
still send to it. That is a server-side trace of the thing the burn exists to eliminate, and it is
arguably an oracle (an account that never again sends or receives is distinguishable from a live
one).

**Not necessarily a defect** — the relay is zero-knowledge, holds no linkage, and does no request
logging, so the account is not obviously tied to a person or device. But it was **not disclosed
anywhere**, and "returns the app to a fresh install" in the 0.9.3 release notes and
`SECURITY_MODEL.md` could be read as covering it.

- [x] **DISCLOSURE SHIPPED 2026-07-26**, merged immediately rather than bundled into 0.9.4, because
      it is a claim correction on something already published. `SECURITY_MODEL.md` gained a
      "Pucker Burn — SCOPE: what a burn does NOT reach" section; the burn-behaviour paragraph and the
      CHANGELOG 0.9.3 entry now qualify "fresh install" to LOCAL state; and the **published GitHub
      release notes for v0.9.3-beta were edited in place** with a visible post-publication correction
      rather than a silent rewrite. Wording states all three parts: all local state is destroyed; the
      relay account remains registered; the relay holds no linkage and no logs so it is not a link to
      the user, but the account's existence is a fact on the server a fresh install would not have.
- [ ] **STILL OPEN — the fix itself.** Disclosure bounds the damage; it does not remove the residual.
      Decide: leave it disclosed, or make the burn best-effort-delete the account. The latter has its
      own problem — a relay call at burn time is a network signal at exactly the wrong moment, and it
      fails closed with no connectivity. Track independently of 0.9.4; it is a deniability question,
      not a rate-limiting one.
- [ ] **Consider whether the in-app warning needs it too.** `BurnSetupDialog` says "everything
      Zitrone holds on this device", which is accurate and already device-scoped — but a user under
      duress may still assume the account is gone. Changing UI copy needs a release, so it was NOT
      done as part of the doc correction; decide whether it rides along with 0.9.4.

### DOES NOT BLOCK — ships separately and sooner (CX23, direct access required)
See the RELAY (CX23) section below for the full record. Both need HoboJoe.
- **P1:** port 8443 publicly reachable, plaintext, full API, bypassing Caddy/TLS.
- **P2:** widen `registerLimit` as interim; read the Caddyfile to determine whether `ProxyHeader` is
  safe — **only if Caddy OVERWRITES `X-Forwarded-For`, not appends**, otherwise clients spoof their
  own bucket, which is worse than the collapse.

## 0.9.3-beta — ✅ SHIPPED 2026-07-26 (vc19). Pucker Burn is COMPLETE and settable.

Unit S merged as PR #63 → `a961e2d7`; bump `29292309`; website flip `949ce033`.
Release **v0.9.3-beta** (prerelease), apk sha256 `db02cd09…8078`, cert `6c7f92a7…892753`
(continuity holds — installs over 0.9.2). **Human confirmed burn + collision refusal on a real
device.** Suite 574/571/0/3; all 9 CI checks green including the burn gate.

**No fresh install required this time** — IMAGE_VERSION stays 3 and Unit S changed no format
constant, so a 0.9.2 install upgrades in place. Verified against source, not carried from 0.9.2.

### What shipped
Store writer `armBurnSlot` (slot-0 seal in place, collision refusal, imageLock, refuse-while-
delete-pending, durability-gated), permanent Settings entry, acknowledged warning dialog,
MainActivity wiring, gate extended to cover the TRIGGER. No armed flag anywhere (P1 intact).

### ACCEPTED RISK, recorded because it was a real decision
Merged on explicit maintainer instruction with **two commits unreviewed**: `25e93756` (IME
keyboard fix) and `578b4967` (gate exclusion + disclosure). **Device confirmation predates both**
(it was against `fd4c301b` = `643a842b`). Neither can alter arming/burn behaviour, but no
independent eyes saw them and no human ran the exact shipping binary.

### THE LESSON TO CARRY — review scope
Rounds 1–2 were scoped to MY diff. A real security defect in the ORIGINAL unit — the burn
password fields declared no `KeyboardType.Password`, so an IME could learn a DURESS credential
into its personal dictionary — was found by the PR review bot, not by either paired-blind
reviewer, because it was outside the scope I gave them. It was the only passphrase field in the
app missing it. **Future units: review the whole unit, not only your delta.** Scoping a review to
your own change leaves the base with whatever it shipped with.

### Also worth keeping
- Gemini (PR bot) independently found the round-1 rotation defect → THREE reviewers on one
  finding, retiring the Grok "deferrable" split.
- Round 2 caught that `burnArmOutcome()` was extracted for testability while production kept an
  inline COPY — the suite pinned a helper the app never ran, so the round-1 mutation proof was
  true but beside the point. **A green suite pinning a parallel copy is worse than no test.**
- Burn gate red on `profileInstalled` was NOT a wipe defect: `obliterateLocked` unlinks a NAMED
  list and never claimed to clear `filesDir`. The gate's model of a fresh install was wrong.
  Excluded in the snapshot + disclosed in SECURITY_MODEL.md (maintainer's call).
- Gemini's i18n finding: premise FALSE vs source (`stringResource` used ZERO times app-wide).
  App-wide i18n is a real pre-existing gap → standing item below, not a Unit S regression.

### Follow-ups opened by this release
- [ ] **App-wide i18n**: all Compose UI is hardcoded English; `strings.xml` holds 8 entries and
      `stringResource` is never used. Pre-existing, not Unit S.
- [ ] **Post-hoc review of `25e93756` + `578b4967`** if you want the coverage gap closed.
- [ ] **Per-vault destruction** still NOT built (whole-image account delete only) — locked design
      in VAULT_ARCHITECTURE.md §3.4. Docs must keep saying so.

## DONE — 0.9.2-beta SECOND VAULT (slot B), Android (shipped vc18; Pucker Burn completed separately in 0.9.3, see above)
Closes the PD gap (0.9.1 shipped ONE vault). Locked: slot-B creation ONLY via the PIN/passphrase router,
NO discoverable UI. **Full decision record (REVISED 2026-07-24, supersedes the earlier double-entry/25%
version): `/root/l00prite/zitrone-vault-ledger.md` top block.** Key deltas from the earlier plan:
**OQ1 revised single→double→TRIPLE-entry + uninterrupted-sequence guard**; **NEW Pucker Burn duress
credential in reserved slot 0** (replaces rejected "N wrong passwords wipes"); **OQ2 corrected ~25%→~33%**
(blind placement now over slots 1–3, slot 0 reserved). OQ3/4/5/6 unchanged.

### Slot model: SLOT_COUNT=4. Slot 0 = burn (reserved, excluded from placement). Slots 1–3 = vault pool.

- [x] **PR-1 — ✅ MERGED** (user-approved 2026-07-24). PR #51 → squash `2de2bac` on main; all 8 CI checks
      green; remote branch deleted. **Version UNCHANGED (vc17/0.9.1-beta)** — 0.9.2 stays unbumped until the
      phase completes. Store-layer only; no user-reachable behavior change (create has no caller until PR-2).
- [x] **PR-2 — ✅ MERGED** (squash `374bd44`, PR #54, all CI green). Was: IMPLEMENTED + REVIEW-CLEAN → open →
      Branch `feat/0.9.2-vault-pr2-router` (7 commits `63b0762`..`30a6c33`), PUSHED. Units 1–4: router
      fusion + triple-entry gate + uninterrupted-sequence guard. Paired-blind security-review-loop
      (Codex+Grok) ran to **clean convergence at round 6** (both CLEAN, no Crit/High/Med, adjudicated vs
      source). Big catches: R4 deferred-`withContext`-boundary cancellation → outer-catch CE reset
      (`81def41`); R5 rotation re-entry race (process-scoped streak vs composition-local `unlocking`) →
      process single-flight `tryBeginUnlock`/`endUnlock` (`30a6c33`), mirroring onboarding's `vaultCreating`.
      2 accepted Info residuals (busy-reject timing; no post-rotation busy spinner). NO version bump.
      **NEXT: watch CI green → explicit merge call → squash-merge; if any check fails STOP + report.**
      Detail: `/root/l00prite/zitrone-vault-ledger.md` + `pr2-fix{,2,3,4,5}-review-{codex,grok}.md`.
      PR #54: https://github.com/jackofall1232/zitrone/pull/54
- [x] ~~PR-1 — FULLY REVIEW-CLEAN, awaiting merge call.~~ (merged; superseded above.) Branch `feat/0.9.2-vault-slotb-pr1` =
      `321b358`+`9ab8cb0`+`296ebc6`+`8f4545d`+`be18911`, LOCAL only, NOT pushed, no version bump. EVERY
      reviewed seam PASSED both blind reviewers (Codex+Grok): the fix round `321b358..296ebc6` and the G3
      delta `296ebc6..8f4545d`+`be18911`, all no Crit/High/Med. G3 re-review cleanups applied (`be18911`):
      KDoc wording (Codex F1), spec supersession banner (Codex F2/Grok G3-L1), null-open-arm test (Grok I2).
      Grok I1 (outer image not self-verified) = documented pre-existing residual + fundamental same-provider
      limit, not a regression. Full unit suite + assembleRelease green. Reports: `pr1-g3-review-{codex,grok}.md`.
      **NEXT: user's merge decision. Then PR-2 (router + triple-entry) or burn setup/wipe.**
- [x] ~~PR-1 initial (321b358) — both reviewers REJECT → superseded by the 9ab8cb0 fix round above.~~ Codex+Grok blind, both NOT-merge-clean;
      full detail in `/root/l00prite/zitrone-vault-ledger.md` + `pr1-review-{codex,grok}.md`. BLOCKING:
      **B1** (Crit/High, both) — Created clears delete markers over a LIVE image → cancels A's auto-destroy
      (forensic remanence of a server-deleted account) + A's delete-reconcile; root = OQ3 "clear like
      create()" is unsafe (create clears only when image ABSENT). **NEEDS USER DECISION (reverse OQ3):**
      recommend fail-closed — refuse to create while any delete marker present. **B2** (High/Med, both) —
      dropped unlockImage re-verify INSUFFICIENT; fix = decrypt candSlot.wrapped w/ candidate master key,
      compare candKey (0 extra Argon2id). Also: F4 (Codex, Med) candKey/unlock.vaultKey wipe gap on throw;
      F6 (Grok, Low) marker-clear-fail skips payload GCM; F9 (Grok, latent) unlockWithKey accepts slot 0.
      CLEAN both: corrupt-payload asymmetry, §10.1 legacy isolation, KDF/payload timing parity, retire
      can't delete v3. Spec §5 wrapped-GCM table corrected (1→5; test was right). NEXT: user rules on B1,
      then one fix commit (B2+F4+F6+F9) → re-review. NO push/merge/version bump without approval.
      `VaultImageStore.attemptUnlockOrAdd(...)`, BURN-AWARE. Outcomes {Unlocked, Burn(slot-0), Created,
      Rejected}. tryPassphrase ONCE incl. slot 0; unconditional 5th candidate seal + 1×256KiB GCM parity;
      blind placement 1–3 ONLY; create builds VaultOpen directly (no unlockImage verify — review must
      give an explicit VERDICT on sufficiency, amendment 2); reuse DEK/atomic-write/dirSync; clear stale
      markers like `create()`. Companion: `create()` places A in 1–3.
      **BLOCKING + IN-SCOPE: IMAGE_VERSION 2→3**; `open()` gains a known-old-version branch (v2 →
      onboarding, NOT CorruptImage, NOT slot-0 interpretation) + its own test; slot-0 semantics must not
      land before it. Ships despite no real users ("no users" is not a safety property).
      **Review amendments recorded:** (1) invariant 6 gets FULL marker writer/reader enumeration incl.
      mid-write crash states (rounds-13–16 discipline); (2) explicit verdict on dropped re-verify.
      After implementation: STOP, report, user dispatches review.
- [x] ~~**PR-2 — router fusion + TRIPLE-entry gate + timing parity** (design detail).~~ BUILT + review-clean;
      see the live PR-2 entry above (PR #54). Router RAM `candidateHash`/`candidateCount` with the
      uninterrupted-sequence guard implemented as specified; store-side 5-Argon2id + 256KiB-GCM parity
      from PR-1 preserved.
- [ ] **FOLLOW-UP (new, from PR-3 Unit 1 round-4 scope decision): make biometric-ENABLE atomic/idempotent.**
      The enable flow (`newEncryptCipher` deletes+regenerates the SINGLE Keystore alias → BiometricPrompt
      → seal → save the single prefs wrap) is not concurrency-safe: two overlapping enables (double-tap,
      offer-vs-Settings, rotation mid-prompt) or an interrupted enable can ORPHAN a wrap. Blast radius is
      BOUNDED and NON-security (NO repoint, NO destruction of a pre-existing valid binding, NO A/B tell, NO
      passphrase/vault brick) — so correctly kept OUT of the A-only-guard PR. **Recovery is NOT uniformly
      automatic (round-5 Codex, adjudicated correct vs source):** the key-ABSENT orphan self-heals (biometric
      unlock → `cipherForDecrypt` null → UNAVAILABLE → `disableBiometricThen` clears + re-offers), BUT the
      key-REPLACED orphan — the actual concurrent-enable outcome, where a peer's `newEncryptCipher` put a
      DIFFERENT key in the shared alias — makes `cipherForDecrypt` succeed and GCM `doFinal` fail (bad tag) →
      VaultBiometricResult.FAILED, which does NOT clear the wrap. That leaves biometric stuck failing until the
      user passphrase-unlocks + manually disables. The follow-up should (a) make enable atomic/idempotent so the
      orphan can't form, and consider (b) treating a persistent decrypt-FAILED wrap as clearable (careful: don't
      clear on a mere transient auth failure). Fix needs PROCESS-correct serialization or atomic keygen (NOT Activity-scoped — see
      failures.md: the round-3 Activity-scoped single-flight was reverted). Also fold in the disable-∥-enable
      race (disable/account-delete not synchronized with enable's seal/save). Own spec + invariant table +
      paired-blind loop. Pre-existing (predates 0.9.2); not release-blocking.
- [ ] **FOLLOW-UP (new, from the Unit W-A follow-up review — Codex LOW): no in-app exit from a
      PERSISTENT delete fault.** After W-A, `onRetryDestroy` routes to ONBOARDING only when
      `vaultProvenAbsent` (`Files.notExists` over all four image-bearing files). Destroy is idempotent,
      so retry is SAFE and a TRANSIENT fault clears — but a PERSISTENT unlink or stat fault (corrupt
      or pathological filesystem; the new test's own non-empty `vault.dek` DIRECTORY is the shape)
      keeps every retry on `Route.DeleteIncomplete`, and the app offers no other exit. **Not a routing
      defect and must NOT be "fixed" by weakening the proven-absence criterion** — fail-closed is
      correct and strictly safer than the pre-W-A onboarding it replaces. It is a PRODUCT/SUPPORT
      question: what does a user do when the fault never clears (documented app-data reset? an
      explicit last-resort action, with the deniability implications worked through? support
      guidance?). Deliberately out of scope for the W-A delta — solving it there would be scope creep
      into the release cut. Not release-blocking.
- [ ] **FOLLOW-UP (new, from the Unit W-A follow-up review — Grok INFO): stale-hold strand on the
      delete-retry path; FOLD INTO the 0.9.3 derivation work.** `onRetryDestroy` deliberately does not
      supersede `residueSweepHold` (the delete-completion callback does). The omission was justified
      with "a held boot admits no session — so hold and this path cannot coexist"; that is FALSE, and
      the comment is now corrected in place. A hold raised while an image is PRESENT routes to LOCKED
      via the image arm, and a lock screen admits an unlock → session → in-session delete → a failed
      first destroy → `DeleteIncomplete` with the hold still up. Then a SUCCESSFUL retry over a clean
      disk is reported as FAILURE for the rest of the process. Reachable only via the fail-closed
      default (cancelled boot, or a throw escaping `sweepOrphanedResidue` before gate 1) — remote,
      since the sweep's own gates return `NO_MUTATION` over a present image — and restart-recoverable.
      The fix is the 0.9.3 fold of the hold into the derivation for every consumer at once, NOT two
      more bare `imageLock` calls on the Main dispatcher at this one site. Not release-blocking.
- [ ] **OPEN GAP (2026-07-25) — only ONE PR-attached reviewer.** GitHub-Codex is out of credits;
      Gemini alone satisfies the PR gate by maintainer decision (recorded on the process branch,
      `security-review-loop.md`, as a time-bounded (c) waiver). The paired-blind loop is unaffected —
      four lenses on the delta. What is single-source is the **whole-repo view**, and Gemini has a
      documented right-conclusion-wrong-MECHANISM pattern (3 occurrences), so every Gemini finding
      must be VERIFIED against source and any wrong mechanism called out explicitly. **Restore a
      second PR-attached lens when Codex credits return, or substitute one.** This is NOT resolved by
      Gemini performing well — until it closes, every merged unit has had exactly one whole-repo look.
- [ ] **PR-3 Unit 2 (docs) — SEPARATE PR, must land AFTER Unit 1 merges.** VAULT_ARCHITECTURE §3.3/§3.4
      wizard→silent triple-entry; SECURITY_MODEL flip to "two vaults creatable" + disclosures (triple-entry/
      systematic-entry limit, ~33% blind-overwrite, biometric A-only, burn permanence deferred to burn PR
      per OQ-C). The SECURITY_MODEL "two vaults creatable" flip must NOT land before Unit 1 (else it claims a
      capability whose stated biometric-A-only safety property is unenforced). Spec: `/root/l00prite/pr3-spec.md`.
- [x] ~~**PR-3 — UI + docs (light)** (original single-PR framing).~~ SUPERSEDED/SPLIT: create-wiring
      (MainActivity no-match→create) already shipped in PR-2; biometric A-only guard (OQ4) = **Unit 1**
      (in review, above); docs (OQ5) = **Unit 2** (separate, after Unit 1, above). Enable-atomicity =
      the new follow-up above.
- [ ] **UNIT W-B — burn mechanism + completion presentation. SCOPE APPROVED 2026-07-25; SPEC NEXT,
      NO IMPL.** Scope statement: `/root/l00prite/unit-wb-scope.md` (approved with rulings A–E).
      Sources `pucker-burn-spec.md` + `burn-unit-w-invariant-table.md` are PRE-SPLIT and STALE where
      they conflict with shipped W-A code — **shipped code wins, and each staleness is corrected
      explicitly, not silently.**

      **DEFINITION OF DONE (binding):**
      1. `obliterate()` marker-free, fail-closed, keys-first (dek before bin); markers cleared
         STRICTLY last (after unlinks are proven durable); verify uses `Files.notExists`
         PROVEN-ABSENCE — **ruling C: the spec's `exists()` verify is SUPERSEDED, not deviated from.**
         `exists()` is fail-open on the one operation where fail-open is least acceptable.
      2. `destroy()` behavioural equivalence verified AGAINST SOURCE; the unlink-order change
         (bin-then-dek → dek-then-bin) named as a review item, never "identical by construction";
         `keysFirst` param is the landing spot if a reviewer rejects it.
      3. Burn NEVER writes `vault.delete-confirmed`; no burn-produced state can route to
         `Route.DeleteIncomplete`.
      4. **ONE DURABILITY OWNER WITH TWO PRODUCERS** (the boot sweep and burn's `obliterate`) — NOT a
         second hold alongside the first. A failed-but-clean burn (unlinks landed, durability
         unproven) MUST NOT present as a fresh install. **BLOCKING invariant, not a robustness
         residual.**
      5. Items #1 and #5 land as ONE change with one design: all five Main-thread disk reads
         (`MainActivity.kt` 631, 1046, 1170, 1171, 1219) folded INTO the derivation — never wrapped
         at the call sites — and the `destroySupersedesResidueHold` re-derivation + torn pair-read at
         1170/1171 removed by the same fold. Every boot-routing consumer shown consuming the single
         verdict.
      6. Coordinator extracted ("snapshot → claim → apply/ack") so apply-once is tested against
         PRODUCTION code, not a stand-in.
      7. Reachability of `completeInterruptedBurn` and `reconcileOrphanedBurnMarkers` RE-DERIVED
         against W-B's design — never restored from W-A-era comments, whose exclusion argument
         explicitly cited the absence of the duress wipe and therefore voids by its own premise.
      8. Byte-for-byte Robolectric gate green — and **ruling E: it compares the DERIVED VERDICT, not
         only files/prefs/Keystore.** SPECIFIC ASSERTIONS OWED (a gap described precisely gets closed;
         a gap described generally gets closed approximately): (a) **the burn path CONSUMES
         `wipeBiometricMaterial()`'s boolean and FAILS the wipe on false** — currently untested because
         it lives on `AppContainer`, which needs an `Application`; (b) post-burn `BootDecision` equals
         post-fresh-install `BootDecision`, hold included. "Fresh install" now has a derived-verdict precondition (no hold
         raised), so a file-only comparison would prove the wrong thing. Shadow gaps are in-test
         exclusions WITH reasons + `SECURITY_MODEL.md` lines.
      9. `SECURITY_MODEL.md` honesty pass: local-only scope, crypto-erase not NAND sanitisation,
         single-snapshot indistinguishability, burn consumes the credential.
      10. Item #4 residue: assert the sweep-hold VALUE is PRESERVED across `runDeleteRetry`, not
          merely that a raised hold yields failure. The rest of #4 shipped in `1b5f5e0`; **W-B must
          not re-do it.**

      **DIVERGENCE BOUNDARY:** robustness residuals (R2 wall-clock) may defer to a later hardening
      layer, tracked. **Anything that breaks post-burn ≡ fresh install BLOCKS** — that is the feature
      failing at its purpose, not a hardening gap.

      **PROCESS:** Rule of 6, HARD CAP, no self-reset, third lens blind at the cap, stop for the
      maintainer regardless of outcome. Single whole-repo PR lens while Codex credits are out (see
      the open-gap entry above) — front-loaded review matters MORE, not less.
- [ ] **FOLLOW-UP (W-B, demonstrated defect class): sweep for "exists only if the feature was used"
      artifacts BEYOND the burn window.** The byte-for-byte gate proves POST-BURN
      indistinguishability, not indistinguishability from never-used at ALL TIMES. An artifact created
      lazily and then correctly wiped passes the gate while still being an oracle **between creation
      and burn** — a device seized in that window discloses the feature was used. Not a hypothesis:
      the gate's first execution found the vault device-key Keystore alias surviving every burn.
      Enumerate deliberately rather than trusting the diff (the diff only catches what a burn LEAVES
      BEHIND): files, prefs KEYS, database tables, WorkManager job names, notification channels, cache
      dirs. Disclosed in SECURITY_MODEL.md as a stated limit in the meantime.
- [ ] **PUCKER BURN sibling PRs (0.9.2):** (a) burn SETUP UX — settings "Pucker Burn Password Setup"
      above "Delete Account", disappears once set, actively-acked permanence warning (3 points); (b) burn
      WIPE execution. Scope/sequencing TBD. PR-1 only makes the store burn-AWARE, not setup/wipe.
- [ ] **Destruction (per-vault): SEPARATE FUTURE PHASE.** Needs a new primitive (overwrite one
      slot+payload, keep others) — does not exist. `destroy()` stays whole-image; documented as-is.
- [ ] **OPEN (do not decide):** (1) burn wipe SCOPE — local slots only vs also relay account(s);
      conspicuous or not. (2) burn ↔ D2c delete-state-machine interaction — separate or intertwined?
      (3) 0.9.1-image incompat / IMAGE_VERSION bump (see PR-1).
- Review intensity: between D3 and D2c, LEAN per [[workflow-agent-budget-discipline]] (≤5 agents). NO
  version bump / branch cut / merge without approval.

## Prior — 0.9.1-beta vault track (PR-D) — ✅ DONE (all merged, cut live)
- [x] **D2c** — slot-A live over the vault (fresh-install, vault-only): onboarding passphrase +
      biometric unlock, session-over-vault, flush-before-ack durability, atomic contact delete,
      no-remanence account delete, render-gated lemon-drop delivery. **PR #46 MERGED @ `3c598ad`.**
      Hardened over 16 review rounds (two-marker delete state machine; durable-intent-derived
      auth guard). **D4 absorbed into D2c.**
- [x] **D3** — user-configurable idle auto-lock (device-level). **PR #48 MERGED @ `891cd32`
      (2026-07-24T01:08Z).** Configurable timeout (immediate/1/5/15 min, default 5), fires on
      ProcessLifecycleOwner background, full teardown through the SAME `UnlockController.lock()`
      (not a new writer to delete/token state), honest no-push tradeoff copy. Reviews: Grok DONE
      (0 Crit/High/Med, 3 non-blocking Low); Gemini round-1 = HIGH ANR (main-thread `synchronized`
      read in `isTerminalWipe()` behind background `lock()` drain) + MED negative-timeout label —
      both fixed in `0a17be4` (`terminalWipe` now `@Volatile`, lock-free getter; `autoLockLabel`
      `<= 0 -> "Immediate"`) + 2 tests. CI green, merged on human approval. Branch deleted.
- [x] **D5** — **DROPPED (human decision 2026-07-24).** D5 was the migration step. There are no
      real external users (author's own devices only), so **fresh-install is acceptable** — the
      migration is not built. This makes the "fresh install required" disclosure in PR-F mandatory
      and true. See [[zitrone-storage-format-stability-gate]]. (Consistent with PR-E/migrations
      also having been dropped earlier.)
- [x] **PR-F** — docs / release notes. **PR #49 MERGED to main as squash `b7e4b87` (2026-07-24).**
      Docs-only (no version bump). CHANGELOG [0.9.1-beta] w/ 3 disclosures (fresh-install,
      storage wipe-on-breaking-change, contact-deletion permanence) + honest "second vault not
      creatable → PD not usable on Android". Reconciled VAULT_ARCHITECTURE/SECURITY_MODEL/README
      present-tense-only-for-shipped. All CI green after rebase over the postcss fix.

## 0.9.1-beta — ✅ CUT + CLEARNET FLIP DONE (2026-07-24, verified live)
- [x] vc17/0.9.1-beta (commit `55540e3`); signed APK cert `6c7f92a7…892753`; GH Release
      **v0.9.1-beta** (prerelease) live; asset sha256 `6064024f…3914` == links.ts; clearnet
      `www.zitrone.app/download/beta` LIVE on v0.9.1-beta (Vercel deploy success).
- [ ] **ONION — DEFERRED to operator (do off remote-control):**
      1. **VERIFY relay onion vs CX23 `.env`.** CX33 `.env` baked
         `ytdx5ulpxxyabye73xsyymf6qoykylujwymy4nwyigg4zp6qd2lmxzad.onion`, but DEPLOYMENT.md documents
         prod as `fbytdx5ulpxxyabye73xsyymf6qoykujwymy4nwyigg4zp6qd2lmxzad.onion` — DIFFERENT. SSH read
         to CX23 (`root@178.104.19.240`) blocked by classifier + self-grant blocked. If baked onion is
         wrong, only Tor transport is affected (clearnet fallback works); rebuild + re-release to fix.
      2. **Stage APK into CX23 onion-site mirror:** `rm -f onion-site/*.apk; cp zitrone-v0.9.1-beta.apk
         onion-site/; (cd onion-site && sha256sum *.apk > SHA256SUMS)`. Built APK is at
         `/root/zitrone/zitrone-v0.9.1-beta.apk`.
      3. **Vercel apex-domain flip** (make `zitrone.app` primary, redirect `www`) so App Links verify.

## Release gate (0.9.1-beta cut + website flip) — ✅ ALL GATE ITEMS MERGED
Gate = PR-D (D2c✅ + D3✅) + PR-F✅ (`b7e4b87`) + postcss CVE fix✅ (`0d1a3dc`); **D5 DROPPED**.
main head `b7e4b87`, all green. **THE CUT ITSELF IS NOW UNBLOCKED — awaiting explicit human "cut
it" only.** Steps, all in one release commit/run on approval:
1. Bump `apps/android/app/build.gradle.kts`: versionCode 16→17, versionName 0.9.0-beta→0.9.1-beta.
2. Signed `:app:assembleRelease` (JAVA_HOME 17; keystore.properties present) → `apksigner verify
   --print-certs` MUST equal cert `6c7f92a7…892753`.
3. GH Release (tag v0.9.1-beta) w/ the CHANGELOG [0.9.1-beta] notes + APK asset + SHA-256.
4. Vercel apex (website) flip.
NOTE (hygiene, non-blocking for an OWN-DEVICE cut): fix broken semgrep SAST + release-apk.yml
shell-injection + website web-overclaim BEFORE any external tester. Phase order after cut:
P2/PR_C2 (2nd vault slot + teardown-on-switch) → P3/PR_C3 (setup wizard + destruction).
User intent recorded 2026-07-24: "at some point we need to cut 0.9.1 apk and flip website."

## Blocking CI — postcss CVE — ✅ DONE
- [x] **`postcss` 8.4.31 → CVE-2026-45623 (HIGH) — FIXED.** PR #50 MERGED to main as squash
      `0d1a3dc` (2026-07-24). pnpm override `postcss: ^8.5.12`; lockfile deduped to 8.5.15, no
      8.4.31 remains. All CI green incl. Security scanning (35s pass). Root cause was Next's
      transitive exact-pin (website app). Verified locally: frozen-lockfile + build:packages +
      website build green. (Distinct from the broken-semgrep SAST item below — different scanner.)

## Standing hygiene — owed before external testers (outside the release gate)
- [x] **CI SAST silently broken + `release-apk.yml` shell-injection — ✅ FIXED (PR #59, branch
      `feat/ci-security-hardening`).** SAST: replaced `semgrep-action@v1` (exit 0 on crash/registry-fetch)
      with a DIGEST-pinned `semgrep/semgrep` container + `--config .semgrep --error --strict` in a run: step
      (findings/config-errors/crash all fail the job); rules VENDORED under `.semgrep/` (no registry fetch) =
      official github-actions security + Go security + a local `no-run-block-interpolation` rule (flags ANY
      `${{ }}`→run, closing the derived-`steps.*.outputs.*` + multiline-span variants the upstream rule
      misses). Injection: env-var indirection for every `${{ }}`→run (zero remain) + validate-first tag gate
      + `::error::` sanitize. POSITIVE CI PROOF: a throwaway PR with a planted injection FAILED Security
      scanning (exit 1) — the gate fires in CI, not just locally. 6-round-equiv paired-blind loop → clean
      convergence round 3. No version bump.
- [ ] **FOLLOW-UP 1 (from CI-security unit, UNSEQUENCED — user prioritizes): pin all `uses: @vN` actions to
      SHAs + add Dependabot.** The now-working SAST flags `github-actions-mutable-action-tag` (a mutable tag
      can be repointed to malicious code — real supply-chain hardening). Deferred from the injection unit as
      its own unit; deliberately omitted from the current gate (documented in `.semgrep/README.md`). Pairs
      naturally with the injection fix. Not blocking.
- [ ] **FOLLOW-UP 2 (from CI-security unit, UNSEQUENCED — user prioritizes): expand SAST language coverage
      (Kotlin/TS/JS) with CURATED per-language subsets.** CONSTRAINT: the full semgrep language packs
      false-positive on the vault's CORRECT AES-GCM (`gcm-detection`) and are audit-noisy (TS alone ~244
      findings) — this needs curation, NOT a bulk enable. Do NOT suppress a rule that's flagging correct
      crypto to force a noisy pack green. Not blocking.
- [ ] **Website web-overclaim:** the site presents an undeployed web client as available. Correct
      to the platform honesty hierarchy.
- [ ] **🔒 TRIGGER-GATED — client string constants still claim Sealed Sender + continuous cover
      traffic.** The doc overclaims were corrected in `96982421`, but the same false claims remain
      in four **code** string constants:
      - `packages/protocol/src/connection.ts:55` — "Tor routing + single relay hop + Sealed Sender."
      - `apps/android/app/src/main/java/com/zitrone/app/data/ConnectionMode.kt:48` — same string
      - `apps/ios/Sources/Networking/ConnectionMode.swift:80` — same string
      - `apps/web/src/screens/Settings.tsx:152-165` — "Cover traffic" toggle, "Continuous decoy
        traffic makes a real send indistinguishable from idle"

      **THE TRIGGER (this is the point of the entry — do not soften it to "when convenient"):
      these MUST be corrected BEFORE the web client deploys, OR before the Android strings ship in
      a build — whichever comes first.**

      **Why trigger-gated and not open-ended.** Today nothing renders them: `ConnectionMode.kt` has
      zero consumers on Android, iOS never renders the description, and only
      `apps/web/src/screens/Settings.tsx:148` renders it — on a client that is not deployed. So the
      honest status is "false but invisible". **That is exactly the shape of the burn release-note
      claim**: accurate about intent, false about shipped state, and harmless *only* until something
      renders it. The moment web deploys or an Android surface reads the enum, a published client
      asserts sender anonymity it does not have. **A residual with a named trigger gets closed; one
      tracked open-endedly does not** — which is the whole lesson of the CX23 P1/P2 entry above.

      Substance of the correction: Sealed Sender is NOT implemented for ordinary messaging
      (`sender_id`/`recipient_id` cleartext; relay binds sender to the authenticated connection at
      `ws/hub.go:166`); cover traffic is NOT built on Android. See `docs/SECURITY_MODEL.md`
      (corrected 2026-07-27) for the wording to match. Folds into 0.10.0 U6, or lands earlier.
- [x] **Storage-format stability GATE — ✅ ANSWERED 2026-07-27, no longer deferred.** Full reasoning
      in `docs/design/DECOY_TRAFFIC_0.10.0_SPEC.md` §4.1. **The answer is DISCLOSE
      wipe-on-breaking-change**, and it could not honestly be anything else: migrations are not
      built, no migration framework exists, and 0.10.0 is itself a second breaking change — a
      stability promise today would be one the project has no mechanism to keep.
      **The condition that flips it to a stability commitment, stated so this is a commitment and
      not an indefinite disclaimer: a migration path exists AND has been exercised across at least
      one real format change.** Until then every release carrying a format change repeats the
      disclosure. Disclosure text is drafted in §4.1 and ships WITH 0.10.0 (release notes +
      `SECURITY_MODEL.md`); it is a precondition for external testers, not for merge.
      This gate had been deferred twice — it is closed here rather than carried a fourth time.

## RELAY (CX23) — from the 2026-07-26 429 diagnostic. PRIORITY ORDER IS AS LISTED (user-set)

All three need **CX23 access, which CX33 does not have** (see Housekeeping + constraints.md
"Box roles"). Diagnostic was read-only; nothing on CX23 was changed. Evidence: `ratelimit.go`,
`handlers.go:48/160`, `cmd/server/main.go` fiber.Config, `docker-compose.tor.yml:32`, plus
external probes from CX33 (TLS handshake, `GET /healthz` on 443 and 8443, `dig`).

> **⚠️ STATUS CORRECTION 2026-07-27 — P1 and P2-interim were FIXED on 2026-07-26 and this section
> did not say so for a full day.** Commit `20ade12b` ("fix(cx23): close 8443 to the open internet,
> widen registerLimit interim") bound the publish to `127.0.0.1:8443` and widened `registerLimit`
> 5/hr → 300/hr. Maintainer confirms it was rebuilt, redeployed, and verified on CX23; that
> deployment state is **taken on report, not independently verified** (CX33 has no SSH to CX23, and
> probing `/api/v1/register` to measure the limiter would consume the very global bucket at issue).
> The commit lived **only** on `origin/cx23/urgent-8443-and-ratelimit-interim` and was **not on
> main** — so main still carried both defects and any redeploy from main would have silently
> reverted them. Merged to main 2026-07-27 (`go build ./...` + `go vet` clean); **push to origin
> still owed.**
>
> **THE PATTERN, recorded because the record is what failed: A FIX RECORDED ONLY IN COMMIT HISTORY
> IS NOT RECORDED.** The fix was fully described — in a commit message, on an unmerged branch,
> where nothing that reads this ledger would ever see it. This section, which exists precisely to
> answer "what is the state of the relay", asserted the opposite. A later session read it, took the
> 5/hr figure as current, and built a release budget on it. The stale entry did not merely lag; it
> actively produced a wrong number in downstream design work. **This happened one day after the
> ledger-cadence rule was added, which is the point: the rule did not fail, the follow-through did.**
> Closing a finding means updating the record that tracks it in the same session, not the artifact
> that fixes it. See `failures.md` → "A fix recorded only in commit history is not recorded."

- [x] **P1 — PORT 8443 IS PUBLICLY REACHABLE OVER PLAIN HTTP. Highest priority, above the limiter.**
      **FIXED `20ade12b` (deployed 2026-07-26, on main 2026-07-27)** — publish bound to
      `127.0.0.1:8443` so only the host's Caddy can reach it. Original finding follows.
      `http://178.104.19.240:8443/healthz` returned 200 from CX33 over the open internet; the same
      app serves the FULL API there. This defeats TLS, defeats cert pinning for any client induced
      onto it, and hands an attacker their own rate-limit bucket (a direct-to-8443 peer is not
      Caddy, so it gets a distinct `c.IP()` key). Likely an artifact of Docker publishing
      `8443:8443` past the host firewall (`docker-compose.yml:26-27`) rather than a deliberate
      choice — **CONFIRM INTENT FIRST**, then close it: bind the publish to `127.0.0.1:8443` or
      firewall 8443 so only Caddy can reach it. NOTE: changing the published port is a compose
      change — three-file invocation required
      (`-f docker-compose.yml -f docker-compose.tor.yml -f docker-compose.i2p.yml`).
- [x] **P2 — FIXED 2026-07-29 (`88078cc`, deployed). AND THE SCOPE WAS WRONG: ten call sites, not
      just registration** — register, challenge, drops (×2), the relay drop path, QR drops (×3),
      blobs (×2); nine on `main`, which has no challenge endpoint. See CX23 TRIP item (d) for the
      design (trusted-peer gate + last-XFF element + HMAC'd keys) and why `ProxyHeader` was NOT
      used. **`header_up` route (i) was NOT taken** — the Caddyfile is untouched, so nothing about
      the TLS front-end changed. Original finding follows, for the diagnostic record.
      **P2 — `registerLimit` collapses to ONE GLOBAL BUCKET keyed on Caddy's socket address.**
      **~~INTERIM APPLIED, REAL FIX STILL OPEN~~ — REAL FIX NOW APPLIED.**
      - **Interim (`20ade12b`, deployed 2026-07-26, on main 2026-07-27):** widened **5/hr → 300/hr**.
        `handlers.go` now reads `ratelimit.New(300, time.Hour, ...)`. This is **relief, not a fix** —
        the key is unchanged, so it is still one bucket shared by every client worldwide. At 5/hr it
        was a wall at ~2 registrations/hour globally.
      - **✅ PRECONDITION NOW CLOSED — `ProxyHeader` is CONFIRMED UNSAFE as-is.** The blocker was
        "read the Caddyfile first; only safe if Caddy OVERWRITES rather than appends
        `X-Forwarded-For`." Answered: **Caddy's `reverse_proxy` has NO `header_up` override, so it
        APPENDS.** Trusting the header as-is would let clients spoof their own bucket — strictly
        worse than the collapse. **Do not set `ProxyHeader` against the current Caddyfile.**
      - **The real fix, now designable.** Two viable routes, either of which makes per-client keying
        sound: (i) **`header_up X-Forwarded-For {remote_host}` in the Caddyfile**, so Caddy
        overwrites rather than appends and the header becomes trustworthy, then enable Fiber's
        `ProxyHeader`; or (ii) **last-hop parsing server-side** — take only the final XFF element
        (the one Caddy itself appended) rather than trusting the client-supplied prefix. Note
        neither route helps Tor/I2P, which collapse via the sidecars regardless — non-IP keying
        (0.9.4 registration PoW is the per-client cost) remains the answer there.
      - **Registration volume is a SHARED GLOBAL RESOURCE while this stands.** Anything that adds
        registrations per onboarding spends everyone's headroom, not its own. 0.10.0 decoy traffic
        adds one synthetic account per vault (2 → 3 registrations), cutting worldwide onboarding
        capacity 150 → 100 devices/hour. Sequence non-IP keying before any announcement that grows
        volume.
      - Original finding follows, for the diagnostic record.
      `handlers.go:48` was `ratelimit.New(5, time.Hour, ...)`, keyed on `c.IP()` at `handlers.go:160`.
      **No `ProxyHeader`/`TrustedProxy` is set anywhere in the Go source** (Fiber v2.52.11 →
      `c.IP()` is the raw socket peer), and the relay sits behind Caddy, so **every clearnet user
      worldwide shares ONE bucket of 5 registrations/hour**. Tor/I2P collapse the same way via the
      sidecars — worse than per-exit-node, and Tor is NOT distinguishable to the limiter (`onion.go`
      routes by Host header; the limiter never sees it).
      - The limiter is **FIXED window, not rolling** (`ratelimit.go:50-52` returns false WITHOUT
        incrementing `count` or moving `w.start`). Retries do NOT extend the lockout — the earlier
        self-refreshing-lockout hypothesis is FALSIFIED by source. The bucket clears exactly one
        hour after the FIRST request in the window; client backoff caps at ~60s so it cannot
        outlast it. That fully explains `boot[0]` already being 429 with no preceding error.
      - **DO NOT apply the `ProxyHeader` route unverified.** It requires reading the Caddyfile first
        and is only safe if Caddy **OVERWRITES** rather than appends `X-Forwarded-For` — otherwise
        clients spoof their own bucket, which is strictly worse than the collapse.
      - Widening the limit is the cheap interim. **Keying registration on something other than IP is
        the real fix and needs design** (not a drive-by patch).
- [ ] **P3 — the KNOWN LIMIT comment is under-scoped.** `handlers.go:60-64` documents sidecar
      collapse for `dropLimit`/`blobLimit`/`qrDropLimit` only. It omits `registerLimit` and omits the
      clearnet/Caddy case — which is the one that actually bit. Fix when the limiter is touched.

### ACCEPTED COST, not a defect — the relay cannot answer "one client or many"
The relay does **no request logging** by design (`cmd/server/main.go:54` "No access logging, no body
logging — application errors only"; `handlers.go:156-158` — the client address is used transiently
for rate limiting and never stored or logged). So "was this one IP or many?" is **unanswerable by
construction**, even with CX23 access. That is the zero-knowledge property working as intended.
The cost is that this class of incident is **undiagnosable after the fact** — recorded here so it is
known before the next one rather than rediscovered. Do not "fix" it by adding access logs.

### Operational note (no production change)
The bucket self-clears one hour after the first request in the window. Pausing burn testing for an
hour restores registration without touching production. Burn testing is a plausible consumer:
`MessagingCoordinator.kt:385` registers only when `api.accountId == null`, which is exactly the
post-wipe state every Pucker Burn test produces — but this was NOT confirmed (see accepted cost).

### Also confirmed clean (2026-07-26) — no action
DNS `relay.sublemonable.com` → `178.104.19.240` (CX23), TTL 600. **Nothing half-migrated to CX-IS:**
CX-IS (89.127.235.188) appears nowhere in DNS, and the live cert SPKI
`TZbasNP1niaVV0fEtpn2QbjY1QiIS8R7w4zhaU5Yw3U=` matches `CertificatePinning.kt:50` PRIMARY_PIN
exactly (NOT CX-IS's `CEe6/ep5…`). Android's configured host matches reality. The two
`UnknownHostException`s at 12:11/12:12 fit a transient resolver failure at a TTL-600 re-resolution
boundary and are independent of the 429s. Relay is NOT behind on anything meaningful: newest
`server/` commit on main is `2cda83a9` (2026-07-21); no local branch has real pending relay work
(the three that appear "ahead" are stale pre-squash remnants). "We're on 0.9.x" is a CLIENT-version
fact — the whole 0.9.x vault/burn track is Android-side. Deployed SHA is unknowable without CX23
(compose uses `build: ./server`).

## Housekeeping
- [ ] **Reconcile the two ledgers:** in-repo `.l00prite/ledger.md` (0.7.5→0.8.1 era) vs
      `/root/l00prite/zitrone-vault-ledger.md` (0.9.x vault arc) are separate, non-overlapping
      histories. Decide on one canonical in-repo ledger going forward.
- [ ] Consider SSH-key rotation (long-standing, carried from the 0.8.x list).

## Done recently (see ledger for detail)
- 0.8.1-beta released (PR #8 + #9 merged @ `c78a606`, GH release live, website flipped PR #10).
- 0.9.x vault track P1a/P1b-1/PR-A/B/C/D1/D2a/D2b then D2c all merged to `3c598ad`.

## W-A FOLLOW-UP DELTA — ✅ LANDED as `bdde066`, follow-up round adjudicated (Codex + Grok, both READY TO MERGE)
Held out of the convergence commit `acb5904` deliberately: adding them would have made the converged
commit a new delta needing its own round. "It's only tests" is NOT a safety argument in this unit —
three test-only edits here silently destroyed coverage (dropped `@Test`, deleted row 7, defanged the
retry test). Batched into ONE delta and given ONE paired-blind round; the round's confirmed items are
in the follow-up fix commit on top. Detail: ledger, "Unit W-A FOLLOW-UP round".

- [x] Apply `/root/l00prite/unit-wa-r4-info-tests.patch` — 4 tests closing the two uncovered
      post-mutation branches (Kimi: post-unlink re-stat; Gemini: `catch (Throwable)`) + the two
      afterPublish cancellation characterisation tests. Verified: applies cleanly to `acb5904`,
      suite 487 → 491, 0 failures, 3 of 4 mutation-verified (the 4th is labelled as catching none).
      Both follow-up lenses re-ran both mutations independently: each fails as claimed.
- [x] `BootReconcileOwnerTest.kt:314` — stale docstring claiming production wraps `afterPublish` in a
      local `runCatching`; `acb5904` removed that (the wrapper contains now). Raised independently by
      Grok (INFO-1) and Kimi (LOW) — the only finding two lenses converged on. **The fix corrected 2
      of the 3 instances of this fact; the third (`ZitroneApp.kt:1172`) was caught by BOTH follow-up
      lenses and is fixed in the follow-up commit — see the binding close-out rule in failures.md.**
- [x] `MainActivity.kt` ~697-704 `onRetryDestroy` — was still `!hasVault() && !serverDeleteConfirmed()`,
      the weaker sibling of the predicate `acb5904` unified everywhere else; now routes through
      `deriveBootDecisionFromDisk()`. **Kimi's safety derivation ("reachable only via
      `Route.DeleteIncomplete`, which requires the confirmed marker; a held boot admits no session")
      is REFUTED on its second clause** — follow-up Grok, adjudicated against source: a hold raised
      while an image is PRESENT routes to LOCKED via the image arm, and a lock screen admits an
      unlock, hence a session. Remote and restart-recoverable; tracked above with the 0.9.3 fold.
- [x] `MainActivity.kt` ~1129-1130 — comment overstates: destroy's survival verify is `exists()`-based
      (proven-present only), so the required `dirSync` is the real second barrier, not the verify.
- [x] `runBootReconcile` kdoc — said "production passes `Dispatchers.IO`"; production relies on the
      parameter default.
- [ ] **SAME CLASS, TRACKED, NEXT** (reclassified 2026-07-25 — was "not a W-A regression, therefore
      out of scope", which was true on provenance and wrong on framing):
      `VaultImageStore.serverDeleteConfirmed()` uses `File.exists()`, not the `Files.notExists`
      tristate discipline — an indeterminate marker stat reads "not confirmed" and fails **OPEN**
      with respect to delete ownership (PR #60 gate, Codex, item E: it can admit legacy onboarding).
      Pre-existing on main and uniform across routing inputs, so not a defect this unit introduced —
      **but W-A exists to close a CLASS, and fixing the retry call site while leaving the identical
      fail-open in the marker read closes an instance, not the class.** The honest changelog line is
      "closes the fail-open at the retry-destroy call site", NOT "closes the fail-open class".
      Does not block #60. The type and the rule now exist (`Residence`, and "only ProvenAbsent may
      route to ONBOARDING"), so migrating this call site — and `hasVault()`'s other consumers — is
      MECHANICAL rather than a second act of judgment. Do it next, as its own scoped unit with its
      own round; do NOT fold it into a release cut.

- [ ] **UNIT: BURNPLAN REGISTRY — make the burn's cleanup axes STRUCTURAL** (opened 2026-07-26,
      from Kimi k3's advisory in round 3; explicitly NOT folded into W-B, and the reason matters:
      restructuring the burn's cleanup path mid-loop would have made the delta under review a
      REFACTOR instead of four verified fixes, and round 4 would have reviewed the wrong thing.
      Same call as the `onRetryDestroy` scoping decision.)

      **Problem it solves.** Three rounds of this unit produced the same failure in three costumes:
      a cleanup that was gated but not durable, durable but not memory-clearing, enumerated on one
      axis while another went unexamined. Enumerating harder has now failed twice — the round-2
      commit enumerated all six cleanups correctly on the gating axis and still shipped two blocking
      defects on axes it never named. You will never enumerate all axes; make the axes checkable
      consequences instead of remembered properties.

      **Shape to preserve (Kimi's, adjudicated sound):**
      - A CLOSED SET OF PRIMITIVES that own delete+prove+fsync in one body. `deleteTreeDurably`
        (landed in W-B) is the first: it returns `Unit` and throws, so "deleted but didn't fsync" is
        unrepresentable rather than discouraged. NO tri-state result type — `NotDurable` has no
        legitimate consumer at the burn boundary (it throws, same as `Failed`), so it is a trap with
        a name: the predictable accident is `if (outcome != Failed)` shipping the defect again with
        type safety making it look checked.
      - `BurnPlan.steps: List<BurnStep>` as DATA, each step carrying a DECLARED durability mechanism
        (`FsyncedDir(dir)` / `KeystoreTransactional` / `PrefsStore(name)`) and a `verify()`
        postcondition. Per-mechanism names, NOT a generic `NotApplicable` — a step touching a file
        cannot plausibly select `KeystoreTransactional`, whereas everything can select "n/a".
      - ONE enumeration, THREE consumers: the burn executes the steps, the gate asserts every step
        declares a mechanism (and asserts the step COUNT, so a cleanup added outside the registry
        fails CI), and the gate's fresh-baseline assertion iterates the same `verify()` lambdas
        instead of maintaining a parallel checklist that goes stale.
      - Honest limit to write into the kdoc rather than overclaim: Kotlin cannot stop a call site
        inside the burn from calling `file.delete()` directly. That is a LINT boundary, not a type
        boundary — close it with an arch rule (a ~15-line source-tree test failing if
        `deleteRecursively|Files.delete|SharedPreferences.edit` appears outside `wipe/` and the gate).
      - The payoff to state in the spec: when the NEXT axis appears, add a field to `BurnStep` and
        every existing step fails to compile until it is addressed.

      Needs its own spec, its own invariant table, and its own paired-blind round. Do NOT fold into
      a release cut.

- [ ] **Notification channel state is NOT reset by the burn** (round 3, Codex; claim corrected in
      W-B). `ensureChannel` runs in `Application.onCreate` on every launch including a fresh install,
      so a channel's EXISTENCE is not a vault-use oracle — but a user's own importance/sound/vibration
      changes survive a burn and differ from fresh. The FALSE gate claim ("channels ARE compared, via
      prefs") is already fixed; the reset itself is deferred. If taken: delete+recreate channels in
      the burn and add a `NotificationManager` domain to the snapshot. Note the one exclusion that
      must remain and be documented — an app-level notification block (`areNotificationsEnabled()`
      false) cannot be programmatically undone by any API.

- [ ] **Gate follow-up: assert post-burn state at NEXT LAUNCH, not in-process** (round 3). Now that a
      successful burn ends in process death, the gate's `terminate = {}` seam exercises a strictly
      WEAKER in-process arrangement than production ships. A burn → relaunch → assert-at-boot test
      would cover the contract actually shipped. Needs multi-process orchestration the current
      harness lacks.

## 0.10.0-beta — decoy traffic

- [x] **U1: synthetic-account provisioning + `TAG_DECOY` (0x06)** — branch
      `feat/0.10.0-decoy-u1-provisioning` (LOCAL, not pushed, not merged, no version bump).
      Invariant table written BEFORE code at
      `l00prite/.l00prite/reviews/decoy-0.10.0/u1-invariant-table.md`. Shipped: the codec section
      (optional, omitted when empty), `DecoyState`, `DecoyAuthStore` + `StagingAuthStore`,
      `DecoyIdentity`, `DecoyRelayApi`/`ApiClientDecoyRelay`/`RegistrationPowSolver`,
      `DecoyAccountProvisioner`, ~~`DecoyCounterReservation`~~ **(DELETED at U2 fix round 2,
      2026-07-27 — the idle ping was cut, leaving it with no consumer; `TAG_DECOY.counterHighWater`
      and `deadAirNextFireAtMs` went with it. `DecoySectionLock` survives on its other callers.)**
      Evidence: `:app:testDebugUnitTest` 645 tests / 0 failures / 3 skipped (was 598; +47 new) and
      `:app:assembleDebug`, both BUILD SUCCESSFUL, GRADLE_EXIT=0, `--rerun-tasks`, 2026-07-27.
      Measured byte budget: worst-case section delta **640–643 B** against a declared
      `DECOY_SECTION_BUDGET_BYTES = 1024`; realistic state with the section 924–927 B of 262 112 B.
      **U1 is deliberately UNWIRED** — nothing in production constructs these yet, because the
      trigger ("first session that actually sends a decoy") is U3. No registration can be spent
      from the shared global bucket by this branch.
      **REVIEW ROUND 1 DONE + FIXED (2026-07-27).** Paired-blind Codex + Grok, adjudicated in
      `reviews/decoy-0.10.0/u1-r1-adjudication.md`: ten confirmed findings, F1-F10, all fixed in
      fix round 1 of a hard cap of 6. Root cause of three of the five most severe:
      **`VaultRuntime.mutate` is NOT durable** — it schedules; `flushBeforeAck` is the durable
      step and its throw means the value was never issued. The counter reservation, the credential
      commit and both back-offs now flush; readiness consults `capacityExceeded`; the counter
      allocator is one-per-runtime structurally; a capacity failure reverts and backs off durably.
      Spec §2.3/§4/§6.2a and the invariant table were corrected too — the "persisted by writing to
      `VaultState`" wording was the architect's error and is amended in place.
      Re-verified: `:app:testDebugUnitTest` **659 tests / 0 failures / 0 errors / 3 skipped** and
      `:app:assembleDebug`, GRADLE_EXIT=0, `--rerun-tasks`, 47/47 executed, 2026-07-27.
      Re-measured budget: worst-case delta **645 B** of 1024; realistic state 929 B of 262 112 B.
      **REVIEW ROUND 2 DONE + FIXED (2026-07-27).** Paired-blind Codex + Grok over the whole unit,
      adjudicated in `reviews/decoy-0.10.0/u1-r2-adjudication.md`: 1 P1, 7 P2, 6 P3 → eleven
      confirmed findings G1-G11, all fixed in fix round 2 of a hard cap of 6.
      **All three guards added in round 1 became round-2 defects**, sharing one shape: state sampled
      outside the lock that protects it, or two questions folded into one predicate. Fixed at the
      root, not per interleaving — three structural changes:
      (1) **one SECTION lock** (`crypto/vault/DecoySectionLock.kt`) shared by the allocator,
      `DecoyAuthStore` and the provisioner, guarding SEQUENCES rather than single mutates — closes
      the P1 TOCTOU counter regression and the stale-snapshot revert together;
      (2) **the readiness predicate SPLIT** into `hasAccount()` (gates registration, reads nothing
      but the section) and `canSend()` (gates cover traffic) — the round-1 single predicate was the
      ARCHITECT's error, ratified into the spec and falsified by review;
      (3) **the back-off is written AHEAD of the registration**, so a vault too full to record that
      it tried never spends one — the absolute-capacity edge is removed rather than patched.
      Two deliberate behaviour changes: every failed attempt now defers 60-90 min (not only a 429),
      and a `TAG_DECOY` section appears before any relay contact, which moves the 0.9.x downgrade
      trigger from "generated cover traffic" to "tried to provision" — **§4.1's disclosure must be
      re-read when U3 wires the call.**
      Ten mutations applied and each observed to FAIL its intended test, then reverted; two needed a
      second attempt to become discriminating, and that is recorded in the invariant table.
      Re-verified: `:app:testDebugUnitTest` **669 tests / 0 failures / 0 errors / 3 skipped** and
      `:app:assembleDebug`, GRADLE_EXIT=0, 2026-07-27.
      **REVIEW ROUND 3 DONE + FIXED (2026-07-27).** Paired-blind Codex + Grok over the whole unit,
      adjudicated in `reviews/decoy-0.10.0/u1-r3-adjudication.md`: **0 P1**, 6 P2, 5 P3 → ten
      confirmed findings H1-H10, all fixed in fix round 3 of a hard cap of 6. First real convergence
      signal: the two blind reviewers independently found the SAME top three defects (r1 was fully
      disjoint, r2 overlapped on 2 of 11), and round 2's section lock and predicate split were probed
      by both and broken by neither.
      **H2/H3/H4 are one pattern — the guard's scope did not match the resource's scope**, the
      0.9.2 PR-3 lesson, and the fix round 1 already applied once to the counter allocator:
      (1) `DecoyAccountProvisioner`'s constructor is now **private** with a `forRuntime` factory, and
      the one-attempt latch + the unconfirmed-flush memory live in a per-runtime `Gate` — two
      provisioners over one runtime used to each hold their own latch (two registrations from the
      shared worldwide bucket, one orphan) and disagree about durability;
      (2) `refreshTokens`' read→network→write is now conditional on the account still being the one
      refreshed (`DecoyAuthStore.storeTokensForAccount`), so a concurrent `clearAccount` is no longer
      undone by the relay's response restoring live bearer credentials for a retired account.
      **H1/H5: the write-ahead back-off is retired when the attempt failed BEFORE `register`** —
      round 2 made it permanent, so an offline attempt cost 60-90 min of cover-traffic silence AND a
      `TAG_DECOY` section that a 0.9.x build rejects, for something that spent nothing. From
      `register` onwards it stands (a `register` that throws may still have created the account).
      Twelve mutations applied and each observed to FAIL its intended test, then reverted; **four
      tests had to be restructured** to keep discriminating, because a runtime-scoped latch means "a
      later session" must be a new runtime built from the image on disk, not a fresh provisioner over
      a live one.
      Re-verified: `:app:testDebugUnitTest` **675 tests / 0 failures / 0 errors** and
      `:app:assembleDebug`, GRADLE_EXIT=0, 2026-07-27.
      **STILL OWED:** review ROUND 4 against the WHOLE unit, then a maintainer merge decision. Three
      of six rounds used. Flag to the round-4 reviewers: the per-runtime `Gate` (a THIRD process-wide
      `WeakHashMap` registry — allocators, section monitors, now gates) and whether `forRuntime`
      returning a fresh instance over shared guard state is the right call; the deferral
      retire/keep boundary (is `register` the correct discriminator?); and §4.1's re-worded
      disclosure.

- [ ] **§4.1 disclosure wording needs MAINTAINER RE-RATIFICATION — now on its THIRD pass.** This
      adjusts a maintainer ruling, not a typo, which is why it is flagged in the spec rather than
      quietly rewritten. Current text: *"once a vault has **set up cover traffic** — which happens
      the first time it sends any, and is complete as soon as its cover-traffic account is
      registered — it can no longer be opened by 0.9.x; downgrading will present that vault as
      corrupt. A vault that has never used cover traffic, or whose setup never reached the relay, is
      unaffected."*
      **It has now been wrong in BOTH directions in consecutive rounds:** round 3's "which happens
      the first time it sends any" understated the break (a vault that registers and never sends
      still carries the tag); the round-4 replacement first proposed, "the first time it *tries to*
      send any", overstated it (a vault that fails offline before `register` retires its deferral
      and keeps its 0.9.x readability). The durable trigger is **setup that reaches relay
      registration**. The four-path truth table it must be re-derived from now lives in
      `VaultState.kt`'s codec kdoc, next to the code that produces it — see the failures.md entry
      "A doc that drifts in BOTH directions is being edited from itself".
      The maintainer's stated reason for the original narrowing (an overstated disclosure is its own
      dishonesty) still holds; an understated one is just worse, which is why it was applied rather
      than left standing while it waits.

- [ ] **U1 follow-up — account deletion / burn leaves the synthetic relay account registered.
      ⚠️ NOW LIVE AND UNANSWERED — U3 WIRED PROVISIONING (2026-07-27). MERGE GATE.**
      `deleteAccountAndWipe` deletes the REAL relay account and obliterates the image; a provisioned
      synthetic account survives on the relay as an orphan. ~~Not live today (U1 is unwired and
      nothing provisions), but it must be answered before U3 wires provisioning~~ — **U3 is built and
      wired on `feat/0.10.0-decoy-u3-pairing`, so a vault that sends anything now provisions.** The
      answer is still owed and is now a precondition for MERGING U3, not for writing it: either
      delete the synthetic account alongside the real one, or state in `SECURITY_MODEL.md` that it is
      left and why that leaks nothing beyond what §1's threat model already concedes. Same question
      applies to the Pucker Burn wipe, which never contacts the relay at all.

- [x] **U2 must settle §2.2 vs §2.3 for the first envelope. — SETTLED, and the ruling was already
      made before U2 started.** The maintainer's §2.3 ruling governs: **no `SessionBuilder.process`**,
      §2.2 amended to be a requirement on the OBSERVABLE rather than on the machinery. U2 fabricates
      the fields and pins the absence with a test (`building cover traffic writes no Signal record`).

- [x] **U2 — decoy envelope builder. BUILT 2026-07-27 on `feat/0.10.0-decoy-u2-envelope-builder`
      (LOCAL, not pushed, not merged, no version bump). Deliberately UNWIRED.**
      `decoy/DecoyEnvelopeBuilder.kt` + `DecoyEnvelopeBuilderTest.kt` (14 gate tests);
      `DecoyIdentity` gained `ONE_TIME_PREKEY_IDS` / `FIRST_ONE_TIME_PREKEY_ID` / `SIGNED_PREKEY_ID`
      so the prekey batch has ONE declaration that both the generator and the builder read.
      **694 tests / 3 skipped / 0 failures**, `assembleDebug` exit 0. **Fix round 1 of 6 applied:
      18 mutations, 17 discriminated** (the survivor a deliberate defence-in-depth probe). No invariant table: U2 adds no durable field and no writer — the decision and
      its justification are in `reviews/decoy-0.10.0/u2-invariant-table-decision.md`.
      ~~**Paired-blind review round 1 complete, adjudicated and fixed; ROUND 2 NOT YET DISPATCHED —
      that is the next thing U2 owes.**~~ **Rounds 1 AND 2 complete, adjudicated and fixed; ROUND 3
      NOT YET DISPATCHED — that is the next thing U2 owes.** Ruling 2 was deviated from with a proof
      of impossibility and still needs a MAINTAINER decision, not just a reviewer's.
      **FIX ROUND 2 of 6 applied 2026-07-27 — NOT review-driven.** It implements the maintainer's
      §3.0 cut of the idle ping (`c65d9a3e`), which round 1's Ruling-2 finding made decidable.
      Removed: `DecoyCounterReservation` + its 14 tests, `TAG_DECOY.counterHighWater` (W3) and
      `deadAirNextFireAtMs` (W4) from both codec sides. Kept with the argument written down:
      `DecoySectionLock`. **679 tests / 3 skipped / 0 failures**, `assembleDebug` exit 0,
      `--rerun-tasks`; **6 mutations, 6 discriminated**. Re-measured section: raw body 717 B → 700 B;
      the *encoded* delta is run-to-run noise at 636–646 B and did **not** shrink — the removed bytes
      were the most compressible in the section. Budget stays 1024 B as a bound.
      **FIX ROUND 3 of 6 applied 2026-07-27 — review-driven, answering round 2 (0 P1, 1 P2, 4 P3).**
      **G2-A (P2): a real first message may carry `ephemeral_key` set and `prekey_id` NULL** —
      ordinary signed-prekey-only X3DH, reached whenever the peer's one-time prekey batch is
      exhausted. The builder asserted the biconditional and the whole first-shaped path rested on it
      (the `require`, `requireNotNull(cover.preKeyId)`, protobuf field 1 always written, the wrapper
      sized with it, `baseKeyOffset` assuming it). **Once U3 wires the pairing that meant a real send
      to such a peer got NO COVER AT ALL — an unpaired real frame.** Fixed in all four places;
      measured against real libsignal (no-OPK 402 B vs OPK-present 404 B). The "covering" test pinned
      the wrong property with an internally inconsistent fixture; both variants are now built from
      genuine no-OPK sessions and are in the gate cross-product. G2-B: the gate fixtures now VARY
      `media_type`/`version`/`previous_chain_length` — they only ever compared defaults, and `"file"`
      is the same width as `"text"`. G2-C: the U1 invariant table corrected IN PLACE (18 stale
      references), with `DecoyState`'s kdoc made the canonical field-set pointer. G2-D: the
      provisioner's allocator-based lock justification rewritten, decision kept.

- [x] **U3 — pairing at the send choke point. BUILT and WIRED 2026-07-27 on
      `feat/0.10.0-decoy-u3-pairing` (`ba5a6b9e`; LOCAL, not pushed, not merged, no version bump).**
      `decoy/DecoySendPairing.kt` — the `CoverTraffic` seam (`paired(cover, publish)` + `stop()`,
      `CoverTraffic.NONE` as the whole "off" implementation) wrapped around the NON-SUSPENDING publish
      tail at all three `ws.sendMessage` sites, plus `SignalProtocolManager.localIdentitySerialized()`
      for the 33-byte `IdentityKey.serialize()` form the builder's `Sender` needs.
      **696 tests / 3 skipped / 0 failures**, `assembleDebug` exit 0, `--rerun-tasks`;
      **15 mutations, 15 killed** (M14/M15 added because the first thirteen left two tests
      undiscriminated — no test in the unit is carried by another guard).
      **No invariant table: U3 adds no durable field and no writer** — it reads
      `TAG_DECOY.accountId` through `DecoyAuthStore`'s existing getter and mutates nothing; the
      registration writes it triggers are U1's.
      **The two OPEN questions are answered in `DecoySendPairing`'s kdoc, with the argument:**
      (1) the gap is **uniform over 5‥50 ms per send** — max-entropy over the bound R-U3-1 forces,
      floor set so the two writes cannot share one TCP segment, and the generator is typed
      `SecureRandom` because the OBSERVABLE gap would otherwise leak the state that produces the
      UNOBSERVABLE order bit; (2) **every envelope through the choke point is paired**, receipts and
      attachment control payloads included, because those are built to be indistinguishable from text
      and selective pairing would hand an observer a receipt detector that does not exist today.
      **Mechanism notes worth carrying into review:** the tail is passed as a plain `() -> Unit`, so
      "no suspension between `contactExists` and `ws.sendMessage`" is now compiler-enforced; a
      `finally` guarantees the real publish runs exactly once on every path including cancellation; a
      pairing `Mutex` keeps a queued real send from overtaking a decoy-first pairing (reordering is
      forbidden categorically, unlike delay) and keeps both branches equally interleaving-free.
      `canSend()` is deliberately NOT the send predicate — it folds in the transient
      `capacityExceeded`, which is the stutter R-U3-3 forbids, and is unobservable at a point the
      flush has already passed.
      ⚠️ **THE PARAGRAPH ABOVE IS SUPERSEDED BY FIX ROUNDS 2 AND 3 AND IS LEFT ONLY AS THE RECORD OF
      WHAT WAS BUILT.** Round 2 (maintainer ruling): the ordering is real-frame-first, the pairing
      `Mutex` is DELETED, and the `finally` now guards the COVER frame (an unpaired real frame is a
      marked frame), not the publish. Round 3 (third-lens P1s): `paired(cover, publish)` is DELETED —
      the seam is `cover(real)` and cannot be handed a real send at all, because entering it was
      itself cover work inside the process-death window; the non-suspending tail lives at the call
      site as `MessagingCoordinator.publishOutgoing` / `publishReceipt`, which is what keeps D2c
      compiler-enforced; and `stop(invalidateTransport)` now OWNS the disconnect, running it only
      after draining every admitted pairing. **28 pairing tests + 33 provisioner tests; 712 total /
      3 skipped / 0 failures; 11 mutations, 11 discriminated.** See
      `reviews/decoy-0.10.0/u3-fix-r3-subordinate.md`.
      **OWED: paired-blind review round 1 of U3 (0 of a hard cap of 6 used), and the now-live U1
      follow-up above (orphaned synthetic account on delete/burn) as a merge gate.**
      **681 tests / 3 skipped / 0 failures**, `assembleDebug` exit 0, `--rerun-tasks`;
      **7 mutations, 7 discriminated** — and M5/M6/M7 fail ONLY the new test, confirming the old
      coverage proved nothing about mirroring. Section budget re-measured over three runs: raw body
      700 B, encoded delta 635/641/645 B — recorded as a DISTRIBUTION, since the previously recorded
      "640–643 B" was a two-run interval that three fresh runs already fall outside.

- [x] ✅ **MAINTAINER DECISION #1 — RULED 2026-07-27 (`81761dfb`): REAL-FRAME-FIRST, ALWAYS.
      Random ordering CONCEDED. Implemented in U3 fix round 2.**
      Recorded as a RULING rather than a preference because the exhaustion proof makes it one: three
      possible gap positions on a decoy-first send, all three break something, no fourth position —
      **decoy-first has no correct implementation, not merely a worse one.** Structural beats
      guarded. The traded property is near-worthless against the targeted adversary (5–50 ms of
      correlation ambiguity, only for an observer watching BOTH ends, who already reads
      `recipient_id` in cleartext on both envelopes). Residual now recorded in §2.4.
      Derivation: `reviews/decoy-0.10.0/u3-fix-r1-ordering-decision.md`; implementation:
      `reviews/decoy-0.10.0/u3-fix-r2-real-first.md`.

- [ ] ⚠️ **MAINTAINER DECISION #2 — the rate-budget conflict, which decision #1 does NOT close.**
      **SPLIT AND SUPERSEDED 2026-07-27 (`75f1b68d`) — see CX23 TRIP items (a) and (b) at the top of
      this file, which are the live tracking. Kept here as the derivation only.** Fix round 2
      confirmed the split from the code side: real-first makes SELF-preemption inside a pair
      impossible by construction and is tested (`with one send permit left the REAL frame takes
      it`), and cross-send preemption is the part no ordering can touch.
      **The adjudication states U3-C as an ordering defect and that is wrong.** Real-first removes
      only self-preemption inside one pair; send N's cover frame goes out 5–50 ms AFTER send N's real
      frame and can still take the last permit send N+1's real frame needed. Cross-send preemption is
      inherent to doubling volume on a shared per-account budget, whatever the order.
      Real shape: cover halves the account's effective send capacity, and a rate-limited real send is
      **silently unrecoverable** — `hub.go` sends `rate_limited` with no message id and
      `MessagingCoordinator.onServerError` (`MessagingCoordinator.kt:2120`) is a no-op, so the bubble
      sits in `SENDING` forever with nothing to retry. ~~**Only a relay-side answer closes it:**~~
      **⚠️ HALF WRONG, corrected 2026-07-28 (U3 fix round 6).** *"Only a relay-side answer"* conflated
      two separable problems. **Cover competing for the budget is now closed client-side** —
      `CoverPressure` yields on `rate_limited` and on the session's own frame rate, which needs no
      message id and no knowledge of the limit, so the "unsound" ruling applied only to a *headroom*
      policy. **The silently-unrecoverable real send is NOT closed** and still needs the relay to
      carry the message id; that half stays exactly as stated, under CX23 item (a). Exempting or
      raising the per-account `message.send` budget for cover frames remains worth doing, as an
      improvement rather than the fix.

- [ ] **U3 FIX ROUND 2 of 6 APPLIED (2026-07-27) — the ruling implemented as a SIMPLIFICATION.**
      Record: `reviews/decoy-0.10.0/u3-fix-r2-real-first.md`. `paired` is now `publish()` — first
      statement, outside every `try`, no suspension in front of it — then the cover frame after a
      drawn gap. **U3-A, U3-B, U3-C's self-preemption half and U3-D are impossible BY CONSTRUCTION;
      U3-E, U3-G and U3-H are GONE rather than repaired; U3-F is repaired and demoted with a
      derivation** (a coalesced pair is one record of twice the frame length, which says exactly what
      two frames say — cosmetic, not a leak). **The pairing `Mutex` is DELETED**, argued from its
      callers: both of its justifications were decoy-first artefacts and it had no third caller.
      Also deleted: `Plan`, the order bit, three branches, the latching booleans, the nested
      `finally`. Kept and re-argued: the `finally` (an unpaired frame is a MARKED frame — R-U3-3),
      `coverFor`'s catch-all (justification INVERTED: it now stops a cover-side throw from marking an
      already-delivered message FAILED), and `SecureRandom` by type (the gap is now the only drawn
      quantity and is directly observable — a `java.util.Random` becomes a device fingerprint that
      could link two vaults' traffic).
      **U3-I discharged in full:** 15 → 20 tests, all four named gaps covered — process death at the
      only suspension point, a `deleteContact` queued on one `StandardTestDispatcher`, the
      `sendLimit` boundary, and a concurrent send delayed by nothing; plus `no cover-side code runs
      before the real publish`, which catches the quiet regression the others would miss.
      **15 mutations, 15 discriminated, 0 survivors; all 20 tests killed by at least one.**
      **701 tests / 3 skipped / 0 failures**, `assembleDebug` exit 0, Gradle exit 0.
      **The ruling left two gaps, both closed here as documentation:** §2.4 never received the
      residual the ruling promised it, and §5's U3 row still demanded a statistical order test.
      Still open: U3-C **cross-send** (relay-side, decision #2 above), review round 2 not dispatched.
      **4 of the 6 fix rounds remain.**

- [ ] **U3 FIX ROUND 4 of 6 APPLIED (2026-07-28) — the COMPOSED fix. Severity had gone UP.**
      Round 3's review returned **4 P1** where round 2 returned 2, two of them new — the
      fix-introduces-defects signature. Two facts are recorded because they are the point:
      **one P1 was caused by the architect's own instruction**, and **one was an impossibility claim
      from fix round 3 that a reviewer refuted with a construction.** Full record:
      `reviews/decoy-0.10.0/u3-fix-r4-composed.md`.
      **W4 — the construction, and everything follows from it.** Teardown does not need to be atomic
      with the handoff, only SERIALISED against it, and the coordinator already owns a serialisation
      point every send goes through: its `limitedParallelism(1)` confined worker. Terminal teardown
      is now enqueued there, so it runs strictly before or strictly after a send's publish-then-pair
      slice and never inside it. The declared R-U3-1 residual is **closed, not accepted**, and no
      lock and no cover-side instruction was added in front of any real send. R-U3-5 step 1's other
      half is an `acceptingSends` gate read before any crypto on all three send paths.
      **W1 — the architect's instruction.** `publishOutgoing`/`publishReceipt` returned `Unit`, so
      contact-deleted, socket-refused and handed-off were indistinguishable and cover ran in all
      three: two of them emitted a **lone decoy**, a frame the user never generated. Both tails now
      return "handed to the relay"; all three call sites are `if (publish…) cover(…)`.
      **W2 — no wall clock survives.** The drain's 100 ms deadline abandoned any build that overran
      it, and "non-suspending" bounds suspension, not time. `cover()` now BUILDS then ADMITS, so the
      register only holds built pairings; deadline, wait loop, condition variable and `resolved` flag
      are all deleted.
      **W3 — the Tor/I2P toggle no longer splits a pair** across a TLS teardown/reconnect (third lens:
      a split pair is a STRONGER signal than a missing cover frame). New **non-terminal**
      `CoverTraffic.quiesce`; the disconnect tripwire's deliberate carve-out for `ZitroneApp` is GONE
      rather than converted into a tracked exception.
      **W5** — `ensureProvisioning` holds the teardown lock across check → CAS → assign.
      **W6 — the call-site tripwire PASSED WHILE W1 WAS LIVE** (it pinned adjacency, not dependence).
      All three re-derived; a fourth pins the confined dispatch and the send gate.
      **Evidence:** `:app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL, Gradle exit 0,
      **716 tests / 0 failures / 0 errors** (712 → 716); pairing suite 28 → 35. **13 mutations, 12
      discriminated**; the survivor (reverting to round 3's admit-then-build) is reported as
      behaviour-preserving under confinement rather than as a test gap.
      **Residual declared:** `stop()` blocks up to 250 ms for the worker, then falls back to the
      caller — a scheduling bound, not a cover-work bound, required because `runtime.close()` follows
      `stop()` immediately. **2 of the 6 fix rounds remain. Review round 4 not dispatched.**

- [ ] **U3 FIX ROUND 5 of 6 APPLIED (2026-07-28) — the lock boundary, and one reused primitive.**
      Round 4's paired-blind review was the **FIRST CONVERGENCE IN SEVEN ROUNDS**: both reviewers
      landed on the same top finding independently, with severity FALLING — exhaustion, not
      anchoring, per `failures.md`. Adjudicated 1 P1 / 3 P2 / 5 P3. Full record:
      `reviews/decoy-0.10.0/u3-fix-r5-lock-boundary.md`.
      **X1 (P1 on tie-break) — the round-4 fix was made through a REUSED PRIMITIVE.**
      `reconnectTransport` shared terminal teardown's helper, whose 250 ms **caller-thread fallback**
      is terminal-safe only for `stop()` (which invalidates the transport and refuses late
      admissions). `quiesce` deliberately leaves the register OPEN, so when the fallback fired it
      drained an empty register on the caller, swapped the socket, and let a send still mid-slice on
      the worker emit its cover frame on the NEW connection while its real frame had gone out on the
      old — a **split pair across a TLS boundary**, correlated with a transport change. **No
      coroutine suspension is needed for it:** the uninterruptible-slice argument only ever held
      against teardown running ON the worker, and the fallback had just taken it off. It did not
      merely have an unjustified bound — it structurally defeated the argument the round-4 fix rests
      on, exactly when it fired.
      **FIXED AT THE LOCK BOUNDARY, not at the fallback**, because lengthening or dropping the bound
      reinstates a verified five-step deadlock (`applyTransport` holds `transportLock` → blocking
      reconnect waits on `confined` → `deleteAccountAndWipe` runs there → `onConfirmed` → `lockIf` →
      `stopSession` takes `transportLock`). `applyTransportLocked` now installs the endpoints and
      RETURNS the session to redial; `applyTransport` releases the lock and only then requests a
      reconnect that is confined to the worker, skipped once terminal teardown has begun, coalesced
      by generation, and has **no fallback and no wait at all**. *(Deviation from the ruling,
      recorded: the ruling said the reconnect may "wait for confinement without a fallback"; it does
      not wait — waiting was the fallback's only reason to exist and would relocate the hang.)*
      **X5 (P2) — the tests named for confinement did not test confinement, which is WHY X1
      survived.** No test instantiated `MessagingCoordinator`; both behavioural tests built their OWN
      `Executors.newSingleThreadExecutor()`; the fallback branch was never executed by anything. The
      dispatch is now production code a JVM test can build — **`CoverTrafficWorker`**, three
      deliberately different entry points (on-worker terminal / dispatched+bounded terminal /
      dispatched-only non-terminal) — driven by seven behavioural tests, including an end-to-end one
      over a socket whose identity changes on a swap, so a split pair is OBSERVED, not argued.
      **X3/X4** — the dispatch tripwire now pins the swap's confinement and the lock boundary; the
      declared residual path is executed by a test asserting exactly what it costs (an unpaired REAL
      frame, never a lone decoy, never a split pair).
      **X6** both terminal waits are bounded (round 4 left the second unbounded, in the function whose
      whole rationale is that an unbounded wait is the worst outcome). **X7** the
      natural-socket-death-mid-gap residual is re-declared in the spec after being struck by accident.
      **X8** the "35 pairing tests" claim was wrong (34) and is corrected AS AN ERROR. **X9** three
      tripwire evasions closed — token spacing (`coverTraffic . cover(`, `disconnect( )`) normalised
      away, and the scans read EVERY app source rather than two named files.
      **Evidence:** `:app:testDebugUnitTest :app:assembleDebug --rerun-tasks` → BUILD SUCCESSFUL,
      Gradle exit 0, **three consecutive runs**; **723 tests / 78 classes / 3 skipped / 0 failures**
      (716 → 723); pairing suite 34 → 41. **12 mutations, 12 discriminated.**
      **Process failure kept on the record:** the first mutation harness was killed by a timeout and
      left one mutation applied in an UNTRACKED file, so `git status` hid it and the baseline was red
      — every mutation would have reported "caught" for free. The re-run asserts a green baseline
      first, restores in a `finally`, checksums every touched file after each restore, and re-checks
      the baseline at the end.
      **New residual declared:** a transport swap now WAITS for the worker instead of pre-empting it.
      Latency, not framing — the endpoints are already re-pointed, so only the one live socket
      lingers, and the registration PoW (the only multi-second CPU work) runs on `Dispatchers.Default`.
      **1 of the 6 fix rounds remains. Review round 5 not dispatched.**

- [ ] **U3 inherits three things from U2, none of them optional.** *(Rewritten at U2 fix round 1 —
      the interface changed, so two of the three old items no longer say the right thing.)*
      1. **Hand `DecoyEnvelopeBuilder.build` THE REAL ENVELOPE**, the one about to go to
         `ws.sendMessage`. Not a block count, not a descriptor you assemble — the envelope. It is the
         only input that carries shape, counter magnitude, timestamp width and TTL width, and the
         round-1 P1 was precisely that a block count does not.
      2. **Supply the sender's own registration id and 33-byte serialized identity key**
         (`SignalProtocolManager.localRegistrationId()` / `localIdentityPublicKeyBytes()`), not
         placeholders — both are inside a real first message's ciphertext and both change its length.
         The registration id is now range-checked to `1..16380` and fails closed outside it.
      3. **`build()` THROWS rather than return a decoy whose frame does not match**, and U3 owns what
         happens next. Whatever it does, it must not fail or delay the REAL send: the durability
         barrier and the send latency are U3's gate. Decide deliberately whether an unmatched decoy
         means "send the real message uncovered" or "do not send at all", and write the reasoning
         down — it is a threat-model choice, not an error-handling detail.

- [ ] **U4 inherits what U2's fix round 1 changed about counters (U5 is CUT) — the OLD residual is withdrawn
      and three new ones are declared.** The monotonic-counter residual (a decoy counter climbing
      through replies that should have reset it) is **gone**: the paired decoy mirrors the covered
      envelope's `message_number`, because a base64 field's length is always a multiple of 4 and so
      the ciphertext cannot absorb a decimal-width difference. What replaces it, all relay-visible
      only and all in §2.4: the random body is not always a padded-block multiple; the synthetic
      conversation's counters repeat; `prekey_id` may name an id the account never published when the
      covered id has four or more digits. **U6 must not claim coverage past those.**
      ~~**U5 additionally inherits `DecoyCounterReservation` itself**~~ — **U5 IS CUT (2026-07-27,
      maintainer, spec §3.0). There is no unit and no follow-up gate.** The allocator therefore had
      no consumer at all and was DELETED at U2 fix round 2, along with `TAG_DECOY.counterHighWater`
      and `deadAirNextFireAtMs`. **Dead-air periods are not covered, and that is an accepted
      documented limit — U6 must state it as such and must not imply otherwise.** So this item is now
      U4's alone; the three §2.4 residuals above still stand.

- [ ] **U6 owes the DELIVERY of the storage-format disclosure.** The gate itself is answered above
      (line ~598, `a4f118df`) — do not re-answer it here. What is still outstanding is shipping the
      text: release notes plus `SECURITY_MODEL.md`, saying that 0.10.0 vaults cannot be opened by
      0.9.x and that downgrading presents them as corrupt. 0.10.0 must not ship without it, because
      0.10.0 is the release that makes the second break real (spec §4.1 sequencing note).

- [ ] **Production diagnostics rescope (maintainer decision 2026-07-29) — its own unit, NOT part of
      U4/0.10.0.** Direction approved: in RELEASE builds the Diagnostics screen is backed by a
      **RAM-only ring buffer** — current process, current session, cleared on vault lock, never
      written to disk — and the `Log.w("ZitroneBoot", …)` logcat mirror is release-stripped too.
      The durable `BootDiagnostics` file survives in DEBUG builds only (the "parallel developer
      install" is the debug flavor, not a second app). Why: `boot-diagnostics.log` is device-global
      and lives OUTSIDE the vault, so it accumulates cross-vault evidence (registration lines, PoW
      records, socket churn) a decoy-vault unlock can display; and the handshake-failure line's
      `${t.message}` embeds relay hostnames for UnknownHost/Connect exceptions despite the "never
      the URL" comment — the "basic" line is the leakiest one to persist. Constraints: keep the
      single full-erase function wired to every wipe path (Pucker Burn, account delete) for the
      debug artifact; per-session scoping must prevent a hidden vault's lines being readable after
      switching vaults. Slot: 0.11.0 polish (final alpha), same before-external-testers bucket as
      the storage-format disclosure.

- [ ] **CX23 item (a) — `onServerError` surfaces nothing: RELAY HALF DONE (`e25d59a` deployed /
      `8c91809` for main), CLIENT HALF OWED.** *(This entry previously cited `1c63e8c`; that commit
      was amended away and exists on NO branch — cherry-picking it gets nothing.)*
      Not checked, per the CX23 note: the relay half does not fix the
      user-visible symptom, so until the client half ships in a release **users still see `SENDING`
      forever** on a rejected send — not failed, not retried, no error. Predates decoy traffic;
      worth fixing on its own merits.

      **✅ RESOLVED 2026-07-29 — PUSHED.** The warning below was correct and has been acted on. The
      relay half is now on `origin` twice: `cx23/relay-attribution-for-main` (`8c91809`, the fix
      alone, cherry-picked onto main and re-verified there) and `cx23/0.9.4-pow-deploy` (`76399f7`,
      exactly what production runs — backup/audit only, do NOT merge). The wire contract below is
      no longer an unverified claim: it was read from source and the 0.10.1 client branch was
      checked against it (`WsClient.kt` reads
      `frame.optString("message_id").takeIf { it.isNotEmpty() }` and names the same three codes).
      **(ii) still stands until merge:** a redeploy from `main` reverts attribution to nothing.

      *Original warning, kept because the pattern is the point:* `1c63e8c` IS NOT ON `origin`
      (verified 2026-07-29 from CX33) — the deployed relay fix was SINGLE-COPY on a production box,
      the same trap the 8443/rate-limit warning had, recurring for real.

      **Wire contract the relay now provides (additive; older clients unaffected):**
      `serverEvent.MessageID` populated on `rate_limited` (when the header parsed), `store_failed`,
      and `bad_envelope` (when the id is a well-formed UUID). Echoed ONLY for well-formed UUIDs.
      **Empty id = unattributable → fall back to the connection-level path, never treat as id `""`.**
      `rate_limited` keeps precedence over `bad_envelope` (both-rejected ⇒ `rate_limited`, empty id).

      **Client changes (file:line VERIFIED against main 2026-07-29):** `net/WsClient.kt:125`
      (interface `fun onServerError(code: String, message: String)`) and `:340` (`l.onServerError(
      frame.optString("code","unknown"), "")` — the id is dropped on the floor here). Implementors:
      `MessagingCoordinator.kt:2327`, `decoy/WsSyntheticSocket.kt:72`. Test doubles:
      `WsClientFrameTest.kt:125`, `WsSyntheticSocketTest.kt:48,58,59`.

      **➕ TWO THINGS THE NOTE MISSES, both found by verifying it:**
      1. **A U3 TRIPWIRE PINS THE BODY OF THE VERY FUNCTION BEING CHANGED.**
         `DecoySendPairingTest.kt:1526-1536` locates `bodyOf(code, "override fun onServerError(")`
         and asserts it contains **literally** `if(code == ERROR_RATE_LIMITED)
         coverTraffic.onRelayRateLimited()`. Adding a third parameter keeps the locator prefix
         intact, but any restructure (a `when(code)`, or attribution logic interleaved into that
         branch) FAILS it. That tripwire is precisely what enforces the note's own constraint 3
         ("the yield must not be entangled with error handling") — so keep the statement in that
         exact form, or change the tripwire consciously and say why.
      2. **TWO COMMENT BLOCKS ARE NOW FALSE IN PRODUCTION** and are false *today*, before any client
         work: `MessagingCoordinator.kt:~2337` ("needs the relay to carry the message id on the
         error, **which it does not**") and `DecoySendPairing.kt:~109` ("the relay's `rate_limited`
         carries **no message id**"). `e25d59a` is deployed, so both assert something untrue of the
         live relay. This project treats a kdoc claim the code does not support as a finding; fix
         them with the client half.

      **Constraints that must survive:** a COVER frame's rejection must never surface to the user —
      attribute only ids the REAL send path owns (a membership check, not trust in the echo: the
      relay is conceded and can echo any well-formed UUID); the retry path must not resurrect the
      R-U3-1 class (a retry IS a real send, so cover must never precede or compete with it);
      failing on `store_failed` is correct because the relay does not hold the envelope.

      **Tests owed:** id ⇒ that message FAILED and others untouched; empty id ⇒ no state change;
      rejected cover frame ⇒ nothing user-visible but `CoverPressure` still fed; `store_failed` /
      `bad_envelope` attribute the same way; retry emits a real send with no cover frame preceding.
      **Per DoD: paired-blind independent review before merge (send path is security-sensitive) plus
      mutation evidence.** Build on `main` or a branch off it. **NEVER build Android on CX23** —
      live Postgres + relay, and `ci-gradle` (flock, disk floor, daemon caps) exists only on CX33.

## 🔄 DESIGN REVERSAL — registration PoW is OUT; `clientKeyer` is the answer (2026-07-29)

**Read this before believing any PoW document in this repo.** Two design positions were in the tree
at once: the 0.9.4 CHANGELOG entry and `docs/REGISTRATION_POW_CALIBRATION.md` describe registration
proof-of-work as the shipped answer to registration rate limiting, and it is not — `clientKeyer`
(trusted-proxy client keying, `server/internal/api/clientkey.go`) is. Both PoW docs are now marked
superseded and point here. Nothing below deletes the measurements; see "recoverable".

### What `clientKeyer` actually does, and why it is sound

Per-client buckets for **clearnet**, replacing one global bucket that every clearnet user shared
because Caddy **appends** `X-Forwarded-For` rather than overwriting it. Its safety is structural, not
conventional:

- **`X-Forwarded-For` is consulted ONLY when the socket peer is a configured trusted proxy.** An
  untrusted peer's header is ignored entirely.
- **Trusted entries are EXACT IPs — CIDRs are deliberately rejected** (dropped as junk at
  construction), so a broad range can never be trusted by accident.
- **The LAST XFF element is used**, because that is the only one the trusted proxy itself wrote;
  everything to its left is client-supplied and untrusted.
- **The derived address is HMAC'd under a salt generated fresh at process start** and never
  persisted, so buckets are not a stored, correlatable record of who connected.

### Why PoW was chosen originally — and why that reason still stands unfixed

PoW was chosen because **IP keying is structurally meaningless behind Tor and I2P**. That is still
true. `clientKeyer` **explicitly cannot fix overlay collapse, and does not claim to**: its own test
(`clientkey_test.go:35-39`, commented "THE ONE THAT MATTERS MOST") **asserts that two Tor clients
claiming different addresses must land in the SAME bucket**. The Tor sidecar forwards raw HTTP, so an
overlay client can set `X-Forwarded-For` to anything; trusting the sidecar would reopen a full
spoofing bypass. So the test pins the collapse *deliberately* rather than treating it as a defect.

### The accepted position on overlay traffic — one shared bucket per transport

Accepted, with the reason recorded rather than waved at:

1. **Registration volume over Tor is expensive to achieve.** Circuit building and introduction-point
   setup impose real per-attempt cost on the attacker, so the shared bucket is not the free
   amplification it would be on clearnet.
2. **The sidecars can never be trusted for header-based keying** without reopening the exact bypass
   `clientKeyer` exists to close — a trusted sidecar means any overlay client picks its own bucket.

**This is NOT "users tolerate outages."** The position is that the attack is costly at the source and
that the alternative keying is unsound at any price. If overlay registration abuse ever becomes real,
the answer is a cost function (PoW) or an invitation/credential scheme, not header trust.

### PoW remains RECOVERABLE — this is a reversal, not a deletion

The path back is **re-merging `dda31b9`** from `cx23/0.9.4-pow-deploy` or
`cx23/0.9.4-registration-pow`. Kept deliberately:

- **`docs/REGISTRATION_POW_CALIBRATION.md` is marked SUPERSEDED, not deleted.** The **D=5**
  derivation, the **Revvl floor measurements**, and the relay-side sweep are real measured work that
  would have to be redone from scratch if PoW returns. Same for `docs/DEPLOY_0.9.4_POW.md`, which
  holds the deployment sequence and the fail-closed secret requirement.

### Scope precision — what is NOT being removed

**`server/internal/pow/` STAYS.** It is still imported by `drops.go` and `qrdrops.go` for the
**dead-drop** proof-of-work (`DROP_POW_DIFFICULTY`, default 20), which is a different feature and is
unaffected. "Removing PoW" means **registration** PoW and its challenge endpoint only. Deleting the
package would break dead drops.

### Env vars: already inert, and already absent

`REGISTRATION_POW_ENABLED` and `REGISTRATION_CHALLENGE_SECRET` **are not in `config.go`, not in
`server/.env.example`, and not in the live `.env`** (verified 2026-07-29) — there is no flag left to
flip and no config to strip. The only place they still appear is `docs/DEPLOY_0.9.4_POW.md`, which is
now headed as superseded so nobody follows it expecting an effect.
