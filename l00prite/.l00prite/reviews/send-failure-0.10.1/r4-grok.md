I'll do a read-only adversarial review of the round-3 delta and the full send-failure unit, verifying claims against source rather than prior rounds.Next I'll read the full unit against hub.go and the Android paths, then re-attack each load-bearing claim.Checking all `sendMessage` paths, cover-id handling, and whether the arming pin can stay green while reintroducing the P1.Checking one more edge case: whether the arm pin can be satisfied without a live call, and whether `markFailedByRelay` is tested for unknown ids.Working tree has uncommitted edits; comparing them to HEAD so the review targets the real unit state.# FINDINGS

## F1 — P3 — traded one wrong claim for another: “lost frame” as a null-id rejection

**Files:**
- `apps/android/app/src/main/java/com/zitrone/app/net/WsClient.kt` ~132–136 (`onServerError` kdoc, HEAD)
- `apps/android/app/src/test/java/com/zitrone/app/ServerErrorRouterTest.kt` ~53–55
- (same phrasing) `MessageRepositoryTest.kt` ~331 — less wrong there; see CONFIRM-OR-REFUTE

**Sequence (documentation, not a runtime bug):**
1. Round 3 rewrote the null-id story to match merged `handleSend` (parse header, then rate-limit).
2. Replacement lists “a lost frame” among cases where `onServerError` sees a null id / the unattributable `rate_limited` path runs.
3. Against `hub.go` and `dispatchFrame`: a frame the relay never processes produces **no** `error` event. `onServerError` never runs. Only the 90 s send timeout bounds that case.

**Wrong outcome:** A reader of the authoritative wire API kdoc is told lost frames are a null-id rejection path. They are a **no-event** path. Same class as the round-3 finding: comment claims a mechanism the code does not implement.

**Why tests miss it:** No test asserts documentation accuracy. Behaviour is correct; only prose is wrong.

---

## F2 — P3 — new arming pin is presence-only; dual-arm reopens round-2 P1 with every guard green

**File:** `apps/android/app/src/test/java/com/zitrone/app/DecoySendPairingTest.kt` ~1495–1502 (HEAD)

**Sequence (regression the pin claims to block):**
1. Keep `messages.armSendTimeout(messageId)` in the `ws.sendMessage` success branch of `publishOutgoing` (pin stays green).
2. **Also** call `messages.armSendTimeout(messageId)` earlier on the attachment path after `addOutgoing` / before `uploadBlob` (or in `retryable` before re-upload). Production today has exactly one call site; this is the addition the pin does not forbid.
3. User sends a large attachment on a slow link. Upload exceeds 90 s.
4. Early timer fires → CAS SENDING→FAILED while attempt #1 is still uploading.
5. User taps retry → second encrypt/upload/send under the same id. When #1 completes and is acked/deleted, #2 can store and deliver → **double delivery** (round-2 P1 class).

**Wrong outcome:** Pin’s failure message says reintroduction of arm-before-handoff is caught. Presence-only check does not enforce “armed **only** at handoff.” `MessageRepositoryTest` “no timeout before handoff” only proves `addOutgoing` does not arm — not that no other production site arms early.

**Why tests miss it:** Lexical `in handoffBranch` + behavioural “`addOutgoing` does not arm.” No exclusivity count, no coordinator path test. (Uncommitted WIP on the tree adds an exclusivity count; that is not in HEAD and is not credited here.)

**Secondary pin issues (same finding):**
- Token match accepts a non-executing occurrence if the exact substring appears in the brace-walked branch (comment / dead code).
- Legitimate refactors (`armSendTimeout(envelope.id)`, helper wrapper) fail the pin without a behaviour change — mild over-constraint; secondary to the hole above.
- Lives in `DecoySendPairingTest` because it reuses the cover-guard brace walk. That creates a decoy↔send-timeout test coupling, not a production defect.

---

## F3 — P3 — stale false claim: “missing harness is what let round 2’s P1 escape”

**Files:**
- `ServerErrorRouter.kt` ~12–18
- `MessagingCoordinator.kt` ~2347–2350
- `ServerErrorRouterTest.kt` ~15–19
- `DecoySendPairingTest.kt` ~1556–1562

**Sequence:**
1. Round 2 P1 was arm-at-`addOutgoing` (bubble ≠ handoff), caught by a `MessageRepository` behavioural test with no coordinator harness.
2. Round 3 adjudication **refuted** the claim that the missing harness let that P1 through; commit `8764de78` records the refutation.
3. Four places still assert the refuted premise (extraction justified because the tripwire “did not catch round 2’s P1” / “absence of harness let P1 escape”).

**Wrong outcome:** Maintainers are told a false causal story about a shipped P1. Same class as the round-3 stale-comment finding: adjudication knew better; source still teaches the wrong lesson. Extraction remains valuable for **routing** behaviour; the historical claim is false.

**Why tests miss it:** Comments are not asserted.

---

## F4 — P3 — orphaned kdoc documents the wrong constant

**File:** `MessagingCoordinator.kt` ~2371–2373

```kotlin
/** The relay's `message.send` throttle code (`server/internal/ws/hub.go`). */

const val BASE_BACKOFF_MS = 1_000L
```

**Sequence:** Round-2 extraction moved `ERROR_RATE_LIMITED` to `ServerErrorRouter.kt` and left the kdoc behind. Kotlin attaches it to `BASE_BACKOFF_MS`.

**Wrong outcome:** Generated/read docs claim the boot backoff base is the relay throttle code.

**Why tests miss it:** No doc check; tripwire pins the constant string elsewhere.

---

# CONFIRM-OR-REFUTE

### Round-3 delta

| # | Claim | Verdict |
|---|--------|---------|
| 1 | `WsClient.onServerError` kdoc vs merged `hub.go` | **Mostly correct, one wrong trade.** `handleSend` unmarshals header, then `Allow`, then emits `rate_limited` with `MessageID` only for a well-formed UUID (`msgID` empty → `omitempty` → client null). Ordinary well-formed rate-limited sends **do** carry id. Null still correct for parse/UUID failure under limit, unattributed codes, older relays. **Incorrect:** classifying “lost frame” as a null-id `onServerError` case (F1). |
| 2 | Timeout-test justification | **Holds.** Core justification is independence from the relay (RAM-only store, only FAILED is clickable). Frequency of unattributable rejections is not required. Timeout still covers no-receipt including lost frames and older relays; listing “lost frames” as timeout-relevant is fine; listing them as router null-id causes is not (F1). |
| 3 | Unattributable-yield test prose vs assertion | **Assertion correct** (`rate_limited` + null → yield only, no fail). **Prose at HEAD** claims the path “covers … lost frames” — **false** (F1). Yield-must-not-depend-on-id remains load-bearing and tested. |
| 4 | New arming source pin | **Presence of handoff arm: yes. Exclusivity / P1 class: no (F2).** Cannot see dual early+handoff arm. Over-constrains renames. Opportunistic home in decoy test file is a coupling smell, not a ship-blocker. |

### Load-bearing claims (re-attacked against source)

| Claim | Verdict |
|--------|---------|
| Cover rejection cannot surface to user | **Holds.** Cover uses a fresh id (`DecoyEnvelopeBuilder.newMessageId`); no `Message` row. Real path `markFailedByRelay` no-ops on unknown id. Synthetic socket ignores `messageId` and never touches the user store. |
| Hostile/buggy relay falsify state | **Bounded as designed.** Relay can name any UUID; CAS is SENDING-only for relay fails; unknown id no-ops; SENT immune to `markFailedByRelay`; receipts heal FAILED. Cross-conversation fail of another live SENDING id is possible under a lying relay — temporary false FAILED, not structural bypass of the bounds. |
| Retry double-deliver / R-U3-1 | **No R-U3-1 resurrection in this unit.** Retry re-enters deliver* under same id; cover only after successful `publishOutgoing`; yield on `rate_limited` is cover-side. Same-id retry after false fail remains residual double-delivery risk if the first envelope was truly stored and later deleted — mitigated by healing when receipts arrive; not introduced by round 3. |
| Timeout local work / session / double-fire / id reuse | **Holds at HEAD production code.** Single arm at handoff; SENDING-only fire; cancel on markSent/Delivered/Failed/ByRelay/burn/remove; `clearAll` cancels jobs; re-arm replaces; clearAll+re-add same id tested. Conditional job-map removal still untestable on single-thread virtual clock (declared residual). |
| Send path to `ws.sendMessage` without arm | **User bubbles: only via `publishOutgoing`, which arms on success.** `publishReceipt` and cover/synthetic sends create no user bubble. Dead socket → `markFailed`, no arm. |

### R-U3-1

**Not violated by this unit.** Real send is not delayed/blocked for cover; cover is after handoff and only if handoff succeeded; rate-limit yield drops cover, not the real send; a retry is a real send and re-arms at its own handoff.

---

# HARNESS RULING

**Debt to schedule — does not gate this merge.**

**Did the refutation move the view?** Yes. Round 2’s P1 was repository timing (arm at bubble creation), caught by constructible `MessageRepository` tests. Claiming “the missing coordinator harness let that P1 through” is **false**; the ServerErrorRouter extraction would not have caught it either. That refutation holds against source and the project record.

**Did the new pin move the view?** Slightly toward “schedule, don’t block.” The pin adds a second angle (source location of arm) that repository tests cannot see, so the **specific** round-2 P1 is now covered from two sides: “`addOutgoing` does not arm” + “handoff branch contains arm.” That is enough for the defect that already shipped once.

**Why not “harness required before merge”?**
- Remaining risk is **pattern** (lexical gates keep escaping in new shapes — F2 is another instance), not an untested load-bearing behaviour of the shipped fix.
- Full `MessagingCoordinator` constructability remains Robolectric-scale and is already a declared residual.
- Cheaper unexploited seam at HEAD: **exclusivity** of `armSendTimeout(` in main sources (count == declaration + one call), not a full harness. That would close F2 without constructing the coordinator.

**Do not restate a pure “asserted-is-enough” victory:** three same-shaped escapes still mean lexical-only gates are a weak long-term strategy; they do not mean this PR’s behavioural surface is unproven.

---

# RESIDUAL CLASSING

| Declared residual | Correctly classed? |
|-------------------|-------------------|
| No test constructs `MessagingCoordinator`; wiring asserted | **Yes.** `routeServerError` behaviour is tested; `onServerError` wiring is tripwired. Residual is real; not a silent ship-blocker for routing. |
| Conditional-removal race + cancel-vs-CAS need barrier dispatcher | **Yes.** Documented in `armSendTimeout`; mutation sweep agrees; single-thread virtual clock cannot express. Guards correctly kept. |
| Live `deliverAttachment` + slow body + timer E2E untested | **Yes.** Unit pins “no arm before handoff” at repository + source pin; full upload×timer integration still absent. Residual, not a claim that production arms early (it does not at HEAD). |

No declared residual is actually a P1/P2 in production code at HEAD.

---

# MISSING CONTEXT

- Did not run the Android suite (read-only).
- Did not construct or execute a coordinator integration path.
- Working tree had **uncommitted** edits during review (WsClient / ServerErrorRouterTest “lost frame” prose, DecoySendPairingTest exclusivity count, r4 review files). **This report is against committed HEAD `8764de78`.** Those WIP edits appear to partially address F1/F2; they are not treated as merged.
- Did not re-verify round-2 mutation suite execution, only the static structure those tests encode.

---

**VERDICT: FINDINGS (0 P1, 0 P2, 4 P3)**
