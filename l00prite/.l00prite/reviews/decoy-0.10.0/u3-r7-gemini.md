Warning: True color (24-bit) support not detected. Using a terminal with true color enabled will result in a better visual experience.
Ripgrep is not available. Falling back to GrepTool.
1. **RULING**
Position A is correct; Position B is dangerously wrong. A declared residual cannot satisfy a requirement declared absolute. You cannot declare your way out of a supremacy clause—if transport physics makes the requirement impossible, the requirement MUST be explicitly relaxed. Normalizing a broken constraint invalidates the constraint entirely.

For R-U3-3, the rationale absolutely turns against the feature: if an unpaired frame is a "marked" frame, and natural network drops guarantee a nonzero rate of unpaired frames, then the feature actively exposes the user exactly when the network stutters, making them more vulnerable than if cover traffic was disabled.

A residual rate becomes net-negative the moment it is **adversary-inducible**. If an attacker can force a TCP disconnect or stuff the OkHttp queue, they can strip the cover traffic at will, turning the feature into an active unmasking oracle. Shipping without quantifying the natural network drop rate is indefensible for a security feature.

There is a material difference between the mechanisms: Mechanisms 1 (queues), 2 (timeouts), and 3 (budgets) are deterministic and adversary-inducible (e.g., via flooding or CPU starvation), meaning the adversary can actively unmask traffic. Mechanism 4 is environmental but can still be induced via TCP RST.

**Replacement wording:**
"Pairing MUST be best-effort atomic over the transport. If a pair cannot be successfully sent in its entirety, the transport connection MUST be terminated immediately to prevent further unpaired frames, and shared resource limits (queues, budgets) MUST account for pairs as single indivisible units."

2. **FINDINGS**
- **P1** — `MessagingCoordinator.kt: deliverText` — **Interleaving of sends breaks pairing.** `deliverText` calls `coverTraffic.cover(envelope)` which suspends via `delay(gapMs())` on the single-threaded `confined` dispatcher. Because `delay` yields the thread, a second queued `deliverText` coroutine will execute its `publishOutgoing` BEFORE the first coroutine's cover frame is emitted. This interleaves the frames on the socket (Order: Real 1, Real 2, Cover 1, Cover 2), completely breaking the pairing signature and marking both real frames. Existing tests miss this because they do not queue back-to-back sends in virtual time or they mock the cover traffic without simulating the coroutine yield.
- **P2** — `ZitroneApp.kt: runTerminalBurnLocked` — **Burn wipe races with busy confined worker.** `runTerminalBurnLocked` calls `unlockController.lock()`. If the `confined` worker is busy (e.g., encrypting a large payload), the teardown falls back to the calling thread and `lock()` returns after a 250ms timeout. The burn wipe then proceeds to obliterate the vault and wipe keys WHILE the `confined` worker is still executing the interrupted session's crypto operations, causing unpredictable crashes or corrupt state reads. Tests miss this because they do not simulate a CPU-bound `confined` worker during a terminal wipe.

3. **MISSING CONTEXT**
- `CoverTrafficWorker.kt` (implementation details of the fallback logic) — to verify if `terminalWaitMs` can be completely bypassed by other scope cancellations.
- `net/WsClient.kt` — to check exactly how OkHttp queues messages internally during a concurrent disconnect and if `intentionallyClosed` properly prevents late sends.
- `crypto/vault/VaultRuntime.kt` — to check if `close()` blocks on any active `mutate` calls, which would upgrade the P2 finding.

4. **`VERDICT: FINDINGS (1 P1, 1 P2, 0 P3)`**
