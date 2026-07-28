# OUTPUT CONTRACT — read this first, obey it exactly

**READ-ONLY. Do not create, edit or delete any file. Do not run any mutating command.**

Produce, in this order:
1. **FINDINGS** — max 10, severity-ordered. Each: severity, `file:line`, the **concrete failure**
   (exact inputs, state, or interleaving → wrong outcome; not "this could be racy"), and **why the
   existing tests miss it**.
2. **CONFIRM-OR-REFUTE** — for each prior hypothesis listed below, either confirm with a concrete
   trace or explicitly refute. One line each.
3. **HYPOTHESES NOT IN THE PRIOR LIST** — *mandatory section, must not be empty of effort*. What did
   you look for that nobody told you to look for?
4. **MISSING CONTEXT** — any file, symbol or call site you needed and could not reach, and the defect
   class you would have checked there.
5. **`VERDICT: CLEAN`** or **`VERDICT: FINDINGS (n P1, n P2, n P3)`** — final line, nothing after it.

Severity by consequence and reachability: **P1** = data loss, deniability break, or categorical
violation of a requirement declared absolute, from reachable state. **P2** = real defect, bounded
blast radius. **P3** = latent, or a doc/test gap. Frequency informs priority, **not** class.

**Assume a defect exists and your job is to construct the trigger.** A review that finds nothing is a
failed review unless you can argue the code is sound. `VERDICT: CLEAN` is legitimate — but earn it.

---

# SCOPE

Repo `/root/zitrone`, branch `feat/0.10.0-decoy-u3-pairing` @ `7ae06e8f`. **This is the final review
round of a 6-round cap; the unit goes to a human merge decision on your answer.**

**Unit under review:** cover traffic — `decoy/DecoySendPairing.kt`, `decoy/CoverTrafficWorker.kt`,
`decoy/DecoyEnvelopeBuilder.kt`, `decoy/DecoyAccountProvisioner.kt`, `crypto/vault/DecoySectionLock.kt`.

**Context files that have repeatedly contained the actual defects — treat as in scope, not
background:** `MessagingCoordinator.kt` (call sites, teardown), `ZitroneApp.kt` (`applyTransport`,
`stopSession`, transport lock), `MainActivity.kt` (`lockIf`), `UnlockController.kt`,
`net/WsClient.kt`, `crypto/vault/VaultState.kt`.

**Requirements:**
- **R-U3-1, ABSOLUTE:** a real send is never blocked, failed, materially delayed, reordered or made
  less durable by cover traffic. Ruled: *"materially" modifies "delayed", not "made less durable"* —
  no de minimis exception for durability.
- **R-U3-3:** failure must be **uniform, never intermittent**. An unpaired real frame, a lone decoy,
  or a pair **split across a TLS connection boundary** is a *marked* frame. A split pair is a
  **stronger** signal than a missing one (it links frames across connections).

Defect classes that have actually occurred here — check these specifically, ignore generic checklists:
cover work preceding or outliving the real send; a guard whose scope is narrower than the resource it
protects; a test or tripwire that passes while the property it names is broken; a claim in prose that
the code no longer satisfies.

---

# PRIOR HYPOTHESES — confirm each with a trace, or refute

1. The transport swap can no longer run off the confined worker on **any** path.
2. Terminal teardown and a send's publish→admit slice cannot interleave.
3. A cover frame can never be emitted on a different TLS connection than the real frame it covers.
4. `stop()`'s bounded wait cannot lose a pairing that was admitted.
5. No lock is held across a blocking wait on the worker (the previously-verified deadlock cycle:
   `applyTransport` → `transportLock` → blocking reconnect → worker → `deleteAccountAndWipe` →
   `onConfirmed` → `lockIf` → `stopSession` → `transportLock`).
6. Provisioning cannot start after teardown, and cannot spend more than one registration per vault.
7. The seam cannot be handed a real send (no parameter can hold one).
8. Nothing decoy-related reaches device-level storage, logs or diagnostics; nothing survives lock.

**Declared residuals — judge whether each is acceptable, do not merely note them:** the transport swap
is now unbounded in latency behind a blocked worker (no fallback); a natural socket death mid-gap can
still split a pair; `stop()` bounds its wait because the vault runtime closes the instant it returns.

---

# WHAT CHANGED (facts only — do not treat this as a map of where to look)

A new `CoverTrafficWorker` owns three entry points: on-worker terminal, dispatched-and-bounded
terminal, dispatched-only non-terminal. `applyTransportLocked` installs endpoints and **returns** the
session to redial; the caller releases `transportLock` before requesting the reconnect. The swap has
**no fallback and no wait**, is skipped once terminal teardown begins, and coalesces by generation.
Seven behavioural tests now drive the production dispatch class; one runs a real pairing over a socket
whose identity changes on swap.

**Reminder: `VERDICT:` must be your final line, and findings are capped at 10.**
