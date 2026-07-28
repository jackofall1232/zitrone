# U3 — ROUND 3 adjudication (Codex + Grok paired-blind, + Kimi on one dispute)

Codex: `FINDINGS (4 P1, 1 P2, 0 P3)` · Grok: `FINDINGS (0 P1, 2 P2, 3 P3)` · **Kimi: P1 on the dispute.**
**Adjudicated union: 4 P1, 2 P2.** Severity went **UP** from round 2 — the fix-introduces-defects
signature, and two of the four are new.

**Sixth consecutive round of disjoint top findings.** Grok missed all three of Codex's P1s.

## W1 — P1. THE ARCHITECT'S INSTRUCTION CAUSED THIS.

I ruled "invert the call so cover follows the handoff." It did. But `publishOutgoing`/`publishReceipt`
return **`Unit`**, and have three outcomes — contact deleted (frame discarded), socket refused (marked
failed), handed off — **which the caller cannot distinguish.** `cover()` runs in all three.

**Two of those three emit a decoy with no real frame behind it.** Verified at source
(`MessagingCoordinator.kt:365-378`).

Same class as every other P1 in this unit, opposite direction: I converted *"cover may precede real"*
into *"cover may exist without real."* A solitary decoy is arguably worse than an unpaired real — it
is a frame the user never generated. **The fix is a success signal from the publish tail; cover runs
only on a genuine handoff.**

## W2 — P1. "Non-suspending" bounds suspension, not time.

The drain's 100 ms wait abandons any build exceeding it. Slow cryptographic generation, scheduler
starvation, or a stalled `recipient()` all exceed it without suspending. The result is a
**deterministically unpaired real frame at teardown** — precisely what the drain exists to prevent.
The existing test asserts the *discard* rather than the security property, so it passes while the
requirement fails.

## W3 — P1 (Codex P1 / Grok P3 → **Kimi ruled P1**). The Tor-toggle disconnect.

`ZitroneApp.applyTransportLocked` disconnects without draining. Declared and deliberately left
unbuilt, on the sound *process* ground that it needs a **non-terminal quiesce** — a new lifecycle
state on a hardened surface — which should not be added unreviewed at the end of a round.

**Kimi ruled P1, and made a distinction neither reviewer did: a SPLIT pair is a STRONGER signal than
a missing cover frame.** A missing frame is one low-grade anomaly plausibly attributable to jitter. A
split pair is *structured*: two identical-length frames milliseconds apart, straddling a TLS teardown
and immediate reconnect. That (a) lets an observer **link frames across connection boundaries**,
defeating the unlinkability padding exists to provide; (b) binds the marked frame to an independently
observable infrastructure event, giving a cross-check; and (c) correlates it with *"the user just
changed their anonymity transport"* — a **more** privacy-significant action than locking the vault.

On the tripwire that deliberately excludes this path: it does not change the class, but it
**eliminates the only mitigating consideration** — a guard that excludes the known-bad path converts
a latent defect into a *known, unmonitored* violation, with no regression alarm if the path widens.
**Minimum bar: extend the tripwire, or record the exclusion as an explicit, reviewed, tracked
exception tied to the quiesce work — not a silent carve-out in the checker.**

Remediation priority may sit behind the more frequent paths; the class does not move.

## W4 — P1. The impossibility argument is unsound, and Codex supplied the construction.

Round 3 claimed step 1 ("stop admitting new real sends") is not jointly satisfiable with "cover never
precedes the real send". **It is.** Serialize terminal teardown on the coordinator's **already
existing confined worker**: stop accepting new sends, enqueue teardown *behind* already-running
sends, and let each running send reach its non-suspending admission before its first suspension. No
cover lock and no cover instruction precedes the real handoff.

This is an answer, not an objection — and it means the declared residual need not be accepted.

## W5 — P2 (both). `ensureProvisioning` CAS-then-assign race.

Wins the CAS, is preempted before assigning `provisionJob`; `stop()` sees null, invalidates, returns;
the lazy job then starts **after teardown**, potentially spending a scarce registration and touching a
closing runtime.

## W6 — P2. The tripwires do not pin what they claim.

- **The call-site tripwire passes despite W1.** It proves statement *adjacency*, not that the real
  send happened. A guard green while the defect it guards is live is the non-discriminating class,
  applied to a guard.
- The **reflection tripwire** catches only a re-added parameter named on `cover` — a differently
  named method, a SAM, or a constructor-held publisher bypasses it.
- The **disconnect tripwire** is format-fragile and reads one file.

## Note for the fix

W1 and W4 are the two that matter, and they compose: a **success signal** from the publish tail
(W1) plus **teardown serialized on the confined worker** (W4) resolves W2's timeout pressure and W3's
ordering at the same time, because a drain that cannot race admission does not need a wall-clock
bound. Prefer the composed fix to four separate repairs.
