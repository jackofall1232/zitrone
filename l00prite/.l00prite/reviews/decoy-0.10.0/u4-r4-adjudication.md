# U4 review round 4 — adjudication

**Codex: 0 P1, 0 P2, 2 P3. Grok: 0 P1, 0 P2, 2 P3.** No P1 or P2 from either lens, second round running.

| Round | Codex | Grok | Total |
|---|---|---|---|
| 1 | 1 P1, 1 P2, 1 P3 | 2 P1, 2 P2, 1 P3 | 7 |
| 2 | 0 P1, 1 P2, 1 P3 | 0 P1, 2 P2, 2 P3 | 7 |
| 3 | 0 P1, 0 P2, 1 P3 | CLEAN | 1 |
| 4 | 0 P1, 0 P2, 2 P3 | 0 P1, 0 P2, 2 P3 | 4 |

| # | Lens | Sev | Finding | Verdict | Action |
|---|---|---|---|---|---|
| 1 | Codex U4-R4-1 | P3 → **treated as P2** | The R-U4-1 guard called `diag()`, which `BootDiagnostics.record` writes to `boot-diagnostics.log` on disk and surfaces in Settings → Diagnostics — a durable, timestamped, user-copyable record that this device received cover traffic | **UPHELD** | `diag()` removed; a tripwire forbids every logging sink in the guard and in both U4 files |
| 2 | Codex U4-R4-2 | P3 | The disconnect-ownership scan matches the token `disconnect()`, so `val d = ws::disconnect; d()` walks past it | **UPHELD** | A second scan forbids `::disconnect` anywhere in the app |
| 3 | Grok U4-R4-1 | P3 | **My round-3 edit deleted the redial-independence tripwire, and my round-3 adjudication recorded the loss as closed when only one of two deleted tests had been restored** | **UPHELD** | Restored, and it now pins *position* rather than token presence |
| 4 | Grok U4-R4-2 | P3 | The exemption is structural for the wrapper's own client, but a same-file helper `fun disconnectClient(ws: WsClient)` inherits the file's carve-out and can close the REAL socket from any caller | **UPHELD** | No `WsClient`-typed declaration may appear anywhere in that file, not just its constructor |

**All four upheld. Nothing argued down.**

## Row 1 is rated P3 by the lens and treated as P2 here, deliberately

Codex filed it against R-U4-3's "adds no durable-state writer" and judged the requirement merely
*too weak*. In this product it is more than that. **Plausible deniability is the feature.** A
timestamped on-disk line stating that a cover-account envelope arrived is evidence that *this
device* ran cover traffic — which implies a vault with a provisioned synthetic account exists here —
and it survives the process that wrote it. That is precisely the class of artefact the vault exists
to prevent, and the rest of the decoy code already holds the discipline: the pairing, the builder and
the provisioner take no logger at all and fail silent. **My guard was the single place in U4 that
broke it**, and it was introduced by U4 rather than inherited.

## Row 3 is a correction to this project's own record, and it should read as one

Round 3's adjudication says the mutation sweep caught a silently deleted tripwire. It caught **one**.
My edit had removed **two** adjacent tests — the `rate_limited` channel pin and the redial-independence
pin — and I restored the one the sweep pointed at, then wrote up the loss as closed. Grok found the
other by reading the unit rather than the delta.

Two things follow, and both are worth more than the finding:

1. **A mutation sweep proves the mutations you wrote are caught. It says nothing about a guard that
   no longer exists**, because there is nothing left to mutate. Deletion is invisible to the very
   technique that found the deletion — it only surfaced because the *rate_limited* pin happened to
   be on my mutation list. The general defence is not a better sweep; it is that the test count and
   the test *names* are part of the diff to be reviewed.
2. **Restoring what an error revealed is not the same as repairing the error.** I fixed the instance
   the tool showed me and generalised nothing.

## Evidence

`ANDROID_HOME=/opt/android-sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug --rerun-tasks`
→ **BUILD SUCCESSFUL, Gradle exit 0, 798 tests / 0 failures / 0 errors / 3 skipped.**

*(The first attempt at this run reported exit 1 with all 798 tests passing — the Gradle daemon had
crashed, not the build. Recorded because "exit 1, zero failures" is exactly the shape that gets
waved through as a flake, and the honest response is to re-run rather than to report the test
numbers alone.)*

**Fix-targeted mutations: 5 applied across two sweeps, 5 discriminated** (3 for Codex's rows, 2 for
Grok's). The restored redial pin now asserts the synthetic reconnect sits *outside* the real socket's
connection-state gate — position, not presence — because re-nesting it keeps every token in place
while reinstating round 1's P1.
