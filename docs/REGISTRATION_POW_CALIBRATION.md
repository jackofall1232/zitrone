# Registration proof-of-work — Argon2id calibration (0.9.4-beta)

Status as of 2026-07-27: **BOTH SIDES MEASURED — calibration RESOLVED at D=5.** The floor
device (Revvl 6x, battery saver ON, foreground) was measured through the Diagnostics-screen
`pow:` lines of a real registration on the `test-pow-d6b12587` cut; see "Measured — client
side" below. The 0.9.4 client ships `RegistrationPow.DEFAULT_PARAMS` = hashcash d=20,
19 MiB/t=1, **D=5**, and the relay env must pin the same four values at flip time.
`REGISTRATION_POW_ENABLED` stays `false` until every test device is on 0.9.4 (deploy
runbook step 5).

History: the first cut shipped D=4 as an unmeasured attempt; the instrumented recorder
(`diagnostics/RegistrationPowSolveRecorder`) it carried is what produced the measurement —
one registration on the device, no `adb`, no gradle harness — and D moved 4→5 by the target
rule below. The recorder stays: any future difficulty change is re-measured the same way.

## What the scheme actually costs, structurally

Read `server/internal/regpow/regpow.go` first. The load-bearing fact for calibration:

> **The Argon2id stage is a leading-zero PUZZLE, not a single evaluation.** The client searches
> `ArgonNonce` until `Argon2id(challenge||identityKey, nonce)` has `Argon2DifficultyBits` leading
> zero bits. So the **client runs ~2^D Argon2id evaluations; the relay runs exactly ONE.**

That asymmetry is the whole design. It also means:

| | cost |
|---|---|
| Client solve | `2^D × ms_per_eval(m, t)` on the *client's* hardware |
| Relay verify | `1 × ms_per_eval(m, t)` on the *relay's* hardware, + `m` MiB resident |

`D` is the tuning knob. `m`/`t` set the per-evaluation cost on **both** sides at once — raising
them to slow the client also slows the relay, one-for-one. Prefer tuning `D`.

**Solve time is geometrically distributed, not fixed.** `2^D` is the *expected* number of
evaluations; the actual count has a long tail. Roughly 37% of solves take longer than expected,
~5% take more than 3× expected, ~1% more than 4.6×. This is not a defect to engineer away — it is
inherent to hashcash-style puzzles — but it has two direct consequences:
- it is the real justification for the 60-second prompt (a fraction of users *will* hit it even
  with a well-chosen `D`);
- **UI progress can legitimately exceed 100%.** See the UI contract; this is why progress is
  "fraction of *expected* work" and must not be clamped in a way that stalls at a full pitcher.

## Measured — relay side

Host: **CX33 dev box**, AMD EPYC-Rome, 4 vCPU, 7 GiB RAM. Go 1.25, `golang.org/x/crypto/argon2`
(v0.52.0), `IDKey`, p=1, 32-byte output — the exact primitive and parameters `regpow.Verify` uses.

⚠️ **CX33 is not CX23.** These are the correct *primitive* and *parameters* but not the production
relay's hardware. Re-measure on CX23 before the enforcement flip, or treat these as an upper bound
on throughput only if CX23 is known to be at least as fast.

Full sweep, p=1, 32-byte output:

| mem | t=1 | t=2 | t=3 | t=4 |
|---|---|---|---|---|
| 8 MiB | 9.0 ms | 17.9 ms | 23.7 ms | 24.3 ms |
| 16 MiB | 17.2 ms | 34.1 ms | 38.5 ms | 53.4 ms |
| 32 MiB | 46.4 ms | 54.9 ms | 78.0 ms | 100.1 ms |
| 64 MiB | 63.7 ms | 107.0 ms | 153.8 ms | 205.8 ms |
| 128 MiB | 135.5 ms | 224.7 ms | 363.9 ms | 440.4 ms |

Focused second pass at t=1 (12 reps/point, warmed):

| mem | 16 | 19 | 24 | 32 | 48 | 64 |
|---|---|---|---|---|---|---|
| ms/eval | 19.8 | **23.2** | 24.5 | 33.3 | 47.1 | 59.9 |

Reference points from the same box: `19 MiB/t=2` = 34.4 ms, `19 MiB/t=3` = 49.1 ms,
`32 MiB/t=2` = 53.6 ms. SHA-256: **10.01 MH/s single core**, so the shipped hashcash difficulty 20
(~1.05 M expected hashes) is **0.10 s** here.

The vault's own derivation params (64 MiB / t=3) cost **153.8 ms** — recorded because those params
must *not* be reused here, and it is useful to see why: they are ~6.6× the placeholder's cost.

## Measured — relay verification at volume

Per concurrent verification the relay pays **one evaluation of latency and `m` MiB of resident
memory**. At the placeholder `19 MiB / t=1`:

- 23.2 ms → **43 verifications/sec/core**; 4 cores → ~172/sec if memory allows
- memory is the binding constraint: `concurrent_verifies × 19 MiB`. 172 concurrent = 3.3 GiB.

**Steady-state volume is a non-issue.** Registration is ~2 per user (slot A + slot B). A 500-user
beta week = ~1000 registrations = ~23 seconds of total CPU. Sizing is driven entirely by burst and
abuse, not by legitimate load.

### Two findings that follow from the numbers

1. **The relay MUST bound Argon2id concurrency with a semaphore, and that bound is a memory
   decision.** Unbounded concurrency at 19 MiB/verify is an OOM vector, and OOM on the relay is a
   full outage, not a degraded registration path. Size the semaphore as
   `memory_budget / Argon2MemoryKiB` and return a retryable status when it is saturated. This is
   not currently in `regpow.go` (which is a pure verify function — correctly, it is the *call site*
   in `handlers.go` that owns admission).

2. **The SHA-256 pre-stage does not protect the Argon2id stage from a GPU-equipped attacker, and
   should not be relied on to.** At difficulty 20 the pre-stage costs a *CPU* attacker ~0.10 s to
   force ~23 ms of relay Argon2id — already only ~4:1 in the defender's favour. On a GPU, SHA-256
   runs several orders of magnitude faster, so the pre-stage cost collapses to near-zero while the
   relay's memory-hard cost does not move at all. The pre-stage is still worth keeping — it is free
   reuse and it does stop unauthenticated *garbage* from reaching Argon2id — but the actual DoS
   defences are **(a)** the concurrency semaphore above and **(b)** rate-limited challenge issuance.
   Do not raise the pre-stage difficulty expecting it to solve this; that taxes honest phones on the
   floor device far more than it taxes the attacker.

## Measured — client side (Revvl 6x, 2026-07-27). RESOLVED: D=5.

Source: Diagnostics-screen `pow:` lines from a real registration on the `test-pow-d6b12587`
cut (maintainer's device). Conditions recorded by the lines themselves:
**battery_saver=true, foreground=true, backgrounded_mid_solve=false** — the worst legitimate
condition, which is the one being calibrated.

| stage | observed | derived rate | expected at full difficulty |
|---|---|---|---|
| SHA-256, d=20 | 455,763 hashes in 725 ms | **0.63 MH/s** | 2^20 hashes ≈ **1.67 s** |
| Argon2id, 19 MiB/t=1 | 7 evaluations in 257 ms | **36.7 ms/eval** | 2^D evals: D=4 ≈ 0.59 s, **D=5 ≈ 1.17 s**, D=6 ≈ 2.35 s |

**Calibrate on the rates, not the observed total.** That run completed in 982 ms because it
drew ~0.43× the expected work on *both* stages — both searches are geometric, and a
single draw (or a small average of draws) is not the expectation. Casual repeat runs
averaging ~1 s are consistent with normal-mode (non-battery-saver) expectation, not with
the floor.

Applying the target rule (expected solve ≈ 3 s at the floor), **now against the measured
total including the pre-stage**:

| D | expected total (battery saver) | ~5% tail | attacker cost / account (CX33-class core) |
|---|---|---|---|
| 4 | ~2.3 s | ~7 s | ~0.48 s |
| **5** | **~2.8 s** | **~8 s** | **~0.85 s** |
| 6 | ~4.0 s | ~12 s | ~1.6 s |

**D=5** is the largest D that keeps the battery-saver expectation inside "a few seconds";
normal-mode expectation is roughly half that. The tail stays far under the 60 s prompt, and
the pitcher UI renders past-1.0 progress honestly by design.

### Finding from the device numbers: the pre-stage taxes the phone 16×, Argon2id only 1.6×

The Revvl's SHA-256 rate is **16× slower** than the CX33 core (0.63 vs 10.01 MH/s), while its
Argon2id evaluation is only **1.6× slower** (36.7 vs 23.2 ms). The memory-hard stage travels
across hardware exactly as intended; the compute-bound pre-stage does not — at d=20 it costs
the honest floor device ~1.7 s (over half the total) while remaining near-free for the
GPU-equipped attacker finding 2 already describes. **Future rebalance candidate, deliberately
NOT taken in the 0.9.4 cut:** lower the pre-stage (e.g. d=18 ≈ 0.4 s on the floor) and raise
D one more step, shifting honest-user cost into the stage that actually deters. Not now
because d=20 is the production-proven dead-drop constant, the current split already lands on
target, and changing two knobs at once would re-open a calibration this measurement just
closed.

### How the measurement was almost mis-read (kept as a warning)

An x86 emulator would not have been a valid substitute — different ISA, different memory
subsystem, and no battery-saver thermal throttling, which is precisely the condition
calibrated. Equally, the observed 982 ms total would have been a wrong calibration input:
the derived rates above are what carry.

### The gradle harness — written, compiles, ultimately NOT the channel used

The in-app recorder above delivered the calibration without it. It stays for when a full
`(m, t)` sweep on a device is ever wanted (the recorder only measures the shipped params):

`apps/android/app/src/androidTest/java/com/zitrone/app/RegistrationPowCalibrationTest.kt`
(verified: `:app:compileDebugAndroidTestKotlin` exit 0, class file emitted).

It sweeps the same grid through the **production** libsodium path (`crypto_pwhash`,
`ALG_ARGON2ID13`, p=1) and records `Build.MODEL`, power-save state, charging state and thermal
status in its own output, so a result carries evidence of the conditions it was taken under. It
asserts nothing about timing — a timing assertion would be flaky on exactly the throttled hardware
that matters.

```bash
# Phone: Settings > Battery > Battery Saver ON, and UNPLUGGED
# (charging suppresses battery saver on many OEM skins, TCL's included).
cd apps/android
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.zitrone.app.RegistrationPowCalibrationTest
adb logcat -d -s PowCalib:I
```

A run reporting `POWER_SAVE=false` is not the floor — rerun it.

### The pre-measurement procedure (SUPERSEDED — kept to show what the measurement changed)

The planned rule: pick the smallest `D` satisfying `2^D × ms_eval_revvl ≈ 3000 ms` (target: a
few seconds at the floor), then sanity-check the tail. **The measurement changed the rule's
input:** on the device the SHA-256 pre-stage is NOT negligible (~1.7 s, over half the total),
so the 3 s target is applied to the *whole solve*, which is how D=5 landed rather than the
argon-only D=6 this rule would have produced.

Projection table at the placeholder `19 MiB / t=1`, from the CX33 figure of 23.2 ms/eval. The
right-hand columns were illustrative phone-slowdown guesses; the measured reality split them —
**1.6× for Argon2id, 16× for SHA-256** — which is itself the rebalance finding above:

| D | expected evals | solve @CX33 | @5× slower | @12× slower |
|---|---|---|---|---|
| 2 | 4 | 0.1 s | 0.5 s | 1.1 s |
| 3 | 8 | 0.2 s | 0.9 s | 2.2 s |
| 4 | 16 | 0.4 s | 1.9 s | 4.4 s |
| 5 | 32 | 0.7 s | 3.7 s | 8.9 s |
| 6 | 64 | 1.5 s | 7.4 s | 17.8 s |
| 7 | 128 | 3.0 s | 14.8 s | 35.6 s |
| 8 | 256 | **5.9 s** | **29.7 s** | **71.2 s** |

### Finding: the shipped placeholder default is far too high

`config.go` defaults `REGISTRATION_ARGON2_DIFFICULTY_BITS` to **8**. That is 256 expected Argon2id
evaluations — **5.9 seconds on a 4-core server**. A throttled budget phone is unambiguously slower
than an EPYC core, so the floor-device solve time at D=8 lands in the tens of seconds, plausibly
past 60 s, on *every* registration — and the 37%-over-expected tail lands well past it. The settled
copy ("squeeze a few lemons", implying seconds) would become the "small lie" the spec brief
explicitly warns about.

This does not require the Revvl number to conclude: **D=8 is wrong by a wide enough margin that no
plausible phone measurement rescues it.** The likely landing zone is **D=4–5** at 19 MiB/t=1, to be
confirmed — not decided — by the on-device run. The placeholder was correctly labelled unmeasured;
this is that label being cashed in, not a defect in the relay code.

*2026-07-27: confirmed. The on-device run landed it at D=5 (see "Measured — client side").*
