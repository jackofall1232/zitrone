# OUTPUT CONTRACT — read first, obey exactly. READ-ONLY: create/edit/delete nothing, run nothing that mutates.

Produce, in this order:

1. **RULING** — answer the design question in §A. Verdict first, then reasoning. **This is the primary
   deliverable.**
2. **FINDINGS** — max 8, severity-ordered: severity, `file:line`, the concrete failure (exact inputs,
   state or interleaving → wrong outcome), and why existing tests miss it. `None` is acceptable.
3. **MISSING CONTEXT** — any file, symbol or call site you needed and could not reach, and the defect
   class you would have checked there.
4. **`VERDICT: CLEAN`** or **`VERDICT: FINDINGS (n P1, n P2, n P3)`** — final line, nothing after.

**P1** = data loss, deniability break, or categorical violation of a requirement declared absolute,
from reachable state. **P2** = real defect, bounded blast radius. **P3** = latent, or doc/test gap.
Frequency informs priority, **not** class.

---

# §A — THE DESIGN QUESTION (primary)

Repo `/root/zitrone`, branch `feat/0.10.0-decoy-u3-pairing` @ `7ae06e8f`.

**The feature.** Cover traffic: every real outbound message is paired with a synthetic frame of
identical serialized length, sent shortly after over the same TLS connection, so a passive network
observer cannot tell which frame carried the real message.

**Two requirements, both written as absolute:**
- **R-U3-1:** a real send is never blocked, failed, materially delayed, reordered or made less
  durable by cover traffic. ("materially" modifies *delayed*, not *made less durable*.)
- **R-U3-3:** failure must be **uniform, never intermittent** — an unpaired real frame, a lone decoy,
  or a pair split across a TLS boundary is a **marked** frame. Its stated rationale: *intermittent
  cover is worse than no cover, because one unpaired frame among a hundred is marked.*

**Four mechanisms exist in the shipped design. Both prior reviewers agree these are real and describe
them identically:**

1. A decoy consumes capacity in OkHttp's bounded outbound queue; on a stalled writer the next **real**
   send can return false where it would otherwise have succeeded.
2. If cover construction blocks the confined worker beyond a 250 ms bound during terminal teardown,
   the teardown runs on the calling thread, invalidates the transport, and a mid-build pairing's later
   admission fails — leaving an **unpaired real frame**.
3. Cover doubles consumption of the relay's per-account send budget, so a real frame can be rejected
   at half the nominal limit.
4. If the TLS socket dies naturally during the 5–50 ms gap, the cover frame fails to send — leaving an
   unpaired real frame.

**The key property of all four:** each is "something can go wrong **between** frame one and frame
two." None is an ordering defect. Each is a property of the transport or of a shared resource, not of
the pairing code — **no implementation can make a network incapable of failing between two writes, or
make a shared budget unshared.**

The implementation **declares** these as residuals, and in one case ships a test that asserts the
otherwise-forbidden outcome as accepted.

**The two positions, which you must rule between:**

- **Position A — these are P1 violations.** A requirement declared absolute, with an explicit
  supremacy clause, is violated by a reachable failure regardless of how well documented it is. You
  cannot declare your way out of an absolute requirement. If the requirement cannot be met, the
  requirement or the feature must change — not the bar.
- **Position B — these are declared residuals and inherent costs, not defects.** They re-file known
  trades rather than new reachability. Every residual is an **unpaired real frame — never a lone
  decoy, never a split pair** — on paths requiring a blocked worker or a stalled writer. The
  alternative in case 2 is a hung lock that skips a cryptographic key wipe, which is strictly worse.

**Rule on all of the following. Be decisive; if a position is wrong, say so.**

1. **Can a declared, tested residual satisfy a requirement declared ABSOLUTE?** If not, what is the
   correct disposition — relax the requirement explicitly, or do not ship the feature?
2. R-U3-3's rationale is *intermittent cover is worse than none*. **If unpaired frames are unavoidable
   at some nonzero rate, does that reasoning turn against the feature itself?** Cover marks exactly
   the sends that hit a transport failure; no cover marks nothing.
3. **What residual rate would make cover traffic net-negative?** Nobody has measured the actual rate.
   Is shipping without that number defensible?
4. Is there a **material difference** between the four mechanisms — e.g. is an unpaired real frame
   meaningfully less dangerous than a split pair, and does an adversary-*uninducible* residual differ
   in kind from an inducible one?
5. If the requirement must be relaxed, **write the replacement wording.**

---

# §B — SECONDARY: anything the prior six rounds missed

Six adversarial rounds have run. Prior rounds established (do not re-derive): the transport swap
cannot run off the confined worker; terminal teardown and a send's publish→admit slice cannot
interleave **except** via the 250 ms fallback; the seam cannot be handed a real send; nothing
decoy-related reaches device storage, logs or diagnostics.

**Unit:** `decoy/DecoySendPairing.kt`, `decoy/CoverTrafficWorker.kt`, `decoy/DecoyEnvelopeBuilder.kt`,
`decoy/DecoyAccountProvisioner.kt`, `crypto/vault/DecoySectionLock.kt`.
**In scope, and where the real defects have repeatedly been:** `MessagingCoordinator.kt`,
`ZitroneApp.kt`, `MainActivity.kt`, `UnlockController.kt`, `net/WsClient.kt`,
`crypto/vault/VaultState.kt`.

Defect classes that have actually occurred here: cover work preceding or outliving the real send; a
guard whose scope is narrower than the resource it protects; a test or tripwire that passes while the
property it names is broken; a prose claim the code no longer satisfies.

**Reminder: RULING first, `VERDICT:` last.**
