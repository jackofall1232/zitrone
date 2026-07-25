# Zitrone 0.9.2-beta — PR-3 SPEC (biometric A-only guard + honest docs)

Status: SPEC ONLY — awaiting user review. No implementation, no review dispatch until approved.
Base: `main` @ `374bd44` (PR-1 `2de2bac` + PR-2 `374bd44`). No version bump. Branch (on approval):
`feat/0.9.2-vault-pr3-biometric-docs`.

---

## 0. Context correction (READ FIRST) — creation is already live

The original PR plan put the MainActivity "no-match → create" wiring in PR-3. **PR-2 already
shipped it**: `onUnlockPassphrase` maps `PassphraseOutcome.Created → onUnlockSuccess()`
(`MainActivity.kt:790`), and `attemptPassphrase` creates on the 3rd identical entry. So **as of the
PR-2 merge, a user who enters the same never-before-used passphrase three times DOES create and open
a second vault on `main` today.** There is no feature flag.

Consequences that reshape PR-3:
1. PR-3 is **no longer the gate that enables creation** — that horse is out. PR-3 closes the two
   things that now LAG behind a live capability: the **biometric A-only guard** and **honest docs**.
2. There is a **live (minor, self-inflicted) gap on `main` right now**: nothing stops a user in a
   second-vault (B) session from enabling biometric, which repoints the single wrap from A to B (A
   loses biometric; the wrap now names B's slot). It is NOT an adversary path (it requires the
   legitimate user to both create B and then enable biometric from the B session) and there is only
   ever ONE wrap (no multi-wrap "two vaults exist" artifact). But it violates OQ4 and is exactly what
   this PR closes — so PR-3 should **not lag**.
3. This deviation should be recorded; if the user considers the live gap unacceptable even briefly,
   we can fast-track just Unit 1 (the guard) ahead of the docs.

---

## 1. Scope (OQ4 + OQ5 only)

In: (Unit 1) biometric-enable A-only guard — logic + UI suppression + tests; (Unit 2) docs
reconciliation (`VAULT_ARCHITECTURE.md §3.3/§3.4` + wizard refs → silent-router/triple-entry;
`SECURITY_MODEL.md` flip to "two vaults creatable" + new disclosures).

Out (later phases, unchanged): Pucker Burn setup UX + wipe execution (sibling PRs); per-vault
destruction (`destroy()` stays whole-image); any change to the router/store crypto (frozen after
PR-1/PR-2). No version bump.

---

## 2. WRITER/READER invariant — the single biometric wrap (build BEFORE Unit 1)

The durable multi-reader signal PR-3 touches is the **biometric wrap** `{slotIndex, blob}`
(`BiometricWrappedKey`, one record via `BiometricUnlockStore`). Enumerate before coding:

| Actor | Reads | Writes | Invariant PR-3 must preserve |
|---|---|---|---|
| `enableBiometricFromSession` (writer) | existing wrap slot (NEW) | wrap `{session.slotIndex, blob}` | **A-bound single-wrap:** may write ONLY when no wrap exists OR existing wrap.slotIndex == session.slotIndex. Never repoint to a different slot. |
| `unlockWithBiometric` (reader) | wrap.slotIndex → `unlockWithKey` | — | Unchanged; still opens whatever slot the wrap names. |
| `biometricStore.isEnabled()` (reader) | wrap presence | — | Unchanged. |
| invalidation / disable / account-delete | — | `clear()` | Unchanged; clearing is slot-agnostic and always allowed. |
| onboarding create → enroll offer | — | (via writer) | First enroll (no wrap) permitted → BECOMES the A binding (see OQ open question). |

Guard rule (operationalizes OQ4 "suppress from any non-A-bound session; keep single wrap A-bound"):

```
enableAllowed(session) = !biometricStore.isEnabled()            // no wrap yet → first-bind
                         || biometricStore.peekSlotIndex() == session.slotIndex   // same vault re-enable
```

A "non-A-bound session" = a session whose slotIndex ≠ the existing wrap's slotIndex. The guard both
(a) SUPPRESSES the offer UI and (b) fail-closed REFUSES the write if reached anyway.

---

## 3. Unit 1 — biometric-enable A-only guard

### 3a. Store: expose the bound slot (read-only, no biometric auth)
`BiometricUnlockStore` currently exposes `isEnabled()`, `save()`, `clear()` and does not surface the
persisted wrap's slotIndex without a biometric unlock. Add:

- `fun peekSlotIndex(): Int?` — returns the persisted wrap's `slotIndex` (metadata, NOT inside the
  biometric-key-encrypted blob) or null when no wrap. **VERIFY during impl:** the slotIndex must be
  readable as plaintext metadata alongside the blob (it already is — `unlockWithBiometric` uses
  `wrap.slotIndex`; confirm it is not sealed inside the auth-gated ciphertext). If it IS sealed,
  fall back to tracking the bound slot in the same non-secret prefs the store already writes, set on
  `save()`. No new plaintext artifact beyond what the wrap already implies.

### 3b. Container: the gate + fail-closed writer
- `AppContainer.biometricEnableAllowed(session): Boolean` = the guard rule in §2 (pure, RAM/prefs
  read only).
- `enableBiometricFromSession(cipher, session)` (`ZitroneApp.kt:551`): **before** `biometricStore.save(...)`,
  `require(biometricEnableAllowed(session))`-style FAIL-CLOSED — if a wrap exists bound to a
  different slot, return false and write NOTHING (do not repoint). This is the belt to the UI's
  suspenders: even if the offer leaks, the write refuses. Wipe the transient `blob`/cipher material
  on the refuse path exactly as on success.

### 3c. UI: suppress every enroll entry point for a non-A-bound session
Gate ALL three enroll surfaces on `container.biometricEnableAllowed(session)` (in addition to the
existing `canAuthenticateStrong`):
- the post-onboarding-create offer,
- the post-passphrase-unlock re-offer after invalidation (`reofferBiometric` path, `MainActivity:761`),
- the Settings "enable biometric" toggle/affordance.

For a non-A-bound (B) session the enroll affordance is simply **absent** — no message, no per-slot
copy, no error (a rendered "you can't enable biometric on this vault" would itself be a distinguisher
that says "this is the second vault"). Silence only. Biometric *unlock* (when a wrap exists and the
platform can auth) is unaffected — a B session just never sees the *enable* path.

### 3d. Tests (host unit, `BiometricUnlockStoreTest` / new)
- `peekSlotIndex` returns the saved slot; null when cleared/absent.
- `biometricEnableAllowed`: true when no wrap; true when wrap.slot == session.slot; **false when
  wrap.slot != session.slot**.
- `enableBiometricFromSession` FAIL-CLOSED: with a wrap bound to slot X, an enable from a session on
  slot Y ≠ X returns false and leaves the stored wrap UNCHANGED (still slot X, same blob).
- Same-slot re-enable (X == X) still succeeds (covers post-invalidation re-enroll).
- Keystore-backed cipher paths remain inspection-verified (Android-only, not host-unit-testable) —
  note explicitly, do not fake.

---

## 4. Unit 2 — docs reconciliation (OQ5)

No code. Present-tense only for what `main` actually ships (creation now DOES work; biometric is
A-only; destruction does NOT).

### 4a. `VAULT_ARCHITECTURE.md`
- Line ~22 table row + line ~28 banner ("second vault NOT creatable yet") → **flip to creatable via
  the silent triple-entry router**; keep destruction/per-vault teardown as NOT built.
- §2 "there is no button for the second vault" → keep the spirit (no discoverable UI) but correct any
  wording implying creation is impossible; describe the **triple-entry ceremony** (3 identical
  never-matching entries, uninterrupted; slot 0 reserved for burn; blind placement over slots 1–3).
- **§3.3 "Setup"** → the current text says a "dedicated, explicit **setup wizard**." OQ5: **there is
  no wizard** — replace with the silent-router/triple-entry description. Remove the "wizard copy needs
  review before ship" note.
- **§3.4 "Destruction"** → keep as an explicitly FUTURE phase (needs a single-slot-overwrite primitive
  that does not exist; `destroy()` is whole-image and removes ALL on-device identities — OQ3).
- Any `PR_C3 wizard` cross-reference → silent-router/triple-entry.

### 4b. `SECURITY_MODEL.md`
Flip status to **"two vaults creatable on Android"** and add the honest disclosures the new capability
requires:
- **Triple-entry gate + systematic-entry limitation** — creation needs 3 consecutive identical
  uninterrupted entries of a never-before-used passphrase; a systematic/scripted enumerator entering
  many DIFFERENT passphrases will not trip it, but 3× the SAME new passphrase will create. State this
  plainly (it bounds the deniability: an adversary who makes you enter a chosen wrong passphrase 3×
  could induce a creation — document it).
- **~33% blind-overwrite** — a create blind-places over slots 1–3; ~1/3 chance it overwrites an
  existing OTHER vault's slot (full-pool-overwrite certainty once the pool is full). This is a
  data-loss disclosure.
- **Biometric is A-only** — only the biometric-bound (everyday) vault can use biometric; a second
  vault is passphrase-only. There is exactly one wrap; it never names two slots.
- **Burn permanence** — the Pucker Burn credential (slot 0) is a duress wipe; once burned, gone
  (forward-reference the burn PRs; keep it accurate to what ships — burn setup/wipe are NOT in this
  PR, so word it as "reserved / not yet user-settable" unless the burn PR lands first).
- Keep the server zero-knowledge statements untouched.

### 4c. `CHANGELOG` / README
- CHANGELOG `[Unreleased]` (NOT a version bump): "second vault creatable via silent triple-entry;
  biometric A-only." README any "second vault not creatable" honesty line → corrected.

---

## 5. Verification
- `:app:compileDebugKotlin` + `:app:testDebugUnitTest` (new + existing biometric/router/autolock
  suites) green.
- `:app:assembleRelease` signed, cert `6c7f92a7…892753` (release-path sanity; no version bump).
- Docs: grep for stale "not creatable"/"wizard" strings across `docs/`, README, CHANGELOG → none
  remain; every present-tense claim matches shipped behavior.

---

## 6. Sequencing & review
- Unit 1 before Unit 2 (guard before we DOCUMENT the guard as true). Both in one PR.
- Security-sensitive surface (durable wrap invariant) → the paired-blind security-review-loop
  (Codex + Grok; Moonshot as the round-6 third lens only on non-convergence; HARD STOP + HIL after
  round 6). Focus items: the fail-closed writer refuse (no repoint, no partial write, material wiped);
  peekSlotIndex exposes no NEW plaintext artifact beyond the existing wrap; UI suppression leaks no
  distinguisher (silence, not a message); no regression to unlock/invalidation/disable/account-delete.
- Stop at ready-to-merge. No push/merge/version-bump without explicit per-action approval.

---

## 7. OPEN QUESTIONS for the user (do NOT decide unilaterally)

**OQ-A (the one real ambiguity in OQ4): identity of "A" on the FIRST enable, when no wrap exists.**
OQ4 says "keep the single wrap A-bound" and "suppress from any non-A-bound session." When a wrap
already exists this is unambiguous (slot-match). But on the **first** enable (no wrap yet), which
session may bind it?
- **(i) First-enable-wins (proposed default):** any session may bind the wrap when none exists; from
  then on it is A-bound and the guard locks it. Simplest; matches "keep the single wrap A-bound" (it
  only constrains AFTER binding). Edge: if the user creates B and enables biometric before ever
  enabling on A, B becomes the biometric vault. Low-likelihood (biometric is typically enabled during
  early everyday-A use, before B exists).
- **(ii) Pin A at onboarding:** record the onboarding-created slot as A (in non-secret prefs) and
  allow first-enable only from that slot. Stronger "A = the everyday vault" guarantee, but adds a
  durable "slot N is A" artifact — a (mild) plausible-deniability distinguisher that (i) avoids. This
  cuts against the "A and B are structurally indistinguishable" principle.
- **(iii) Bind A implicitly = the slot with an existing wrap, and require biometric be enabled during
  onboarding** (force the choice early). Changes onboarding UX.

Recommendation: **(i)** — it honors OQ4 literally, adds no new distinguishing artifact, and the edge
is a benign user choice. But this is your call.

**OQ-B: the live-gap urgency (from §0.2).** Ship Unit 1 + Unit 2 together (normal), or fast-track
Unit 1 (guard) as its own quick PR ahead of the docs because creation is already live on `main`?

**OQ-C: burn wording in SECURITY_MODEL now, or defer that paragraph to the burn PR?** (§4b last
bullet.) Documenting burn permanence before burn setup/wipe ships risks describing an unshipped
feature; deferring keeps docs strictly present-tense.
