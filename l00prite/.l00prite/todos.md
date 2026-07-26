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

## NEXT — 0.9.4-beta: REGISTRATION PROOF-OF-WORK. Spec brief, not started.

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
- **Hybrid SHA-256 pre-stage before Argon2id?** The HMAC'd challenge already gives cheap rejection
  of garbage. The gap it does NOT close: an attacker holding a VALID challenge (issuance is
  unauthenticated) can force Argon2id verification with wrong proofs. A cheap SHA-256 first stage
  closes that; rate-limiting challenge issuance bounds it. Decide whether the extra round-trip and
  protocol surface is worth not depending on that limit being tuned right.
  **Note the prerequisite finding:** the pre-stage is `pow.Verify`, already shipped on server,
  Android and TS — so the cost of this option is protocol surface only, not implementation.
- **Argon2id parameters (memory, iterations)** — server verification cost is real and scales with
  them. Size for tolerable relay cost at expected volume.
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
logging, so the account is not obviously tied to a person or device. But it is **not currently
disclosed anywhere**, and "returns the app to a fresh install" in the 0.9.3 release notes and
`SECURITY_MODEL.md` could be read as covering it. **Decide: disclose the residual, or make the burn
best-effort-delete the account (which has its own problem — a relay call at burn time is a network
signal at exactly the wrong moment, and it fails closed on no connectivity).** Track independently
of 0.9.4; it is a deniability question, not a rate-limiting one.

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
- [ ] **Storage-format stability GATE:** before external testers, either commit to storage-format
      stability or disclose wipe-on-breaking-change (migrations aren't built).

## RELAY (CX23) — from the 2026-07-26 429 diagnostic. PRIORITY ORDER IS AS LISTED (user-set)

All three need **CX23 access, which CX33 does not have** (see Housekeeping + constraints.md
"Box roles"). Diagnostic was read-only; nothing on CX23 was changed. Evidence: `ratelimit.go`,
`handlers.go:48/160`, `cmd/server/main.go` fiber.Config, `docker-compose.tor.yml:32`, plus
external probes from CX33 (TLS handshake, `GET /healthz` on 443 and 8443, `dig`).

- [ ] **P1 — PORT 8443 IS PUBLICLY REACHABLE OVER PLAIN HTTP. Highest priority, above the limiter.**
      `http://178.104.19.240:8443/healthz` returned 200 from CX33 over the open internet; the same
      app serves the FULL API there. This defeats TLS, defeats cert pinning for any client induced
      onto it, and hands an attacker their own rate-limit bucket (a direct-to-8443 peer is not
      Caddy, so it gets a distinct `c.IP()` key). Likely an artifact of Docker publishing
      `8443:8443` past the host firewall (`docker-compose.yml:26-27`) rather than a deliberate
      choice — **CONFIRM INTENT FIRST**, then close it: bind the publish to `127.0.0.1:8443` or
      firewall 8443 so only Caddy can reach it. NOTE: changing the published port is a compose
      change — three-file invocation required
      (`-f docker-compose.yml -f docker-compose.tor.yml -f docker-compose.i2p.yml`).
- [ ] **P2 — `registerLimit` is 5/HOUR on a key that collapses to Caddy's socket address.**
      `handlers.go:48` = `ratelimit.New(5, time.Hour, ...)`, keyed on `c.IP()` at `handlers.go:160`.
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
