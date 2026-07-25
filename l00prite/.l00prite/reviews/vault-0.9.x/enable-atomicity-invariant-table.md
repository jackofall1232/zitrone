# Enable-atomicity (Approach B) — WRITER/READER invariant table (built BEFORE code)

The durable multi-reader signals: (1) the persisted biometric wrap `{slotIndex, aliasId, blob}`
(prefs; NEW `aliasId` field), and (2) the set of AndroidKeystore aliases `PREFIX + <aliasId>`.
Approach B: each enable creates its OWN unique alias and the wrap records WHICH alias sealed it;
`newEncryptCipher` no longer deletes any other alias. Format bump (folded under the storage-format
decision; no migration — an old-format wrap without `aliasId` reads as not-enabled → re-enroll).

## CORE INVARIANT (must hold across concurrent enable + mid-enable crash)
**INV-1: the persisted wrap, when present, always references an existing Keystore alias whose key
sealed that wrap's blob.** No orphan (present-wrap → unopenable) can form. Held by construction:
- Each enable draws a FRESH random `aliasId` (collision-negligible) and generates its key under
  `PREFIX+aliasId`. Two enables never touch the same alias.
- `newEncryptCipher(aliasId)` creates ONLY its own alias — it never deletes another (unlike today's
  destructive `deleteKey()`+`generateKey()` on one shared alias).
- The wrap is saved AFTER its alias exists, referencing that alias.
- GC (`deleteAllAliasesExcept(currentWrapAliasId)`) deletes only aliases the CURRENT persisted wrap
  does not reference, and runs ONLY at quiescent points (cold-start init, and disable/account-delete
  which also clear the wrap) — never concurrently with an in-flight enable. So GC never deletes the
  alias the current wrap references.

## Actors

| Actor | Reads | Writes | Invariant contribution |
|---|---|---|---|
| `newEncryptCipher(aliasId)` (enable, per-enable unique alias) | — | creates key at `PREFIX+aliasId` ONLY | Never deletes another alias → an interrupted/concurrent enable cannot destroy an existing binding (fixes the round-4 MEDIUM). |
| `sealVaultKey` + `store.save({slot, aliasId, blob})` (enable commit) | vault key | prefs wrap (slot, aliasId, blob) | Saves AFTER the alias exists; wrap references its own alias → INV-1. Belt `biometricEnableAllowed(boundSlot, sessionSlot)` still refuses a different-slot save if a wrap appeared mid-flight (never-repoint-established, Unit 1). |
| `cipherForDecrypt(aliasId, nonce)` + `unlockWithBiometric` (reader) | key at `PREFIX+wrap.aliasId`, wrap.blob | — | Uses the wrap's OWN alias → the present key is always the sealing key → opens (INV-1). Absent → null → UNAVAILABLE (self-heal). Invalidated → INVALIDATED (self-heal). |
| `store.load()` (reader) | prefs slot, aliasId, blob | — | Missing/blank aliasId (old format) OR off-range slot OR off-shape blob → null (not enabled) → graceful re-enroll; no crash. |
| `disableBiometric()` | current wrap aliasId | `store.clear()` + `deleteAllAliasesExcept(null)` (delete all) | Removes wrap AND every alias; idempotent; leaves no orphan. |
| `destroyVaultForAccountDeletion()` | — | (best-effort) clear wrap + delete all aliases | Same as disable; tolerated (image destroy is the load-bearing step). |
| GC `deleteAllAliasesExcept(keepId)` (cold-start init; keepId = current wrap aliasId) | keyStore aliases, current wrap aliasId | deletes `PREFIX+*` except `PREFIX+keepId` | Bounds stale-alias accumulation; runs only quiescent → never deletes the referenced alias, never races an enable. Best-effort (leftover aliases are harmless — unlock uses the wrap's alias). |

## Concurrency / crash cases (all preserve INV-1 → no orphan)
- **Concurrent first-enable, different slots:** both pass `isEnabled()==false`; each creates its own
  alias; the SECOND to save hits the belt (`boundSlot` = first's slot ≠ its slot) → refused, no save;
  its alias is orphaned-unused → GC. One wrap persists, references an existing alias. ✓
- **Concurrent first-enable, same slot:** both belts allow; last save wins (wrap → aliasY, which
  exists); aliasX orphaned-unused → GC. Wrap references an existing alias. ✓
- **Interrupted enable (rotation/process-death mid-prompt):** alias created, no wrap saved (or the old
  wrap untouched); the existing binding is INTACT (newEncryptCipher deleted nothing); the stray alias
  → GC. ✓ (This is the round-4 MEDIUM, now structurally impossible.)
- **disable ∥ enable:** disable clears wrap + deletes all aliases; enable (if it commits after) saves a
  wrap → its own fresh alias (re-enabled), or (if disable wins) no wrap. Either consistent; the wrap,
  if present, references an existing alias. ✓

## OQ-3 resolution (report to maintainer)
Under B, a present alias key ALWAYS opens its own wrap (each wrap has a unique alias no other enable
touches). So the "key present but wrap cryptographically dead" state — the one that produced a
non-self-healing `FAILED` — **cannot form**. The only unlock failures are: alias ABSENT
(`cipherForDecrypt` null → UNAVAILABLE → auto-clear + re-offer) or INVALIDATED (new enrollment →
auto-clear). Both already self-heal. Therefore the risky "route a present-key AEAD-open failure to
UNAVAILABLE→clear" recovery is **UNNECESSARY and OMITTED** — an AEAD-open failure with a present key
would now indicate memory corruption / a bug, not a recoverable orphan, and clearing on it would risk
nuking good biometric state on a transient (the regression the maintainer warned against). `FAILED`
stays "drop to passphrase, do not clear." This is the clean separation OQ-3 required: B removes the
ambiguous state rather than adding a dangerous clear-path. **OQ-3 → OMITTED (moot under B).**

## Docs impact (ships in THIS PR)
Unit-2's biometric bullet says the concurrent-enable orphan is user-recoverable via passphrase +
manual disable/re-enable. Under B that orphan no longer forms. Update VAULT_ARCHITECTURE §3.2 + the
SECURITY_MODEL biometric bullet: enable is now atomic (per-enable alias, never destroys an existing
binding); the only biometric-unlock failures are absent/invalidated key, both of which auto-clear and
re-offer; no manual recovery path is needed. Keep first-enable-wins / never-repoint / slot-agnostic.
