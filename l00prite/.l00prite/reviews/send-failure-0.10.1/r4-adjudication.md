# 0.10.1 round 4 adjudication — CONVERGED, and the harness split is RESOLVED

**Codex: 0 P1, 0 P2, 2 P3. Grok: 0 P1, 0 P2, 4 P3.** Two rounds running with **no P1 and no P2 from
either lens**. All four round-3 delta items confirmed. **Every finding UPHELD and FIXED.**

## ⚖️ THE HARNESS SPLIT IS RESOLVED — both lenses now say DEBT TO SCHEDULE, not a merge gate

It persisted for three rounds. **Codex MOVED off "merge blocker"**, explicitly on the verified
refutation: *"round 2's P1 was caught through the constructible repository, so lack of a full
coordinator harness did not cause that escape."* Grok, asked directly whether the refutation and the
new pin moved its view, answered **yes** and **slightly further toward schedule-don't-block**.

Both name the same remaining seam and neither wants Robolectric. Convergence is on the substance,
not just the verdict.

## The findings — both lenses hit the same two, independently

| # | Lens(es) | Finding | Fix |
|---|---|---|---|
| 1 | **Codex R4-1 + Grok F2** | **My new arming pin was PRESENCE-ONLY.** Keep `armSendTimeout` in the handoff branch AND add it to `retryable` — which runs before re-encryption and the unbounded upload — and the round-2 P1 returns **with every guard green**. "Armed here" is half the invariant; "and nowhere else" is the other half | **EXCLUSIVITY** assertion: exactly one production call site plus the declaration. Mutation-verified with Codex's exact evasion (arming added to `retryable`) — now caught |
| 2 | **Codex R4-2 + Grok F1** | **"Lost frames" is NOT a null-id source.** A frame the relay never processes produces no error event at all, so `onServerError` never fires — only the timeout covers it. I corrected one wrong causal claim in round 3 and introduced a smaller one in the same breath | Both the `WsClient` kdoc and the router test comment now say what actually produces null: parse failures, unattributed codes, older relays — and say explicitly that a lost frame is not one |
| 3 | **Grok F3** | **Four places still taught the refuted causal story** — that the missing harness let round 2's P1 escape. The round-3 adjudication refuted it; the *source* still asserted it. Grok's framing is exact: *"adjudication knew better; source still teaches the wrong lesson"* — the same class as round 3's finding | All four corrected. The extraction now rests on the justification that survives: it covers routing behaviour that was previously only pattern-matched |
| 4 | **Grok F4** | **Orphaned kdoc documented the wrong constant.** Moving `ERROR_RATE_LIMITED` into `ServerErrorRouter.kt` left its kdoc behind, so Kotlin reattached "the relay's throttle code" to `BASE_BACKOFF_MS`, the boot backoff base | Kdoc rewritten for the constant it actually sits on |

**Residual classing: both lenses confirmed all three declared residuals are correctly classed** —
including that the two kept guards are sound by inspection and the deleted `isMine` was correctly
deleted.

## Evidence

**BUILD SUCCESSFUL, exit 0, 821 tests / 0 failures / 3 skipped.** Round-4 mutation: 1 applied
(Codex's exact dual-arm evasion), 1 discriminated.
