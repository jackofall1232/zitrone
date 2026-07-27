# U1 (decoy traffic — synthetic-account provisioning + `TAG_DECOY`) — WRITER/READER invariant table

Built BEFORE implementation, per the standing rule: any change to a durable multi-reader signal gets
its writers, its readers, and **what each reader assumes the signal MEANS at the moment it reads**
enumerated first. The durable signal here is **`VaultState` TLV section `TAG_DECOY = 0x06`**.

> **CORRECTED after review round 1 (2026-07-27).** The first version of this table equated a
> successful `VaultRuntime.mutate` with a durable write. **It is not one.** `mutate` encodes the
> state and hands it to `VaultSession.update`, which snapshots, marks dirty and returns — "no I/O
> here" (`VaultRuntime.kt:132`). The bytes reach disk when the ≤2 s coalescing ceiling fires, or
> synchronously via `VaultRuntime.flushBeforeAck()` → `VaultSession.flushNow()`. **A throw from
> `flushBeforeAck` means the value was never issued / the state was never recorded.** Rows W3, W5,
> R2 and the crash matrix carried that error and are corrected below; W6 is new; so are the rows on
> the capacity back-off and on allocator uniqueness. Corrections are marked **[R1]** and the
> superseded text is struck through rather than deleted, because a table that quietly rewrites
> itself teaches the next unit nothing.

Source-verified against `main` @ `d44616c5`:
`crypto/vault/VaultState.kt` (tags `0x01`–`0x05` at 158–162; strict-v1 unknown-tag throw at 286 under
the comment at 285; `VaultState.wipe()` at 83–92; `parsePlaintext` decode-failure wipe at 311–320),
`crypto/vault/VaultRuntime.kt` (single mutation gate, 119–144; `capacityExceeded` 96–98;
`flushBeforeAck` 168–186), `ZitroneApp.kt` (`SessionContainer` 1562+, decode-at-construction 1600+),
`data/AuthStore.kt` (`AuthState` 27–31, `VaultAuthStore` 134–161),
`net/ApiClient.kt` (`register` 147–169, `createSession` 176–187, `refreshSession` 193–198),
`server/internal/auth/jwt.go:26` (`RefreshTokenTTL = 7 * 24 * time.Hour`),
`server/internal/api/handlers.go:54,166`, `server/internal/db/schema.sql:34-40`.

**Two spec facts were re-verified and found STALE — see “Spec corrections” at the end.** Neither
changes the design; both change what U1 may assume.

## The signal

A new **optional** TLV section in the per-vault sealed payload. It holds, for the vault it lives in:

| Field | Type | Purpose | Written by |
|---|---|---|---|
| `accountId` | nullable utf8 | the synthetic relay account's UUID | W1, **W2c (clear) [R1]** |
| `identityKeyPair` | nullable bytes | libsignal `IdentityKeyPair.serialize()` — the synthetic account's long-term identity (PRIVATE key material) | W1, **W2c (clear) [R1]** |
| `accessToken` / `refreshToken` | nullable utf8 | that account's session tokens | W1, W2, W2b |
| `counterHighWater` | i64 | counter-reservation high-water mark: every value `< counterHighWater` is considered ISSUED | W3, **W2c (reset) [R1]** |
| `deadAirNextFireAtMs` | nullable i64 | dead-air schedule next-fire (field reserved; **U1 never sets it**) | W4 (U5) |
| `provisionNotBeforeMs` | nullable i64 | cross-session provisioning deferral after a 429 **or a capacity failure [R1]** (**added by U1 — see “Deviations”**) | W1b, **W1c [R1]** |

It lives inside the vault region and nowhere else. **Nothing decoy-related may be written to
device-level storage** (`SettingsRepository`, `DeviceSettings`, any `SharedPreferences`) — a
device-level record of how many synthetic accounts exist is a vault-count oracle and destroys the
deniability `VAULT_ARCHITECTURE.md` §3 establishes. **This extends to diagnostics:**
`BootDiagnostics` writes a device-level file, so no decoy component may take a diagnostics or log
sink at all. That is enforced structurally (the U1 classes have no such constructor parameter), not
by discipline.

The sealed region is fixed-size (`SLOT_PAYLOAD_BYTES = 256 KiB`, `VaultPayload.kt:17`) and does not
grow, so the section's presence or absence is not observable from the encrypted image.

## WRITERS

| # | Writer | When | What it writes into `TAG_DECOY` | Durable? | Status |
|---|---|---|---|---|---|
| W1 | `DecoyAccountProvisioner.provision()` | first unlocked session in which provisioning is requested, no synthetic account exists, and no deferral is in force | **ONE `mutate`** setting `accountId` + `identityKeyPair` + both tokens together. Never a partial credential set. | **YES [R1]** — `flushBeforeAck` before it returns `true`. A throw ⇒ returns `false` ("not this session"); the credentials stay live+scheduled, the key is NOT wiped, and the next session finds them rather than re-registering | **this unit (U1)** |
| W1b | `DecoyAccountProvisioner.provision()` on **429** | relay answers `register` with 429 `rate_limited` | `provisionNotBeforeMs` only; credentials untouched (still absent) | **YES [R1]** — "back off ACROSS sessions" is a durability claim, so mutate **and** flush. Best-effort: a flush failure is swallowed (a lost back-off costs one extra attempt, and this path may not throw) | **this unit (U1)** — see Deviations |
| W1c | `DecoyAccountProvisioner.revertAndDefer()` on **`VaultCapacityException`** | the credential commit does not fit the fixed region | restores the section to its pre-commit value **and** sets `provisionNotBeforeMs`, in ONE mutate | **YES [R1]**, best-effort as W1b | **this unit (U1)** — **NEW [R1]** |
| W2 | `DecoyAccountProvisioner.refreshTokens()`, via `DecoyAuthStore.storeTokens` | session mint at unlock; refresh on a mid-session 401 (refresh-token TTL is 7 days, `auth/jwt.go:26`) | tokens only; all other fields untouched | **NO, deliberately** — coalesced, exactly like `VaultAuthStore`: tokens are re-mintable from the stored identity key | **this unit (U1)** |
| W2b | `DecoyAuthStore.clearTokens()` | caller drops the synthetic session | both token fields → null; the holder is NOT created if absent | NO — coalesced, same reasoning as W2 | **this unit (U1)** — **missing from the first table [R1]** |
| W2c | `DecoyAuthStore.clearAccount()` | caller retires the synthetic account | zeroes the identity key in place, then nulls `accountId` + `identityKeyPair` **and resets `counterHighWater` to 0** | NO — coalesced. A lost clear re-exposes credentials the caller wanted gone; acceptable only because the array was already zeroed in RAM and the next writer re-schedules. **U2+ must flush if it ever means "account destroyed"** | **this unit (U1)** — **missing from the first table [R1]** |
| W3 | `DecoyCounterReservation.next()` | reservation exhausted, or the durable mark no longer matches the held block (once per 64 issued counters) | `counterHighWater` only, **monotonically increasing** | **YES [R1]** — `mutate` then `flushBeforeAck`, and **only then** does the RAM cursor advance. ~~"the reservation is written durably by the mutate"~~ was the round-1 error | **this unit (U1)** — moved from U2 by the U1 task brief |
| W4 | `DeadAirPinger.rearm()` | after each dead-air ping fires | `deadAirNextFireAtMs` only | U5 decides | **U5 — not built here** |
| W5 | `VaultRuntime.mutate` (existing) | every write above, without exception | re-encodes the WHOLE `VaultState` under `stateLock` and **SCHEDULES** one atomic reseal | **NO — this is the correction.** ~~"schedules one atomic reseal" read as durability~~; `VaultSession.update` marks dirty and returns | existing |
| W6 | `VaultRuntime.flushBeforeAck` (existing) | W1, W1b, W1c, W3 | nothing of its own — it forces the scheduled payload to disk synchronously | **it IS the durability**; refuses while `capacityExceeded` is set; a throw means DO NOT ACK / never issued | existing — **new row [R1]** |

**W5 is not a formality, and W6 is the half it was missing.** There is no decoy-specific persistence
path: `DecoyAuthStore` and `DecoyCounterReservation` reach disk only through `VaultRuntime.mutate`,
exactly as `VaultAuthStore` does — but reaching `mutate` only makes a write *scheduled*. Every U1
write whose correctness depends on surviving process death pairs it with `flushBeforeAck`, and the
table now states per writer which ones those are.

Lock order stays `reservation lock → runtime.stateLock → session locks → storage lock`
(the reservation lock is a new OUTERMOST lock held by exactly one class; nothing takes
`runtime.stateLock` and then the reservation lock, and no decoy component is ever called from inside
a session persist sink). **Adding `flushBeforeAck` does not change that** — it takes `stateLock`,
RELEASES it, then runs the disk-bound `flushNow` (`VaultRuntime.kt:168-186`), so holding the
reservation lock across it nests no deeper than `mutate` already did.

### Allocator uniqueness — new invariant [R1]

**A runtime must have at most ONE live counter allocator.** Two allocators each hold their own RAM
block over one durable mark and can interleave `0, 64, 1` — a counter REGRESSION on the wire, which
is the exact fingerprint the reservation exists to prevent. Round 1 found this enforced only by a
kdoc sentence, i.e. not enforced. Two structural defences now:

1. `DecoyCounterReservation`'s constructor is **private**; `forRuntime(runtime)` returns the SAME
   instance per runtime (weak on both sides, so nothing is kept alive), making two live allocators
   unrepresentable rather than merely discouraged.
2. Every `next()` re-reads the durable mark and **abandons its block unless the mark still equals
   the block's exclusive end**. So any future writer of `counterHighWater` (W2c's reset, U5) causes
   a fresh reservation — a skip — never a spend below the mark.

## READERS, and what each assumes `TAG_DECOY` MEANS

| # | Reader | Assumes `TAG_DECOY` means | Still true after W1–W4? |
|---|---|---|---|
| R1 | `VaultStateCodec.decode` | "a section tag I recognize; an unrecognized tag is corruption" (`VaultState.kt:285-286`) | **NO for 0.9.x builds — see the hazard below.** YES for builds carrying the tag. |
| R2 | `DecoyCounterReservation` / `DecoySender.send()` (U2) | "these counter values have never been issued before" | YES **[R1, corrected mechanism]** — ~~"the reservation is written durably BEFORE any value in it is spent"~~ was true as an invariant and false as a description of the code: `mutate` only scheduled it. The mark is now made durable by `flushBeforeAck` before the RAM cursor advances, so a crash SKIPS values and can never reuse one. A flush throw issues nothing. |
| R3 | `DeadAirPinger` (U5) | "next-fire is in this vault's own timeline, not the device's" | YES — per-vault, torn down at lock. **U1 leaves the field unset**, so U5 inherits `null` = "never armed". |
| R4 | provisioning entry point (`DecoyAccountProvisioner.provisionIfNeeded`) | ~~"absent section = not provisioned; present = ready"~~ ~~**CORRECTED:** "`accountId != null && identityKeyPair != null` = ready"~~ **CORRECTED AGAIN [R1]:** "the credential pair is present in the live state **AND** `VaultRuntime.capacityExceeded` is clear" | YES **only with both corrections**. The first row is falsified by W1b (a 429 creates a section that is PRESENT and NOT ready). The second is falsified by the capacity path: an overflowing `mutate` RETAINS the credential pair in the live state unscheduled, so a check against live presence alone answers "ready" for credentials that `flushBeforeAck` refuses and that lock/process death discards. The flag is runtime-wide, so this reports false while an unrelated overflow is outstanding — conservative in the right direction, since nothing decoy-related can be made durable then anyway. |
| R5 | `VaultRuntime.capacityExceeded` (via `mutate` → `encode`) | "the encoded state fits `MAX_PAYLOAD_CONTENT_BYTES`" | YES, **and it is proved, not assumed** — U1 ships a measured worst-case byte budget (`DECOY_SECTION_BUDGET_BYTES`) and a test asserting it. `capacityExceeded` fail-closes `flushBeforeAck` (`VaultRuntime.kt:176-178`), so an overflow here is a durability bug, not cosmetic. |
| R6 | `VaultState.wipe()` (`VaultState.kt:83`) | "every held secret is zeroed / dereferenced at close" | **NO until amended — this is a NEW obligation.** `identityKeyPair` is raw private key material in a `ByteArray`, the exact class of secret `wipe()` is required to ZERO (not merely dereference). U1 amends `wipe()`. |
| R7 | `VaultStateCodec.parsePlaintext`'s decode-failure catch (`VaultState.kt:311-320`) | "a decode failure strands no key material un-wiped" | **NO until amended.** The catch today zeroes only the partial `signal` map. `TAG_DECOY` decodes to a live private key that a LATER malformed/duplicate/unknown section would strand. U1 extends the catch. |
| R8 | `SessionContainer` init (`ZitroneApp.kt:~1600`) | "`VaultStateCodec.decode` throws ⇒ refuse the unlock, wipe the `VaultOpen`" | YES, unchanged — and this is the path a 0.9.x downgrade takes (see hazard). |

## THE HAZARD THIS TABLE EXISTS TO CATCH

**`VaultStateCodec` is strict-v1: an unknown tag THROWS, it is never skipped** (`VaultState.kt:286`,
comment at 285: "an unknown tag is corruption / a wrong version, never skipped"). A vault written by
a 0.10.0 build carrying `TAG_DECOY`, then opened by a 0.9.x build — downgrade, sideload of an older
APK, a rollback — **does not degrade gracefully: it reads as a corrupt vault.** `SessionContainer`'s
decode-first construction (R8) turns that into a refused unlock.

**RULING (maintainer, 2026-07-27, spec §4): option (a).** One-way format bump, disclosed exactly as
0.9.1's fresh-install-only decision was. **Do NOT add forward-tolerance to the codec.** U6 owns the
disclosure text (spec §4.1); U1 must not weaken the strictness to soften it.

**Second-order consequence U1 must respect:** the break is only *realized* when a vault actually
carries the tag. U1 therefore writes `TAG_DECOY` only when it has something real to record —
credentials, a reservation, or a deferral — and omits the section entirely otherwise. A vault that
never provisions and never gets a 429 stays byte-compatible with 0.9.x by construction. This is a
consequence of "optional section, omitted when unset", not a new tolerance mechanism.

## THE ORDERING CONSTRAINT — register BEFORE commit

`TAG_DECOY` is written only through `VaultRuntime.mutate`, which re-encodes the entire state under
one lock and schedules one atomic reseal. There is no multi-write sequence and therefore no partial
state to reason about: a crash leaves either the previous whole state or the new whole state.

The one ordering constraint, enforced in code and pinned by test:

> **The synthetic account must be registered on the relay BEFORE its credentials are committed to
> `VaultState`. A commit failure must leave an ORPHANED RELAY ACCOUNT (harmless — an unused
> registered account), never a `VaultState` referencing an account that does not exist (which breaks
> every subsequent decoy).**

This has a consequence that rules out the obvious implementation. `ApiClient.register()` writes the
new account id into its `AuthStore` the instant the 201 lands (`ApiClient.kt:167`), and
`createSession()` writes tokens the instant they are minted (`:186`). Wiring the synthetic client
straight to a vault-backed store would therefore commit `accountId` **alone**, with no identity
keypair — and an account id whose signing key was never persisted is exactly the dangling reference
above (worse than an orphan: it is unauthenticatable and permanent).

→ **Provisioning runs its `ApiClient` against a RAM-only `AuthStore`** (`StagingAuthStore`), so
`register` + `createSession` mutate nothing durable, and the credential set
`{accountId, identityKeyPair, accessToken, refreshToken}` is committed in **one** `runtime.mutate`
afterwards. Interruption points and their outcomes:

| Crash / failure point | Relay state | `VaultState` state | Reported to caller | Verdict |
|---|---|---|---|---|
| before `register` | nothing | unchanged (absent) | `false` | clean retry |
| `register` request sent, response lost | account may exist | unchanged (absent) | `false` | **orphan — accepted, harmless** |
| after 201, before `createSession` | account exists | unchanged (absent) | `false` | **orphan — accepted** |
| after tokens minted, before `mutate` | account exists | unchanged (absent) | `false` | **orphan — accepted** |
| `mutate` throws (capacity), **IN SESSION** **[R1 — the row that was missing]** | account exists | mutation retained in RAM, NOT scheduled; `capacityExceeded` set — the live state shows a complete credential pair that no reader will ever find on disk | — | This is the state round 1 caught: it lasts only until W1c runs, but while it lasts `isProvisioned` must NOT say ready (R4) |
| …then W1c reverts + defers **[R1]** | account exists | section restored to its pre-commit value **plus** a durable `provisionNotBeforeMs`; `capacityExceeded` CLEARED by the successful re-encode | `false` | **orphan — accepted.** The back-off bounds re-registration to once per 60–90 min instead of once per unlock; clearing the flag is required so a cover-traffic write never blocks the inbound path's flush-before-ack |
| …and even the revert cannot be encoded | account exists | a bare revert is attempted; if that fails too, the live state keeps the mutation and `capacityExceeded` stays set | `false` | last-resort; the identity key is then NOT wiped, because the live state still references it |
| after `mutate` returns, before `flushBeforeAck` **[R1]** | account exists | credentials scheduled, not durable | `false` (the flush's throw is not swallowed into `true`) | orphan on the next open **unless** the pending reseal or `close()` lands them; either way no caller was told "ready" on non-durable bytes |
| after `flushBeforeAck` returns | account exists | credentials durable | `true` | success |

**No row produces `accountId` without `identityKeyPair`, in RAM or on disk.** The on-disk half of
that is now pinned by a test that inspects **every sealed generation** the persist sink was handed,
under a zero-length coalescing ceiling (`no generation EVER written carries a half credential set`)
— a multi-step commit's intermediate state would show up there, and does: the test was verified to
fail against a deliberately two-mutate commit.

Tokens are deliberately NOT flush-before-ack'd: like `VaultAuthStore`'s (`AuthStore.kt:145` kdoc),
they are recoverable by re-minting a session from the stored identity key, so a coalesced write is
correct. **The credential set and the back-offs are not in that category and are flushed** (W1,
W1b, W1c, W3).

## THE COUNTER INVARIANT — skip, never regress

`counterHighWater` means: **every counter value strictly below it may already have been issued.**

- Session start: RAM `next = limit = counterHighWater` (durable).
- `next()` when `next == limit`: `mutate { counterHighWater += 64 }` FIRST; only on a successful
  mutate do the RAM `next`/`limit` advance. Values in `[old, old+64)` are then issued from RAM.
- Crash at any point: the next session reads the persisted high-water and starts there. Unspent
  reserved values are **skipped**.

A skip is invisible — a real Double Ratchet skips counters on any dropped message. A REGRESSION is a
tell no real ratchet can produce, which is why the durable write precedes the first spend and why the
RAM advance is conditional on the mutate succeeding. One durable write per 64 decoys, per §2.3.

## WHAT THIS WRITE MUST NOT DO

1. **No device-level storage.** Vault-scoped or nowhere — including logs and `BootDiagnostics`.
   Enforced structurally: no decoy class takes a diagnostics/log sink.
2. **Must not make the sealed region's size vary with decoy state.** The region is fixed-size and
   stays so; the section rides inside the compressed, padded, sealed plaintext.
3. **Not a device-global singleton.** One instance per live `SessionContainer` (`NotificationScheduler`
   parity invariant 3). U1 ships the components unwired (see “Scope boundary”), constructed from a
   `VaultRuntime` — which is already per-session — so a global is structurally impossible.
4. **Must not survive teardown.** The provisioner is `suspend` and owns no timers; cancelling the
   session scope cancels it. U3/U5 add the `cancelAll()`-equivalent when they add timers.
5. **Must not name a slot, vault index, or real/decoy VAULT status** in code, logs, diagnostics, or
   string resources. (`TAG_DECOY` / `Decoy*` name the *cover-traffic mechanism*, which is the spec's
   own vocabulary — §4 W1–W4. The forbidden thing is labelling a VAULT as real vs decoy, or naming a
   slot index. U1 adds no string resource and no log line at all.)
6. **Must not push `VaultState` near `MAX_PAYLOAD_CONTENT_BYTES`.** U1 delivers a measured worst-case
   budget + a headroom test, since R5 depends on it.

## REGISTRATION IS A SCARCE SHARED GLOBAL RESOURCE

`registerLimit` is keyed on `c.IP()` (`handlers.go:166`), which behind Caddy is Caddy's socket
address — **one bucket worldwide** for clearnet and every Tor/I2P client. Therefore:

- **Lazy.** `provisionIfNeeded()` is called from the first session that actually needs a decoy — never
  eagerly at vault creation. A vault that never sends never spends a registration. (U1 ships the entry
  point; U3 supplies the caller.)
- **One RELAY attempt per session, ever.** An in-RAM latch means a failure is not retried within the
  session — no tight loop is even expressible. **[R1]** The latch is taken immediately before the
  relay sequence and is NOT burned by a purely local refusal: a back-off window that expires
  mid-session must still get its one attempt, because the latch is one *attempt*, not one *check*.
  (Round 1: burning it on the deferral check meant a long-lived session made zero attempts for the
  whole 60–90 min window and then still made none.)
- **429 backs off ACROSS sessions**, durably (W1b), for a randomized 60–90 min (the limiter window is
  1 h; the jitter avoids a synchronized retry stampede).
- **A vault that cannot STORE the account backs off the same way (W1c) [R1].** Without it, a vault
  near `MAX_PAYLOAD_CONTENT_BYTES` registers a fresh account on EVERY unlock and discards it —
  systematic, unbounded spend against a bucket shared by every client worldwide, which is a
  different thing from the accepted one-off orphan. **Residual, stated rather than hidden:** the
  back-off bounds this to one registration per 60–90 min per chronically-full vault, not to zero. A
  pre-flight headroom check would suppress the register entirely, and was deliberately NOT added:
  the only accurate capacity test is the encode itself, and a conservative budget-based pre-flight
  would make the genuine commit-overflow path unreachable and therefore untestable. Revisit if a
  vault is ever expected to sit at the boundary (a realistic populated state is ~8 KB of 262 112 B).
- **Every failure degrades SILENTLY to decoys-off.** No exception escapes `provisionIfNeeded()`, no
  UI is shown, no diagnostic is written, onboarding is never blocked. The caller gets
  `null` = "no synthetic account this session".

## CAPACITY BUDGET (to be measured, then recorded here)

Worst-case section contents: 36-char UUID + 65-byte `IdentityKeyPair.serialize()` + an RS256 access
JWT (~530 chars: 342-char base64 signature over a 2048-bit key, plus header/claims) + a 43-char
refresh token (`auth/jwt.go` `NewRefreshToken`: 32 random bytes, RawURL base64) + three fixed-width
integers. Uncompressed section ≈ 790 B. `DECOY_SECTION_BUDGET_BYTES = 1024` with the measured
deflated delta asserted under it. `MAX_PAYLOAD_CONTENT_BYTES = 262 112`, and a realistic full state
is ~8 KB (PR-D benchmark), so the headroom is ~3 orders of magnitude — the budget test exists to
catch a FUTURE field addition, not because this one is tight.

**MEASURED** (`VaultDecoySectionTest."the decoy section costs less than its declared budget…"`,
run 2026-07-27, twice): worst-case **encoded delta = 640–643 B** against a declared budget of
**1024 B**; a realistic populated state carrying the section encodes to **924–927 B of 262 112 B**,
leaving **~261 185 B (99.6 %) free**. The few-byte run-to-run spread is DEFLATE reacting to a
freshly generated (genuinely random) identity keypair, not fixture noise. The test asserts
`delta > 0` as well as `delta ≤ budget`, so a codec that silently dropped the section cannot
satisfy it.

## SCOPE BOUNDARY — what U1 deliberately does NOT do

The trigger for provisioning is "the first session that actually sends a decoy", and the decoy sender
is U2/U3. U1 therefore ships the codec section, the provisioner, the auth facade, and the counter
reservation **unwired from `SessionContainer`** — the same posture `VaultRuntime` itself shipped in
(`VaultRuntime.kt:69-70`: "deliberately NOT wired into any app coordinator, DI graph, unlock router,
or migration — that is a later sub-phase"). Nothing in production calls them yet, so U1 cannot
register a synthetic account on any real device and cannot spend a registration from the shared
bucket. U3 supplies the call site.

## SPEC CORRECTIONS — facts in `DECOY_TRAFFIC_0.10.0_SPEC.md` that are stale at `d44616c5`

1. **§6.1 “`regpow` is not in this tree — it lives on the unmerged `origin/cx23/0.9.4-registration-pow`
   branch.” — STALE for the CLIENT.** `apps/android/.../crypto/RegistrationPow.kt` is on `main` and
   is wired into `MessagingCoordinator.bootstrapLoop()` (`MessagingCoordinator.kt:465-486`), shipped
   in 0.9.4-beta at `D=5`. `ApiClient.registrationChallenge()`/`register(powProof=)` exist
   (`ApiClient.kt:133,147`). Still TRUE for the RELAY: `handlers.go` `Register` (154–208) has no PoW
   check on `main`. Consequence for U1: the synthetic registration must mirror the real path —
   fetch a challenge, treat 404 as "relay predates PoW, register proofless", otherwise solve — and
   the §6.2a "decide before U1" question is answered: **background solve, no progress UI, silent
   failure**, because the hard constraint "never block onboarding, never surface an error implying a
   fault" forecloses reusing the pitcher screen.
2. **§6.2 “main still reads `ratelimit.New(5, time.Hour, ...)` at `handlers.go:48`” — STALE.** `main`
   now reads `ratelimit.New(300, time.Hour, cfg.RateLimitEnabled)` at `handlers.go:54`; the interim
   widening is merged. The §6.2a budget arithmetic (300/h global bucket, 150→100 devices/h) is
   therefore correct as written; only the "not merged to main / a redeploy silently reverts it"
   warning no longer applies to the limiter. **The `c.IP()` keying is unchanged (`handlers.go:166`),
   so the bucket is still global — CX23 P2 remains open.**

## DEVIATIONS FROM THE SPEC, AND WHY

1. **`provisionNotBeforeMs` is a SIXTH thing in a section §4 describes as holding three.** The U1
   brief requires "on 429 back off **across sessions**". Across-sessions means durable, and the
   no-device-storage rule means vault-scoped, so the deferral has exactly one legal home: this
   section. Consequence, carried into R4 above: **section presence no longer implies readiness**, and
   every reader must key on the credential pair. Flagged rather than absorbed silently, because it is
   precisely the "moving what a durable signal MEANS" shape the round-12 pattern warns about.
2. **W1 does not write a first dead-air fire time**, though §4's W1 row says it does. The dead-air
   *schedule* is U5 and §3.2 re-framed it from wall-clock to in-session ("1–2 per equivalent
   unlocked-day"), which makes a durable wall-clock next-fire of questionable meaning — U5 must
   settle that. The field exists and round-trips; U1 writes `null`. Deciding the distribution here
   would be U1 designing U5's mechanism blind.
3. **W3 (counter reservation) is built in U1**, not U2 as §4's writer table says. This follows the U1
   task brief, which lists counter reservation in U1 scope. Only the reservation ALLOCATOR is built;
   the `DecoySender` that spends the values is still U2.

## REVIEW ROUND 1 — what changed in the unit, and what did not

Paired-blind (Codex + Grok), adjudicated in `u1-r1-adjudication.md`. Fix round 1 of a cap of 6.

| # | Finding | Disposition |
|---|---|---|
| F1 | counter reservation spends after `mutate`, which only schedules | **fixed** — `mutate` → `flushBeforeAck` → advance the RAM cursor. W3/R2 corrected above. |
| F2 | the reservation lock is per allocator instance, not per runtime | **fixed structurally** — private constructor + `forRuntime` returns the one allocator per runtime, plus stale-block abandonment. See "Allocator uniqueness". |
| F3 | `isProvisioned()` reads live state only, so it reports ready for retained-over-capacity credentials | **fixed** — readiness also requires `!capacityExceeded`. R4 corrected. |
| F4 | no durable back-off on capacity ⇒ a new registration on every unlock | **fixed** — W1c reverts the retained mutation and writes a durable deferral in one mutate. Residual recorded above. |
| F5 | the 429 back-off is written the same non-durable way | **fixed** — W1b mutates and flushes. |
| F6 | the one-attempt latch is burned by a purely local deferral check | **fixed** — the latch is taken immediately before the relay sequence. |
| F7 | prekey PRIVATE halves left on the heap | **partially fixed, and the rest is stated as not fixable.** They are never serialized: they live in Rust-owned memory behind a libsignal handle, and `ECPrivateKey` in libsignal-client 0.46.0 exposes no `close()`/`destroy()` — only `finalize()`. Calling `Native.ECPrivateKey_Destroy` via `unsafeNativeHandleWithoutGuard()` would double-free at finalization. The same residue applies to every libsignal key this app creates, the real account's identity included. What WAS in reach is residency: the bundle is now generated by `DecoyIdentity.generateBundle()` immediately before `register`, so the 101 private keys no longer live across the seconds-long PoW solve. Recorded in the class kdoc so it is not rediscovered as a defect. |
| F8 | `clearAccount()` leaves `counterHighWater`, so a re-provisioned account starts at 128 | **fixed** — the mark is reset with the credential set (W2c). Safe against a live allocator because of the stale-block check. |
| F9 | non-discriminating tests | **fixed, and each replacement was verified by mutation** — see below. |
| F10 | invariant-table defects | **fixed** — this document. |

### The F9 tests, and the mutation each was checked against

The standing failure mode here (`failures.md`, six prior occurrences) is a test that passes whether
or not the property holds. Each test below was run against a deliberately broken implementation and
observed to FAIL; the mutations were then reverted and the suite re-run green.

| Test | Mutation it was verified against |
|---|---|
| `the first value is issued only AFTER a reservation is DURABLE`, `one durable write per block`, `a restart SKIPS the unspent remainder`, `concurrent callers never receive the same value`, `a custom block size is honoured` | `flushBeforeAck` removed from `reserveLocked` — all fail. They now read the SEALED PAYLOAD the persist sink was handed (opened with the vault key, decoded through the real codec) instead of the live state; the restart case reopens from that image rather than rebuilding `DecoyState` in RAM. |
| `a reservation whose durable write FAILS issues nothing` | new: a persist sink that throws. Fails without the flush (a value is issued against a mark that never reached disk). |
| `provisioning registers on the relay BEFORE it commits, and the commit is DURABLE` | `flushBeforeAck` removed from `provision` — fails. |
| `no generation EVER written carries a half credential set` | the credential commit split into TWO mutates — fails. Zero coalescing ceiling + unconfined flush context makes "the reseal landed between two mutations" deterministic instead of a rare race; every generation handed to the sink is decoded and checked. |
| `a 429 defers provisioning ACROSS sessions`, `a back-off window that expires mid-session still gets its one attempt` | flush removed from the deferral write — fail. The "next session" is built from the persisted image, not from the same live runtime. |
| `a failed capacity commit does NOT report the vault as provisioned` | `capacityExceeded` dropped from the readiness check — fails. |
| `a capacity failure backs off DURABLY` / `hands the vault back a flushable state` | W1c removed — fail. |
| `two callers over one runtime get the SAME allocator`, `a second caller asking for a different block size fails closed`, `a block whose durable mark moved underneath it is abandoned` | the shared-instance factory disabled / the staleness check removed — fail. |
| `clearAccount resets the counter mark` | the reset removed — fails. |
| `the decode-failure cleanup ZEROES the decoy identity key` | `decoy?.wipe()` removed from `wipePartialDecode` — fails. |

**Two things are deliberately NOT claimed**, because claiming them is the defect this list exists to
prevent:

1. **The decode-failure wipe is not observable through `decode`.** Both buffers are allocated inside
   the decoder and are unreachable from a caller, so a test that only decodes a malformed payload
   can assert the rejection and nothing more. The cleanup was therefore split into
   `VaultStateCodec.wipePartialDecode`, which IS tested directly on arrays the test owns; the
   remaining unobserved step is the single call from `parsePlaintext`'s catch. The old test's name
   and comment implied coverage it did not have and were corrected.
2. **`interleaved use never regresses` does not discriminate between the two allocator defences.**
   It passes with the shared-instance factory disabled, because the staleness check already prevents
   the regression. That was measured, not assumed, and the test says so; defence 1 is pinned
   separately by `assertSame`.

Also renamed rather than re-scoped: the budget test no longer calls its input a "worst case". The
JWT shape is server-fixed and the refresh token is 32 random bytes, so what it measures is the
largest section the RELAY can produce, which is what the budget needs to cover.
