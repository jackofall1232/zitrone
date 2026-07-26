You are an INDEPENDENT SECURITY REVIEWER acting as a TIE-BREAKER for Zitrone, a zero-knowledge
plausible-deniability messenger. Two independent reviewers examined the same code and derived the
SAME mechanism from the same source lines, then reached OPPOSITE conclusions about what it means.
You are being asked to adjudicate — independently, from source.

**You are NOT told which reviewer said what, and you should not try to guess.** Derive your own
answer. If you conclude both are partly right, say exactly which part of each.

You have a private read-only checkout at /root/zitrone. Read anything. Verify against source; do not
trust comments, kdoc, or commit messages — this unit's history is a history of confident, internally
coherent, WRONG prose, including a claim that was FALSE THE DAY IT WAS WRITTEN.

## THE FEATURE
A "Pucker Burn" duress passphrase triggers an irreversible local wipe. The guarantee:
**post-burn state is indistinguishable from a fresh install.** A coerced user hands over a device
that looks like it never held a vault.

## THE BLOCKING BOUNDARY you must classify against
Robustness residuals MAY be deferred and tracked. **Anything that breaks post-burn ≡ fresh install is
NOT a hardening layer — it is the feature failing at its purpose, and it BLOCKS.** State explicitly
which side of that line your verdict falls on, and defend the classification.

## THE MECHANISM (agreed by both reviewers; verify it yourself before adjudicating)
The burn is: raise an in-RAM `durabilityHold` → `imageStore.burnObliterate()` (removes vault image,
DEK, temps, markers, with its own dirSync) → then five further gated cleanups (biometric aliases,
device-key alias, boot diagnostics, plaintext cache tree, preference stores) → lower the hold →
`Process.killProcess()` on the SUCCESS path only.

`durabilityHold` is a `MutableStateFlow` — RAM only, deliberately not persisted. A raised hold forces
the next boot to a lock screen instead of onboarding. At next boot the hold is re-derived from disk by
three reconcilers. Read their trigger predicates in
`apps/android/app/src/main/java/com/zitrone/app/crypto/vault/VaultImageStore.kt`
(`completeInterruptedBurn`, `reconcileOrphanedBurnMarkers`, `sweepOrphanedResidue`).

**The scenario in dispute:** `burnObliterate()` SUCCEEDS (image/DEK/markers durably gone), then a
LATER cleanup fails (say the cache or diagnostics wipe throws) leaving that residue on disk. The hold
is raised in RAM. The process then dies for ANY reason (force-stop, OOM kill, crash). At next boot,
every reconciler trigger requires image-bearing state or a marker — all of which are already gone —
so all three report no mutation, the hold publishes FALSE, and routing presents ONBOARDING while the
failed cleanup's residue (plaintext attachment cache, diagnostics log, preference keys) survives.

## QUESTION 1 — SEVERITY
Is this BLOCKING or DEFERRABLE against the boundary above? Consider and rule on each argument:
- **For DEFERRABLE:** process death only ever runs on the SUCCESS path, after every cleanup proved
  itself, so the change that introduced it never leaves residue. The dangerous window requires an
  EXTERNAL process death during an already-failed burn, and that window exists identically without
  the recent change — it is a pre-existing property of a RAM-only hold, not a regression.
- **For BLOCKING:** the outcome is a device presenting as a fresh install while carrying vault-use
  residue, which is the feature failing at its stated purpose. Provenance (pre-existing vs regression)
  does not change what the user gets, and the boundary is about kind, not probability or origin.

## QUESTION 2 — THE FIX, AND A CONSTRAINT THAT MAY FORBID IT
The obvious fix is a DURABLE "burn in progress" marker written BEFORE the first mutation and retired
only after every cleanup has proven itself, with boot completing all outstanding cleanup domains
before it publishes a route.

**But this design forbids exactly that kind of artifact**, and the reason is the feature's whole
point: a durable marker meaning "a burn was started here" is itself a vault-use oracle. Worse, a
marker written BEFORE the first mutation can survive on a device whose vault is still fully intact
(process dies in that window) — a discoverable artifact telling a coercer the duress passphrase was
entered, on a device that otherwise looks normal. Note the project has already once refused a
pre-burn intent marker on these grounds and solved that case with a marker-free disk signature
instead (`completeInterruptedBurn` keys on `{vault.bin present, vault.dek proven absent}`, a shape
`create()` structurally cannot produce because it renames the DEK into place FIRST).

Adjudicate:
(a) Is the oracle objection FATAL to the durable-marker fix, or is it manageable — e.g. because the
    marker only persists in states where residue exists anyway, or because the pre-mutation window
    can be closed by ordering?
(b) **Is there a MARKER-FREE disk signature for "obliterate succeeded but later cleanup did not"** —
    the same trick that solved the earlier case? Consider what each surviving artifact implies. Be
    concrete and name the files.
(c) If no marker-free signature exists, what is the least-oracle-bearing durable signal that works,
    and where exactly must it be written and retired?
(d) Is there a third option neither reviewer proposed — e.g. reordering cleanups so the image is
    destroyed LAST, making the image's own presence the durable doubt signal?

## QUESTION 3 — A SEPARATE CLAIM, VERIFY IT INDEPENDENTLY
The code kills the process as the last act of a successful burn, and in-tree prose calls this a
"deterministic drain" of pending asynchronous writes. One reviewer argues this is an overclaim: an
async writer ALREADY EXECUTING on another thread can land a write after the final absence proof and
before SIGKILL, so process death prevents FUTURE userspace work but does not roll back work already
submitted. Rule on this, and say what process death does and does not actually buy.

## OUTPUT
Per question: your verdict, the source you read to reach it (file:line), and your reasoning. Then a
one-line summary: for Q1, the single word BLOCKING or DEFERRABLE. Be willing to say "both reviewers
are wrong" if that is what source supports. Do not invent additional findings — this is a targeted
adjudication, not a general review.
