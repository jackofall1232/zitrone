I'll conduct an adversarial security review of Unit U3 (send pairing) against the real-frame-first design and the claimed structural impossibilities. Starting with the protocol, design docs, and full unit source.Reviewing U3 pairing, the ruling, and the full unit source against the claimed structural impossibilities.Reading the coordinator integration, tests, and related U1/U2 surfaces next.Checking teardown vs. `finally` and other structural claim edges.Checking one more edge: whether `coverFor` outside `try` can leave a marked unpaired frame under cancellation, and verifying the full claim set.# Adversarial Security Review — U3 Send Pairing, Round 2

**Unit:** whole decoy stack on `feat/0.10.0-decoy-u3-pairing` (`7a798d17` + ancestry from `a4f118df`)  
**Focus:** real-frame-first deletion round; structural claims; deletion side-effects

---

## Claim-by-claim verdicts

### 1. Four round-1 P1s “impossible by construction”

| Claim | Verdict |
|---|---|
| Process death mid-pair loses real after cover ran first | **Structurally impossible.** `publish()` is first; sole suspension is `sleep` after handoff. Cover cannot put the real frame after a gap. |
| `deleteContact` interleaves before publish | **Structurally impossible.** No suspension between flush and the non-suspending `() -> Unit` tail; compiler-enforced at three sites. |
| Self-preemption of `sendLimit` within a pair | **Structurally impossible.** Real enqueues first; cover only after. |
| Cancellation skips real publish | **Structurally impossible.** `publish()` precedes every `try`; entering suspend is not a suspension point. |

**Caveat (not a re-opened P1):** U3-A’s *justification* (“a process can only die at a suspension point”) confuses **cooperative cancellation** with **OS process death**. SIGKILL/LMK can still hit between flush and `publish()` — same pre-U3 window, not a cover-widened one. The cover-induced P1 is still structurally gone; the wording is wrong (see P3 below).

### 2. Pairing mutex removal

**Verified:** no `Mutex` / `window` remains in `DecoySendPairing`; only historical kdoc mentions. No other caller.

Nothing depended on it for teardown (stop never took it). Concurrent reals no longer queue on a pair lock. **“True bound is zero”** holds for **lock/suspension before a real frame**; post-publish `coverFor` still burns confined-worker CPU before the first yield (see P3).

### 3. Concurrent pair interleaving

Declared harmless; **agree under the threat model.**

On the confined worker: `R_A → coverFor_A → sleep_A` yields → `R_B → …` → `D_A`, `D_B`. Observer sees equal-length frames; halves still associable by size; real-first is public. No conversation id in the interleaving. Spec §2.4 residual is honest.

### 4. `SecureRandom`

**Implementation OK:** parameter type is `SecureRandom`; default `SecureRandom()`; gap is the only draw.

**Reasoning overstated:** two vault sessions get two instances. Independent LCGs do not share state, so a weak PRNG is a **session/device fingerprint**, not a structural A↔B link unless seeds collide. Still correct to ban `java.util.Random` by type (see P3).

### 5. Coalescing derivation

**Sound for conversation deniability.** Fixed order + coalesced TLS record ≈ 2× frame length does not name a conversation. Equal-length half-indistinguishability is moot when there are no halves. Residual is cosmetic vs the feature’s size-class disclosure. Floor remains best-effort; kdoc matches.

### 6. Surviving `finally`

**Logic is right; production teardown defeats it.** See **P2-1**.

### 7. Registration budget / wiring

Lazy, silent, one job latch + provisioner runtime latch — good against hot-loops and multi-register. **Mid-session backoff expiry is dead on the wired path.** See **P2-2**.

### Contracts (§4.3 / §5 / field set)

Spec U3 row and R-U3-2 match real-first. `DecoySendPairing` does not restate `DecoyState` field set or codec tag-write trigger. Counter/allocator absence matches code. No thirteenth-style parallel false field set found in this unit’s live code.

---

## Findings

### P2-1 — Production teardown disconnects the socket *before* cancellation; `finally` cannot pair on vault lock

**Severity:** P2  
**File:line:**
- `UnlockController.kt:123–148` (`lockCurrent`: `stopSession` then `scope.cancel`)
- `MessagingCoordinator.kt:665–675` (`stop`: `ws.disconnect()` then `coverTraffic.stop()`)
- `WsClient.kt:165–170`, `211–212` (`webSocket = null` → `send` returns `false`)
- `DecoySendPairing.kt:297–305` (the `finally` kept for R-U3-3)

**Concrete failure:**

1. User sends; real publishes; pairing is in `sleep(gapMs())` (5–50 ms).
2. Idle auto-lock / lock: `stopSession` → `ws.disconnect()` → `webSocket = null`.
3. Then `sessionScope.cancel()` → `sleep` throws `CancellationException`.
4. `finally { emit(decoy) }` → `sendMessage` → `false` (null socket). Cover never leaves.
5. Wire: real frame alone, then TLS close. The unpaired real is exactly the marked class `finally` was kept to prevent on “vault lock, teardown, backgrounding.”

`coverTraffic.stop()` only cancels the provisioning job; it does not drain in-flight pairs *before* disconnect.

**Why tests miss it:**  
`cancellation inside the drawn gap still publishes the real frame and still pairs it` cancels the job only; `send` always succeeds. It never runs disconnect-before-cancel. Mutation M4 (“drop finally”) is killed by a path that production lock does not take.

---

### P2-2 — U3’s one-shot job latch kills U1’s mid-session backoff recovery

**Severity:** P2  
**File:line:**
- `DecoySendPairing.kt:367–378` (`provisioningStarted` CAS; single `launch`)
- `DecoySendPairing.kt:325–328` (only entry when `recipient() == null`)
- `DecoyAccountProvisioner.kt:87–91`, `220–234`, `535–541` (latch is one *attempt*, not one *check*; `isDeferred` before CAS)
- `ZitroneApp.kt:1726–1731` (only production caller)

**Concrete failure:**

1. Prior session left durable `provisionNotBeforeMs` (e.g. after 429); backoff 60–90 min.
2. New unlock → new `DecoySendPairing` / new runtime gate.
3. First send while still deferred: `recipient() == null` → `ensureProvisioning()` → CAS wins → one job → `provisionIfNeeded()` hits `isDeferred()` → returns `false` **without** burning `Gate.attempted`.
4. `provisioningStarted` stays `true` for the session.
5. Clock passes `notBefore`; user still unlocked (long session / long idle timeout).
6. Further sends: `recipient()` still null; `ensureProvisioning()` returns immediately; **`provisionIfNeeded` is never called again**.
7. Cover stays off until the *next* unlock, even though U1 explicitly requires the opposite.

U1 pins this with `a back-off window that expires mid-session still gets its one attempt` — two direct `provisionIfNeeded()` calls on the same session. The wired path makes only one call.

**Why tests miss it:**  
`an unprovisioned vault sends uncovered, provisions ONCE, then covers everything` forces success after one in-flight job (`gate.complete`). It never models “first call deferred, second call after clock advances.” Pairing suite never drives a real `DecoyAccountProvisioner`.

This is a **deletion/wiring** defect: U3’s job bound silently un-asserts a U1 property that only shows up when provisioning is reachable.

---

### P3-1 — U3-A process-death wording is false (property still holds)

**Severity:** P3  
**File:line:** `DecoySendPairing.kt:90–93`; test `DecoySendPairingTest.kt:426–432`; fix note U3-A table

**Issue:** “A process can only die at a suspension point” is false for OS kill. What is true: cover introduces **no new suspension** between durability and real publish; cover-first death window is gone.

**Tests:** Sleep-seam assertion is right for *cancellation* and for “only suspension in this class,” not for “process death = suspension points only.”

---

### P3-2 — “Link two vaults” via weak PRNG overstated

**Severity:** P3  
**File:line:** `DecoySendPairing.kt:200–209`

**Issue:** One `SecureRandom` per live vault session; independent seeds do not chain A’s gaps to B’s. Weak PRNG is still bad (predictable gaps, client fingerprint). Type constraint remains correct.

---

### P3-3 — “Zero delay” ignores post-publish `coverFor` on the confined worker

**Severity:** P3  
**File:line:** `DecoySendPairing.kt:223–226`; concurrent test `DecoySendPairingTest.kt:505–529`

**Issue:** No lock wait before reals (claim holds). After real A, non-suspending `coverFor` (keygen + blob) still occupies `Dispatchers.IO.limitedParallelism(1)` before `sleep` yields, so concurrent real B waits that CPU time. Concurrent test only checks virtual time / no `delay` between reals — would pass with a multi-second CPU `coverFor`.

Not R-U3-1 data-loss; latency only.

---

### P3-4 — Cancellation test does not pin the property it names under production teardown

**Severity:** P3  
**File:line:** `DecoySendPairingTest.kt:391–404`

**Issue:** Same as P2-1’s blind spot: test name/kdoc claim vault-lock pairing survival; harness never disconnects. Another guard (always-true `send`) carries the pass.

---

## Claim 1–7 summary (attack results)

| # | Result |
|---|---|
| 1 Structural impossibilities | **Hold** for the four cover-induced P1s (wording nit P3-1) |
| 2 Lock removal | **Clean** — no remaining caller; no teardown dependency |
| 3 Concurrent interleave | **Acceptable** under stated adversary |
| 4 SecureRandom | **Code OK**; vault-link claim overstated (P3-2) |
| 5 Coalescing | **Derivation sound** for conversation deniability |
| 6 `finally` | **Defeated on common teardown path** (P2-1) |
| 7 Registration / whole unit | **Hot-loop safe**; mid-session backoff recovery **broken by wiring** (P2-2) |

---

## What is solid (not findings)

- Real-first body is the entire R-U3-1 argument; no decoy-first branch left to reintroduce by accident.
- `publish: () -> Unit` keeps D2c atomicity compiler-checked at text / attachment / receipt.
- `coverFor` catch-all after publish correctly protects against marking an already-delivered message FAILED.
- Equal-length pairing of every choke-point envelope class is intentional and tested.
- Mutex, order bit, latches, nested finally truly gone; no orphaned lock order.
- Provisioning is lazy, silent, budget-bounded against multi-register; deniability (no device store/logs on this path) holds.
- Mutation story for the 20 pairing tests is credible for *in-class* regressions; blind spots are integration-order and cross-unit wiring (above).

---

**VERDICT: FINDINGS (0 P1, 2 P2, 4 P3)**
