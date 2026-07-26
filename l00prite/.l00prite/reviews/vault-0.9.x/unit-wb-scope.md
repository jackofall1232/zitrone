# Unit W-B — SCOPE STATEMENT (for review; no code written)

Reconciled against `pucker-burn-spec.md`, `burn-unit-w-invariant-table.md` (both PRE-SPLIT, written
against `c3e4038`), what W-A actually shipped (`main..1b5f5e0`), and the six post-cap decisions.

**Status: scope proposal. Nothing implemented. DoD lands in `todos.md` only after this is approved —
a DoD derived from an unapproved scope would pin the wrong thing.**

---

## 1. What W-B is

The deferred half of the parent unit: the burn MECHANISM (`obliterate()`/`burnObliterate()`, the real
`onBurn`, `wipeBiometricMaterial()`, app-local state teardown) plus the COMPLETION PRESENTATION. W-A
(cold-start orphan residue sweep + fail-closed boot routing) was extracted from the same parent and
has shipped.

**Unit S (setup/arming, spec §5) is NOT W-B.** Spec §6 sequencing is W→S, and with slot 0 unarmed
`Burn` is structurally unreachable in production — which is exactly what makes landing the mechanism
first safe. W-B must not pull any of §5 forward.

---

## 2. Spec claims that are STALE and must be re-derived, not carried

The spec predates W-A. Every one of these is a stated fact whose source moved:

| Spec location | Stale claim | What is true now |
|---|---|---|
| §3.4 | "Current boot routing (`MainActivity.kt:647`): `!hasVault() && !serverDeleteConfirmed()` → onboarding" | That predicate is GONE. Routing is `bootRoute` with five inputs and a fixed precedence: confirmed → legacy∧present → present → hold → provenAbsent → else LOCKED. W-A replaced the last consumer of that predicate in `bdde066`. |
| §3.4, §9 | Post-burn `hasVault()`=false, `confirmed`=false ⇒ Onboarding ✓ | **NO LONGER UNCONDITIONAL.** Onboarding now also requires `vaultProvenAbsent` AND no `residueSweepHold`. A burn that unlinks without proving durability lands on LOCKED, not Onboarding. This is the hinge of decision #2 below. |
| Table §0 | "`Route.Locked` only when `serverDeleteConfirmed()` is FALSE" | Still true, but incomplete: W-A added TWO new ways to reach LOCKED **with no image present** (the hold arm, and the else arm over an unprovable image). The bounding-fact proof must be re-derived over the new arms, not just re-cited. |
| §3.2 | `obliterate()` verify uses `File.exists()` on four paths | W-A established the tri-state discipline (`Residence`, `Files.notExists` for proven absence). An `exists()`-based verify is PROVEN-PRESENCE only — it passes on an indeterminate stat. For a duress wipe, "I could not stat it" must never read as "it is gone." |
| §3.2 comment | Fallback is a `keysFirst: Boolean` param | Still available and unchanged; R3 stands. |

---

## 3. The six post-cap decisions, mapped

**#1 — Single authoritative boot verdict.** W-A built the owner (`deriveBootDecision` /
`deriveBootDecisionFromDisk`, one `Residence` classification). W-B must enumerate every consumer and
show each consuming it. **Current violation, already visible:** `MainActivity.kt:1165-1175`, the
delete-completion callback, takes TWO fresh disk stats (`vaultProvenAbsent()`, `serverDeleteConfirmed()`)
to decide `destroySupersedesResidueHold(...)`, then calls `deriveBootDecisionFromDisk()` which reads
the disk AGAIN. That is a second re-derivation and a torn read across the two. The supersede decision
belongs INSIDE the derivation. This is the same fix as #5 and should be one change, not two.

**#2 — Grok's round-6 HIGH, closed STRUCTURALLY. This is the unit's central invariant.** Today the
durability hold has exactly one producer: `runBootReconcile` publishing the boot sweep's verdict.
Burn's own `obliterate()` is a SECOND producer of the same class of fact — "image-bearing files were
unlinked, durability proven or not" — with no channel into the verdict. So a failed-but-clean burn
(unlinks landed, `dirSync` not DURABLE) leaves a directory that stats clean, and after recreation
presents as a FRESH INSTALL while the wipe was never proven durable. **W-B must give burn's obliterate
the same carried-verdict treatment the boot sweep has**, so an unproven burn is fail-closed rather
than indistinguishable from success. Structural close = one durability owner with two producers, not
a second hold bolted alongside the first.

**#3 — Coordinator extraction ("snapshot → claim → apply/ack").** The completion presentation is the
apply-once surface. Extract it so apply-once is exercised against production code rather than a
stand-in — the same shape as W-A's `runBootReconcile` (claim by CAS, publish in `finally`) and
`runDeleteRetry`. Scope: the coordinator + its tests. It is NOT a licence to redesign the
presentation.

**#4 — `onRetryDestroy` orchestration extraction. ⚠ ALREADY SHIPPED IN W-A (`1b5f5e0`).**
`runDeleteRetry` exists with `DeleteRetryOwnerTest` covering Codex's list: destroy-before-derive,
uses the derived route, only ONBOARDING is success, and a `LOCKED`-from-hold is failure.
**One precision:** the test asserts a raised hold does not produce success; it does NOT assert the
hold VALUE is left untouched. If "hold preserved" means the stronger property, that assertion is
owed — a one-line addition, and the only part of #4 W-B still owns. Otherwise #4 is closed and W-B
must not re-do it.

**#5 — Gemini's Main-thread finding: fold into the derivation, do NOT wrap call sites.** I enumerated
rather than accepting the count. **Five bare disk reads on the Main thread — the count matches:**
1. `MainActivity.kt:631` — `container.hasVault()` in a `remember` initializer (composition thread)
2. `MainActivity.kt:1046` — `container.hasVault()` inside `withContext(Dispatchers.Main)`
3. `MainActivity.kt:1170` — `container.vaultProvenAbsent()` on `Dispatchers.Main.immediate`
4. `MainActivity.kt:1171` — `container.serverDeleteConfirmed()` on `Dispatchers.Main.immediate`
5. `MainActivity.kt:1219` — `container.vaultDeleteIntentPending()` in the session collector

Each takes `imageLock` and stats disk. Wrapping each site in `withContext(IO)` is the fix that
regrows the problem (five wrappers, five chances to omit one — the shape W-A already punished at
`deriveBootDecisionFromDisk`, which moved the dispatcher INSIDE for exactly this reason). Fold the
inputs into the derivation instead.

**#6 — `completeInterruptedBurn` / `reconcileOrphanedBurnMarkers` return.** W-A excluded them with a
reachability argument that CITED THE ABSENCE OF THE DURESS WIPE ("their trigger states are
unreachable by construction without the duress wipe"). W-B makes the wipe exist, so **the argument
voids by its own terms** and reachability must be re-derived from W-B's design. Restoring the W-A-era
comments would be restoring a proof of a premise that is no longer true.

---

## 4. Ownership ambiguities — FLAGGED, not assumed

**A. Two boot reconciliations, one boot.** Spec §9's `reconcileOrphanedIntentMarker()` (image absent
+ confirmed absent + intent present → clear both markers) is a boot-time DURABLE MUTATION. W-A
already owns exactly one boot-time durable mutation, inside `runBootReconcile`, whose whole design is
one claim, one verdict, one publication in `finally`. Adding a second boot mutation OUTSIDE that owner
recreates the multi-authority family this unit exists to close. **My reading: it belongs inside
`runBootReconcile`'s swept step, sharing the claim and the verdict.** Confirm before I design to it.

**B. Is §9's `reconcileOrphanedIntentMarker` the same thing as #6's `reconcileOrphanedBurnMarkers`?**
Same trigger shape, different names, written at different times. If they are one function, say so; if
two, the second one's trigger needs stating, because I can't find it derived anywhere.

**C. `obliterate()`'s verify predicate.** Spec §3.2 writes it with `exists()`. Under W-A's discipline
a duress wipe's proof-of-absence should be `Files.notExists` (proven absence), with indeterminate
treated as SURVIVOR → `DestroyFailed`. That is strictly more fail-closed than the spec text but it IS
a deviation from the written spec, so it is yours to rule on, not mine to slip in.

**D. LOCKED with no image (new interaction W-A created).** The hold arm can present a lock screen
over an absent-or-unprovable image. The spec's §0 proof assumed `Route.Locked` ⇒ image present.
`Burn` requires a real slot-0 match, so a burn should be unreachable there — but the passphrase router
also has a CREATE branch, and what a create means over a held directory is a W-A/W-B interaction
nobody has derived. Flagging rather than resolving.

**E. Byte-for-byte gate scope.** §4's Robolectric gate is written for the whole parent unit. Post-burn
≡ fresh install is the BLOCKING invariant (below), so the gate is W-B's. But "fresh install" now has a
W-A-shaped precondition: a fresh install has no hold raised, so the gate must compare the derived
VERDICT too, not only the file/prefs/Keystore surface.

---

## 5. Divergence boundary (as instructed)

- Robustness residuals (R1 interrupted-burn visible damaged state, R2 wall-clock) MAY defer to a later
  hardening layer, tracked.
- **Anything that breaks post-burn ≡ fresh install BLOCKS.** That includes decision #2's failed-but-
  clean burn, and any gate exclusion that would let a surviving artifact pass unasserted. This is the
  feature failing at its purpose, not a hardening gap.

## 6. Proposed DoD (for `todos.md` on approval)

1. `obliterate()` marker-free, fail-closed, keys-first; markers cleared STRICTLY last; verify is
   proven-absence, not proven-presence (pending 4C).
2. `destroy()` behavioural equivalence verified against source, unlink-order deviation named and
   ruled on (not "identical by construction").
3. Burn never writes `delete-confirmed`; no burn-produced state routes to `DeleteIncomplete`.
4. Burn's durability verdict flows through the SINGLE boot verdict; a failed-but-clean burn cannot
   present as fresh install (#2, blocking).
5. All five Main-thread reads folded into the derivation; every boot-routing consumer shown consuming
   the single verdict (#1, #5).
6. Coordinator extracted; apply-once tested against production code (#3).
7. Reachability of the two returning reconcilers RE-DERIVED against W-B (#6), not restored.
8. Byte-for-byte Robolectric gate green; shadow gaps as in-test exclusions + `SECURITY_MODEL.md`
   lines; OS residuals explicitly asserted.
9. `SECURITY_MODEL.md` honesty pass (local-only, crypto-erase not NAND, single-snapshot, burn
   consumes credential).

## 7. Process

Rule of 6, hard cap, no self-reset, third lens blind at the cap, stop for you regardless of outcome.
Single whole-repo lens on the PR (Codex out of credits) — front-loaded review matters more.
