THIRD LENS — settle one severity dispute between two blind reviewers. You are not told which held which position. Be decisive.

## System

An encrypted messenger with **cover traffic**: each real outbound message is paired with a synthetic frame of identical length sent shortly after, over the same TLS connection. A passive network observer sees only frame sizes and timings.

Governing requirement, from the approved spec:

> **R-U3-3:** Failure must be **uniform, never intermittent.** Intermittent cover is worse than none — one unpaired frame among a hundred paired ones is *marked*. A condition preventing cover must produce a consistent state, not a stutter.

Severity: **P1** = data loss, deniability break, or categorical violation of a stated absolute requirement. **P2** = real defect, bounded blast radius. **P3** = correctness nit, doc or test gap.

Established precedent on this feature: *frequency and trigger-window width are inputs to remediation priority, not severity class.* A prior ruling already held that unpaired frames correlated with **vault lock / teardown / app backgrounding** are a **P1 deniability break**, because they form a recognisable class correlated with a user action.

## The dispute

**Facts, verified in source.** Teardown was fixed: it now drains in-flight pairings before disconnecting the socket, so lock-correlated frames are paired.

**But a second, separate disconnect path exists and was NOT fixed.** When the user toggles the Tor transport setting, `applyTransportLocked` disconnects the socket directly, without draining. If a pairing is mid-gap (5–50 ms) at that moment:

- the real frame becomes the **final frame on the old TLS connection**;
- the cover frame is either refused (socket already null) or becomes the **first frame on the newly established connection**.

Either way the pair is split across a TLS connection boundary or lost entirely. The client reconnects immediately.

It was left unfixed deliberately. The stated reason: fixing it requires a **non-terminal quiesce** — the existing teardown is terminal by design (session over, nothing resumes), whereas a transport change must pause and then *resume*. That means a new lifecycle state on a security-sensitive surface, which the implementer declined to add unreviewed at the end of a fix round, naming it for review instead.

Also relevant: a source-level tripwire exists to catch undrained `disconnect()` calls, but it reads only one file and **deliberately excludes this path**.

- **Position A: P1.** It is the same defect class already ruled P1 for teardown, merely on a different trigger. A transport toggle is a deliberate user action, so the marked frame is correlated with something an observer can often infer independently. Splitting a pair across a TLS connection boundary is *more* distinctive than dropping a cover frame, not less — the two halves land in different connections. That the fix is architecturally awkward is a remediation-cost argument, not a severity argument, and the tripwire excluding the path makes it invisible to the guard built to catch exactly this.
- **Position B: P3.** Materially narrower than the teardown case: it is not correlated with locking or backgrounding (the frequent, security-relevant events), the connection re-establishes immediately so the user's traffic pattern resumes, and toggling a transport setting is rare and not adversary-triggerable. It should be recorded and fixed properly with a real lifecycle state, but it does not carry the teardown case's severity.

## Rule

1. **P1, P2 or P3?**
2. Does a pair **split across a TLS connection boundary** constitute a stronger or weaker signal than a simply-missing cover frame? That is the crux of Position A's claim that this is worse, not milder.
3. Does the *rarity and user-initiated nature* of a transport toggle reduce the severity class, given the precedent that frequency informs priority rather than class?
4. Does a guard that **deliberately excludes** the path it would otherwise catch affect the rating?

Brief. Verdict first.
