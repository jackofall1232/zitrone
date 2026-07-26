# Registration proof-of-work — Argon2id calibration (0.9.4-beta)

Status as of 2026-07-26: **relay side MEASURED. Client side NOT MEASURED — blocked on hardware.**
The difficulty constants are therefore still `TODO(pow-calibration, unmeasured)` and
`REGISTRATION_POW_ENABLED` must stay `false`.

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

## NOT measured — client side. This is the blocker.

**The Revvl 6x measurement could not be taken in this session: no Android device is attached to
CX33** (`adb devices` empty, no emulator images installed). The Revvl is with the maintainer.

No number was estimated in its place. An x86 emulator would not be a valid substitute — different
ISA, different memory subsystem, and no battery-saver thermal throttling, which is precisely the
condition being calibrated for.

### The harness is written and compiles — it needs a phone, not more work

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

### Completing the calibration is then one substitution

With `ms_eval_revvl` at the chosen `(m, t)`, pick the smallest `D` satisfying
`2^D × ms_eval_revvl ≈ 3000 ms` (target: a few seconds at the floor), then sanity-check the tail:
`3 × that` should still be tolerable, and only ~5% of users will see it.

Projection table at the placeholder `19 MiB / t=1`, from the CX33 figure of 23.2 ms/eval. **The
right-hand columns are illustrative phone-slowdown ratios, NOT measurements:**

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
