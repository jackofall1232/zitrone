# 0.10.1 send-failure surfacing — review round 1 adjudication

**Codex: 1 P1, 1 P2, 1 P3. Grok: 0 P1, 2 P2, 2 P3.** Strong convergence: both lenses independently
found the same top defect and the same null-id gap. **All findings UPHELD; nothing argued down.**

| # | Lens(es) | Sev | Finding | Verdict | Fix |
|---|---|---|---|---|---|
| 1 | **Codex SF-1 (P1) + Grok F1 (P2)** → **P1** | A relay-attributed failure could falsify a send that SUCCEEDED, permanently. Two routes to it, one per lens: Codex via retry (FAILED → `retryable` → SENDING under the SAME id → retry succeeds → a stale/duplicated copy of the ORIGINAL error re-fails it); Grok via SENT directly (relay says `message.stored` → SENT → a later error naming it is accepted because the CAS allowed SENT). **Verified against source: `markSent` requires SENDING and `markDelivered` requires SENDING/SENT, so both REJECT FAILED — no receipt could ever heal it.** Outcome: a stored message shown as failed forever, and the user's only recovery (retry under the same id) genuinely double-delivers. Grok's framing is the sharper one — this was **strictly worse than the relay dropping the send**, which at least leaves an honest SENT. **This defect was INTRODUCED by 0.10.1**: before it, errors were swallowed and no such corruption existed | **UPHELD at P1** | Split the entry point: new **`markFailedByRelay`** accepts **SENDING only**, so an error contradicting the relay's own receipt is ignored. `markFailed` keeps SENDING/SENT for LOCAL failures, where the device knows first-hand. **And receipts now HEAL**: `markSent`/`markDelivered` accept FAILED, so a real receipt outranks a spurious error instead of being latched out |
| 2 | **Codex SF-2 (P2) + Grok F2 (P2)** | P2 | With a null id the message stays SENDING forever — the original defect, untouched. Grok closed the loop on why there is no escape: **the UI makes only FAILED clickable** (`MessageBubble.kt:199-221`), the store is RAM-only, so there is no timeout, no tap-to-retry, no recovery short of process death. **And both lenses verified the in-repo relay emits ALL FOUR error codes with NO `message_id`** (`hub.go:160,165,171,178`) — so against any relay built from this repository the client half fixes **nothing** | **UPHELD** | **NOT FIXED IN THIS ROUND — needs a maintainer decision (see below)** |
| 3 | **Grok F3 (P3)**, also flagged by Codex | P3 | Comments claimed an ownership bound the code does not implement — `MessagingCoordinator` said the CAS checks "SENDING/SENT **and ours only**" and `WsClient`'s kdoc said a receiver "must check the id against sends it actually owns". No `isMine` check exists; what makes incoming mail unreachable is that `addIncoming` forces DELIVERED. Grok sharpened my own removal argument: it is a property of the **production call graph, not the type** — `addOutgoing` would accept `isMine = false` at the default SENDING state (`Models.kt:24`) | **UPHELD** | Both comments rewritten to state the actual mechanism and to say explicitly that ownership is **not** enforced |
| 4 | **Codex SF-3 (P3) + Grok F4 (P3)** | P3 | The ordering tripwire proved source order, not the property. `if (messageId == null) return` inserted ABOVE both statements keeps every pinned substring present and the indices correctly ordered, while making the cover yield conditional after all — exactly what it claims to prevent. Also over-constraining: it rejects harmless refactors | **UPHELD** | Added an assertion that nothing may `return` ahead of the yield. Brittleness against refactors is **accepted and declared**: the alternative is no guard at all, and a behavioural test needs the harness below |

## The one place the lenses disagreed — the coordinator harness

**Codex: merge blocker. Grok: acceptable residual, not a blocker** (its condition: F1/F2 fixed or
accepted as known residuals). Not adjudicated here, because **item 2 blocks the merge anyway** on
grounds both lenses agree on. Recorded as an open question for round 2. Codex's constructive
suggestion is the way through and does not need Robolectric: **extract the handler into a
behaviourally testable seam** rather than build a full application harness.

## Item 2 needs a MAINTAINER DECISION — it is a product question, not an implementation one

The null-id case leaves a send stuck with no user-visible path out. Three options, none free:

1. **Ship 0.10.1 only after `1c63e8c` is pushed and merged**, and accept that an unattributable
   rejection still hangs. Smallest change; leaves the most common rejection class unfixed.
2. **Add a send timeout** — a SENDING message with no receipt inside N seconds goes FAILED. Fixes
   the defect independently of the relay, including relay rollback, and is the only option that
   works with the relay as it exists in this repo. Costs a new timer on the send path (the
   repository already has `clock` and `scheduleTtl` infrastructure) and needs its own review.
3. **Fail in-flight sends on disconnect.** Cheap, but wrong on a flaky Tor circuit where the send
   may well have landed — it would trade a stuck bubble for a false failure and duplicate sends.

**Recommendation: (2), plus (1) as a precondition.** It is the only one that does not depend on a
commit that exists on one box, and the defect's whole point is that the user is never left lying to.

## ⚠️ HARD DEPENDENCY, restated

**`1c63e8c` is still not on origin.** Both lenses independently confirmed the in-repo relay attaches
no id to any error. Until that commit is pushed and merged, **0.10.1 fixes nothing that anyone can
build**, and a redeploy from `main` silently reverts the production relay to attributing nothing.

## Evidence

`ci-gradle :app:testDebugUnitTest :app:assembleDebug --rerun-tasks` → **BUILD SUCCESSFUL, exit 0,
808 tests / 0 failures / 3 skipped** (806 → 808). Round-1 fix mutations: **7 applied, 7
discriminated**, restores checksum-verified. The sweep also found a defect in my OWN round-0 work —
an `isMine` guard that was unreachable, with a test that passed off a different check entirely and
could not fail; both were removed rather than kept for comfort.
