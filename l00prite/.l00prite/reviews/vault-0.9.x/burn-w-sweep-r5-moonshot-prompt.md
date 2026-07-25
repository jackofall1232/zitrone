You are an INDEPENDENT SECURITY REVIEWER for Zitrone, a zero-knowledge plausible-deniability messenger.
This is a FULL REPASS with COMPLETE SOURCE. Earlier you reviewed a trimmed excerpt and had to caveat
several verdicts as "cannot verify from the inline" — those caveats should now be closed. Every file
you need is inlined below in full. Verify EVERY claim against the source given; do not inherit any
earlier round's conclusion, and do not trust comments or commit messages — this unit's comments have
been wrong repeatedly (an invariant table internally coherent but wrong about ownership; a kdoc
asserting "Splash blocks on bootReconciled" when it did not; a kdoc claiming create() "refuses to run
while either marker is present" when it CLEARS them; a test kdoc naming a mutation it cannot catch).

CONTEXT. Pucker Burn is a DURESS credential: entered at the lock screen it wipes the vault. Slot 0 is
UNARMED (uniformly-random filler), so the wipe is unreachable in production — this unit ships the
MECHANISM only. Central invariant: post-burn ≡ fresh install, AND a burn that did NOT fully take must
never present that way. The residue sweep is a DESTRUCTIVE BOOT OPERATION: it unlinks files at cold
start before any authentication.

FIVE STANDING INSTRUCTIONS
1. PROVE ANY TABLE COMPLETE, NOT SELF-CONSISTENT. Hunt the MISSING ROW.
2. A GATE CAN BE WRONG BY BEING TOO NARROW. Prove BOTH directions: what it wrongly admits AND what it
   wrongly STRANDS.
3. HUNT THIS PATTERN — it has produced a HIGH SIX times in this unit, each inside the fix for the
   previous one: *an authoritative result exists, and a consumer uses something weaker.* Four forms so
   far: (a) data-flow — verdict discarded, recomputed from a cheaper signal; (b) lifecycle — verdict
   carried but a consumer runs BEFORE publication and reads a default; (c) second authority — a
   separate code path decides the same thing; (d) incomplete input set — the SAME decision function
   called with fewer arguments than another caller passes. For every safety verdict ask: who consumes
   this, do they use THIS EXACT VALUE, are they ORDERED AFTER publication, is there ANOTHER writer,
   and does EVERY caller pass the FULL input set?
4. A CLAIM AND THE WORK IT CLAIMS MUST HAVE THE SAME LIFETIME.
5. A TEST THAT A VALUE IS COMPUTED IS NOT A TEST THAT IT IS USED. Judge coverage at the CONSUMPTION
   site. You already found two false claims in test headers this round — look for more.

FOCUS
A. Enumerate EVERY site assigning `route` or `vaultExists` in MainActivity.kt and, for each, state
   whether it is ordered after `bootReconciled`, whether it uses the carried `residueSweepHold`, and
   whether it passes the FULL bootRoute input set including `legacyImage`. The last four rounds each
   found one more such site than the previous round believed existed. Find the next one or state
   positively that none remains — you now have the whole file, so this is decidable.
B. `bootRoute` precedence with the legacy arm; `runBootReconcile`'s contract (once-only, publication
   in `finally` on every exit, fail-closed default, cancellation cannot strand, dispatcher injection).
C. `sweepOrphanedResidue`'s gate: `image PROVEN absent AND no delete-confirmed`. The delete-intent
   clause was removed and that removal is RATIFIED. Prove it safe in EVERY state; prove the
   WRITER/READER table COMPLETE. You can now read destroy(), create() and obliterateLocked in full —
   verify the PREMISES you previously had to take on trust.
D. The cumulative unit: destroy() equivalence under keys-first unlinks; marker clear strictly after
   unlinks proven durable; all boot healers as ONE system (overlap, contradiction, or a state no one
   owns); WRITER/READER invariants for durable signals AND in-flight verdicts; reachability (slot 0
   unarmed, wipe wired only to lock-screen dispatch); concurrency/lifecycle; fail-closed (can a
   partial burn present as success, or leave state worse than not burning?).
E. Test quality: does any test pass vacuously, and does any test header claim a mutation it does not
   catch? Name the failure shape that is STILL untested.

NOTE (do not count as a new defect): the `File.exists()` verify INSIDE `obliterateLocked` is
pre-existing, inherited from destroy(), deliberately out of scope. Say if you disagree.

OUTPUT: for each finding give SEVERITY (CRITICAL/HIGH/MEDIUM/LOW/INFO), file:line, the defect, why it
matters, and a concrete fix. Cite source you actually read. Give explicit verdicts on A-E. State
clearly whether this is READY TO MERGE. An honest clean pass is the expected outcome if the code
holds — do NOT invent findings to appear thorough.

================ COMPLETE SOURCE FOLLOWS ================
