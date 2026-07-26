# Design contract — Registration "squeeze the lemons" progress screen

**For whoever builds the real animation. You need no other file, no prior context, and no
knowledge of this project. Everything required is here.**

The component you are replacing is `RegistrationPowScreen.kt`, in this same directory. It is a
deliberately plain placeholder — a text label and a progress bar — so the feature could be
tested before the real art existed. **You are replacing the rendering. You are not replacing
the plumbing.**

---

## 1. What this screen is

Zitrone is an encrypted messenger. It does not ask for a phone number or an email address, so
it needs some other way to stop one person from creating ten thousand accounts. Instead of
identifying the user, the app makes their **device** do a chunk of math that takes a few
seconds. That is cheap for one honest person creating one account, and expensive for someone
creating accounts in bulk.

**This screen is what the user looks at while that math runs.** It appears exactly once, during
account creation, and then never again. It usually lasts a few seconds. On old or throttled
phones it can last a minute or more.

That is the entire feature, from your side. **You are building a progress indicator that
receives numbers and renders them.** You do not need to understand — and should not need to
reason about — cryptography, networking, or security to build this well. If anything below
seems to require that, the contract is underspecified and you should push back rather than
guess.

## 2. The metaphor and the brand

The product's identity is lemons. The animation is **lemons being squeezed into a pitcher**,
with the pitcher filling as progress advances. That's the concept; the execution is yours.

- **Audience:** privacy-literate adults. They chose an encrypted messenger on purpose. They
  will read the copy and understand it. Do not talk down to them, do not over-explain, do not
  add reassurance they didn't ask for.
- **Tone:** dry and a little wry. The subline is a joke; let it be one. Nothing here should
  feel like a security warning or an error.
- **The app is dark-only.** There is no light theme, and the system light/dark setting is
  deliberately ignored. **Never use a white or near-white background.** The lightest background
  value permitted anywhere in this app is `#1A1800`.
- **Lemon yellow `#F5E642` owns all interactivity.** Nothing interactive is ever blue.

### Design tokens — use these, do not invent new ones

All in `app/src/main/java/com/zitrone/app/ui/theme/` (`Color.kt`, `Type.kt`, `Theme.kt`).

**Core palette**
| token | value | |
|---|---|---|
| `Lemon` | `#F5E642` | the brand yellow; all interactivity |
| `LemonBright` | `#FFE500` | |
| `LemonDeep` | `#D4C200` | |
| `LemonPale` | `#FFFDE0` | |
| `LemonZest` | `#E8B800` | |
| `Pulp` | `#FFF8C0` | |
| `Rind` / `RindSoft` | `#2A2500` / `#3D3800` | |

**Semantic**
| token | value | |
|---|---|---|
| `BackgroundPrimary` | `#0D0C00` | |
| `BackgroundSecondary` | `#1A1800` | lightest permitted background |
| `BackgroundElevated` | `#242100` | |
| `TextPrimary` | `#FAFAF2` | |
| `TextSecondary` | `#A8A070` | sublines, secondary detail |
| `TextMuted` | `#5A5630` | |
| `TextOnLemon` | `#0D0C00` | text on a lemon fill |
| `BorderColor` / `BorderActive` | `#2E2B00` / `#F5E642` | |

**Motion** (`Theme.kt`, `object Motion`) — `DurationFastMs` 120, `DurationBaseMs` 200,
`DurationSlowMs` 400, `DurationDramaticMs` 600; easings `EasingDefault`, `EasingBounce`,
`EasingBurn`.

**Type** (`Type.kt`) — `DisplayFamily`, `BodyFamily`, `MonoFamily`; scale `Xs` 12 → `Hero` 48sp.

There is an existing particle animation in this directory (`BurnParticles.kt`) if you want a
sense of the house style for motion.

## 3. The copy — FINAL, verbatim, not yours to revise

These strings are set by the product owner and are already in the code as
`RegistrationPowCopy`. **Use them exactly. Do not reword, shorten, sentence-case, or "improve"
them.** Lowercase starts are intentional.

- **Primary:**
  > proving your device is real so we don't need your phone number

- **Subline:**
  > you have to squeeze a few lemons to get lemonade

- **Shown at 60 seconds:**
  > this is taking longer than expected — your device may be in battery saver or under heavy
  > load. Try again with the app in the foreground, or plugged in.

- **The two options on that prompt:** *keep waiting* / *try later*

If you believe a string is wrong, raise it — don't silently change it.

## 4. What you are given

One immutable value, re-emitted as work progresses. You render it. You derive nothing.

```kotlin
data class RegistrationPowUiState(
    val state: RegistrationPowState,      // IDLE, SOLVING, PROMPTED_AT_60S,
                                          // BACKGROUNDED, COMPLETE, CANCELLED
    val fractionOfExpectedWork: Float,    // progress — see the hard constraints
    val elapsedSeconds: Long,             // display only; must NOT drive progress
)
```

and two callbacks, `onKeepWaiting: () -> Unit` and `onTryLater: () -> Unit`, which you invoke
when the user taps those options. What they do is not your concern.

## 5. What you must NOT own

Not "should avoid" — these live elsewhere and wiring them into the UI is a defect:

- the solve loop itself
- cancellation semantics
- the foreground service
- challenge lifecycle (fetching, expiry, refresh)
- anything network

## 6. Hard constraints

Each has a reason. The reasons are short because you shouldn't need the history.

### 6.1 Progress tracks actual work, never elapsed time
`fractionOfExpectedWork` is computed from the real count of operations completed. **Never
animate the bar against a timer, and never interpolate toward a deadline.** A phone can be 90%
done at 60 seconds, or 10% done — those are both normal, and a time-based bar lies about both.

### 6.2 Progress CAN EXCEED 1.0, and must keep moving when it does
This is the constraint most likely to be missed, so: the amount of work needed is *random*, and
`1.0` is the **average**, not the maximum. Roughly **37% of users will pass 1.0**, and about
**5% will pass 3.0**.

- Do not clamp the visual into a stalled full pitcher. A user watching a full pitcher and
  nothing happening reads it as a hang — worse than an honest slow bar.
- Decide deliberately what "overfull" looks like. Overflowing the pitcher, a slow shimmer, a
  second pitcher, a switch to indeterminate — your call, but it must communicate *still
  working*, not *stuck* and not *broken*.
- The placeholder handles this crudely (clamps the bar, appends "unlucky — still going"). Do
  something better; just don't do nothing.

### 6.3 The 60-second prompt is non-blocking, over still-running work
At 60 seconds `state` becomes `PROMPTED_AT_60S`. **The solve does not pause, slow, or restart.**
It is still going the entire time the prompt is up.

- Render the prompt *over* or *beside* live progress — the progress must remain visible and
  must keep advancing.
- **"Keep waiting" changes nothing about the solve.** It only dismisses the prompt. Do not
  treat it as a resume, retry, or restart — there is nothing to resume.
- **"Try later" aborts cleanly.** Just call `onTryLater()`.
- Work already done is never thrown away by anything in this screen.

### 6.4 Respect reduced motion
If the user has set animation scale to zero, honour it: no looping motion, no particles, no
continuous animation. Show progress as a static, updating fill. A helper is provided —
`rememberReducedMotion()` in the placeholder file — already wired to the right system setting.

### 6.5 Backgrounding
The user may leave the app mid-solve. The work continues in a background service with a
persistent notification; the notification is the progress indicator while the app is away.

- On `BACKGROUNDED`, tell the user the work continues and it's safe to leave. Placeholder copy
  is `"Still working. You can leave this screen — we'll finish in the background."` — this one
  is **not** locked; improve it if you can.
- **On return, resume rendering from the current state.** Do not replay the animation from
  zero, do not re-run an intro. The pitcher should be as full as the work actually is. A
  restarted animation reads as lost progress.
- `COMPLETE` may arrive while backgrounded; returning to a finished state must look finished,
  not skip to a celebration mid-flight.

## 7. Done means

- Copy in §3 rendered verbatim.
- Progress driven only by `fractionOfExpectedWork`, correct and legible past 1.0.
- The 60s prompt appears over live, still-advancing progress; both options wired to their
  callbacks.
- Reduced motion honoured.
- Background → return resumes rather than restarts.
- Dark-only, on-token, no background lighter than `#1A1800`.
- Drop-in: same state type, same copy constants, same callback shape.
