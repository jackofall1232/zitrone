# 0.10.1 send-failure surfacing — review round 2 adjudication

**Codex: 1 P1, 0 P2, 1 P3. Grok: 1 P1, 0 P2, 2 P3.** **Both lenses independently found the same P1**,
and it is a defect in the send timeout *this unit added in round 1*. All findings UPHELD.

| # | Lens(es) | Sev | Finding | Verdict | Fix |
|---|---|---|---|---|---|
| 1 | **Codex SF-R2-1 + Grok F1** | **P1** | **The timeout armed at bubble creation, not at handoff — so it timed local work and could cause DOUBLE DELIVERY.** `deliverAttachment` calls `addOutgoing` (arming the 90 s timer) *before* `uploadBlob`. Grok added the numbers: an 8 MiB attachment is ~11 MiB base64, and OkHttp's `writeTimeout` is **per-write, not whole-body**, so a slow continuous upload is never cut off at 20 s. The timer fires mid-upload → **false FAILED with a retry affordance on a still-live send** → user retries → two independently encrypted envelopes under one id. Both lenses then derived the same relay detail independently (and I verified it): `StoreEnvelope` is a bare `INSERT` and `envelopes.id` is a `UUID PRIMARY KEY`, so the second insert is *rejected* **unless the first was already delivered and acked, deleting the row** — at which point the peer genuinely receives the message twice. **My own design claim ("times the relay's RECEIPT, not delivery") was false as implemented** | **UPHELD at P1** | **Arming moved to the socket handoff and nowhere else** — inside `publishOutgoing`'s `ws.sendMessage` success branch, the single point both text and attachment paths pass through. Removed from `addOutgoing` **and** from `retryable` (a retry re-enters the send path and arms at its own handoff). The window now contains no local work, which is what the design always claimed |
| 2 | **Codex SF-R2-2 + Grok (same area)** | P3 | **Timeout jobs outlived their session, and a fired job could disown a replacement.** `clearAll` cancels `ttlJobs`, `readBurnJobs`, `revealJobs` — but **not** `sendTimeoutJobs`, so a timer survived vault lock, logout, revocation and confirmed deletion for up to 90 s. Separately, the job body called `sendTimeoutJobs.remove(messageId)` **unconditionally**, so a retry that re-armed between the old job's CAS and that line had its handle deleted — leaving a live timer nothing could cancel. My "disarmed on remove/lock" claim was false | **UPHELD** | `clearAll` now cancels and clears `sendTimeoutJobs` first. Self-removal is **conditional** (`remove(key, value)` on the job's own handle) |
| 3 | **Grok F2** | P3 | **`markSent` / `markDelivered` kdocs still said receipts "can never resurrect a FAILED message"** — the exact opposite of round 1's healing fix, which their bodies now implement. Grok's point is the dangerous part: someone "restoring monotonicity" from the comment would reintroduce the round-1 P1 latch | **UPHELD** | Both kdocs rewritten to state that FAILED is accepted deliberately, and why |
| 4 | **Grok F3** | P3 | **My comments described the PRE-MERGE relay.** They claimed the send budget is checked *before* the envelope is parsed, so `rate_limited` "frequently" carries no id. Against the merged `handleSend` that is **false** — it unmarshals the header first, *then* rate-limits, so a normal rate-limited send **does** carry its id. Verified at `hub.go:169-175`. The timeout is still justified (parse/UUID failures, lost frames, older relays) but the stated rationale was wrong | **UPHELD** | Corrected in `MessagingCoordinator`, `MessageRepository` and the tripwire prose, naming what an unattributable rejection actually means now |

**Nothing argued down.** Codex additionally **confirmed** the UUID canonicalisation coupling holds for
every id this client mints, and **confirmed my cancel-vs-CAS redundancy argument is sound and the race
reachable** — naming the harness that would discriminate it (a controllable dispatcher with a barrier
between `delay` completion and the CAS).

## A tripwire had to be relaxed, and the reasoning matters

Moving the arming into `publishOutgoing` failed the U3 tripwire *"the coordinator covers a send only
when the relay actually took the real frame"*, which asserted the literal token run
`if(ws.sendMessage(envelope)) { return true` — i.e. that the handoff branch does **nothing** but
return. **Adjacency was never the property; ownership is.** It now brace-walks the branch (`bodyOf`)
and asserts the single `return true` lives **inside** it, so statements may be added but the return
cannot escape. R-U3-1 is untouched: the arming is strictly *after* `ws.sendMessage` returns, so
nothing was added ahead of any real handoff.

## THIRD instance of the same testing limitation — declared, not hidden

The round-2 sweep found that making the self-removal unconditional again **broke no test**. The reason
is structural: re-arming *cancels* the old job, so on a single-threaded virtual clock the old job never
runs its tail concurrently with the new one — **the interleaving cannot be expressed in this harness.**

Kept, and the test's own comment now says so plainly instead of implying coverage. This is the same
class as the cancel-vs-CAS redundancy (Codex validated that one), and the opposite of round 0's
`isMine` clause, which was **unreachable by construction** and therefore deleted. The rule this unit
has now demonstrated three times:

> **A surviving single mutation means "this test cannot see it", not "this code is dead."** Decide by
> reachability under real concurrency — this class is documented as hit from the main thread and
> several dispatchers — never by the sweep result alone.

## The harness question — THE LENSES SPLIT AGAIN, and they agree on the remedy

- **Codex: MERGE BLOCKER.** Its argument is now evidential rather than principled: *the missing
  harness is what let this round's P1 escape.* The central production claim (attribution reaches
  `onServerError`, causes the transition, and still yields cover when unattributable) is only
  textually inspected.
- **Grok: acceptable residual, not a blocker**, conditional on the P1s being fixed — because the
  dangerous behaviour lives in the repository CAS/timeout and `WsClient` normalisation, both tested
  behaviourally, and the coordinator glue is two straight-line statements whose order and form a
  tripwire already defends.

**They propose the SAME remedy**, and neither wants Robolectric: extract a small pure/injectable
error-router — `handleServerError(code, messageId, onRateLimited, markFailedByRelay)` — plus a narrow
fake upload/socket seam to test the timeout race behaviourally.

**Adjudication: NOT resolved here, and deliberately.** Round 2 found a P1 in the very surface the
harness would cover, which is evidence *for* Codex's position; but the fixes now in place are
behaviourally tested at the repository level, which is Grok's condition. **Round 3 should be told the
split persists and asked to rule against the round-2 fixes** rather than the round-1 code. My own
recommendation, which is not a ruling: **do the extraction** — both lenses named it independently, it
is cheap, and it is the only thing that would have caught this round's P1.

## Evidence

`ci-gradle :app:testDebugUnitTest :app:assembleDebug --rerun-tasks` → **BUILD SUCCESSFUL, exit 0,
816 tests / 0 failures / 3 skipped** (813 → 816). Round-2 fix mutations: **3 applied, 2
discriminated, 1 survived and is the declared limitation above** (M-r2a re-arming at `addOutgoing`
→ caught by two tests; M-r2b dropping the `clearAll` disarm → caught; M-r2c unconditional
self-removal → **survived**, harness cannot express the race).
