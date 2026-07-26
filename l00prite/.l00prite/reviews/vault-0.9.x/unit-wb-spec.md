# Unit W-B — Pucker Burn WIPE mechanism + completion presentation — IMPLEMENTATION SPEC

**Status: SPEC ONLY. No implementation. Pending maintainer review.**
Supersedes `pucker-burn-spec.md` §3 and `burn-unit-w-invariant-table.md` wherever they conflict with
shipped W-A code. Scope approved 2026-07-25 (`unit-wb-scope.md`, rulings A–E). Unit S (setup/arming)
is OUT — sequencing stays W→S, and with slot 0 unarmed `Burn` is structurally unreachable in
production, which is what makes landing the mechanism first safe.

Source re-verified this session against the CURRENT tree (`1b5f5e0`), not `c3e4038`.

---

## 0. Corrections to the source documents (stale, not authoritative)

Recorded explicitly per the deliver-then-claim standard. Each was verified against shipped code.

| # | Source claim | Correction |
|---|---|---|
| C1 | spec §3.4: "Current boot routing (`MainActivity.kt:647`): `!hasVault() && !serverDeleteConfirmed()` → onboarding" | **GONE.** Routing is `bootRoute`, precedence: `confirmed` → `legacy && present` → `present` → `hold` → `provenAbsent` → else LOCKED. W-A removed the last consumer of that predicate in `bdde066`. |
| C2 | spec §3.4 + table §1/§8: post-burn `hasVault()`=false ∧ `confirmed`=false ⇒ **Onboarding ✓** | **FALSE since W-A.** Onboarding additionally requires `vaultProvenAbsent` AND no `residueSweepHold`. This is the hinge of §3 below. |
| C3 | spec §3.2: `obliterate()` verify uses `File.exists()` on four paths | **SUPERSEDED (ruling C).** Verify is `Files.notExists` proven-absence; an indeterminate stat is a SURVIVOR → `DestroyFailed`. `exists()` is fail-OPEN on the one operation where fail-open is least acceptable. Note the parent branch's own `imageBearingFilesProvenAbsent()` already did this — the spec text lagged its own code. |
| C4 | table §10 R1: interrupted-burn damaged state is "unavoidable without a durable pre-burn intent marker" | **FALSE.** `completeInterruptedBurn()` on the parent branch resolves it with NO marker, keyed on the unambiguous `{bin present, dek PROVEN absent}` signature. R1 is not an accepted residual; it has a fix that ships in W-B. |
| C5 | spec §9 names one reconciler, `reconcileOrphanedIntentMarker()` | **There are TWO, with different triggers** (§4). The spec's name maps to `reconcileOrphanedBurnMarkers()`; `completeInterruptedBurn()` is a distinct second one the spec never describes. Ruling B determined this by reading both, not by comparing names. |
| C6 | table §0: "`Route.Locked` only when `serverDeleteConfirmed()` is FALSE" ⇒ image present | Still true that confirmed outranks, but W-A added LOCKED **with no image** (hold arm, else arm). The bounding fact survives for a reason the table never states — see §6, derived. |

---

## 1. The primitive

```
private fun obliterateLocked()      // marker-free, fail-closed, keys-first. Caller holds imageLock.
S0  wipe RAM DEK; canonical = null                         [no durable effect]
S1  unlink vault.dek + vault.dek.tmp                       [KEYS FIRST]
S2  unlink vault.bin + vault.bin.tmp
S3  unregister()                                           [no durable effect]
S4  imageBearingFilesProvenAbsent()  == false → throw DestroyFailed   [C3: PROVEN absence]
S5  dirSync(baseDir) != DURABLE      → throw DestroyFailed
S6  clearBothMarkersDurably()        == false → throw DestroyFailed   [STRICTLY LAST]
```

`fun burnObliterate() = imageLock.withLock { obliterateLocked() }` — no marker written, ever.
`fun destroy()` keeps its confirmed-marker crash-bridge, then calls `obliterateLocked()`.

**S6 last is binding.** Clearing markers over a live image reproduces PR-1's B1 state. Because S4/S5
prove the image durably absent first, the markers at S6 are orphaned BY DEFINITION — the same
precondition that makes `create()`'s clear safe.

**`destroy()` equivalence is a REVIEW ITEM, not a claim.** End state is identical; the one intentional
deviation is unlink order (bin-then-dek → dek-then-bin). The confirmed marker is written first, so a
crash at any point re-runs the idempotent destroy regardless of order. That is an ARGUMENT, and
reviewers evaluate it against source. Fallback if rejected: `keysFirst: Boolean` on the primitive —
one primitive, one branch, never two divergent unlink implementations.

---

## 2. THE CENTRAL CHANGE — one durability owner, two producers

**The defect (Grok round-6 HIGH, closed structurally).** `residueSweepHold` today has exactly ONE
producer: `runBootReconcile` publishing the boot sweep's verdict. Burn's `obliterate` produces the
SAME CLASS OF FACT — "image-bearing files were unlinked; durability proven or not" — with no channel
into the verdict. Consequence with C2: a burn whose unlinks land but whose `dirSync` is not DURABLE
throws `DestroyFailed`, yet the directory now STATS CLEAN. `vaultProvenAbsent` is true, no hold is
raised, and the next boot routes to **ONBOARDING — a fresh install presented over a wipe that was
never proven durable**, where a journal replay can bring the image back.

**The structure.** One owner of the durability verdict; two producers publishing into it.

```
                 boot sweep ──┐
                              ├──> durability verdict (ONE owner, process-scoped)
   burn's obliterate ─────────┘            │
                                           └──> bootRoute's hold input ──> single derivation
```

Binding properties:
- **P1.** A burn that reaches S2 (unlinks attempted) and does not complete S5 durably MUST raise the
  hold, exactly as a non-durable sweep does. Fail-closed default, published on EVERY exit path
  including the throw — the `finally`-publication discipline `runBootReconcile` already proves.
- **P2.** The hold is raised BEFORE the first destructive mutation is attempted and lowered only on
  proven-durable completion — never "raise on failure", which loses the crash window.
- **P3.** NO second hold field. A second field is the accretion shape the cap decision was about.
  Adding a `burnHold` beside `residueSweepHold` would recreate exactly what W-A collapsed.
- **P4.** A completed, proven-durable burn supersedes an earlier hold on the same evidence basis
  `destroySupersedesResidueHold` uses: proven absence + its OWN required dirSync.

**Test obligation:** the failed-but-clean burn must be a direct test, not an argument — unlinks land,
`dirSync` returns non-DURABLE, assert the next derivation is NOT ONBOARDING.

---

## 3. Boot reconciliation — inside `runBootReconcile` (ruling A)

W-A made `runBootReconcile` the single boot-time durable-mutation owner: one CAS claim, one verdict,
publication in `finally`. Both reconcilers below run INSIDE it, sharing that claim and that
publication. **No second boot-time mutation owner.**

Ordering within the swept step (each is surgical and mutually exclusive by trigger):

1. `completeInterruptedBurn()` — finishes an interrupted keys-first wipe.
2. `reconcileOrphanedBurnMarkers()` — clears markers orphaned by a crash before S6.
3. `sweepOrphanedResidue()` — W-A's existing orphan sweep, unchanged.

All three feed the SAME durability verdict (§2). A reconciler that mutates and cannot prove
durability raises the hold, identically to the sweep.

**ORDERING IS IRRELEVANT BY PROOF, NOT BY REASONING (maintainer strengthening).** Do NOT ship "their
triggers are mutually exclusive, so ordering is not observable" as an argument — that is an
instance-level claim about today's predicates. Convert it to a TEST: over the enumerated state space,
assert **AT MOST ONE reconciler's trigger predicate is true** in every state. Ordering then cannot
matter, and if a future change WIDENS a trigger the test fails loudly instead of ordering silently
beginning to matter. Same instance-vs-class distinction that made W-A's structural fixes hold.

**HOLD SEMANTICS UNDER THREE MUTATORS (maintainer strengthening).** The hold means exactly one thing:
**"some boot-time mutation did not prove durable."** Full stop. Routing cares ONLY that it is raised,
never WHICH mutator raised it. **If any consumer ever needs to know which, that is the signal the
single-field design has broken down — surface it as a FINDING, do not work around it by adding a
discriminator.** This is the first place to look for an unintended interaction, and it is a named
focus item in the reviewer brief.

---

## 4. The two reconcilers are DIFFERENT (ruling B — determined by reading both)

| | `completeInterruptedBurn()` | `reconcileOrphanedBurnMarkers()` |
|---|---|---|
| **Window** | crash BETWEEN S1 and S2 | crash BETWEEN S2/S5 and S6 |
| **Trigger** | `confirmed` proven absent ∧ **dek proven absent** ∧ **bin PRESENT** | image-bearing **all proven absent** ∧ `confirmed` proven absent ∧ `intent` PRESENT |
| **Action** | run `obliterateLocked()` — finish the wipe | `clearBothMarkersDurably()` |
| **Why it is unambiguous** | `create()` renames the DEK in FIRST, image SECOND, so a partial create is `{dek present, bin absent}` — the exact INVERSE. No codebase ordering produces `{bin present, dek absent}` except an interrupted keys-first obliteration or genuine DEK media loss; both unrecoverable, so completing destroys nothing still readable. | image PRESENT is never touched (a live intent is a genuine pending reconcile, round-14 F1); `confirmed` PRESENT is never touched (that is D2c's self-heal, and clearing it would strip the auto-destroy authorisation mid-heal). |
| **Credential** | none required — the state is unrecoverable regardless | none |

The spec's §9 `reconcileOrphanedIntentMarker` **is** `reconcileOrphanedBurnMarkers`; the naming is
corrected here. Both must be RE-DERIVED against W-B rather than restored (item #6): W-A's exclusion
argument cited the absence of the duress wipe, so it voids by its own premise the moment W-B lands.
**Their trigger predicates are `Files.notExists`-based throughout — never `exists()` (C3).**

---

## 5. Items #1 and #5 — ONE change, one design

Fold the inputs into the derivation; do NOT wrap call sites. Five Main-thread disk reads today:

| Site | Read | Note |
|---|---|---|
| `MainActivity.kt:631` | `hasVault()` | `remember` initializer, composition thread |
| `MainActivity.kt:1046` | `hasVault()` | inside `withContext(Dispatchers.Main)` |
| `MainActivity.kt:1170` | `vaultProvenAbsent()` | `Dispatchers.Main.immediate` |
| `MainActivity.kt:1171` | `serverDeleteConfirmed()` | `Dispatchers.Main.immediate` |
| `MainActivity.kt:1219` | `vaultDeleteIntentPending()` | session collector |

1170/1171 are three defects in one place: Main-thread `imageLock` disk stats, a SECOND re-derivation
(they decide `destroySupersedesResidueHold` from fresh stats, then the derivation reads disk AGAIN),
and a TORN PAIR-READ across the two. The fix is one design: the supersede decision moves INSIDE the
derivation, which already reads one `Residence` classification off the Main thread. Wrapping five
call sites in `withContext(IO)` is the fix that regrows the problem — five wrappers, five chances to
omit one, which is precisely why `deriveBootDecisionFromDisk` moved the dispatcher inside.

**Deliverable:** an enumeration of EVERY boot-routing consumer showing each consuming the single
verdict, in the invariant table, with no consumer re-deriving from disk.

---

## 6. The W-A/W-B interaction, DERIVED (ruling D — not asserted)

W-A created two ways to reach `Route.Locked` with **no image present**: the hold arm, and the else
arm over an indeterminate stat. The invariant table's §0 proof assumed `Route.Locked` ⇒ image
present, so the bounding fact needs re-derivation rather than re-citation.

**Derivation.** From LOCKED-with-no-image the only input is a passphrase at the lock screen:
`attemptPassphrase` → `attemptUnlockOrAdd`, whose first act is `canonical ?: run { open(); canonical!! }`.
`open()` throws `MissingImage` when `vault.bin` is absent (`VaultImageStore.kt:352`). Therefore:

- **`Burn` is unreachable there** — `Burn` requires a slot-0 match from `tryPassphrase(decoded.slots)`,
  and there are no decoded slots without a successfully opened image. Derived from the open path, not
  from "it should be unreachable".
- **The create/add branch is unreachable there** — it mutates `decoded.slots` of an existing image and
  reuses the existing DEK. There is no door by which a held directory gets a fresh vault written over
  it from the lock screen.
- **§0's bounding fact SURVIVES** (a burn can only fire while `delete-confirmed` is absent), but the
  supporting argument must be the one above, not the table's.

**Consequence to disclose, not hide:** LOCKED-with-no-image is an **unpassable lock screen** — every
passphrase fails at `open()` before any slot is interpreted. It is fail-CLOSED and restart-recoverable
(next boot's sweep finds a clean disk, publishes no hold, routes to onboarding), but it is a
W-A-created state with no in-app exit, and it is a tell. Goes in the invariant table and
`SECURITY_MODEL.md`. Related to the tracked stale-hold strand; NOT introduced by W-B, and W-B must not
paper over it.

---

## 7. Wiring, teardown, presentation

- **`onBurn` replaces the stub** (`MainActivity.kt:890`, currently uniform-failure + `unlocking=false`):
  begin terminal exclusion → `burnObliterate()` → `wipeBiometricMaterial()` → app-local teardown →
  completion presentation → onboarding.
- **Wiring invariant (pin it):** the wipe fires ONLY from the lock-screen dispatch's
  `PassphraseOutcome.Burn` (`MainActivity.kt:909`). `attemptUnlockOrAdd` has a single caller and
  returns `Burn` only on a real slot-0 match; a create-collision returns `Rejected`. No other consumer
  of `Burn` may wipe — any future caller treats it as "reject candidate".
- **Terminal exclusion before the FIRST destructive mutation.** Reuse `UnlockController.isTerminalWipe()`
  (`UnlockController.kt:180`), already fencing the auto-lock timer. Enumerate every teardown the
  lock-screen path lacks versus the account-delete path (which runs from a live session) — the
  byte-for-byte gate is what proves that enumeration complete.
- **Keystore/biometric:** factor `wipeBiometricMaterial()` shared by burn and
  `destroyVaultForAccountDeletion()`, under `biometricWriteLock` so a racing in-flight enable cannot
  re-persist a wrap.
- **Auth-guard interaction:** `hasDeleteIntentMarker()` gates token-clearing via
  `MessagingCoordinator` (`intentMarkerPresent`, line 1840). Burn clears the intent marker at S6 and
  runs with no live session — review must confirm terminal exclusion means no live session can read a
  just-cleared marker mid-burn.
- **Completion presentation — coordinator extraction (item #3):** "snapshot → claim → apply/ack",
  extracted so **apply-once is tested against production code, not a stand-in**. Same shape as
  `runBootReconcile` (CAS claim, publish in `finally`) and `runDeleteRetry`. Post-burn presentation is
  P2's VISIBLE RESET — ordinary onboarding, no special screen, no decoy.

---

## 8. Byte-for-byte gate (ruling E)

Robolectric in `src/test`, running in CI on every PR. Compares post-burn app-local state against
post-fresh-install: files (image, DEK, temps, markers), SharedPreferences/EncryptedSharedPreferences,
databases, attachments, caches, notification channels, WorkManager jobs, Keystore aliases.

**AND the DERIVED VERDICT (ruling E).** A fresh install has no hold raised; a post-burn state might
compare byte-identical on disk while the derived verdict differs, and a file-only gate would prove the
wrong thing. The gate asserts the `BootDecision` matches too.

Shadow-fidelity gaps are accepted ONLY as explicit in-test exclusions with reasons IN the test, AND a
`SECURITY_MODEL.md` line — the app cannot claim fresh-install-indistinguishability for anything the
test does not verify. OS residuals (install time, UsageStats, notification history, MediaStore, NAND)
are enumerated and asserted expected-different, never silently dropped.

---

## 9. Named review items for the paired-blind loop

- [ ] `destroy()` equivalence verified against source; unlink-order deviation ruled on, not assumed.
- [ ] `obliterate()` marker-free, fail-closed, keys-first; **verify is proven-absence (C3)**.
- [ ] Marker clear STRICTLY after unlinks proven durable (B1 reproduction check).
- [ ] Burn never writes `delete-confirmed`; no burn-produced state reaches `DeleteIncomplete`.
- [ ] **ONE durability owner, TWO producers; failed-but-clean burn cannot present as fresh install.**
      Direct test, not an argument. (BLOCKING)
- [ ] Both reconcilers inside `runBootReconcile`; reachability RE-DERIVED, not restored.
- [ ] Five Main-thread reads folded into the derivation; every consumer shown consuming one verdict.
- [ ] Coordinator apply-once tested against production code.
- [ ] Hold VALUE preserved across `runDeleteRetry` (item #4 residue).
- [ ] Wiring: `Burn` → wipe from the lock screen only; terminal exclusion before first mutation.
- [ ] Byte-for-byte gate green INCLUDING the derived verdict; exclusions reasoned in-test + disclosed.
- [ ] `SECURITY_MODEL.md`: local-only, crypto-erase not NAND, single-snapshot, burn consumes the
      credential, R1 now FIXED (C4), LOCKED-with-no-image disclosed (§6).

## 10. Divergence boundary

R2 (post-burn wall-clock observability) may defer to a later hardening layer, tracked. **Anything that
breaks post-burn ≡ fresh install BLOCKS** — the feature failing at its purpose, not a hardening gap.
