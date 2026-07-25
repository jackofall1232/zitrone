# Zitrone 0.9.2-beta — FOLLOW-UP SPEC: biometric-enable atomicity (close the orphan-wrap gap)

Status: SPEC ONLY — awaiting maintainer review + an approach decision (OQ-1) before any code. No
version bump. Base: `main` (PR-1 `2de2bac` + PR-2 `374bd44` + PR-3 Unit 1 `23c9bc4` + Unit 2 `956bae9`).
Not release-blocking (pre-existing; disclosed in `SECURITY_MODEL.md` as a known robustness gap).

## 0. Why this exists
PR-3 Unit 1 established the A-only guard (never-repoint) but deliberately left the biometric **enable
flow** un-serialized (maintainer Option 2, round-4 scope decision). The docs (Unit 2) now DISCLOSE the
gap. This PR closes it. Recorded lesson (`failures.md`): the fix must be **process-correct**, NOT
Activity-scoped — a round-3 Activity-instance single-flight was reverted because an instance flag
cannot guard a PROCESS-shared resource and it introduced a sync-throw lockout.

## 1. The defect (verified against source)
One fixed Keystore alias `zitrone_vault_biometric_key` backs biometric. `newEncryptCipher()` does
`deleteKey()` then `generateKey()` — a **destructive replace** of that shared alias. The persisted
wrap `BiometricWrappedKey{slotIndex, blob}` records NO key/alias identity, so `cipherForDecrypt` opens
the blob with **whatever key currently sits at the alias**. Consequences under concurrency/interruption:
- **Concurrent first-enable** (double-tap; offer racing the Settings toggle): two enables both pass
  `isEnabled()==false`, each `newEncryptCipher()` replaces the alias; the persisted wrap ends sealed
  under K1 while the alias holds K2 → **key-replaced orphan**. On unlock: `cipherForDecrypt` succeeds
  (K2 present) but AEAD `doFinal` fails → `VaultBiometricResult.FAILED`, which does NOT clear the wrap
  or re-offer → biometric stuck failing; recovery = passphrase unlock + **manual** disable/re-enable.
- **Interrupted enable** (rotation/process-death mid-prompt): the old key was already deleted; if the
  enable never commits, biometric is left disabled (benign) — but if a peer committed, orphan as above.
- **disable ∥ enable**: `disableBiometric`/account-delete `clear()`+`deleteKey()` are NOT synchronized
  with enable's seal+save → an enable can persist a wrap after a disable cleared it (orphan/leak of a
  live-looking wrap), or vice-versa.

Blast radius (unchanged from the Unit-1 adjudication, dual-confirmed): **NO repoint of an established
wrap, NO destruction of a pre-existing valid binding, NO A/B distinguisher, NO passphrase/vault brick.**
It is an availability glitch — but a user-recoverable-only one, worth removing.

## 2. WRITER/READER invariant table — the shared Keystore alias + the single wrap (build BEFORE code)

| Actor | Reads | Writes | Invariant this PR must add/keep |
|---|---|---|---|
| `newEncryptCipher` (enable) | — | Keystore alias (delete+regen) | Must NOT destroy a key another in-flight/established binding still needs; a replace must be atomic w.r.t. the wrap that references it. |
| `sealVaultKey` + `BiometricUnlockStore.save` (enable commit) | vault key | prefs wrap `{slot, blob}` | The saved wrap must reference a key that EXISTS and opens it (no orphan). One wrap at a time; never repointed (Unit 1 belt — keep). |
| `cipherForDecrypt` + `unlockWithBiometric` (reader) | alias key, wrap.blob | — | Must resolve the SAME key that sealed the wrap it is opening. |
| `disableBiometric` / account-delete | — | `clear()` (wrap) + `deleteKey()` (alias) | Must be serialized with enable so a disable and an enable can't interleave into an orphan/leak. Clear is always allowed. |
| unlock result mapping | wrap presence, decrypt outcome | (on INVALIDATED/UNAVAILABLE) `clear()` | A persistently-unopenable wrap should be recoverable (OQ-3). |

## 3. Design approaches (maintainer decides — OQ-1)

### Approach A — process-scoped single-flight (serialize the whole enable)
A single-flight owned by `AppContainer` (process lifetime) admits ONE enable at a time; disable/
account-delete take the same guard. Closes the concurrency race by serialization; the alias stays a
single fixed alias. **The hard part is releasing the guard reliably across the interactive
BiometricPrompt without stranding** (the reason the Activity-scoped attempt failed):
- Drive the enable from `container.scope` (process-lifetime), not `lifecycleScope`, so a rotation does
  not cancel it mid-flight; the prompt is bound to the current Activity via a weak handle and a prompt
  whose Activity dies resolves as an error → the enable's `finally` releases the guard.
- Belt: a lease with a reclaim (D2c `lock.json` style in-memory) — a guard older than a bound timeout
  with no live owner is reclaimable, so a truly-lost enable cannot permanently lock out biometric.
- Pros: smallest change; no persisted-format change. Cons: serialization of an interactive flow is
  subtle (Unit 1 showed how easy it is to get wrong); must prove no stranding AND no lockout.

### Approach B — atomic keygen / self-describing wrap (eliminate the shared-alias race by construction)
Make enable non-destructive-until-commit: generate the new key under a **fresh alias** (alias + a
generation id, or ping-pong `…_a`/`…_b`), seal the wrap **recording which alias/generation** sealed it,
save atomically, THEN delete the previous alias. `unlockWithBiometric` reads the wrap's alias id and
uses that key. Concurrent enables each use their own alias → last-committed-wrap wins cleanly and the
wrap ALWAYS references an existing key → **no orphan can form**; an interrupted enable leaves the old
binding intact.
- Pros: robust by construction; tolerates interruption; no serialization of the prompt needed. Cons:
  changes the persisted wrap layout (`BiometricWrappedKey` gains an alias id) — a storage-format change
  (see `[[zitrone-storage-format-stability-gate]]`); needs lazy cleanup of stale aliases; a bit more code.

**Recommendation:** **Approach B** if we accept the small wrap-format change — it removes the failure
mode rather than racing to serialize an interactive flow, and it also fixes the interrupted-enable and
disable-∥-enable cases without a lease/stranding puzzle. If the maintainer wants to avoid ANY
persisted-format change right now, **Approach A** with the lease-reclaim is the fallback. This is OQ-1.

## 4. Scope (whichever approach)
- IN: close the concurrent/interrupted enable race (no orphan); serialize disable/account-delete with
  enable; keep Unit 1's A-only belt + slot-agnostic enroll UI intact (no A/B tell); OQ-3 recovery of a
  persistently-unopenable wrap.
- OUT: any change to the triple-entry router, the store writer, or the A-only guard's semantics; the
  Pucker Burn feature; per-vault destruction. No version bump.

## 5. OQ-3 — recover a persistently-unopenable wrap (decide with OQ-1)
Today a key-replaced orphan yields `FAILED` (no auto-clear). Option: treat a wrap that fails AEAD-open
with a PRESENT key as unopenable → route to `UNAVAILABLE` (clear + re-offer), NOT `FAILED`. **Care:** a
transient auth hiccup must not clear a good wrap — only a genuine bad-tag/key-mismatch open failure
(not a user cancel, not a lockout) should. Approach B largely removes the need (no orphan forms); A
still benefits from it as a belt. Include or defer? (OQ-3.)

## 6. Verification
- Host-testable: the serialization/idempotence decision logic and any wrap-format encode/decode
  (`BiometricWrappedKey` round-trip incl. the alias id under Approach B). Add a concurrency test at the
  pure-logic seam (two overlapping enable decisions cannot both commit a wrap / cannot orphan).
- Android-only/inspection-verified: the Keystore alias lifecycle + BiometricPrompt (as today).
- Full suite + `assembleDebug`; `assembleRelease` cert `6c7f92a7…892753` sanity (no version bump).

## 7. Sequencing & review
- Security-sensitive durable signal (the wrap + alias) → paired-blind security-review-loop (Codex +
  Grok; Moonshot round-6-only on non-convergence; hard-stop + HIL after round 6). WRITER/READER table
  first. Stop at ready-to-merge; no push/merge/version-bump without per-action approval.

## 8. OPEN QUESTIONS for the maintainer
- **OQ-1 (the decision):** Approach A (process single-flight + lease) or Approach B (atomic keygen +
  self-describing wrap)? Recommendation: B, unless avoiding a wrap-format change now is preferred.
- **OQ-2:** If B, the wrap-format change touches persisted storage — fold it under the storage-format
  stability decision (`[[zitrone-storage-format-stability-gate]]`)? (Zero real external users today, so
  a format bump is cheap, but it should be a conscious call.)
- **OQ-3:** Include the "persistently-unopenable wrap → clearable" recovery (§5) in this PR, or defer?
