# CX23 item (a) — the client half

**Status:** relay half DONE and deployed on CX23 (commit `1c63e8c`). Client half NOT started.
**Where:** the client half must be built on `main` (or a branch off it). The deployed CX23 branch
does not contain the 0.10.0 client at all — `apps/android/.../decoy/` does not exist there.
**Do not build Android on CX23.** It runs live Postgres + the relay, and `ci-gradle` (the flock
wrapper, disk floor, daemon caps from ledger `64512cd`) exists only on CX33. An R8 build here can
take the production relay down.

## The defect, restated

`onServerError` surfaces nothing. Every server rejection of a send is silently swallowed, so a
rate-limited or otherwise-rejected message stays displayed as `SENDING` forever — not marked
failed, not retried, no error shown. **Users have no way to know a send failed.** This predates
decoy traffic and is worth fixing on its own merits.

The relay could not have helped before now: the budget check ran before the envelope was parsed,
so `rate_limited` did not carry — and could not carry — a message id.

## What the relay now sends (already live)

`serverEvent.MessageID` is populated on the per-message rejections:

| code | carries `message_id` | when |
|---|---|---|
| `rate_limited` | yes, when the header parsed | send budget exhausted |
| `store_failed` | yes | `StoreEnvelope` failed |
| `bad_envelope` | yes, when the id is a well-formed UUID | header invalid |

Guarantees the client may rely on:

- The field is **additive** — it was already in the wire struct. An older client that ignores it
  behaves exactly as before, which is what made deploying the relay half first safe.
- The id is echoed **only when it is a well-formed UUID**, so it is never arbitrary reflected
  bytes. Treat an empty `message_id` as "unattributable" and fall back to the connection-level
  path, not as a message whose id is `""`.
- `rate_limited` keeps precedence over `bad_envelope`, so a frame that is both rejected by the
  budget and malformed reports `rate_limited` with an empty id.

## What to change

1. **`net/WsClient.kt:125` and `:340`** — the listener signature drops the id today:
   ```kotlin
   "error" -> l.onServerError(frame.optString("code", "unknown"), "")
   ```
   Carry the id: `onServerError(code: String, message: String, messageId: String?)`, reading
   `frame.optString("message_id").takeIf { it.isNotEmpty() }`. Every implementor needs updating —
   `MessagingCoordinator.kt:2327`, `decoy/WsSyntheticSocket.kt:72`, and the test doubles in
   `WsClientFrameTest.kt:125` / `WsSyntheticSocketTest.kt`.

2. **`MessagingCoordinator.kt:2327`** — attribute and surface. With an id, mark that message
   FAILED (not `SENDING`) and make it retryable. Without an id, keep current behaviour.

3. **`decoy/WsSyntheticSocket.kt:72`** — must keep routing `rate_limited` into `CoverPressure`
   unchanged. The yield is a cover-traffic signal, not error handling; it is not a substitute for
   this fix and must not be entangled with it.

## Constraints that must survive the change

- **A cover frame's rejection must not surface to the user.** Cover traffic is invisible by
  design; a decoy failing is not a user-facing event. Attribute only ids the real send path owns.
- **Do not let the retry path resurrect the R-U3-1 class.** Cover must never precede or compete
  with a real send; a retry is a real send.
- Failing a message on `store_failed` is correct — the relay does not hold the envelope.

## Tests owed

Per the project DoD, paired-blind independent review is required before merge (send path is
security-sensitive), plus mutation evidence.

- `rate_limited` with an id marks that specific message FAILED and leaves others untouched.
- `rate_limited` with an empty id changes no message's state.
- A rejected **cover** frame surfaces nothing to the user and still feeds `CoverPressure`.
- `store_failed` and `bad_envelope` attribute the same way.
- Retry of a failed message emits a real send, with no cover frame preceding it.

## Note on what is and is not closed

The relay half does **not** fix the user-visible symptom. Until the client half ships in a
release, users still see `SENDING` forever. Item (a) should stay open, annotated "relay half done
(`1c63e8c`), client half owed", rather than checked.
