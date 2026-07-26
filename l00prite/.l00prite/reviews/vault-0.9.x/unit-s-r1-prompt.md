You are an INDEPENDENT SECURITY REVIEWER for Zitrone, a zero-knowledge plausible-deniability
messenger. This is ROUND 1 of a blind paired review of **Unit S — Pucker Burn ARMING (0.9.3)**.
Another reviewer runs independently on the same range; you are blind to them. Report only what YOU
derive from source.

YOU HAVE A PRIVATE, WRITABLE CHECKOUT at /root/zitrone (branch `feat/0.9.3-unit-s-burn-arming`).
Read anything; you MAY build and run tests.

SCOPE:
  git diff main...HEAD
  git log --oneline main..HEAD

## What this unit is, and why it is dangerous

0.9.2 shipped the duress WIPE but deliberately made it UNREACHABLE — slot 0 held uniformly-random
filler no passphrase derives to, so nothing could trigger a burn. **This unit makes it reachable.**
It adds the first writer that ever puts a meaningful value in slot 0, a permanent Settings entry, an
acknowledged permanence warning, and gate coverage of the whole arm → enter → burn path.

The failure modes are severe in both directions: a user who arms a credential that silently does
nothing has false protection, and a user whose credential collides with a vault password destroys
their own data on an ordinary unlock.

## THE ONE THING TO ATTACK HARDEST

`tryPassphrase` records the FIRST match by ASCENDING SLOT INDEX and slot 0 is index 0, so **an armed
slot 0 outranks every vault slot.** If a burn credential also opens an occupied vault slot, entering
it WIPES instead of UNLOCKING. `armBurnSlot` refuses such a candidate (`ArmBurn.CollidesWithVault`).

**Verify that refusal is complete and correct.** Can any candidate slip past it? What about a
passphrase that opens a slot the sweep did not consider, a race between the sweep and the seal, or a
vault created AFTER arming that happens to collide? Is refusing only against `VAULT_SLOT_RANGE`
right, and is re-arming over slot 0 genuinely safe? This is the correctness story of the unit and it
is the author's own reasoning — treat it as guilty.

## BINDING FOCUS ITEMS — explicit verdict on each

A. **NO ARMED FLAG ANYWHERE (invariant P1).** Armed and unarmed installs must be byte- and
   behaviour-indistinguishable. There is deliberately no "is it set?" readback. Verify NOTHING leaks
   it: not the Settings row (permanent, state-free subtitle), not a preference, not a file, not a
   size or timing difference, not a log line. A row that changed once armed would be the oracle this
   feature exists to avoid.
B. **ARMING IS IN-PLACE AND FORMAT-STABLE.** No IMAGE_VERSION change, no DEK write, slot 0's payload
   untouched and identically sized. Confirm against source.
C. **CRASH ATOMICITY.** The claim is that writes go through `atomicWrite` over the whole image, so a
   crash mid-arm leaves either filler or armed and never a half-armed slot 0 — which is why arming
   needs no marker (a marker would itself be an oracle). Verify.
D. **FAIL-CLOSED REPORTING.** `NotDurable` and `DeletePending` must never reach the user as success.
   Telling someone their duress credential is set when the write may not survive a crash is the worst
   lie this feature can tell. Check every path from `armBurnSlot` to the dialog.
E. **THE WARNING.** Four required points (unrecoverable/uncheckable; anyone who learns it can erase
   the vault; a burn CONSUMES it; re-running silently replaces). Actively acknowledged — confirm
   disabled until ticked. Is the copy accurate, and does anything in the flow contradict it?
F. **KEY MATERIAL.** Is the credential key wiped on every path including throws? Does the passphrase
   linger anywhere (Compose state, logs, the container hop)?
G. **CONCURRENCY.** Arming takes `imageLock` and refuses while a delete is pending. Can arming race a
   burn, an unlock, a second-vault create, or account deletion? Is the marker check TOCTOU-free?
H. **THE GATE.** Two new instrumented tests claim to prove the full user path under real Keystore and
   real Argon2id. Do they discriminate — would they fail if arming silently did nothing? Note the
   gate passes `terminate = {}`, so it exercises a weaker arrangement than production's process death.
I. **RUN THE UNIT SUITE** and report YOUR numbers (claim: 562 total / 559 passed / 0 failures / 3
   skipped). Use `JAVA_TOOL_OPTIONS=-Djna.tmpdir=<writable>` if JNA native extraction fails. Report NO
   numbers rather than adopting the claim if you cannot run it.
J. **ANY OTHER DEFECT**, including whether any comment or commit message overstates what the code does.

## BLOCKING BOUNDARY
Robustness residuals MAY be deferred and tracked. **Anything that makes the burn unreachable when the
user believes it is armed, or that destroys data the user did not intend to destroy, BLOCKS.** State
which side of that line each finding falls on.

## Output
Per finding: SEVERITY, file:line, the defect, why it matters, concrete fix, BLOCKING-or-DEFERRABLE.
Cite source you actually read. Explicit verdicts on A–J. State clearly whether this is READY TO
MERGE. An honest clean pass is a real and expected outcome — do not invent findings to appear
thorough.
