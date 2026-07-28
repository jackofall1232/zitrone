You are a THIRD, INDEPENDENT lens settling **two severity disputes** between two blind reviewers. You are not told which reviewer held which position. Judge on the merits; do not hedge to the middle.

## System

An encrypted messenger with **cover traffic**: every real outbound message is paired with a synthetic frame of identical serialized length, sent shortly after. A passive network observer sees only TLS frame sizes and timings.

Two requirements govern, both from an approved spec:

> **R-U3-1 (declared ABSOLUTE, with an explicit supremacy clause):** A real send is never blocked, failed, **materially delayed**, reordered, or made **less durable** because cover traffic was attempted. If satisfying any other requirement would violate this one, this one wins.

> **R-U3-3:** Failure must be **uniform, never intermittent.** Intermittent cover is worse than none — one unpaired frame among a hundred paired ones is *marked*. A condition preventing cover must produce a consistent state, not a stutter.

Severity scale: **P1** = data loss, deniability break, or categorical violation of a stated absolute requirement. **P2** = real defect, bounded blast radius. **P3** = correctness nit, doc or test gap.

Relevant precedent from an earlier ruling on this same feature: *frequency and trigger-window width are inputs to remediation **priority**, not to severity **class**; and against a requirement declared absolute, blast-radius arguments are a category error.*

---

## DISPUTE 1 — the residual process-death window

**Background.** The design originally sent the two frames in a random order with a 5–50 ms gap. That was ruled out and replaced with **real-frame-first, always**, specifically to make a class of defects *structurally impossible* rather than *guarded*. The implementer justified the new structure by asserting: *"a process can only die at a suspension point, and there is now exactly one, strictly after the socket handoff."*

**The facts, verified.** That assertion is **false**. A coroutine may only *suspend* at a suspension point, but the operating system can kill the process at **any instruction**. The project's threat model explicitly assumes crash at any instruction.

So after the durability barrier commits (the cryptographic ratchet advances, durably), execution must still: invoke a suspend interface method, enter its coroutine state machine, and reach the real publish call. Process death anywhere in that path leaves the ratchet advanced but the real message never handed to the socket — i.e. lost.

**Crucially: a window of this kind existed before cover traffic too.** Pre-feature, the sequence was `flush → check recipient exists → construct JSON → hand to socket`, which is also many instructions during which the process could die. Cover traffic adds an interface dispatch and a coroutine state-machine entry ahead of that existing sequence.

- **Position A: P1.** The real send is made less durable by cover traffic, which is a categorical violation of an absolute requirement, and the outcome is silent message loss. The window shrank from 5–50 ms to a few instructions, but "much smaller" is not "impossible," and the structural-impossibility claim the design decision rested on is simply untrue.
- **Position B: P3.** The *wording* is false and should be corrected, but the property effectively holds. A pre-existing multi-instruction window was widened by a handful of instructions of dispatch overhead. If that counts as "materially less durable," then adding any code before a send — a null check, a log line — violates an absolute requirement, which cannot be the intended reading of "materially."

---

## DISPUTE 2 — teardown disconnects the socket before stopping cover traffic

**The facts, verified.** On vault lock or session teardown the code runs, in this order: `ws.disconnect()` (which nulls the socket, after which sends silently return false), then several other teardown steps, then `coverTraffic.stop()`. Separately, `coverTraffic.stop()` cancels only a provisioning job — it does **not** cancel or drain pairings already in flight.

**Sequence:** a user sends; the real frame is published; the pairing coroutine sleeps for its 5–50 ms gap; the vault auto-locks; the socket is disconnected; the scope is then cancelled, so the sleep throws; the surviving `finally` block fires and tries to emit the cover frame — which silently fails against a null socket.

**Result on the wire:** the real frame alone, then the TLS connection closes. No cover frame.

That `finally` block exists *specifically* to guarantee the pair completes on cancellation, and cancellation was justified as frequent — vault lock, teardown, and app backgrounding. So the sends adjacent to those events form a **recognisable class of unpaired real frames**, which is what R-U3-3 identifies as worse than having no cover at all.

The real message is delivered normally; nothing is lost. Cover traffic is absent for those sends.

- **Position A: P1.** This is a deniability break, not a robustness gap. It systematically marks a class of sends that correlate with a user action (locking the app, backgrounding it), which is exactly the observable the feature exists to eliminate — and R-U3-3 names intermittent cover as worse than none. It also defeats the stated purpose of the one guard the previous round deliberately kept.
- **Position B: P2.** A real defect with bounded blast radius: no message is lost, no data leaks, the failure is confined to sends within a 5–50 ms window of a teardown, and the fix is a straightforward reordering of the teardown sequence.

---

## Rule on each

1. **P1, P2 or P3?** Verdict first.
2. For Dispute 1 specifically: **what does "materially" do** in an absolute requirement? Position B argues that without a materiality threshold the requirement forbids adding any instruction whatsoever; Position A argues materiality cannot rescue a false structural claim that a design decision was justified on. Resolve that directly.
3. For Dispute 2 specifically: does **marking a recognisable class of sends** — with no data loss — reach P1 in a feature whose entire purpose is preventing exactly that observable?

Be decisive. If a position is wrong, say so.
