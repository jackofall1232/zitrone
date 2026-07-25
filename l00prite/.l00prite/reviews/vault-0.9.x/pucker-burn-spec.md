# Pucker Burn — Implementation Spec (setup + wipe)

**Status:** SPEC ONLY — FINALIZED, pending user review before implementation. All decisions resolved
(Q1–Q3 technical, P1–P3 product, harness=Robolectric, sequencing=W→S, marker-clear ordering caveat folded
in). Stop before implementation. Same gate as every 0.9.2 unit (WRITER/READER invariant table → real
build/test evidence → independent paired-blind review → clean convergence → explicit human merge/version
decision).

**Basis:** 4/4 blind advisory convergence (`pucker-burn-synthesis.md`) + three user product decisions
(2026-07-24, recorded in `zitrone-vault-ledger.md`). Source re-verified this session against
`VaultImageStore.kt`, `ZitroneApp.kt`, `MainActivity.kt`, `AndroidManifest.xml`,
`res/xml/data_extraction_rules.xml`.

---

## 1. Scope

**In scope (two sibling units):**
- **Unit W — WIPE:** decompose `destroy()` into a marker-free, fail-closed, keys-first `obliterate()`
  primitive; wire the real duress wipe to the lock-screen `Burn` outcome (replacing the fail-closed
  `onBurn` stub at `MainActivity.kt:786`); byte-for-byte post-burn state gate; boot-time reconciliation
  of an interrupted burn.
- **Unit S — SETUP:** a permanent (never-disappearing) "Pucker Burn Password Setup" settings entry above
  "Delete Account"; an actively-acknowledged permanence warning; arming = seal a derived credential into
  slot 0 in place (no format change); collision rejection against the vault pool.

**Out of scope (recorded, deferred):**
- Relay-account deletion (Q1 local-only — never in the burn path).
- Per-vault destruction + decoy-unlock model (future phase; `destroy()`/`obliterate()` stay whole-image).
- Any IMAGE_VERSION bump (Q3 — none needed; a bump would itself leak).

---

## 2. Resolved decisions (do not relitigate)

| # | Decision | Source |
|---|----------|--------|
| Q1 | Wipe destroys LOCAL state only. No relay deletion. Honest claim: "this device can no longer recover the accounts," not "the relay has no record." | advisory unanimous |
| Q2 | Reuse the destruction PRIMITIVE, not the D2c marker machine. `destroy()` cannot be called as-is (see §3). | advisory + source |
| Q3 | No format change, no version bump. Arm = seal slot 0 in place within v3. | source-verified |
| P1 | Settings entry NEVER disappears — armed/unarmed present byte-identically. Re-running setup RE-SEALS slot 0. Permanence = "unrecoverable & unknowable," not "unrewritable." | user |
| P2 | Post-burn = VISIBLE RESET (ordinary onboarding, no special screen). Decoy deferred. | user |
| P3 | Wipe DoD = BYTE-FOR-BYTE gate: post-burn app-local state diffs zero against post-fresh-install; OS residuals explicitly asserted known-and-accepted with per-exclusion reasons in-test. | user |

---

## 3. Unit W — the `obliterate()` decomposition (the load-bearing change)

### 3.1 Why `destroy()` cannot be called from burn (source-verified)
`VaultImageStore.destroy()` (line 1056):
1. wipes RAM DEK, nulls `canonical`;
2. **`writeDurableMarker(serverDeletedFile)`** — writes `vault.delete-confirmed`, REQUIRED-DURABLE:
   **throws `DestroyFailed` with the vault files UNTOUCHED if it can't fsync** (line ~1068);
3. unlinks `binFile`, `dekFile`, temps;
4. `unregister()`;
5. verify-all-gone (throw if any survive);
6. `dirSync` durable (throw if not);
7. `clearBothMarkersDurably()` (throw if not).

Calling this from burn is broken three ways, all confirmed in source:
- **(a) False fact** — writes "server account confirmed gone" when no server delete occurred.
- **(b) Crash tell** — the confirmed marker written at step 2 means a crash mid-unlink restarts into
  `Route.DeleteIncomplete` ("finish deleting your account"), and on the next live session the D2c
  reconcile path could fire a **real network DELETE**. A discoverable, false, network-triggering state.
- **(c) Fail-OPEN** — step 2 can throw *before anything is destroyed*. A duress wipe must never fail open.

### 3.2 The factored primitive
Extract the marker-free physical/cryptographic core. Both callers share it; only the account-delete
caller adds D2c semantics on top.

```
private fun obliterate() {           // NEW — marker-free, fail-closed, keys-first. Under imageLock.
    dek?.let { wipe(it) }; dek = null; canonical = null
    // ── STEP 1: UNLINK, KEYS-FIRST (advisory / Moonshot) ──────────────────────────────────────
    // Unlink the DEK envelope BEFORE the ciphertext image, so the worst crash interruption leaves
    // ciphertext-without-key (cryptographically erased), never the reverse.
    dekFile.delete(); deleteLeftoverTmp(dekFile)
    binFile.delete(); deleteLeftoverTmp(binFile)
    unregister()
    // ── STEP 2: PROVE THE UNLINKS DURABLE (fail-closed) ───────────────────────────────────────
    if (binFile.exists() || dekFile.exists() ||
        leftoverTmp(binFile).exists() || leftoverTmp(dekFile).exists())
        throw VaultImageException.DestroyFailed()          // a survivor is a FAILED wipe
    if (dirSync(baseDir) != DirSyncResult.DURABLE)
        throw VaultImageException.DestroyFailed()
    // ── STEP 3: CLEAR MARKERS — STRICTLY AFTER the image is proven durably GONE (BINDING) ──────
    // ORDERING IS LOAD-BEARING (user caveat): the marker clear MUST come after the DEK+image
    // unlinks are proven durable, NEVER before. Clearing markers while the image still exists
    // reproduces PR-1's B1 failure state (markers say "gone" over a live image). Because the image
    // is proven absent by Step 2, the markers here are ORPHANED BY DEFINITION — the same
    // precondition that makes create()'s marker clear safe. A crash BETWEEN Step 1/2 and Step 3
    // (image gone, markers still present) is handled by boot reconciliation (§3.4), which completes
    // this clear on next start.
    if (!clearBothMarkersDurably())                        // land with NO markers (fresh-install parity)
        throw VaultImageException.DestroyFailed()
}

fun destroy() {                      // account-delete path
    imageLock.withLock {
        // confirmed-marker crash-bridge BEFORE obliterate (reaching destroy() = server confirmed gone):
        // a crash mid-unlink restarts into DeleteIncomplete and re-runs the idempotent destroy.
        dek?.let { wipe(it) }; dek = null; canonical = null   // (retained: terminal for this store)
        writeDurableMarker(serverDeletedFile)
        obliterate()                                          // unlink → verify → dirsync → clear markers
    }
}
// ⚠ HONEST EQUIVALENCE NOTE (do NOT overclaim "identical"): this refactor preserves destroy()'s
// EXTERNAL, non-crash-observable behavior (same end state: DEK+image+temps gone, dirSync durable,
// both markers retired, DestroyFailed on any survivor/non-durable step, idempotent). It changes ONE
// internal detail: today destroy() unlinks bin THEN dek; the shared primitive unlinks dek THEN bin
// (keys-first). This is a DELIBERATE, SAFE change for destroy() — the confirmed marker is written
// first, so a crash at any point re-runs the idempotent destroy regardless of unlink order; keys-first
// is strictly safer (a crash leaves ciphertext crypto-erased). This is NOT a behavior-preserving
// refactor in the strictest sense and MUST be a named review item (§8), verified against source, not
// assumed by construction. If review judges the order change unacceptable for destroy(), the fallback
// is an `keysFirst: Boolean` param on obliterate() (destroy() passes false, burn passes true) — one
// primitive, one branch — rather than two divergent unlink implementations.

fun burnObliterate() {               // NEW duress path — NO confirmed marker, NO false D2c transition
    imageLock.withLock { obliterate() }
}
```

**Marker handling under burn (design call, deviates from one advisory line — flagged):** `obliterate()`
clears BOTH delete markers at the end. The Codex advisory suggested burn "not clear genuine pre-existing
D2c markers." I am overriding that for the byte-for-byte gate (P3): a fresh install has **no** markers, so
post-burn must have none, or boot routing reads a stale `delete-intent` over a now-absent image
(`deleteIntentPending()` at `VaultImageStore.kt:1132` = intent-present && !confirmed → routes to reconcile
a vault that no longer exists — a bug and a tell). Whole-image obliteration retires all local state
together. This is a WRITER/READER-table item to verify in review.

### 3.3 WRITER/READER invariant table (durable signals touched by burn)

| Signal | Writers (after change) | Readers | Burn's effect | Crash-interruption outcome |
|--------|------------------------|---------|---------------|----------------------------|
| `vault.bin` | create, `attemptUnlockOrAdd`(create), `atomicWrite`; **obliterate unlinks** | `exists()`→`hasVault()`, `open()`, boot routing | unlinked (2nd) | present-or-absent; if present w/ dek gone → crypto-erased, boot must obliterate (§3.4) |
| `vault.dek` | create, dek-write; **obliterate unlinks** | `open()` (DEK unwrap) | unlinked (1st, keys-first) | absent before bin → safe |
| `vault.delete-intent` | `markDeleteIntent`, `clearDeleteIntent`, `clearBothMarkersDurably`; **obliterate clears** | `deleteIntentPending`, `hasDeleteIntentMarker` (auth guard!) | cleared | may survive if crash before clear → boot reconciliation clears it (§3.4) |
| `vault.delete-confirmed` | `markServerDeleteConfirmed`, `destroy`, `clearBothMarkersDurably`; **burn NEVER writes it**; obliterate clears | `serverDeleteConfirmed` (auto-destroy authorization!) | cleared, never written by burn | never written → no false auto-destroy authorization |
| RAM DEK | session, `obliterate` wipes | crypto ops | wiped (1st) | process death wipes RAM anyway |
| Keystore biometric wraps | `enableBiometricFromSession`, `destroyVaultForAccountDeletion` | `BiometricUnlockStore` | burn path must remove (§3.5) | orphan alias = "something was here" residue → gate catches |

**Critical invariant (auth-guard interaction):** `hasDeleteIntentMarker()` gates token-clearing
(`onSessionRevoked`, per the D2c hardening). Burn clears the intent marker as part of obliteration, but
burn also tears down the whole image and session — there is no post-burn session to have its tokens
guarded. Review must confirm burn's teardown order cannot leave a live session reading a just-cleared
marker (see §3.5 terminal-exclusion).

### 3.4 Boot-time reconciliation (interrupted burn) — the crash-between-unlink-and-clear window
The exact window (user caveat): a crash AFTER Step 1/2 (DEK+image unlinked, proven durable) but BEFORE
Step 3 (marker clear) leaves **image gone + markers possibly present**. Boot must treat this as
burn-in-progress and complete the clear.

Boot check (before routing): if the image is absent (`!hasVault()`) but a delete marker survives, silently
complete teardown — `clearBothMarkersDurably()`. Writes nothing new, leaves no marker, lands in clean
onboarding. Idempotent; must NOT route to `DeleteIncomplete`.

Distinguish the two "image-absent + marker-present" causes — they converge on the SAME safe action:
- **Interrupted BURN** (this feature): `delete-confirmed` was never written by burn, but a pre-existing
  `delete-intent` (if a delete was mid-flight when burn fired from the lock screen) may survive.
- **Interrupted account-DELETE** (existing D2c): `destroy()` wrote `delete-confirmed` first, then crashed
  mid-unlink — today this correctly routes to `DeleteIncomplete` auto-destroy over a *present* image.

The disambiguator is `hasVault()`: DeleteIncomplete auto-destroy is authorized only over a PRESENT image
(`serverDeleteConfirmed()` && image exists). With the image ALREADY absent, there is nothing to destroy —
so both causes reduce to "sweep the orphaned markers, land on onboarding." Review must confirm the boot
routing cannot send an image-absent state into `DeleteIncomplete`.

*Current boot routing (`MainActivity.kt:647`): `!hasVault() && !serverDeleteConfirmed()` → onboarding.*
After a completed burn: `hasVault()`=false, `serverDeleteConfirmed()`=false → onboarding ✓. The new
reconciliation only adds: sweep a surviving `delete-intent` (or a stray `delete-confirmed` over an absent
image) so `deleteIntentPending()`/`serverDeleteConfirmed()` can't misroute a post-burn boot.

### 3.5 Wipe wiring + terminal exclusion
- Replace the `onBurn` stub (`MainActivity.kt:786`). Today it fakes a uniform failure; it becomes: begin
  terminal exclusion → `burnObliterate()` + biometric/Keystore teardown → route to onboarding.
- **Wiring invariant (Moonshot ship-blocker, pin it):** the wipe fires ONLY from the lock-screen unlock
  dispatch's `PassphraseOutcome.Burn` (`MainActivity.kt:805`). `attemptUnlockOrAdd` has a single caller
  and returns `Burn` only on a real slot-0 match (create-collision returns `Rejected`, never `Burn`) — so
  a second-vault create can never trigger a wipe. The spec forbids adding any other consumer of the `Burn`
  outcome that wipes; any future caller must treat `Burn` as "reject candidate."
- **Terminal exclusion (advisory #6/#4):** before the first destructive mutation, stop runtime activity so
  no flush/callback/notification/WorkManager job resurrects state or leaks UI after destruction. Reuse the
  `isTerminalWipe()` mechanism (`UnlockController.kt:180`) that already fences the auto-lock timer, so a
  background timer never races the burn. Enumerate every teardown the lock-screen path lacks vs. the
  account-delete path (which runs from a live session): the byte-for-byte gate (§4) is what proves this
  enumeration complete.
- **Keystore/biometric:** burn must run the same biometric wrap/alias removal
  `destroyVaultForAccountDeletion()` does (`ZitroneApp.kt:640-651`) — but WITHOUT `imageStore.destroy()`;
  call `burnObliterate()` instead. Factor a `wipeBiometricMaterial()` helper shared by both.

---

## 4. Byte-for-byte gate (P3) — test design

**Goal:** an instrumented test asserts post-burn app-local state == post-fresh-install state, zero delta.

**Coverage set (must all be equal or explicitly excluded):** files under the app data dir (`vault.bin`,
`vault.dek`, temps, markers), SharedPreferences / EncryptedSharedPreferences, databases (messages,
contacts, attachment index), attachment/media under app-private storage, caches, notification channels the
app created, WorkManager jobs, Android Keystore aliases (biometric wraps), in-memory singletons where
observable.

**Explicit exclusion assertions (known-and-accepted, each with a reason IN the test):** package
install/update time, UsageStats, notification *history* (system-journaled), MediaStore exports, account
manager (app registers none), NAND-level residue. These are OS-level, outside app control — the test must
enumerate and assert them as expected-different (or expected-present), never silently drop them. The same
list goes into `SECURITY_MODEL.md`.

**Harness — DECIDED: Robolectric in `src/test` (user, 2026-07-24).** The only option that delivers the
byte-for-byte decision as made and runs in normal CI on every PR (the property that makes the gate survive
the author no longer thinking about it — same reasoning that chose the mechanical gate over a checklist).
The `androidTest` connected set was rejected: emulator availability in CI is unconfirmed here, and a gate
that can't run in CI isn't a gate. The hybrid was rejected: its manual half would cover exactly the
artifacts most likely to be forgotten.

**Binding requirement on shadow-fidelity gaps (user):** any artifact class Robolectric cannot faithfully
shadow is accepted ONLY as an explicit exclusion with a stated reason IN the test itself (an exclusion
list that grows without scrutiny is a checklist wearing a test's clothes), AND must also appear in
`SECURITY_MODEL.md` as a limitation — the app cannot claim fresh-install-indistinguishability for anything
the test does not actually verify. Review item: audit the Robolectric shadow coverage for Keystore
aliases / notification channels / WorkManager and pin each gap as an in-test exclusion + a SECURITY_MODEL
line.

---

## 5. Unit S — setup + arming

- **Settings entry (P1):** permanent item "Pucker Burn Password Setup" above "Delete Account"
  (`SettingsScreen.kt:267` anchor). Identical for armed and unarmed installs — no armed-flag anywhere
  (that would be the forbidden discoverable artifact + would ride nothing since backup is excluded, but
  still an on-device oracle). Tapping it always opens the set/replace flow; submitting re-seals slot 0.
- **Permanence warning:** actively-acknowledged. Copy must state: this can never be *recovered or verified*
  and anyone who learns it can erase this vault forever; a burn *consumes* the credential (re-arm needed
  after a burn); re-running setup silently replaces the current burn password (there is no "is it set?"
  readback — by design).
- **Arming = seal slot 0 in place (Q3, no format change):** derive a credential and
  `sealSlot`/`sealSlotSelfVerifying` into slot 0's existing salt+wrapped-key region (payload stays
  filler/empty-genesis, sized identically). Persist the whole image via the existing `atomicWrite`
  discipline under the existing DEK (no dek write). Must use a **targeted slot-0 writer** — the
  blind-placement path (`randomVaultSlotIndex`) *excludes* slot 0 by design, so arming needs its own
  writer that commits atomically (a crash mid-arm must leave slot 0 as bytes indistinguishable from
  filler — which it already is, so this fails safe; confirm the atomic property holds for an in-place
  single-slot write, not only whole-image commits).
- **Collision rejection (advisory #3):** setup runs the full sweep and rejects a candidate that matches
  any occupied VAULT-pool slot (1..SLOT_COUNT-1) — else first-match ordering makes slot 0 win on a later
  unlock and wipe instead of unlock. Rejecting against slot 0 (the current burn) is unnecessary since
  re-seal overwrites it. Setup runs inside an unlocked session, so a "choose a different passphrase"
  message is not a lock-screen oracle (acceptable disclosure). Argon2 cost at setup is not timing-sensitive.
- **`imageLock` + refuse-if-delete-pending (advisory #15):** arming rewrites the shared image; take
  `imageLock` and refuse setup while `deleteIntentPending()` (probably surface a benign "try again").

---

## 6. Decisions (all resolved 2026-07-24)

- **Gate harness — DECIDED: Robolectric in `src/test`** (§4). Runs in CI on every PR; shadow gaps become
  explicit in-test exclusions + SECURITY_MODEL lines.
- **Sequencing — DECIDED: Unit W (wipe) FIRST, then Unit S (setup).** With slot 0 unarmed, `Burn` is
  structurally unreachable in prod, so the riskiest durable-state change (keys-first `obliterate()`) lands
  and clears full paired-blind review while nothing can trigger it; setup flips reachability only after the
  mechanism is proven (mirrors PR-1's burn-aware store shipping before UI). Setup-first was rejected on its
  named failure: a user who arms burn over a stub has a duress credential that silently does nothing — the
  worst state this feature can produce, even in a no-external-user beta.
- **Review intensity — DECIDED: full D2c-level for Unit W** (new writer to durable state, touches the
  unlink path, factors a primitive out of `destroy()`): two blind reviewers (Codex + Grok) to clean
  convergence, Moonshot third lens at round 6 on non-convergence, adjudicate every finding against source.
  Unit S is lower-risk (one focused pass may suffice). Per `[[workflow-agent-budget-discipline]]`, ≤5 agents.

## 7. Already-closed / not-owed (verified this session)
- **Auto-Backup exclusion (advisory ship-blocker #11/#4): ALREADY CLOSED.** `allowBackup="false"`,
  `fullBackupContent="false"`, and `data_extraction_rules.xml` excludes every domain (root/sharedpref/
  database/file/external) from BOTH cloud-backup and device-transfer. No burn resurrection via restore.
- **Wiring self-DoS (advisory ship-blocker #16): architecturally prevented today** (single caller, `Burn`
  only on real slot-0 match). Spec pins it as an invariant rather than fixing a live bug.

## 8. Named review items (checklist for the Unit W paired-blind loop)
- [ ] **`destroy()` behavioral-equivalence, verified against source (not assumed):** end state identical
      to today; the ONE intentional deviation is unlink order (bin-then-dek → dek-then-bin, keys-first) —
      confirm it's safe for destroy() (confirmed-marker-first makes re-destroy idempotent regardless of
      order) or fall back to the `keysFirst` param. Do NOT accept a bare "identical by construction."
- [ ] `obliterate()` marker-free, fail-closed, keys-first (dek before bin).
- [ ] **Marker-clear ordering (BINDING):** clear happens STRICTLY AFTER the DEK+image unlinks are proven
      durable, never before — else B1's "markers say gone over a live image" state is reproduced in burn.
- [ ] Burn never writes `vault.delete-confirmed`; never lands in `DeleteIncomplete`.
- [ ] **Boot reconciliation handles the crash-between-unlink-and-clear window** (image gone + markers
      present → complete the clear, land on onboarding); confirm an image-absent state can never route to
      `DeleteIncomplete` auto-destroy.
- [ ] Wipe wired ONLY to lock-screen `PassphraseOutcome.Burn`; no other wiping consumer (invariant, not a
      live bug — pin it).
- [ ] Terminal exclusion begins before the first destructive mutation; no session reads a just-cleared
      marker (auth-guard interaction, §3.3).
- [ ] Setup rejects a candidate matching any vault-pool slot.
- [ ] Arming takes `imageLock`, refuses while delete-intent pending, commits slot 0 atomically.
- [ ] Slot 0 never biometric-wrapped (confirm PR-3 A-only guard precludes it).
- [ ] Byte-for-byte gate green; Robolectric shadow gaps pinned as in-test exclusions + `SECURITY_MODEL`
      lines; OS residuals explicitly asserted.
- [ ] `SECURITY_MODEL.md`: local-only scope; "protects the DATA, not the FACT data existed"; crypto-erase
      not NAND-sanitization; single-snapshot indistinguishability; forensic-image-first bound; burn
      consumes credential.
