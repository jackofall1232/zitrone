## Findings

### P1 — The abandon request violates the disclosure invariant on every non-204 outcome

**Files/symbols:** `ApiClient.abandonBlob`, `ApiClient.execute`; relay `Handlers.AbandonBlob`.

`abandonBlob` transmits the redemption token before it knows deletion succeeded. The relay rate-limits before parsing the body, authentication can reject before the handler, and the response can be lost after the request arrives:

1. Client sends `{token}`.
2. Relay/proxy learns the unauthenticated redemption capability.
3. The request returns 429/401, times out, or its response is dropped.
4. `settleAttachment` has already removed the memo and never restores it.
5. A live blob remains at a relay which now knows its redemption token.

The 30-second deadline bounds the coroutine; it does not make deletion atomic with disclosure. User-visible consequence: a conceded relay can fetch-and-burn the attachment before its intended recipient, producing permanent `attachmentUnavailable`. This contradicts invariant 3 as stated.

### P2 — `NonCancellable` detaches cleanup from the session lifetime and makes auth-loss disclosure likely

**File/symbol:** `MessagingCoordinator.settleAttachment`.

`scope.launch(confined + NonCancellable)` replaces the scope’s `Job` with `NonCancellable`; it is not a child that teardown can cancel or join. It can start even when the supplied scope is already cancelled.

Concrete sequence:

1. Contact deletion reaches `settleAttachment`, claims and removes the memo.
2. The session locks or is revoked; bearer tokens are cleared and transport/session state is torn down.
3. The detached launch starts afterward and constructs the abandon request without a usable bearer, or with one expiring during the call.
4. The endpoint returns 401 before deletion, but the request body has already exposed the token to the relay/proxy.
5. Failure is swallowed; no retry is possible.

This is bounded to 30 seconds per invocation, so it is not unbounded heap growth, but its lifetime explicitly outlives the session whose authority it uses. Consequence is the P1 disclosure plus failed disk reclamation.

### P3 — The decision test substantially overstates what it discriminates

**File/symbol:** `AttachmentSettleDecisionTest.every state is classified deliberately`.

The “cross-product size” assertion is tautological:

```kotlin
allStates = listOf(null) + MessageState.entries
...
assertEquals((MessageState.entries.size + 1) * 2, classified.size)
```

Both sides derive from the same enum. Adding a state automatically grows both sides and does not fail.

Mutations the suite would not catch include:

- `handedOff -> SKIP` instead of `RELEASE_ONLY`.
- `SENT`, `DELIVERED`, `READ`, or `BURNING` returning `SKIP`.
- Any change that preserves only “ABANDON implies `handedOff == false` and not SENDING/FAILED.”
- Removing actual memo release from the coordinator wiring.
- Moving/clearing the handoff bit incorrectly.
- Launching abandon before or concurrently with the claim.
- Removing `NonCancellable`, the per-call timeout, or the sole call-site constraints.

Thus it tests a few safety properties of the pure function, not the claimed full classification or coordinator correctness.

## Explicit answers

**Q1. Can it abandon a blob whose envelope reached a socket?**

Under the single current call site, I could not construct that outcome from the coordinator races alone.

`publishOutgoing` is a non-suspending slice. After `ws.sendMessage` returns true, it arms the timer and adds `messageId` to `handedOffMessages` before another confined coroutine can run. A contact deletion therefore happens wholly before that slice or after it:

- Before: the envelope is not sent and settlement may abandon.
- After: the handoff bit produces `RELEASE_ONLY`.

`onMessageStored` can race from another thread between `sendMessage` and the handoff add, but `releaseDeposit` removes the memo before clearing the bit. A concurrent settlement may calculate ABANDON from mixed reads, but its final memo claim then loses. If the callback wins before the add, the later add leaks a handoff entry but cannot authorize deletion.

This conclusion depends heavily on the sole wired call site and `releaseDeposit`’s present removal order. It does not cure the disclosure finding above.

**Q2. Is clearing the handoff record before the claim wrong?**

It is structurally poor: an ABANDON caller clears the record before proving it owns the memo. A losing caller can therefore erase another caller’s safety fact.

With today’s sole call site, however, the loss does not become deletion:

- No other confined retry/deposit slice can interleave inside `settleAttachment`.
- A racing `releaseDeposit` removes the memo before clearing handoff.
- Therefore an ABANDON decision produced after that race loses `attachmentDeposits.remove` and sends nothing.

So the ordering is fragile and incorrectly described as an exclusive claim over all settlement state, but I did not find a current P1 interleaving from it alone.

**Q3. Is `handedOffMessages` monotone across retry?**

Only until `releaseDeposit`; it is not literally monotone for the message lifetime.

Trace:

1. Attempt 1 hands off: bit set.
2. Receipt timeout changes SENDING → FAILED.
3. User retry changes FAILED → SENDING and starts attempt 2.
4. A late `message.stored` for attempt 1 calls `releaseDeposit` off-worker and clears both memo and handoff.
5. Attempt 2 continues.

If teardown follows without another successful handoff, the old fact is gone. However, the same callback also removed the old memo. Attempt 2 either:

- already captured the old memo, in which case it does not restore it and settlement has no claim; or
- creates a new token/memo, whose blob was not named by attempt 1.

Therefore this disproves the monotonicity claim, but I did not find it causing abandonment of attempt 1’s blob in the present code.

**Q4. Is off-worker `releaseDeposit` safe?**

Its ordering is safety-critical. It removes `attachmentDeposits` first and `handedOffMessages` second. Consequently, a settlement that observes the cleared handoff cannot successfully claim that retired memo.

It can make the pure decision say ABANDON where an earlier snapshot would say RELEASE_ONLY, but the subsequent atomic memo removal loses. It therefore does not currently turn that into an issued abandon.

It can retire a retry’s newly installed memo because acknowledgements are keyed only by message ID, not attempt. That weakens the one-row-per-message memoisation and can cause a later retry to choose another token, but it does not delete a delivered blob in the examined call path.

**Q5. Is the wired call site only reachable when nothing was ever handed off?**

No: across a receipt timeout and retry, `publishOutgoing`’s contact-deleted branch can be reached after an earlier attempt handed off.

The protection comes from the handoff/memo records, not from the call-site comment. A prior handoff normally yields RELEASE_ONLY. If a late acknowledgement cleared the bit, it also cleared the corresponding memo; a retry-created memo then represents a different token. Thus the comment is false across retries, although the current paired retirement prevents abandonment of the old handed-off blob.

**Q6. Lifetime and ordering of the network launch**

- It is detached from the supplied scope’s job by `NonCancellable`.
- It may start when the scope is already cancelled.
- The network suspension frees `confined`; it does not exclude a retry.
- There is no `join` or other ordering against later work.
- A fresh retry is presently blocked because the contact branch discards the message before settlement, so `retryable()` cannot accept it.
- An already-running retry is handled only indirectly by the handoff/memo reasoning above.

The detached lifetime is unsound with respect to bearer/session teardown and disclosure, as described in P1/P2.

**Q7. `stateOf` and retryable coverage**

`stateOf` introduces no apparent lock-order problem. It reads one immutable `StateFlow` snapshot and returns an enum, not a mutable `Message` alias.

SENDING and FAILED are exactly the states accepted or occupied by ordinary retry flow: `retryable()` accepts only FAILED and changes it to SENDING. Other states are not user-retryable.

State alone does not prove there is no already-running coroutine: a repository entry can be discarded while its send coroutine still resumes later. The current sole call site compensates because it runs from that send coroutine after the contact recheck; the pure function itself does not prove this property.

**Q8. Timeout and failure soundness**

Yes. `ApiClient.execute` applies:

```kotlin
call.timeout().timeout(BLOB_ABANDON_TIMEOUT_MS, ...)
```

to the individual OkHttp `Call`, so it is a whole-call, per-request timeout.

Thirty seconds is plausible for a tiny request on clearnet/Tor but aggressive for cold or degraded I2P/Tor setup. A timeout only leaves an orphan for the TTL, which is availability/capacity-safe in isolation. It is not disclosure-safe: the request body may have reached the relay while deletion did not, and the memo is irrecoverably gone.

**Q9. Test weaknesses**

The enum-size assertion is tautological, RELEASE_ONLY behavior is barely pinned, and no coordinator wiring or concurrency invariant is tested. In particular, changing every handed-off outcome to SKIP passes; changing terminal non-handed-off states to SKIP also passes except for the single `state=null` example.

## Verdict

Not clean. The coordinator’s sole call site appears to avoid abandoning a blob actually named by a handed-off envelope, but the network protocol and detached best-effort execution violate the stated disclosure invariant whenever deletion is not confirmed.
tokens used
83,149
## Findings

### P1 — The abandon request violates the disclosure invariant on every non-204 outcome

**Files/symbols:** `ApiClient.abandonBlob`, `ApiClient.execute`; relay `Handlers.AbandonBlob`.

`abandonBlob` transmits the redemption token before it knows deletion succeeded. The relay rate-limits before parsing the body, authentication can reject before the handler, and the response can be lost after the request arrives:

1. Client sends `{token}`.
2. Relay/proxy learns the unauthenticated redemption capability.
3. The request returns 429/401, times out, or its response is dropped.
4. `settleAttachment` has already removed the memo and never restores it.
5. A live blob remains at a relay which now knows its redemption token.

The 30-second deadline bounds the coroutine; it does not make deletion atomic with disclosure. User-visible consequence: a conceded relay can fetch-and-burn the attachment before its intended recipient, producing permanent `attachmentUnavailable`. This contradicts invariant 3 as stated.

### P2 — `NonCancellable` detaches cleanup from the session lifetime and makes auth-loss disclosure likely

**File/symbol:** `MessagingCoordinator.settleAttachment`.

`scope.launch(confined + NonCancellable)` replaces the scope’s `Job` with `NonCancellable`; it is not a child that teardown can cancel or join. It can start even when the supplied scope is already cancelled.

Concrete sequence:

1. Contact deletion reaches `settleAttachment`, claims and removes the memo.
2. The session locks or is revoked; bearer tokens are cleared and transport/session state is torn down.
3. The detached launch starts afterward and constructs the abandon request without a usable bearer, or with one expiring during the call.
4. The endpoint returns 401 before deletion, but the request body has already exposed the token to the relay/proxy.
5. Failure is swallowed; no retry is possible.

This is bounded to 30 seconds per invocation, so it is not unbounded heap growth, but its lifetime explicitly outlives the session whose authority it uses. Consequence is the P1 disclosure plus failed disk reclamation.

### P3 — The decision test substantially overstates what it discriminates

**File/symbol:** `AttachmentSettleDecisionTest.every state is classified deliberately`.

The “cross-product size” assertion is tautological:

```kotlin
allStates = listOf(null) + MessageState.entries
...
assertEquals((MessageState.entries.size + 1) * 2, classified.size)
```

Both sides derive from the same enum. Adding a state automatically grows both sides and does not fail.

Mutations the suite would not catch include:

- `handedOff -> SKIP` instead of `RELEASE_ONLY`.
- `SENT`, `DELIVERED`, `READ`, or `BURNING` returning `SKIP`.
- Any change that preserves only “ABANDON implies `handedOff == false` and not SENDING/FAILED.”
- Removing actual memo release from the coordinator wiring.
- Moving/clearing the handoff bit incorrectly.
- Launching abandon before or concurrently with the claim.
- Removing `NonCancellable`, the per-call timeout, or the sole call-site constraints.

Thus it tests a few safety properties of the pure function, not the claimed full classification or coordinator correctness.

## Explicit answers

**Q1. Can it abandon a blob whose envelope reached a socket?**

Under the single current call site, I could not construct that outcome from the coordinator races alone.

`publishOutgoing` is a non-suspending slice. After `ws.sendMessage` returns true, it arms the timer and adds `messageId` to `handedOffMessages` before another confined coroutine can run. A contact deletion therefore happens wholly before that slice or after it:

- Before: the envelope is not sent and settlement may abandon.
- After: the handoff bit produces `RELEASE_ONLY`.

`onMessageStored` can race from another thread between `sendMessage` and the handoff add, but `releaseDeposit` removes the memo before clearing the bit. A concurrent settlement may calculate ABANDON from mixed reads, but its final memo claim then loses. If the callback wins before the add, the later add leaks a handoff entry but cannot authorize deletion.

This conclusion depends heavily on the sole wired call site and `releaseDeposit`’s present removal order. It does not cure the disclosure finding above.

**Q2. Is clearing the handoff record before the claim wrong?**

It is structurally poor: an ABANDON caller clears the record before proving it owns the memo. A losing caller can therefore erase another caller’s safety fact.

With today’s sole call site, however, the loss does not become deletion:

- No other confined retry/deposit slice can interleave inside `settleAttachment`.
- A racing `releaseDeposit` removes the memo before clearing handoff.
- Therefore an ABANDON decision produced after that race loses `attachmentDeposits.remove` and sends nothing.

So the ordering is fragile and incorrectly described as an exclusive claim over all settlement state, but I did not find a current P1 interleaving from it alone.

**Q3. Is `handedOffMessages` monotone across retry?**

Only until `releaseDeposit`; it is not literally monotone for the message lifetime.

Trace:

1. Attempt 1 hands off: bit set.
2. Receipt timeout changes SENDING → FAILED.
3. User retry changes FAILED → SENDING and starts attempt 2.
4. A late `message.stored` for attempt 1 calls `releaseDeposit` off-worker and clears both memo and handoff.
5. Attempt 2 continues.

If teardown follows without another successful handoff, the old fact is gone. However, the same callback also removed the old memo. Attempt 2 either:

- already captured the old memo, in which case it does not restore it and settlement has no claim; or
- creates a new token/memo, whose blob was not named by attempt 1.

Therefore this disproves the monotonicity claim, but I did not find it causing abandonment of attempt 1’s blob in the present code.

**Q4. Is off-worker `releaseDeposit` safe?**

Its ordering is safety-critical. It removes `attachmentDeposits` first and `handedOffMessages` second. Consequently, a settlement that observes the cleared handoff cannot successfully claim that retired memo.

It can make the pure decision say ABANDON where an earlier snapshot would say RELEASE_ONLY, but the subsequent atomic memo removal loses. It therefore does not currently turn that into an issued abandon.

It can retire a retry’s newly installed memo because acknowledgements are keyed only by message ID, not attempt. That weakens the one-row-per-message memoisation and can cause a later retry to choose another token, but it does not delete a delivered blob in the examined call path.

**Q5. Is the wired call site only reachable when nothing was ever handed off?**

No: across a receipt timeout and retry, `publishOutgoing`’s contact-deleted branch can be reached after an earlier attempt handed off.

The protection comes from the handoff/memo records, not from the call-site comment. A prior handoff normally yields RELEASE_ONLY. If a late acknowledgement cleared the bit, it also cleared the corresponding memo; a retry-created memo then represents a different token. Thus the comment is false across retries, although the current paired retirement prevents abandonment of the old handed-off blob.

**Q6. Lifetime and ordering of the network launch**

- It is detached from the supplied scope’s job by `NonCancellable`.
- It may start when the scope is already cancelled.
- The network suspension frees `confined`; it does not exclude a retry.
- There is no `join` or other ordering against later work.
- A fresh retry is presently blocked because the contact branch discards the message before settlement, so `retryable()` cannot accept it.
- An already-running retry is handled only indirectly by the handoff/memo reasoning above.

The detached lifetime is unsound with respect to bearer/session teardown and disclosure, as described in P1/P2.

**Q7. `stateOf` and retryable coverage**

`stateOf` introduces no apparent lock-order problem. It reads one immutable `StateFlow` snapshot and returns an enum, not a mutable `Message` alias.

SENDING and FAILED are exactly the states accepted or occupied by ordinary retry flow: `retryable()` accepts only FAILED and changes it to SENDING. Other states are not user-retryable.

State alone does not prove there is no already-running coroutine: a repository entry can be discarded while its send coroutine still resumes later. The current sole call site compensates because it runs from that send coroutine after the contact recheck; the pure function itself does not prove this property.

**Q8. Timeout and failure soundness**

Yes. `ApiClient.execute` applies:

```kotlin
call.timeout().timeout(BLOB_ABANDON_TIMEOUT_MS, ...)
```

to the individual OkHttp `Call`, so it is a whole-call, per-request timeout.

Thirty seconds is plausible for a tiny request on clearnet/Tor but aggressive for cold or degraded I2P/Tor setup. A timeout only leaves an orphan for the TTL, which is availability/capacity-safe in isolation. It is not disclosure-safe: the request body may have reached the relay while deletion did not, and the memo is irrecoverably gone.

**Q9. Test weaknesses**

The enum-size assertion is tautological, RELEASE_ONLY behavior is barely pinned, and no coordinator wiring or concurrency invariant is tested. In particular, changing every handed-off outcome to SKIP passes; changing terminal non-handed-off states to SKIP also passes except for the single `state=null` example.

## Verdict

Not clean. The coordinator’s sole call site appears to avoid abandoning a blob actually named by a handed-off envelope, but the network protocol and detached best-effort execution violate the stated disclosure invariant whenever deletion is not confirmed.
