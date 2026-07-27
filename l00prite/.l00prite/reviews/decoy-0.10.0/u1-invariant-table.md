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

> **CORRECTED AGAIN after review round 2 (2026-07-27).** Round 1 answered three findings with three
> guards — a stale-block check inside the allocator, a snapshot revert inside the provisioner, and a
> capacity-aware readiness flag. **All three became round-2 defects**, and they share one shape:
> each reasons about `TAG_DECOY` state sampled OUTSIDE the lock that protects it, or folds two
> different questions into one predicate. Round 2 fixes the two roots instead of the interleavings:
> **(a) one SECTION lock** (`crypto/vault/DecoySectionLock.kt`) serializes every read-modify-write
> across the allocator, `DecoyAuthStore` and the provisioner, so a check is atomic with the spend it
> guards and a revert can only restore state read under the same lock; **(b) the readiness predicate
> is SPLIT** into `hasAccount()` (gates registration, reads nothing but the section) and `canSend()`
> (gates cover traffic). A third structural change follows from the same discipline: the back-off is
> **written ahead** of any relay contact rather than in response to a failure, which removes the
> absolute-capacity edge instead of patching it. Corrections are marked **[R2]**.

> # ⚠️ CORRECTED IN PLACE BY U2 FIX ROUND 3 (2026-07-27) — THE COUNTER STATE IS GONE. READ THIS FIRST.
>
> **This table is not a historical record. It is the live contract U3 and U4 are required to consult
> before implementing against `TAG_DECOY`, and both are unwritten.** Until this correction it still
> specified `counterHighWater`, `deadAirNextFireAtMs`, writers W3 and W4, `DecoyCounterReservation`,
> the allocator's uniqueness and locking rules, and a counter reset inside `clearAccount` — **all of
> which U2 round 2 DELETED** when the maintainer cut the idle/dead-air ping (spec §3.0). An
> implementer following the table faithfully would have rebuilt the allocator and re-added both
> fields to a durable vault surface, which is the opposite of what the code now says.
>
> The removed rows are **struck through in place with the reason**, the way this document already
> strikes its own superseded text and the way the spec strikes its W3/W4 rows. They are not deleted,
> because a contract that quietly rewrites itself teaches the next unit nothing — but they are no
> longer readable as instructions.
>
> **THE CANONICAL STATEMENT OF `TAG_DECOY`'s FIELD SET IS `DecoyState`'s KDOC IN
> `crypto/vault/VaultState.kt`, NEXT TO THE FIELDS THEMSELVES.** It carries the "do not re-add a
> counter field for a paired decoy" instruction and the reason. **This table's field list is a
> derived copy: on any disagreement the kdoc wins, and any field-set change is made THERE first.**
> That is the same canonical-pointer device fix round 4 used for the tag-write trigger (whose four
> rows now live in the codec kdoc beside the `takeUnless { it.isEmpty }` that produces them) — and
> it is used here for the same reason: **this is the ninth recurrence in this feature of a correction
> landing where the reviewer pointed while the parallel copy survived.** Two independent reviewers
> found this one. The rule in `failures.md` — *grep for every restatement, especially the compressed
> and summary ones* — was written inside this very document, in the `[R5]` block below, and this
> document was then the copy that survived.
>
> Corrections from this round are marked **[U2R3]**.

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
| `accessToken` / `refreshToken` | nullable utf8 | that account's session tokens | W1, W2, W2b, **W2c (clear) [R5]** — round 2's G6 made the clear load-bearing (tokens must die with the account, or a cleared account keeps working bearer credentials until expiry); its omission here read as if `clearAccount` left tokens standing |
| ~~`counterHighWater`~~ | ~~i64~~ | ~~counter-reservation high-water mark: every value `< counterHighWater` is considered ISSUED~~ **[U2R3] FIELD DELETED.** The paired decoy mirrors the covered envelope's `message_number` (arithmetic, not taste: base64 quantises to 4 characters, so the `ciphertext` field cannot absorb a 1–3 byte decimal-width difference), which left the allocator with no consumer on the paired path; its last candidate consumer, the idle ping, was **cut** (spec §3.0). U2 R2 deleted the field, `DecoyCounterReservation` and its test class rather than leave an unreachable writer on a durable vault surface. **Do not re-add it** — see `DecoyState`'s kdoc. | ~~W3, W2c (reset)~~ — **no writers** |
| ~~`deadAirNextFireAtMs`~~ | ~~nullable i64~~ | ~~dead-air schedule next-fire (field reserved; **U1 never sets it**)~~ **[U2R3] FIELD DELETED** with the ping that was its only consumer (spec §3.0). U5 does not exist. | ~~W4 (U5)~~ — **no writers** |
| `provisionNotBeforeMs` | nullable i64 | cross-session provisioning deferral — ~~after a 429 **or a capacity failure [R1]**~~ **[R2] written AHEAD of every attempt that reaches the relay sequence** (**added by U1 — see “Deviations”**) | W1 (retires on success), W1b (writes), W1c (restores), **W1d (retires on a spent-nothing failure) [R3, listed R4]** |

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
| W1 | `DecoyAccountProvisioner.provision()` | first unlocked session in which provisioning is requested, no synthetic account exists, and no deferral is in force | **ONE `mutate`** setting `accountId` + `identityKeyPair` + both tokens together, **and `provisionNotBeforeMs = null`** — ~~a success is the only thing that retires W1b's write-ahead deferral~~ **[R3/R4] W1d retires it too, on a failure that spent nothing.** Success is the only retirement that happens *while writing something*, which is why it rides in this mutate rather than a second one: there is no window where the credentials are durable and the deferral is not. Never a partial credential set — **[R4] and the codec now enforces that** (`requireDecoyCredentialsPaired` refuses an id without a key, a key without an id, or tokens without an id, on encode **and** decode), so the pairing is a property of the format and not only of this writer's care. ~~**`counterHighWater` is NOT written here [R2]** — it stays 0 until W3 first reserves.~~ **[U2R3] moot — the field is gone.** | **YES [R1]** — `flushBeforeAck` before it returns. A throw ⇒ returns `false` ("not this session"); the credentials stay live+scheduled, the key is NOT wiped, the next session finds them rather than re-registering, and ~~**[R2]** an instance-scoped~~ **[R3] a per-runtime `Gate`-scoped** `credentialsUnconfirmed` flag keeps `canSend()` false for **every** provisioner over that runtime, so neither a later call nor a second instance can flip to ready on never-flushed bytes (instance scope was H3) | **this unit (U1)** |
| W1b | `DecoyAccountProvisioner.reserveBackoff()` — **WRITE-AHEAD [R2]** | ~~relay answers `register` with 429~~ **BEFORE any relay contact**, on every attempt that passes the deferral check | `provisionNotBeforeMs` only; credentials untouched (still absent) | **YES** — "back off ACROSS sessions" is a durability claim, so mutate **and** flush. ~~Best-effort~~ **[R2] NOT best-effort: a failure means no registration is spent at all.** A capacity failure here is reverted, so a cover-traffic write never leaves `capacityExceeded` set over the real inbound path | **this unit (U1)** — see Deviations |
| W1c | `DecoyAccountProvisioner.revertSection()` on **`VaultCapacityException`** | the credential commit does not fit the fixed region | restores the section to **exactly what the same critical section read immediately before the commit** — which already carries W1b's durable deferral, so there is no separate "and defer" step and no bare-revert branch left [R2] | **NO, and it needs none [R2]** — the restored value IS what is already on disk; the re-encode's only job is clearing `capacityExceeded` | **this unit (U1)** — **NEW [R1], reshaped [R2]** |
| W1d | `DecoyAccountProvisioner.clearBackoff(deferral)` — **the retirement W1b's deferral gets when it protected nothing [R3]** | the attempt fails **before** `register` is entered: offline challenge fetch, DNS failure, failed PoW, a local crypto fault (bundle generation), a cancelled scope | `provisionNotBeforeMs` → null, and nothing else. **Compare-and-clear under the section lock**: retires only the deadline THIS attempt wrote, so a deferral another writer put there meanwhile is left standing — the same "only restore what you read under this lock" rule W1c follows. Emptying the holder is what makes the codec omit `TAG_DECOY` and gives the vault back its 0.9.x readability | **YES** — mutate **and** flush, mirroring W1b: a scheduled-only clear is undone by the same crash the write was made to survive. A throw leaves the deferral standing, which is the safe direction | **this unit (U1 R3)** — **row added R4; a genuine durable writer the WRITER inventory omitted, so W1 read as the only retirement path** |
| W2 | `DecoyAccountProvisioner.refreshTokens()`, via **`DecoyAuthStore.storeTokensForAccount(accountId, …)`** **[R5]** ~~`storeTokens`~~ | session mint at unlock; refresh on a mid-session 401 (refresh-token TTL is 7 days, `auth/jwt.go:26`) | tokens only; all other fields untouched. **The account id is re-read and compared under the section lock, and a mismatch is refused** — this is what closes H4 (snapshot → seconds of network → write, with a `clearAccount()` in the window resurrecting bearer credentials for a cleared account). **A future unit must NOT wire refresh through the bare `storeTokens`**, which writes whatever account is current rather than the one that was refreshed, reopening H4. | **NO, deliberately** — coalesced, exactly like `VaultAuthStore`: tokens are re-mintable from the stored identity key | **this unit (U1)**, path corrected **[R5]** |
| W2b | `DecoyAuthStore.clearTokens()` | caller drops the synthetic session | both token fields → null; the holder is NOT created if absent | NO — coalesced, same reasoning as W2 | **this unit (U1)** — **missing from the first table [R1]** |
| W2c | `DecoyAuthStore.clearAccount()` | caller retires the synthetic account | zeroes the identity key in place, then nulls `accountId` + `identityKeyPair` **+ `accessToken` + `refreshToken` [R2]** ~~**and resets `counterHighWater` to 0**~~ **[U2R3] no counter reset — there is no counter.** Under the SECTION lock [R2]. | NO — coalesced. A lost clear re-exposes credentials the caller wanted gone; acceptable only because the array was already zeroed in RAM and the next writer re-schedules. **U2+ must flush if it ever means "account destroyed"** | **this unit (U1)** — **missing from the first table [R1]**; tokens added **[R2]** |
| ~~W3~~ | ~~`DecoyCounterReservation.next()`~~ | ~~reservation exhausted, or the durable mark no longer matches the held block (once per 64 issued counters)~~ | ~~`counterHighWater` only, **monotonically increasing**~~ | ~~**YES [R1]** — `mutate` then `flushBeforeAck`, and **only then** does the RAM cursor advance~~ | **[U2R3] WRITER DELETED.** The class, its allocator registry, its lock participation and its whole test class are gone. **A future unit that finds itself wanting this writer back has almost certainly reintroduced a decoy that carries a counter of its own — which is a decoy whose frame length can differ from the envelope it covers.** Read `DecoyEnvelopeBuilder`'s kdoc before acting on that impulse. |
| ~~W4~~ | ~~`DeadAirPinger.rearm()`~~ | ~~after each dead-air ping fires~~ | ~~`deadAirNextFireAtMs` only~~ | ~~U5 decides~~ | **[U2R3] WRITER DELETED — U5 is CUT** (spec §3.0, maintainer decision 2026-07-27). There is no dead-air ping and no unit that schedules one. |
| W5 | `VaultRuntime.mutate` (existing) | every LIVE write above, without exception | re-encodes the WHOLE `VaultState` under `stateLock` and **SCHEDULES** one atomic reseal | **NO — this is the correction.** ~~"schedules one atomic reseal" read as durability~~; `VaultSession.update` marks dirty and returns | existing |
| W6 | `VaultRuntime.flushBeforeAck` (existing) | W1, W1b, **W1d [R4]** (**not** W1c [R2]; ~~W3~~ **[U2R3] deleted**) | nothing of its own — it forces the scheduled payload to disk synchronously | **it IS the durability**; refuses while `capacityExceeded` is set; a throw means DO NOT ACK / never issued | existing — **new row [R1]** |

**W5 is not a formality, and W6 is the half it was missing.** There is no decoy-specific persistence
path: `DecoyAuthStore` ~~and `DecoyCounterReservation`~~ **[U2R3]** and the provisioner reach disk
only through `VaultRuntime.mutate`,
exactly as `VaultAuthStore` does — but reaching `mutate` only makes a write *scheduled*. Every U1
write whose correctness depends on surviving process death pairs it with `flushBeforeAck`, and the
table now states per writer which ones those are.

Lock order stays `decoy SECTION lock → runtime.stateLock → session locks → storage lock`
(~~the reservation lock is a new OUTERMOST lock held by exactly one class~~ **[R2] it is held by
THREE**: ~~the allocator,~~ `DecoyAuthStore`'s writers, and the provisioner's commit — **[U2R3] TWO,
the allocator having been deleted; the lock still earns its place, and that was re-verified by
review, because both remaining participants run multi-call read-modify-write sequences over the
section and must exclude each other**; nothing takes
`runtime.stateLock` and then the section lock, and no decoy component is ever called from inside a
session persist sink). **Adding `flushBeforeAck` does not change that** — it takes `stateLock`,
RELEASES it, then runs the disk-bound `flushNow` (`VaultRuntime.kt:168-186`), so holding the section
lock across it nests no deeper than `mutate` already did.

### THE SECTION LOCK — the round-2 root fix [R2]

`crypto/vault/DecoySectionLock.kt`. **One monitor per live `VaultRuntime`, guarding SEQUENCES over
`TAG_DECOY`, not fields.** `stateLock` makes each individual `mutate` atomic, which is the wrong
granularity, because every correctness argument in this unit spans more than one runtime call:

**[U2R3] The allocator rows below are HISTORY — that class no longer exists.** They are kept because
they are the derivation of a lock that is still live and still load-bearing, and deleting the reason
a mechanism exists is how the next round deletes the mechanism. **The lock's remaining justification
does not depend on them:** `DecoyAuthStore`'s writers and the provisioner's commit each run a
read-modify-write sequence over the section that must be atomic against the other, and round 2's
review re-verified that independently of the allocator.

| Sequence | The two calls | What round 1 shipped | What round 2 found |
|---|---|---|---|
| ~~allocator~~ **[U2R3] deleted** | ~~`read` the durable mark → decide the block is current → `mutate`/spend~~ | ~~a private lock + a staleness check~~ | ~~`clearAccount()` takes no such lock, so a reset lands between check and spend; the allocator emits `1, 0` — a cleartext counter regression~~ |
| provisioner | `read` the section → (seconds of PoW + HTTP) → `mutate` credentials → restore on overflow | a snapshot taken before the network | any concurrent decoy write in that window is clobbered wholesale ~~, including a counter reservation — an OLDER high-water mark restored, values reissued~~ **[U2R3]** — today the loss is a concurrent token write or a `clearAccount`, which is enough |
| auth store | ~~`clearAccount()` resets the mark the allocator just checked~~ **[U2R3]** `storeTokensForAccount` reads the account id, does a network round-trip, then writes — with `clearAccount` free to land in the window (H4) | no lock at all | see row 1 |

Both are the same defect: **state sampled outside the lock that protects it.** More checks inside the
pieces cannot fix it; one lock across each whole sequence does. So:

- ~~the allocator's `lock` IS the section lock (not a private one), held from the mark read through
  the mutate, the flush, and the RAM cursor advance;~~ **[U2R3] deleted with the allocator;**
- `DecoyAuthStore`'s three writers take it (reads do not — `runtime.read` is already atomic and a
  caller acting on a stale single value is the caller's own race);
- the provisioner takes it around the **whole commit critical section**, and reads the value its
  revert will restore INSIDE it. **A revert may only ever restore state observed under the same
  lock the revert itself runs under.** The network is deliberately OUTSIDE the lock — holding it
  across a multi-second registration would stall the send path.

Lifetime: weakly keyed on the runtime, values hold no back-reference, nothing durable, no timers —
the same argument that cleared the allocator registry, and it evaporates with the session.

### ~~Allocator uniqueness — new invariant [R1]~~ — **[U2R3] SECTION DELETED**

**There is no counter allocator.** `DecoyCounterReservation` was removed in U2 round 2 along with
`counterHighWater`; nothing in the decoy path allocates a counter. The struck text below is kept
only because its *shape* is the reusable lesson — "a guard whose scope does not match the resource's
scope is not a guard", which H2/H3 then hit twice more with the provisioner's latch and its
unconfirmed-flush flag, and which the per-runtime `Gate` now answers. **Nothing below is an
instruction to implement anything.**

> ~~**A runtime must have at most ONE live counter allocator.** Two allocators each hold their own RAM
> block over one durable mark and can interleave `0, 64, 1` — a counter REGRESSION on the wire, which
> is the exact fingerprint the reservation exists to prevent. Round 1 found this enforced only by a
> kdoc sentence, i.e. not enforced. Two structural defences now:~~
>
> ~~1. `DecoyCounterReservation`'s constructor is **private**; `forRuntime(runtime)` returns the SAME
>    instance per runtime (weak on both sides, so nothing is kept alive), making two live allocators
>    unrepresentable rather than merely discouraged.~~
> ~~2. Every `next()` re-reads the durable mark and **abandons its block unless the mark still equals
>    the block's exclusive end**. So any future writer of `counterHighWater` (W2c's reset, U5) causes
>    a fresh reservation — a skip — never a spend below the mark. **[R2] This defence only means
>    anything because the re-read and the spend are inside the SECTION lock.** As shipped in round 1
>    it was a check in one runtime call acted on in the next, with `clearAccount()` free to land
>    between them — the check passed, the mark was then reset, and the block was spent anyway. A check
>    that is not atomic with the spend is not a check.~~

## READERS, and what each assumes `TAG_DECOY` MEANS

| # | Reader | Assumes `TAG_DECOY` means | Still true after W1–W4? |
|---|---|---|---|
| R1 | `VaultStateCodec.decode` | "a section tag I recognize; an unrecognized tag is corruption" (`VaultState.kt:285-286`) | **NO for 0.9.x builds — see the hazard below.** YES for builds carrying the tag. |
| ~~R2~~ | ~~`DecoyCounterReservation` / `DecoySender.send()` (U2)~~ | ~~"these counter values have never been issued before"~~ | **[U2R3] READER DELETED.** U2 shipped `DecoyEnvelopeBuilder`, which reads **no durable state at all** — it has no `VaultRuntime`, no store, no allocator, and takes the covered `MessageEnvelope` as its only input. "Writes nothing durable" is a fact about its type, not a property a test has to keep re-checking. |
| ~~R3~~ | ~~`DeadAirPinger` (U5)~~ | ~~"next-fire is in this vault's own timeline, not the device's"~~ | **[U2R3] READER DELETED — U5 is CUT** (spec §3.0). |
| R4 | provisioning entry point (`DecoyAccountProvisioner.provisionIfNeeded`) | ~~"absent section = not provisioned; present = ready"~~ ~~**CORRECTED:** "`accountId != null && identityKeyPair != null` = ready"~~ ~~**CORRECTED AGAIN [R1]:** "the credential pair is present in the live state **AND** `VaultRuntime.capacityExceeded` is clear"~~ **CORRECTED A THIRD TIME [R2] — there is no single "ready". TWO predicates:** `hasAccount()` = the credential pair is present **and nothing else**, which gates REGISTRATION; `canSend()` = `hasAccount()` ∧ this session's credential flush confirmed ∧ `!capacityExceeded`, which gates COVER TRAFFIC. | YES **only with all three corrections**. The first row is falsified by W1b (a back-off creates a section that is PRESENT and NOT ready). The second by the capacity path: an overflowing `mutate` RETAINS the credential pair unscheduled, so live presence alone answers "ready" for credentials `flushBeforeAck` refuses. **The third falsifies the R1 correction itself:** it is a SEND predicate that was gating REGISTRATION, so an UNRELATED overflow made a vault holding durable credentials re-enter the register path — a second account against a worldwide bucket, and a good durable account replaced if the flag cleared mid-flight. The R1 note calling that "conservative in the right direction" was wrong: it is harmful, and one predicate cannot serve both questions. |
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
| **W1b write-ahead back-off cannot be encoded/flushed [R2]** | **nothing — not contacted** | reverted to its pre-attempt value; `capacityExceeded` cleared | `false` | **the absolute-capacity edge, CLOSED.** No registration is spent, this unlock or any other. Round 1 reached this state only *after* spending one, with no back-off on disk |
| **anywhere before `register` is entered** — offline challenge fetch, DNS failure, failed PoW, **[R4]** a local fault while generating the prekey bundle, a cancelled scope | nothing | ~~W1b's deferral, durable~~ **[R3] W1d RETIRES the deferral, and the emptied holder is omitted — so NO `TAG_DECOY` at all** | `false` | ~~clean retry after the back-off window [R2]~~ **[R3] clean retry on the NEXT UNLOCK, immediately.** Nothing was spent, so there is nothing for a back-off to protect, and this vault keeps its 0.9.x readability (§4.1). The one-attempt latch still stops a retry inside the same runtime. **[R4] The boundary is the `registrationSpent` flag, which must sit BELOW the bundle generation** — inlined as `register`'s argument it was evaluated after the flag, charging this row's local fault as a possible spend |
| `register` request sent, response lost | account may exist | W1b's deferral, durable | `false` | **orphan — accepted, harmless**; the deferral bounds the repeat to once per 60–90 min |
| after 201, before `createSession` | account exists | W1b's deferral, durable | `false` | **orphan — accepted** |
| after tokens minted, before `mutate` | account exists | W1b's deferral, durable | `false` | **orphan — accepted** |
| `mutate` throws (capacity), **IN SESSION** **[R1 — the row that was missing]** | account exists | mutation retained in RAM, NOT scheduled; `capacityExceeded` set — the live state shows a complete credential pair that no reader will ever find on disk | — | This is the state round 1 caught: it lasts only until W1c runs, but while it lasts `canSend()` must NOT say ready (R4) |
| …then W1c reverts **[R1, reshaped R2]** | account exists | section restored to what the SAME critical section read immediately before the commit — which already carries W1b's durable deferral; `capacityExceeded` CLEARED by the successful re-encode | `false` | **orphan — accepted.** ~~a bare-revert subpath with no back-off~~ **[R2] gone**: the deferral was written before the registration, so no revert path can lose it. Clearing the flag is required so a cover-traffic write never blocks the inbound path's flush-before-ack |
| …and even the revert cannot be encoded | account exists | the live state keeps the mutation and `capacityExceeded` stays set | `false` | last-resort; the identity key is then NOT wiped, because the live state still references it. **[R2] The deferral is still on disk from W1b**, so this does not become a per-unlock spend |
| after `mutate` returns, before `flushBeforeAck` **[R1]** | account exists | credentials scheduled, not durable | `false` (the flush's throw is not swallowed into `true`) | orphan on the next open **unless** the pending reseal or `close()` lands them; **[R2] and `credentialsUnconfirmed` keeps every LATER call in this session from reporting ready either** — round 1 closed only the first call |
| after `flushBeforeAck` returns | account exists | credentials durable; W1b's deferral retired in the same mutate | `true` (i.e. `canSend()`) | success |

**No row produces `accountId` without `identityKeyPair`, in RAM or on disk.** **[R4] And the format
can no longer express one:** `VaultStateCodec` rejects an id without a key, a key without an id, and
tokens without an id, on encode **and** on decode. Until R4 that was a property of the writers only —
the codec round-tripped the forbidden state happily and `isProvisioned` merely *hid* it by answering
`false`, which is concealment of a dangling reference rather than prevention of one. The on-disk half
of the writer-side claim is pinned by a test that inspects **every sealed generation** the persist sink was handed,
under a zero-length coalescing ceiling (`no generation EVER written carries a half credential set`)
— a multi-step commit's intermediate state would show up there, and does: the test was verified to
fail against a deliberately two-mutate commit.

Tokens are deliberately NOT flush-before-ack'd: like `VaultAuthStore`'s (`AuthStore.kt:145` kdoc),
they are recoverable by re-minting a session from the stored identity key, so a coalesced write is
correct. **The credential set and the back-offs are not in that category and are flushed** (W1,
W1b, W1c, W3).

## ~~THE COUNTER INVARIANT — skip, never regress~~ — **[U2R3] SECTION DELETED**

**⛔ THERE IS NO COUNTER INVARIANT. NOTHING IN THE DECOY PATH ALLOCATES A COUNTER.** A paired decoy
carries the `message_number` of the real envelope it covers, read straight off that envelope
(`DecoyEnvelopeBuilder`), because `message_number` is a JSON *number* whose decimal width is part of
the frame and no other field can absorb a 1–3 byte difference in it — base64 quantises to four
characters, so the `ciphertext` field cannot. A monotonic decoy counter and "the two frames are the
same size" are mutually exclusive, and the observable one wins. See spec §2.3/§2.4 and
`DecoyEnvelopeBuilder`'s kdoc for the full argument, including what mirroring gives up.

**If a future unit finds itself needing this section, it has designed a decoy that does not mirror a
real envelope — stop and re-read §2.3 before adding a durable field.**

The mechanism is struck through below rather than deleted, because the `[R5]` note attached to it is
a process lesson this project keeps needing and the note is meaningless without the text it corrects.

> ~~`counterHighWater` means: **every counter value strictly below it may already have been issued.**~~
>
> ~~- Session start: RAM `next = limit = 0` — **not** the durable mark. The first `next()` re-reads the
>   mark and reserves from it. **[R5]** `next = limit = counterHighWater` (durable)~~
> ~~- `next()` when `next == limit`: `mutate { counterHighWater += 64 }`, **then `flushBeforeAck()`**;
>   the RAM `next`/`limit` advance **only after the flush returns**. Values in `[old, old+64)` are then
>   issued from RAM. **[R5]** only on a successful *mutate* do the RAM `next`/`limit` advance~~
> ~~- Crash at any point: the next session reads the persisted high-water and starts there. Unspent
>   reserved values are **skipped**.~~
>
> ~~A skip is invisible — a real Double Ratchet skips counters on any dropped message. A REGRESSION is a
> tell no real ratchet can produce, which is why the **durable flush** precedes the first spend and why
> the RAM advance is conditional on **`flushBeforeAck` returning**, not on the mutate. One durable
> write per 64 decoys, per §2.3.~~

> **[R5] WHY THIS BLOCK WAS WRONG UNTIL ROUND 5, AND WHY IT MATTERS MOST — and [U2R3] why it is the
> reason this whole document had to be corrected in place rather than merely flagged.** The text struck through
> above is **round 1's F1 misconception verbatim — "`mutate` = durable"** — the single conceptual
> error that started this entire review arc. It survived **four fix rounds inside the very document
> written to prevent it**, because each round corrected the detailed W3 row and left this abstract
> summary alone. A reader who skips to "THE COUNTER INVARIANT" would have rebuilt the original P1.
>
> **Rule, now in `failures.md`: when a misconception is corrected, grep for every restatement of it
> — especially the compressed, abstract, or summary ones. Those are the copies that survive**,
> because fixes are applied where the reviewer pointed and summaries are where nobody points.
>
> **[U2R3] And then this whole document became that copy.** U2 round 2 deleted the field, the writer
> and the class; the deletion was applied to the code, to the spec's W3/W4 rows, and to
> `u2-invariant-table-decision.md`'s supersession header — and the U1 table, the one artefact the
> process *requires* an implementer to read first, kept 18 references to the deleted design. Both
> reviewers found it independently. The rule above was written here and then broken here. That is why
> the corrections are struck in place instead of announced in a banner, and why the field set now has
> a single canonical home in `DecoyState`'s kdoc with this table explicitly derived from it.

## WHAT THIS WRITE MUST NOT DO

1. **No device-level storage.** Vault-scoped or nowhere — including logs and `BootDiagnostics`.
   Enforced structurally: no decoy class takes a diagnostics/log sink.
2. **Must not make the sealed region's size vary with decoy state.** The region is fixed-size and
   stays so; the section rides inside the compressed, padded, sealed plaintext.
3. **Not a device-global singleton.** One instance per live `SessionContainer` (`NotificationScheduler`
   parity invariant 3). U1 ships the components unwired (see “Scope boundary”), constructed from a
   `VaultRuntime` — which is already per-session — so a global is structurally impossible.
4. **Must not survive teardown.** The provisioner is `suspend` and owns no timers; cancelling the
   session scope cancels it. ~~U3/U5~~ **[U2R3] U3** adds the `cancelAll()`-equivalent when it adds
   timers (U5 is cut; U2 owns no timer either — `DecoyEnvelopeBuilder` is a pure shaper).
5. **Must not name a slot, vault index, or real/decoy VAULT status** in code, logs, diagnostics, or
   string resources. (`TAG_DECOY` / `Decoy*` name the *cover-traffic mechanism*, which is the spec's
   own vocabulary — §4 ~~W1–W4~~ **[U2R3] W1–W2**. The forbidden thing is labelling a VAULT as real vs decoy, or naming a
   slot index. U1 adds no string resource and no log line at all.)
6. **Must not push `VaultState` near `MAX_PAYLOAD_CONTENT_BYTES`.** U1 delivers a measured worst-case
   budget + a headroom test, since R5 depends on it.

## REGISTRATION IS A SCARCE SHARED GLOBAL RESOURCE

`registerLimit` is keyed on `c.IP()` (`handlers.go:166`), which behind Caddy is Caddy's socket
address — **one bucket worldwide** for clearnet and every Tor/I2P client. Therefore:

- **Lazy.** `provisionIfNeeded()` is called from the first session that actually needs a decoy — never
  eagerly at vault creation. A vault that never sends never spends a registration. (U1 ships the entry
  point; U3 supplies the caller.)
- **One RELAY attempt per RUNTIME, ever.** An in-RAM latch means a failure is not retried within the
  session — no tight loop is even expressible. **[R1]** The latch is taken immediately before the
  relay sequence and is NOT burned by a purely local refusal: a back-off window that expires
  mid-session must still get its one attempt, because the latch is one *attempt*, not one *check*.
  (Round 1: burning it on the deferral check meant a long-lived session made zero attempts for the
  whole 60–90 min window and then still made none.) **[R3]** ~~per SESSION, in an instance field~~ —
  the latch lives in a per-runtime `Gate` behind a private constructor, because two provisioners over
  one runtime each held their own and both registered (H2).
- **An attempt that REACHES THE RELAY backs off ACROSS sessions**, durably (W1b), for a randomized
  60–90 min (the limiter window is 1 h; the jitter avoids a synchronized retry stampede). ~~a 429
  backs off~~ **[R2/R3] a 429 is not the trigger and never was the only one:** the deferral is
  written *ahead* of every attempt, and what varies is whether it is retired — kept from `register`
  onwards whatever the cause, retired by W1d for any failure before it.
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
refresh token (`auth/jwt.go` `NewRefreshToken`: 32 random bytes, RawURL base64) + ~~three~~ **[U2R3]
one** fixed-width nullable long (`provisionNotBeforeMs`; the two counter/dead-air integers were
deleted with the allocator and the ping).
~~Uncompressed section ≈ 790 B.~~ **[U2R3] the raw section body is a test-asserted, deterministic
700 B.** `DECOY_SECTION_BUDGET_BYTES = 1024` with the measured
deflated delta asserted under it. `MAX_PAYLOAD_CONTENT_BYTES = 262 112`, and a realistic full state
is ~8 KB (PR-D benchmark), so the headroom is ~3 orders of magnitude — the budget test exists to
catch a FUTURE field addition, not because this one is tight.

**MEASURED** (`VaultDecoySectionTest."the decoy section costs less than its declared budget…"`).
~~Run 2026-07-27, twice: worst-case **encoded delta = 640–643 B**; a realistic populated state
carrying the section encodes to **924–927 B of 262 112 B**, leaving **~261 185 B (99.6 %) free**.~~

**[U2R3] RE-MEASURED after the field removals, three runs 2026-07-27:** raw section body **700 B**
(deterministic, test-asserted); **encoded delta 635 / 641 / 645 B** against a declared budget of
**1024 B**; a realistic populated state carrying the section encodes to **919 / 925 / 929 B of
262 112 B**, leaving **~261 187 B (99.6 %) free**.

**The encoded delta is a DISTRIBUTION, not a point estimate, and the old two-run "640–643 B" read
like one.** The spread is DEFLATE reacting to a freshly generated — genuinely random — identity
keypair, not fixture noise, and three runs already fall outside the previously recorded interval.
The budget is a **bound**; quote it as such. Note also that removing two integers did *not* reduce
the delta measurably: the section is dominated by incompressible key and token material. The test
asserts `delta > 0` as well as `delta ≤ budget`, so a codec that silently dropped the section cannot
satisfy it.

## SCOPE BOUNDARY — what U1 deliberately does NOT do

The trigger for provisioning is "the first session that actually sends a decoy", and the decoy sender
is U2/U3. U1 therefore ships the codec section, the provisioner, the auth facade ~~, and the counter
reservation~~ **[U2R3] (the counter reservation was deleted in U2 R2)** **unwired from `SessionContainer`** — the same posture `VaultRuntime` itself shipped in
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

1. **`provisionNotBeforeMs` is a SIXTH thing in a section §4 describes as holding three.**
   **[U2R3] It is now the FIFTH and last thing**, the section holding exactly
   `accountId ‖ identityKeyPair ‖ accessToken ‖ refreshToken ‖ provisionNotBeforeMs`. The U1
   brief requires "on 429 back off **across sessions**". Across-sessions means durable, and the
   no-device-storage rule means vault-scoped, so the deferral has exactly one legal home: this
   section. Consequence, carried into R4 above: **section presence no longer implies readiness**, and
   every reader must key on the credential pair. Flagged rather than absorbed silently, because it is
   precisely the "moving what a durable signal MEANS" shape the round-12 pattern warns about.
2. ~~**W1 does not write a first dead-air fire time**, though §4's W1 row says it does. The dead-air
   *schedule* is U5 and §3.2 re-framed it from wall-clock to in-session ("1–2 per equivalent
   unlocked-day"), which makes a durable wall-clock next-fire of questionable meaning — U5 must
   settle that. The field exists and round-trips; U1 writes `null`. Deciding the distribution here
   would be U1 designing U5's mechanism blind.~~ **[U2R3] DEVIATION WITHDRAWN — the field is gone and
   U5 is cut.** This one is worth reading as a lesson rather than a note: the deviation was "U1 ships
   the field but declines to give it meaning, and a later unit will decide". The later unit was cut
   and the field was left behind, unwritten and unread, on a durable vault surface. **A field with no
   writer in the unit that adds it is a field nobody is accountable for.**
3. ~~**W3 (counter reservation) is built in U1**, not U2 as §4's writer table says. This follows the U1
   task brief, which lists counter reservation in U1 scope. Only the reservation ALLOCATOR is built;
   the `DecoySender` that spends the values is still U2.~~ **[U2R3] DEVIATION WITHDRAWN — U2 R2
   deleted the allocator.** Same lesson as 2, one step further along: U1 built an allocator whose
   only consumer lived in a later unit, and when U2 was actually written the arithmetic said mirror
   the covered counter instead. **A mechanism whose consumer is in a future unit is a mechanism
   nobody has validated the requirement for.**

---

> # ⚠️ [U2R3] EVERYTHING BELOW THIS LINE IS A HISTORICAL RECORD OF U1's REVIEW ROUNDS
>
> The round tables record **what each round found and did at the time**. They are deliberately NOT
> rewritten — a review record that is edited to match today's code stops being evidence. But they are
> **not the contract**, and two things in them are no longer true of the tree:
>
> 1. **Every allocator/counter item is history.** F1, F2, F8, G1, G7, G11, H7 and the `[R5]` note all
>    concern `DecoyCounterReservation` and `counterHighWater`, **deleted in U2 round 2**. Read them
>    for the *reasoning patterns* — "a check that is not atomic with the spend is not a check", "the
>    guard's scope must match the resource's scope" — which are live and were reapplied to the
>    provisioner's `Gate`. Do not read them as descriptions of code that exists.
> 2. **The mutation tables list tests that were DELETED with the code they covered.** Everything
>    naming a reservation, a block, a high-water mark or an allocator — the `two callers over one
>    runtime get the SAME allocator` row, the `clearAccount resets the counter mark` row, the
>    `NEGATIVE counter high-water mark` row, and the whole first block of the F9 table — went with
>    `DecoyCounterReservationTest`. **A future round must not treat their absence as regression.**
>    The surviving mutation record for the current tree is the U2 round tables in
>    `u2-r*-adjudication.md` plus the round-2/3 fix commits.
>
> The live contract is everything ABOVE this line, with `DecoyState`'s kdoc canonical for the field
> set.

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

---

## REVIEW ROUND 2 — the three round-1 guards all became defects

Paired-blind (Codex + Grok), adjudicated in `u1-r2-adjudication.md`. Fix round 2 of a cap of 6.
Eleven findings, 1 P1.

**The pattern, named:** round 1 answered F2, F3 and F4 with three *guards* — a stale-block check, a
snapshot revert, and a capacity-aware readiness flag. All three produced a round-2 finding, and all
three share one shape: **each reasons about `TAG_DECOY` state sampled outside the lock that protects
it, or folds two different questions into one predicate.** `failures.md` already records the rule
this hits: *when a fix keeps spawning edge cases, the APPROACH is wrong — step back and simplify
beats patching.* So round 2 changed three structures rather than patching four interleavings.

| # | Finding | Disposition |
|---|---|---|
| G1 (P1) | TOCTOU counter regression: `clearAccount()` resets the mark between the allocator's staleness check and its spend, emitting `1, 0` | **fixed at the root** — one SECTION lock (`DecoySectionLock`) shared by the allocator, `DecoyAuthStore` and the provisioner. The check is now atomic with the spend. Not a new check. |
| G2 | flush-throw readiness lie: the NEXT call reported ready on never-flushed credentials | **fixed** — a `credentialsUnconfirmed` flag gates `canSend()`. ⚠️ **SUPERSEDED BY H3 (round 3):** this row claimed instance scope was right. It was not — a SECOND provisioner over the same runtime defaulted the flag to false. The flag is now runtime-scoped. |
| G3 | the capacity flag used as a REGISTER predicate | **fixed by splitting the predicate** — `hasAccount()` (registration; reads nothing but the section) / `canSend()` (cover traffic). R4 corrected a third time. **This one was the architect's**, ratified into the spec in round 1 and falsified by review. |
| G4 | the bare-revert branch wrote no back-off ⇒ one registration per unlock at absolute capacity | **fixed by inverting the order** — the back-off is now **written and flushed BEFORE any relay contact**, and only a success retires it. If the smallest decoy write does not fit, nothing is spent. The bare-revert branch is gone rather than repaired. |
| G5 | the revert restored a snapshot taken before seconds of network I/O, clobbering concurrent writes | **fixed at the same root as G1** — the value the revert restores is read INSIDE the commit's critical section. A revert may only restore state observed under the lock the revert runs under. |
| G6 | `clearAccount()` retained live bearer tokens | **fixed** — tokens are nulled in the same mutate as the id and the key (W2c). |
| G7 | strict-v1 accepted noncanonical decoy encodings, incl. negative `counterHighWater` | **fixed** — the presence byte must be 0 or 1, an absent long must carry zero, and a negative mark is rejected. |
| G8 | the decode-failure wipe was still unpinned; deleting the production call kept both tests green | **fixed by making it observable** — `parsePlaintext` accumulates into a caller-supplied `PartialDecode`, so a test asserts the zeroing through the REAL decoder path. The round-1 "deliberately NOT claimed" item above is now claimed, and pinned. |
| G9 | the test claiming to pin the capacity half of readiness did not | **fixed** — replaced, and every new/changed test was mutation-checked (below). |
| G10 | the one-attempt latch's CAS loser returned a flat `false` | **fixed** — it returns `canSend()`. No longer a false negative once the winner is done. |
| G11 | spec drift: §4 W1 claimed the first provision writes "counter reservation = 64" | **fixed in the spec** — W1 does not write the mark; it stays 0 until W3 first reserves. |

### Behaviour changes worth stating plainly

⚠️ **BOTH ITEMS BELOW ARE SUPERSEDED BY ROUND 3 (H1/H5)** — see "fix round 3" at the end of this
file. They are kept verbatim because item 2's conclusion ("§4.1's narrowed disclosure is still
accurate") was wrong, and both reviewers found it independently.

1. **Every provisioning attempt now costs a 60–90 minute back-off, not only a 429.** An offline
   challenge fetch defers exactly as a rate-limit does. That is the price of "record the intent
   before spending the shared resource", and for a background nicety measured against a worldwide
   rate-limit bucket it is the right direction. It is a deliberate change, not a side effect.
2. **A vault that calls `provisionIfNeeded()` gets a `TAG_DECOY` section immediately**, before any
   relay contact — so the 0.9.x downgrade break now attaches to "tried to provision" rather than
   "generated cover traffic". §4.1's narrowed disclosure is still accurate for a vault that never
   asks (U1 is unwired, and U3 gates the call), but the trigger moved one step earlier and the
   disclosure should be re-read when U3 wires it.

### The round-2 tests, and the mutation each was checked against

Same discipline as F9, same reason. Each was run against a deliberately broken implementation and
observed to FAIL; every mutation was then reverted and the full suite re-run green.

| Test | Mutation it was verified against | Result |
|---|---|---|
| `clearAccount cannot land BETWEEN the staleness check and the spend` | the allocator given a PRIVATE `ReentrantLock()` again (the round-1 shape) | FAILED |
| `a credential commit whose flush THROWS is never reported as ready` | `credentialsUnconfirmed` dropped from `canSend()` | FAILED |
| `an unrelated capacity overflow stops SENDING without re-entering registration` | `provisionIfNeeded` gated on `canSend()` again instead of `hasAccount()` | FAILED |
| `a vault too full to record a back-off never spends a registration at all` | `reserveBackoff()`'s return value ignored | FAILED |
| `a capacity revert restores what the section held AT COMMIT TIME, not a pre-network snapshot` | the revert value read before the relay sequence again | FAILED |
| `the loser of the one-attempt latch reports the truth, not a flat false` | CAS loser returns `false` | FAILED |
| `clearAccount drops the SESSION TOKENS too` | the two token nulls removed | FAILED |
| `a noncanonical nullable-long presence flag is rejected`, `an ABSENT nullable long carrying a value is rejected` | `readNullableLong` restored to `present != 0` | BOTH FAILED |
| `a NEGATIVE counter high-water mark is rejected` | the `counterHighWater >= 0` require removed | FAILED |
| `the REAL decoder path zeroes the decoy identity key when a later section throws` | `partial.wipe()` removed from `parsePlaintext`'s catch | FAILED |

Two of these needed **two attempts to become discriminating**, and that is worth recording because
it is the same class of mistake F9/G9 keep catching:

- the G3 test first passed under its mutation because the *one-attempt latch* was doing the work
  (the same instance had already provisioned). It only discriminates with a FRESH provisioner
  instance — i.e. a later session, which is the real scenario;
- it then passed a second time because the **write-ahead back-off** independently blocked the
  registration while the overflow was outstanding. The predicate defect is only observable in the
  window where `capacityExceeded` is set **and** the state would now encode — which is a genuinely
  reachable state (the instant before whichever write brings the state back under the cap lands),
  and is what the test now constructs.

**Still not claimed:** `interleaved use never regresses` remains non-discriminating between the two
allocator defences, as recorded in round 1, and the section lock does not change that.

## FIX ROUND 3 (2026-07-27) — the scope of a guard, and a write that was never retired

Paired-blind (Codex + Grok), adjudicated in `u1-r3-adjudication.md`. Fix round 3 of a cap of 6.
**Zero P1s**, and for the first time the two reviewers landed on the SAME top three defects
independently. Ten findings, H1–H10.

**The pattern, named:** H2, H3 and H4 are one defect wearing three hats — **the guard's scope does
not match the resource's scope**, the lesson `failures.md` records from 0.9.2 PR-3. The one-attempt
latch and the unconfirmed-flush memory guarded resources that belong to the RUNTIME (this vault's
one synthetic account; the worldwide registration bucket it may spend from once) while living in
INSTANCE fields; `refreshTokens` guarded its write with a snapshot taken before a network
round-trip. Round 1 had already fixed this exact shape once, structurally, for the counter
allocator. Round 3 applied the same fix in the two places round 1 and 2 did not reach.

| # | Finding | Disposition |
|---|---|---|
| H1 | §4.1's disclosure ("never generated cover traffic ⇒ unaffected") became false: the write-ahead back-off puts `TAG_DECOY` on disk before any relay contact | **fixed at the root, then re-worded.** Retiring the deferral on a spent-nothing failure empties the holder, and an empty holder is omitted, so the failed-offline case keeps its 0.9.x readability. The residual widening ("set up cover traffic", not "generated") is in §4.1 **flagged for maintainer re-ratification**, because the narrow wording was their ruling. |
| H2 | two provisioners over one runtime each held their own latch ⇒ two registrations, one orphan | **fixed structurally** — private constructor + `forRuntime`, with the latch in a per-runtime `Gate`. Same treatment `DecoyCounterReservation` got in round 1. |
| H3 | `credentialsUnconfirmed` was instance-scoped, so a second provisioner answered `canSend() == true` on a commit whose flush threw | **fixed** — the flag moved into the same per-runtime `Gate`. |
| H4 | `refreshTokens` snapshots, blocks on the relay, then writes: a concurrent `clearAccount` was undone by the response, restoring live bearer credentials for a retired account | **fixed** — `DecoyAuthStore.storeTokensForAccount` re-reads and compares the account id under the section lock and refuses a mismatch. `storeTokens` is fail-closed the same way (it never materialises a token-only section). |
| H5 | deferring on EVERY failure disabled cover traffic for 60–90 min after failures that spent nothing | **fixed by the architect's rule** — cleared when the attempt fails before `register` is called; kept from `register` onwards, because a `register` that threw may still have created the account. |
| H6 | `parsePlaintext`'s version check sat outside the `try`, so a header throw skipped `partial.wipe()` | **fixed** — the header read moved inside. |
| H7 | the encoder emitted a negative `counterHighWater` its own decoder rejects | **fixed** — `require` in `encodeDecoy`; strict v1 refuses to produce what it refuses to read. |
| H8 | `provisionNotBeforeMs` kdoc still described the removed 429-only behaviour | **fixed** — rewritten to the write-ahead contract and both retirement conditions. |
| H9 | `clearer.join(30_000).let { true }` asserted nothing (4th non-discriminating assertion in this unit) | **fixed** — `assertFalse(clearer.isAlive)`. |
| H10 | a test comment claimed "the SAME image" while the code built a fresh fixture | **fixed** — the reopen now uses `vault.durableState()`, the image the first run actually left. |

### Behaviour changes worth stating plainly

1. **A provisioning attempt costs a 60–90 minute back-off only once the registration endpoint has
   been reached.** Superseding round 2's "every failure defers": an offline challenge fetch, a DNS
   failure, a failed proof-of-work or a cancelled scope retires the deferral, because none of them
   can have spent anything. From `register` onwards the deferral stands whatever happens, including
   when `register` itself throws — the relay may have committed the account before the response
   died, and "may have spent" counts as spent.
2. **A vault that fails to provision before reaching the relay carries NO `TAG_DECOY`** — the
   deferral is retired and the emptied holder is omitted, so that vault still opens on 0.9.x. The
   break attaches to "set up cover traffic". Superseding round 2's item 2, which said the trigger
   had moved to "tried to provision" and that §4.1 was still accurate; §4.1 has been adjusted and
   is flagged for maintainer re-ratification.
3. **`DecoyAccountProvisioner`'s constructor is private.** `forRuntime` is the only way to build
   one. It returns a NEW instance sharing the runtime's guard state rather than a cached instance —
   deliberately unlike the allocator's registry, because the provisioner's collaborators (relay,
   PoW solver, clock) are per-attempt and caching them would silently bind a later caller to an
   earlier attempt's staging store.

### The round-3 tests, and the mutation each was checked against

Same discipline. Each mutation was applied to the real source, the test observed to FAIL, and the
mutation reverted; the full suite was then re-run green (675 tests).

| Test | Mutation it was verified against | Result |
|---|---|---|
| `two provisioners over ONE runtime spend one registration between them, not two` | the latch put back in an instance field | FAILED |
| `a flush that THROWS is remembered by every provisioner over that runtime` | `credentialsUnconfirmed` put back in an instance field | FAILED |
| `a refresh whose round-trip overlaps clearAccount does NOT resurrect the account` | the account-id comparison dropped from `storeTokensForAccount` | FAILED |
| `tokens are never written for an account this vault does not hold` | `storeTokens` allowed to materialise a section | FAILED |
| `a failure BEFORE register RETIRES the deferral` | `clearBackoff` call removed (round-2 behaviour) | FAILED |
| `crash BETWEEN register and commit …` + 4 others | `clearBackoff` made unconditional (retire even after a spend) | 5 FAILED |
| `a throw on the very FIRST byte still wipes what the accumulator already held` | the version check moved back outside the `try` | FAILED |
| `the ENCODER refuses a negative counter mark too` | the `require` removed from `encodeDecoy` | FAILED |
| `clearAccount cannot land BETWEEN the staleness check and the spend` (H9) | the clearer thread made to outlive the join | FAILED |
| `an already-provisioned vault does no network at all` (restructured) | the `hasAccount()` short-circuit removed | FAILED |
| `an unrelated capacity overflow stops SENDING …` (restructured) | `capacityExceeded` folded back into `hasAccount()` | FAILED |
| `a back-off window that expires mid-session still gets its one attempt` (restructured) | the latch taken BEFORE the deferral check | FAILED |

**Four tests had to be restructured to keep discriminating**, and the reason is worth recording: the
latch is now runtime-scoped, so "a later session" can no longer be modelled as a fresh provisioner
over the same live runtime — that shares the burned latch, and the latch would silently do the
test's work. They now build a genuinely new runtime from the image on disk, which is what a later
session actually is. The last three rows above are those tests re-verified after restructuring.

**Not claimed:** H10 is a fidelity fix to a test's construction, not a new production property —
there is no mutation it newly discriminates. The comment now describes what the code does, and the
reopened image is the one the first run left (`requireNotNull(vault.durableState())` would throw
otherwise).

---

## FIX ROUND 4 (2026-07-27) — an argument evaluated after its own guard, and the prose lagging the code

Two code findings, three documentation findings. Both reviewers independently reached the same top
two items **and proposed the same remedy** for the first, which is the convergence signal the
adjudication acted on: 10 → 11 → 10 → 6 findings, zero P1 for a second consecutive round.

**The shape of this round is worth naming: three of five findings were documentation that had
drifted from behaviour, not defects in behaviour.** The code has now survived two full rounds of
adversarial probing without a structural break; the contracts describing it had not kept up. That is
the `failures.md` rule "when a change removes or alters behaviour, update its doc/contract/spec in
the SAME change" broken three times inside one unit, and the fix was a sweep of every contract
describing the back-off lifecycle and the tag-write trigger, not only the lines the reviewers cited.

| # | Finding | Disposition |
|---|---|---|
| J1 | `registrationSpent = true` sat one line above `relay.register(DecoyIdentity.generateBundle(identity), powProof)`. Kotlin evaluates arguments **after** the preceding statement, so the spent/not-spent discriminator was already true while 101 local keypairs were being generated — a failure there sent **zero bytes to the relay** and was charged as a possible spend, costing the vault a 60–90 min silence plus a durable deferral-only `TAG_DECOY` and its 0.9.x break | **fixed** — the bundle is hoisted to its own statement above the flag. A `bundleFactory` seam was added so the step is failable in a test: the relay fake can only throw once `register()` is entered, which is exactly why three rounds of review found nothing here |
| J2 | The codec did not enforce credential-pair integrity: `DecoyState(accountId = "…", identityKeyPair = null)` encoded and decoded cleanly — the dangling account reference the register-before-commit invariant calls structurally impossible. `isProvisioned`/`hasAccount` only *hid* it | **fixed** — `requireDecoyCredentialsPaired` on **both** sides, refusing an id without a key, a key without an id, and tokens without an id. Strict v1 refuses to produce what it refuses to read; the same rule H7 applied to the negative counter mark |
| J3 | §4.1's user-facing disclosure still understated the break | **fixed, and marked PENDING RE-RATIFICATION** — third pass, recorded below |
| J4 | §6.2a stated round-2 semantics as current law ("only a successful commit retires", "*every* failure defers", "an offline challenge fetch costs a 60–90 minute wait"), contradicting round-3 code. Fourth recurrence of the stale-contract class | **fixed** — §6.2a now carries an explicit RETIREMENT rule superseding R2's second half, with the `register` boundary and the R4 flag-placement constraint stated |
| J5 | This table's WRITER inventory omitted `clearBackoff` — a genuine durable writer (`mutate` + `flushBeforeAck`) — so W1 read as the only retirement path; the crash matrix's "before `register`" row still taught a back-off wait; W1 still described `credentialsUnconfirmed` as instance-scoped after H3 moved it | **fixed** — new row **W1d**; W1, W6, the field table, the crash matrix, the scarce-resource section and the ordering section all corrected |

### The §4.1 disclosure — third pass, and the architect's own proposed fix was ALSO wrong

Round 3 shipped "set up cover traffic — which happens the first time it sends any", which
**understates**. The correction proposed for round 4 was "the first time it *tries to* send any",
which **overstates**: a vault that tries, fails offline before `register`, and retires its deferral
keeps full 0.9.x readability. Grok's truth table is what settled it — the durable trigger is
**setup that reaches relay registration**:

| Path | `TAG_DECOY` on disk? |
|---|---|
| Never calls `provisionIfNeeded` | no |
| Fails **before** `register`, deferral retired **and the retirement flushed** | no — emptied holder is omitted |
| Fails before `register`, but **the process dies after the write-ahead flush**, or the retirement's own flush fails | **yes** — nothing can run to retire it **[R5]** |
| **Reaches `register`** (including 429, or a lost response) | **yes** |
| Succeeds, never sends a decoy | **yes** |

**Why this sentence keeps drifting, recorded so a fifth pass does not repeat it:** its truth depends
on an implementation detail that three rounds have each moved, and every pass so far reasoned from
the *previous wording* rather than from the code. It must be re-derived from those four rows on any
change to a provisioning failure path. The four rows now live in `VaultState.kt`'s codec kdoc, next
to the `takeUnless { it.isEmpty }` that produces them, with that instruction attached.

Applied rather than left standing while it waits for the maintainer, because an understated
format-break disclosure is the more dangerous direction and the previous wording was understated.

### Behaviour changes worth stating plainly

1. **A local fault between the proof-of-work solve and the relay call no longer costs a back-off.**
   Bundle generation is the last local step, and it is now above the discriminator rather than
   inside `register`'s argument list. A vault that OOMs on the prekey batch retires its deferral,
   carries no `TAG_DECOY`, keeps its 0.9.x readability, and gets its next attempt at the next unlock.
2. **The decoy section's credential pair is a FORMAT rule, not just a writer convention.** A crafted
   or corrupt image carrying an id without a key is now rejected at decode instead of being quietly
   reported as "not provisioned". No writer in this codebase can reach the state, which is why it is
   an assertion and not a repair — a silent fix-up would launder a corrupt image into a plausible one.

### The round-4 tests, and the mutation each was checked against

Same discipline: each mutation applied to the real source, the test observed to FAIL, the mutation
reverted.

| Test | Mutation it was verified against | Result |
|---|---|---|
| `the LAST LOCAL step before register is still spent-nothing - the flag sits below it` | the bundle re-inlined as `register`'s argument (the shipped R3 code) | **FAILED** — 1 of 32, and only this one |
| `the ENCODER refuses a credential half-set - an id without its key, and a key without its id` | `requireDecoyCredentialsPaired` removed from `encodeDecoy` | **FAILED** — 1 of 80, and only this one |
| `the DECODER refuses a credential half-set too - strict v1 is symmetric` | `requireDecoyCredentialsPaired` removed from `decodeDecoy` | **FAILED** — 1 of 24, and only this one |

**Every mutation discriminated; none needed a fallback guard to carry it.** The encoder and decoder
mutations were run separately on purpose: removing the encoder check alone left the decoder test
green and vice versa, which is what proves the two assertions are independently load-bearing rather
than one test riding on the other side's guard.

**Note on the J1 seam.** The test would pass against a *correct* implementation for a trivial reason
(any throw before `register` retires the deferral), so the discriminating mutation is not "make the
bundle throw" but **the flag placement** — which M1 is precisely. Without the `bundleFactory` seam no
mutation of this line is expressible at all, which is the actual reason three rounds missed it.
