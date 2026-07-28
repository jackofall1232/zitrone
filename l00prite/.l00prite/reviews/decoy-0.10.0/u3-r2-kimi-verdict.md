moon: asking kimi-k3...
## Dispute 1 — **P1**

Position A is correct on severity.

Under the stated threat model, the implementation creates a new crash trace that did not exist without cover traffic:

1. Ratchet is durably advanced.
2. Cover-related dispatch and coroutine state-machine work execute.
3. Before the real frame reaches the socket, the process is killed.
4. The ratchet has advanced, but the message was never published.

The pre-existing window does not excuse this. If the baseline kill window is \(K\), cover traffic changes it to \(K \cup C\), where \(C\) is a nonempty set of additional instructions. Because the threat model permits death at any instruction, that is a real reduction in durability, even if the added wall-clock time is minuscule. The old window may independently be defective; it does not authorize cover traffic to enlarge it.

### What “materially” does

As written, **“materially” modifies “delayed,” not “made less durable.”** It prevents the requirement from treating insignificant scheduling or timing overhead as a prohibited delay. It does **not** create a de minimis exception for reduced durability.

Position B’s reductio also fails:

- Code independently required for the real send is not added “because cover traffic was attempted.”
- Cover-specific work can be ordered after the real socket handoff.
- If the implementation cannot integrate cover traffic without putting new cover-dependent work before the handoff, the absolute requirement demands restructuring—or a formal spec change—not an unwritten materiality exception.

The false structural-impossibility assertion would be a P3 documentation issue if the property nevertheless held. It does not hold. The assertion concealed an actual added loss path, and the requirement’s supremacy clause forecloses trading that away because the window is small. The window’s size affects remediation priority, not severity class.

---

## Dispute 2 — **P1**

Position A is correct.

A singleton frame in this design is not merely missing an optional robustness property. Because ordinary real sends are paired, an unpaired frame adjacent to teardown is identifiable as a real send. It forms a deterministic, recognizable class correlated with vault lock, teardown, or backgrounding followed by connection close.

That is precisely the metadata signal cover traffic exists to conceal. R-U3-3 explicitly makes intermittent cover worse than no cover: after enough normal traffic establishes the pairing pattern, each singleton marks itself. The fact that no payload or message is lost does not reduce the class because P1 expressly includes a deniability break.

Position B’s “bounded blast radius” argument is a priority argument, not a severity argument. The 5–50 ms width limits how often the defect fires; it does not change what the defect reveals when it fires.

Also, merely moving `coverTraffic.stop()` earlier is not necessarily sufficient. The lifecycle must:

1. Stop admitting new real sends or new pairings during teardown.
2. Stop provisioning new cover work.
3. Cancel, complete, or drain pairings already admitted.
4. Only then invalidate or close the socket.

The current `stop()` does not own in-flight pairings, and the `finally` guarantee is defeated because the socket has already been nulled. This is a systematic deniability failure, not a P2 teardown-ordering nit.
