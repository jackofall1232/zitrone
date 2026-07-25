# Unit W-B — WRITER/READER invariant table (supersedes the pre-split Unit W table)

Built against the CURRENT tree (W-A shipped), not `c3e4038`. Where the pre-split
`burn-unit-w-invariant-table.md` conflicts with shipped code, THIS file wins and the conflict is
named. Named invariants get IDs so review can cite them.

---

## WB-1 — UNIFORM FAILURE AND THE DURABILITY HOLD ARE ONE INVARIANT, NOT TWO PROPERTIES

**Statement.** A failed burn presents EXACTLY as a wrong passphrase (`UNIFORM_FAILURE`, lock screen
retained) **AND** leaves [`durabilityHold`] raised. Neither half is safe alone, and neither may be
changed without the other.

**Why it is one invariant.** The two halves are mutually load-bearing:
- The uniform message is only SAFE because the hold prevents the next boot presenting a fresh install
  over an unproven wipe. Without the hold, "say nothing" degrades to "say nothing and lose the wipe".
- The hold's value AT THE UI LAYER is only realized because the message reveals nothing. Without
  uniformity, the hold silently protects durability while the screen tells a coercer a burn was
  attempted — which is the disclosure the feature exists to prevent.

**The failure mode this ID exists to prevent.** Someone later improves the failure message to be more
informative ("Couldn't complete that — try again"), which is an ordinary, reasonable-looking UX
change. It breaks the deniability half **while every durability test still passes**. Nothing in the
type system or the test suite objects. This entry is the objection.

**Writers:** `onBurn` (`MainActivity`) sets `lockError`; `AppContainer.burnVault` →
`runBurnWipe(raiseHold=…)` raises the hold before the first destructive mutation.
**Readers:** the lock screen (message), `bootRoute`'s hold arm (routing).
**Verify by:** changing either half in isolation and confirming a review item fires — there is no
mechanical guard, which is precisely why it is written here.

---

## WB-2 — THE WIPE IS NonCancellable AS A SECURITY PROPERTY, NOT A ROBUSTNESS ONE

**Statement.** Past the first unlink the burn runs under `NonCancellable`.

**Why.** A duress wipe a rotation can interrupt is a duress wipe a COERCER can interrupt: hand the
phone back, rotate the screen, and the wipe stops half-done. Cancellability here is not a
responsiveness trade-off — it is an attacker-controlled abort.

**The failure mode this ID exists to prevent.** "Make this cancellable so the UI stays responsive" is
a change someone makes later on robustness grounds without realizing the threat model depends on the
opposite. Stated at the call site as well as here.

---

## WB-3 — ONE DURABILITY OWNER, THREE PRODUCERS

**Statement.** `durabilityHold` means exactly "some destructive mutation of local state did not prove
durable". Producers: the cold-start sweep, the two boot reconcilers, and the burn's own obliterate.
Routing cares ONLY that it is raised, never which producer raised it.

**Binding.** No second hold field, and no discriminator. **If any consumer ever needs to know WHICH
mutation failed, the single-field design has broken down — surface it as a FINDING rather than
widening the field.** First place to look for an unintended interaction between W-A and W-B.

---

## WB-4 — `wipeBiometricMaterial()`: ONE HELPER, TWO CONTRACTS, DELIBERATELY

| Caller | Contract | Why |
|---|---|---|
| `destroyVaultForAccountDeletion` | best-effort; a failure does NOT fail the delete | the load-bearing step is the image destroy; a Keystore already unhealthy must not strand it |
| `burnVault` | **consumes the boolean; false FAILS the wipe** | an orphaned Keystore alias is "something was here" residue, and post-burn ≡ fresh install is this feature's purpose |

**The failure mode this ID exists to prevent.** The asymmetry reads as an inconsistency to a reviewer
skimming for uniformity, and "unify these" would silently downgrade the burn contract. Stated at both
call sites, not only here.

---

## WB-5 — THE W-A/W-B INTERACTION: `Route.Locked` NO LONGER IMPLIES AN IMAGE

**Derived, not asserted** (maintainer ruling D). W-A added two ways to reach `LOCKED` with no image
present: the hold arm and the else arm over an indeterminate stat. The pre-split table's §0 proof
assumed `Route.Locked` ⇒ image present.

**Derivation.** From LOCKED-with-no-image the only input is a passphrase: `attemptPassphrase` →
`attemptUnlockOrAdd`, whose first act is `canonical ?: run { open(); canonical!! }`, and `open()`
throws `MissingImage` when `vault.bin` is absent (`VaultImageStore.kt:352`). Therefore:
- **`Burn` is unreachable there** — it requires a slot-0 match from `tryPassphrase(decoded.slots)`,
  and there are no decoded slots without an opened image.
- **The create/add branch is unreachable there** — it mutates `decoded.slots` of an existing image.
- **The pre-split §0 bounding fact SURVIVES** (a burn can only fire while `delete-confirmed` is
  absent) but on THIS argument, not the table's.

**Disclosed artifact (goes to `SECURITY_MODEL.md`):** LOCKED-with-no-image is an **unpassable lock
screen** — every passphrase fails at `open()` before any slot is interpreted. Fail-CLOSED and
restart-recoverable (the next boot's sweep finds a clean disk and routes to onboarding), but it has no
in-app exit. Created by W-A, not W-B; documented rather than hidden.

---

## WB-6 — R1 IS FIXED, NOT ACCEPTED

The pre-split table recorded R1 (interrupted-burn visible damaged state) as "unavoidable without a
durable pre-burn intent marker". FALSE: `completeInterruptedBurn()` resolves it with no marker, keyed
on `{bin PRESENT, dek PROVEN absent}` — a signature `create()` structurally cannot produce, since
create renames the DEK in FIRST and the image SECOND. See `failures.md`, the affirmative case for the
re-derive discipline.

---

## WB-7 — BOOT MUTATOR ORDERING IS IRRELEVANT BY PROOF

Three boot-time durable mutators run inside `runBootReconcile` (the single boot-time mutation owner).
Their trigger predicates are pairwise exclusive over all 32 enumerated on-disk states, asserted in
`BurnReconcilerTriggersTest` with a non-vacuity guard that all three fire somewhere. Ordering is
therefore irrelevant BY PROOF rather than by reasoning; widening any trigger fails the test loudly
instead of the ordering silently beginning to matter.
