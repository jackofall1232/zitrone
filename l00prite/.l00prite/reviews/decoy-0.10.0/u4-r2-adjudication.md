# U4 review round 2 — adjudication

Against the round-1 fixes. Codex **0 P1, 1 P2, 1 P3**; Grok **0 P1, 2 P2, 2 P3**.
**Severity fell on both lenses and neither found a P1** (round 1: 1 P1 and 2 P1). One row per part.

| # | Lens | Sev | Finding | Verdict | Action |
|---|---|---|---|---|---|
| 1a | Codex U4-R2-1 | P2 | `CoverPressure` reads only the REAL socket's queue, so U4's own socket could back up unseen — R-U4-4's "yields on every signal available to it" untrue as written | **UPHELD** | The supplier sums both sockets: they share a device uplink |
| 1b | Codex U4-R2-1 | P2 | An accepted send-back was recorded on no meter at all | **UPHELD, but not as Codex proposed** | Recorded — on the SYNTHETIC ring; see row 2 |
| 2 | Grok F2 | P2 | Routing the synthetic account's `rate_limited` **and** its send-backs into the shared meter lets synthetic-side contention — or **one hostile relay frame** — black out cover for every real send for a full off-window, with the real account nowhere near its limit | **UPHELD** | `CoverPressure` now models **two budgets**: `syntheticRateLimited` / `recordSyntheticFrame` / `yieldingSendBack` gate send-backs only |
| 3 | Grok F1 | P2 | The rewritten pressure tripwire asserted both readings *appear*, not that they are the supplier's **answer** — `{ a(); b(); 0L }` passes and reports an empty queue forever | **UPHELD** | The whole lambda body is pinned exactly |
| 4 | Codex U4-R2-2 | P3 | The receiver-typed disconnect exemption is spoofable by aliasing the real client to a second local `ws` inside the wrapper | **UPHELD** | `ws` must have exactly one binding, the constructor property, and exactly one `WsClient`-typed declaration in that file |
| 5 | Grok F3 | P3 | `applyTransportLocked`'s kdoc still described the **pre-fix** contract from round 1 | **UPHELD** | Rewritten to the contract the body now has |
| 6 | Grok F4 | P3 | `CoverPressure.recordFrame`'s kdoc claims counted frames "charge the same per-account relay bucket" — false once send-backs were counted | **UPHELD** | Resolved by row 2: send-backs no longer touch that ring, and the kdoc says so |

**All seven upheld. Nothing argued down.**

## The round's real content: the two lenses disagreed, and the disagreement was the finding

My round-2 prompt asked explicitly whether the round-1 fix routing synthetic `rate_limited` into the
shared meter had **handed an adversary a lever on the pairing**.

- **Codex answered no:** "synthetic `rate_limited` only suppresses cover; it cannot block a real
  handoff." True, and it is why this is P2 and not P1.
- **Grok answered yes,** and gave the sequence: the relay is conceded, it may emit one `rate_limited`
  on the synthetic connection, and cover then stops for every genuine send for a full minute while
  the real socket is quiet. It also noted the sharper harm — *a consistent minute-long gap is a
  better mark than intermittent drops.*

Both are correct about what they assert; Grok's is the one that changes the design. **The fix a
single lens would have produced was wrong in the direction the other lens was looking**, which is the
case for paired-blind review stated in one paragraph, and worth keeping.

The synthesis satisfies both: send-backs *are* metered (Codex), on the *synthetic* account's own ring
(Grok), because that is the budget they actually charge. The shared **queue** stays shared, because
the uplink genuinely is.

## Evidence

`ANDROID_HOME=/opt/android-sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug --rerun-tasks`
→ **BUILD SUCCESSFUL, Gradle exit 0, 797 tests / 0 failures / 0 errors / 3 skipped** (794 → 797).

**Fix-targeted mutations: 8 applied, 8 discriminated** — after one survivor, again a test defect of
the same family as round 1's four: the asymmetry was pinned by calling the meter **directly**, so a
mutation making the *session* ask `yielding()` instead of `yieldingSendBack()` sailed through. A test
that exercises a collaborator is not a test that the caller uses it.

## Carried forward, unresolved by design

Both lenses again named the **lexical** limit of these source tripwires. Row 3 and row 4 tightened two
of them materially, but a rename or an indirection still defeats the class. This is a standing
property of the approach, not a U4 regression, and it is the strongest argument for the behavioural
tests that back each tripwire up.
