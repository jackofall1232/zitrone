# U4 review round 3 — adjudication

**Codex: 0 P1, 0 P2, 1 P3. Grok: CLEAN.**

Severity across the three rounds, both lenses:

| Round | Codex | Grok | Total |
|---|---|---|---|
| 1 | 1 P1, 1 P2, 1 P3 | 2 P1, 2 P2, 1 P3 | 7 |
| 2 | 0 P1, 1 P2, 1 P3 | 0 P1, 2 P2, 2 P3 | 7 |
| 3 | 0 P1, 0 P2, 1 P3 | **CLEAN** | 1 |

| # | Lens | Sev | Finding | Verdict | Action |
|---|---|---|---|---|---|
| 1 | Codex U4-R3-1 | P3 | The synthetic-socket origin pin still checks *names*: repoint the decoy binding itself at the real client (`decoyRelay?.let { wsClient }`) and every downstream name stays honest while the object is wrong — so `WsSyntheticSocket.disconnect()` could disconnect the REAL socket outside U3's ownership boundary | **UPHELD, and fixed structurally rather than by another assertion** | `WsSyntheticSocket` now **constructs** its own `WsClient` and accepts none |

## Why this one was fixed at the type instead of in the test

This is the **third consecutive round** to raise the same property, each time defeating the previous
guard with a cheaper trick:

- **Round 1** — the pin asserted the constructor argument was *spelled* `syntheticWs`.
  Evasion: rebind that name.
- **Round 2** — the pin added "…and `ws` is bound exactly once in the wrapper."
  Evasion: alias the real client inside that file.
- **Round 3** — the pin added the binding's origin, `syntheticSocket?.let { syntheticWs -> }`.
  Evasion: make `syntheticSocket` *be* the real client.

Three rounds, three lexical guards, three evasions. They share one root cause: **the property was
being checked lexically because the type permitted the mistake.** A guard can only ever chase the
next spelling.

So the injection point is gone. `WsSyntheticSocket(wsUrl, httpClient, scope, …)` builds its own
client; there is no `WsClient` parameter to pass the real socket into, and the compiler enforces
that. U3's disconnect exemption for that file now rests on a fact rather than an assertion. What is
left to pin is only that the parameter has not come back — one test, and it cannot be evaded by
renaming anything.

**This is the general lesson, and it is worth carrying past U4:** when the same finding survives two
tightenings of a guard, the guard is the wrong instrument. Change the type so the mistake is
unrepresentable.

## Grok's CLEAN, and what it is worth

It traced R-U4-1 through five distinct paths (send-back on the real socket, vault closed mid-delivery,
null synthetic id, id collision, bare-ack-vs-`ackDurable`) and found no route into decrypt, the
message store, the roster, the unread count or the notification scheduler. It refuted the
weakened-tripwire concern on the round-2 shape and confirmed the two-budget split does not leak into
`yielding()`.

**A CLEAN is the absence of a finding, not a proof.** Recorded as such. Note also that Grok reviewed
the *committed* tree, so its item-2 reasoning describes the round-2 lexical pins — the round-3
structural fix is strictly stronger than what it approved.

## Evidence

`ANDROID_HOME=/opt/android-sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug --rerun-tasks`
→ **BUILD SUCCESSFUL, Gradle exit 0, 796 tests / 0 failures / 0 errors / 3 skipped.**

**Fix-targeted mutations: 4 applied, 4 discriminated** — after one survivor, and this one is the
sharpest argument yet for sweeping every round rather than only the first. The survivor was not a
weak assertion: **my round-3 edit had silently deleted an entire round-2 tripwire**, because the test
I replaced sat immediately above it and I cut the region by anchor. The suite stayed green, the
count barely moved, and nothing but the mutation would have noticed a guard that had simply ceased
to exist. Restored.
