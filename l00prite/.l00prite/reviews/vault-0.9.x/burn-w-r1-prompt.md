You are an INDEPENDENT SECURITY REVIEWER for Zitrone, a zero-knowledge plausible-deniability messenger.
Review ONE commit on branch `feat/0.9.2-burn-unit-w-wipe` in /root/zitrone: `645b8a8` (Pucker Burn Unit W).
Diff it with: git -C /root/zitrone show 645b8a8

VERIFY EVERY CLAIM AGAINST SOURCE. Do not trust the commit message, the comments, or this prompt —
read the actual code. Report only defects you can point at in source.

## What this change is
Pucker Burn is a DURESS credential: entering it at the lock screen wipes the device's vault. Slot 0 of the
multi-slot vault image is reserved for it. This commit ships the WIPE MECHANISM ONLY — slot 0 is left as
uniformly-random filler (unarmed), so the burn is meant to be structurally unreachable in production.

It factors the physical destruction out of `VaultImageStore.destroy()` into a marker-free
`obliterateLocked()`, shared by `destroy()` (which prefixes a `vault.delete-confirmed` crash-bridge) and
`obliterateForBurn()` (which must NOT write that marker).

## Critical background: the D2c account-delete state machine (hardened over 16 review rounds)
- Two durable markers: `vault.delete-intent` (a delete was initiated; server outcome unknown) and
  `vault.delete-confirmed` (server account provably gone — the ONLY authorization for the
  `Route.DeleteIncomplete` auto-destroy).
- `hasDeleteIntentMarker()` is an AUTH-PROTECTION guard: while true, no path may clear vault-backed tokens.
- `create()` clears stale markers (F2) before writing a successor vault.
- Markers use tristate `Files.notExists` re-stat + required dirSync (fail-closed) — `File.delete()`'s bool
  and `File.exists()==false` are both untrustworthy (I/O failure conflates with absence).

## THE BINDING REVIEW ITEMS (address each explicitly)
1. **destroy() EQUIVALENCE.** The refactor changes destroy()'s unlink order: previously bin-then-dek, now
   dek-then-bin (keys-first). The author's argument: keys-first is strictly safer for burn (a crash between
   unlinks leaves ciphertext without its key = cryptographic erasure), and for destroy() the
   confirmed-marker-first crash bridge makes re-destroy idempotent at any crash point regardless of order,
   so the change costs nothing there. EVALUATE THAT ARGUMENT — do not accept it because it is stated.
   Is destroy()'s externally observable behavior genuinely unchanged? Any crash/interleaving/journal-replay
   case where the new order is worse? If you judge the shared ordering unacceptable for destroy(), say so —
   a `keysFirst` boolean param (destroy passes false, burn true) is the intended fallback.
2. **OBLITERATE ORDERING.** The marker clear must be STRICTLY AFTER the DEK+image unlinks are proven
   durable. Verify NO path can clear markers while the image still exists (that is the "B1" failure state:
   markers saying nothing-pending over live state). Check every early-return/throw/exception path.
3. **BOOT RECONCILIATION.** `reconcileOrphanedBurnMarkers()` handles a crash between the unlinks and the
   marker clear. Verify: (a) the crash window is actually covered; (b) an image-absent state can never route
   into `Route.DeleteIncomplete` under ANY crash point; (c) it cannot clear a marker that D2c still needs
   (a genuine pending reconcile over a live vault, or the confirmed marker mid-self-heal).
4. **WRITER/READER invariants.** For every durable signal the burn touches, is the complete writer set and
   reader set still consistent? Any new writer to state D2c depends on? Any reader that can now observe a
   state it could not before?
5. **REACHABILITY.** Verify in the SHIPPED DIFF (not the intent) that slot 0 remains unarmed and the burn
   is genuinely unreachable in production. Is there ANY path that can arm slot 0 or trigger the wipe?
   Note: `attemptUnlockOrAdd` (which returns the Burn outcome) is ALSO the second-vault add-slot/collision
   path — a wiring that treats Burn as "wipe" anywhere other than the lock-screen unlock dispatch would
   make an unlucky vault creation a self-inflicted total wipe. Verify this cannot happen.
6. **CONCURRENCY / LIFECYCLE.** The wipe runs on a process-scoped coroutine with a terminal-exclusion gate
   (`beginTerminalWipe`/`endTerminalWipe`). Verify the gate can never be stranded (it blocks ALL future
   unlocks AND session publication, including the post-burn onboarding create), that rotation/cancellation
   mid-burn is safe, and that nothing can resurrect state after destruction.
7. **FAIL-CLOSED.** A burn that did not fully take must never present as success. Verify the disk-truth
   check and the failure UX. Also: can a partially-completed burn leave a worse state than not burning?

## Also worth your attention
- `wipeAppLocalStateForBurn()` clears settings/legacy prefs/diagnostics/notifications/cache. Are the
  tolerated-vs-not-tolerated boundaries right? Can a cleanup failure mask or pre-empt the image destroy?
- The plaintext attachment cache (`cameracapture`/`dropshare`) — is the clearing correct and complete?
- Any NEW timing/behavioral oracle introduced (this app's threat model cares deeply about distinguishers).
- Test quality: do the tests actually prove what they claim, or do they pass vacuously?

## Output
For each finding: SEVERITY (CRITICAL/HIGH/MEDIUM/LOW/INFO), file:line, what is wrong, why it matters,
concrete fix. Cite source lines you actually read. Then address review items 1-7 each with an explicit
verdict. If something is correct, say so briefly — do not invent findings. Be adversarial but precise.
