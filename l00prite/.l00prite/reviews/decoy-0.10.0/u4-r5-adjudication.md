# U4 review round 5 — adjudication

**Codex: 1 P1, 0 P2, 2 P3. Grok: 0 P1, 1 P2, 2 P3.** Both lenses independently produced the SAME
top finding and the SAME redial finding — the second convergence of this unit (round 4 was the
first), and this time on findings the round-4 fixes were supposed to have closed.

*Process note: Grok's first round-5 run died mid-review when the session was disconnected
(443 bytes of narration, no verdict). It was re-dispatched blind on the identical prompt and
completed; only the completed run is adjudicated. Codex's run was unaffected.*

| Round | Codex | Grok | Total |
|---|---|---|---|
| 1 | 1 P1, 1 P2, 1 P3 | 2 P1, 2 P2, 1 P3 | 7 |
| 2 | 0 P1, 1 P2, 1 P3 | 0 P1, 2 P2, 2 P3 | 7 |
| 3 | 0 P1, 0 P2, 1 P3 | CLEAN | 1 |
| 4 | 0 P1, 0 P2, 2 P3 | 0 P1, 0 P2, 2 P3 | 4 |
| 5 | 1 P1, 0 P2, 2 P3 | 0 P1, 1 P2, 2 P3 | 6 (4 distinct) |

| # | Lens | Sev | Finding | Verdict | Action |
|---|---|---|---|---|---|
| 1 | Codex U4-R5-1 **+ Grok F1** | Codex P1, Grok P2 → **P1** | The synthetic socket's WHOLE LIFECYCLE writes durable diagnostics: `ZitroneApp` handed `bootDiagnostics.record` as the socket's `diag` sink, so every handshake/connected/closed/failure line from `WsClient` (`WsClient.kt:235–287`) lands timestamped in `boot-diagnostics.log` (`BootDiagnostics.record` → `file.writeText`) and in Settings → Diagnostics. Doubled lifecycle lines and synthetic-only failures are durable evidence a second socket ran on this device; failures surfacing at all violates R-U4-6's "dropped silently" literally | **UPHELD** | `WsSyntheticSocket` no longer HAS a diag parameter — silence is enforced at the type, `WsClient`'s own default `{}` is the sink; the `ZitroneApp` wiring line is deleted; the durable-diagnostic tripwire now bans the bare token `diag` in U4 files AND scans the production construction block |
| 2 | Grok F3 (Codex folded it into R5-1) | P3 | **Requirement defect:** R-U4-3 as written ("no NEW persisted field, no NEW writer") permits *reaching* an existing durable writer — round 4 already showed this class and the requirement was not updated, so row 1 was compliant with R-U4-3's letter while violating the product | **UPHELD** | Spec §4.4 R-U4-3 reworded: "…and it invokes no *existing* durable writer either, diagnostic sinks included", with both instances recorded in the requirement text; the falsification check now includes the construction wiring |
| 3 | Codex U4-R5-2 **+ Grok F2** | P3 | The restored redial tripwire pins `redial > gateEnd` — string geometry, not the property. A second gate or a bare `return` between the real gate's closing brace and the synthetic redial re-gates the redial on the real socket's state (round 1's P1) with the assertion green | **UPHELD** | The segment between the gate's closing brace and the redial must now match `^\s*\}\s*$` — nothing but the brace; any code there has to change the test consciously |
| 4 | Codex U4-R5-3 (Grok: same residual, unscored) | P3 | Reflection escapes every disconnect scan: `javaClass.getMethod("disconnect").invoke(ws)` contains neither `disconnect()` nor `::disconnect`, works from any file, and a helper in the exempted file taking `Any` inherits the ownership exemption | **UPHELD** | New tripwire: zero reflection-lookup tokens in either U4 file; app-wide ban on the string literal `"disconnect"` (every reflective route needs the name as a string; no legitimate use exists). Residual declared: a concatenated name still slips a lexical scan |

**All four upheld. Nothing argued down.**

## Row 1 severity: P1 (Codex) over P2 (Grok), and why

Grok's mitigation is real — the lines are generic (`ws: connected`), shared with the real socket's
legitimate connectivity UX, so no single line says "cover". But three things push it over:

1. **It is unconditional.** Round 4's `diag()` line fired only when a cover envelope actually
   arrived. This fires on **every unlock of every vault with a decoy relay** — two interleaved
   handshake sequences at every session start, reconnect churn from both sockets, and failure
   lines only the synthetic socket can produce (its token expiring mid-session) — for the lifetime
   of the log's rotation window.
2. **Attribution needs no single line**, just the pattern: a device whose diagnostics show two
   concurrent socket lifecycles, on an app whose public builds open one, is a device running the
   cover path. The vault exists to deny exactly that inference.
3. **It is a literal R-U4-6 violation** ("a failed … connection is dropped silently … never
   surfaced"), not only a deniability-class judgement.

Round 4 treated the guard's single diag line as P2 because it was one conditional line; the same
class made unconditional and lifecycle-wide is the top of this unit's severity range. Either rating
compels the identical fix before merge, so nothing turns on the label — recorded for consistency of
the record, not to settle a dispute the fix makes moot.

## The round-4 lesson, recurring

Round 4 closed `diag()` **in the guard** and added a sink ban **in the U4 files**. The bigger
instance of the same defect sat one construction site away in `ZitroneApp`, invisible to both
because the parameter is named `diag` but never *called* in a scanned file. Two rounds in a row now,
the finding was not "the guard is absent" but "the guard's scope is narrower than its claim" —
which is the precise failure mode the U3 record already names for lexical tripwires. The structural
response this round is different in kind, not just wider in scope: the `diag` **parameter is
deleted**, so there is no longer a sink to mis-wire; the tripwires are the backstop, not the fence.

## Evidence

`ANDROID_HOME=/opt/android-sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug --rerun-tasks`
→ **BUILD SUCCESSFUL, Gradle exit 0, 799 tests / 0 failures / 0 errors / 3 skipped** (798 → 799:
one new tripwire, `the U4 files use no reflection at all`). Run twice: once as the post-fix
baseline, once after the mutation sweep to prove the restores left the tree green.

**Fix-targeted mutations: 5 applied, 5 discriminated — each by exactly the test built to catch it,
each restored by reverse-edit and verified by checksum against the recorded pre-mutation SHA-256**
(the fixes were uncommitted, so `git checkout` restores were not available; the checksum file is
the guarantee no mutation half-survived, which is the U3 harness lesson applied):

| # | Mutation | Caught by |
|---|---|---|
| M1 | Full defect reintroduced: `diag` param on `WsSyntheticSocket`, forwarded into `WsClient`, wired to `bootDiagnostics.record` in `ZitroneApp` | `no U4 surface writes a durable diagnostic about cover traffic` |
| M2 | Param only, unused — `diag: (String) -> Unit = {}` back on the constructor, nothing wired | same test (the bare-token ban; the old `diag(` ban passed this) |
| M3 | Synthetic redial wrapped in a second real-socket connection-state gate after the first gate's brace — round 1's P1, verbatim | `the synthetic redial is not gated on the real socket's connection state` (the old `redial > gateEnd` assertion passed this) |
| M4 | `internal val classProbe = this.javaClass` in `WsSyntheticSocket` — reflection token, no string literal | `the U4 files use no reflection at all` |
| M5 | `private val reflectName = "disconnect"` in `MessagingCoordinator` — the literal in a NON-U4 file | `every socket disconnect in the app goes through cover traffic` (the new app-wide literal ban) |

M2, M3 and M5 are the ones that matter most: each is an evasion a round-5 lens named, applied
verbatim, that the round-4 guards demonstrably let through and the round-5 guards demonstrably do
not.
