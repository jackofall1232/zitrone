I'll review the post-cap docs branch against the unit: comments vs code, stale claims, §4.1 accuracy, and whether any non-doc changes snuck in.Focusing on the four substantive docs/comment files and verifying each claim against the code.Verifying claims against implementation: tag write paths, `mutate`/`flush` durability, and a stale-claim sweep.Reading the provisioner and runtime paths that the kdoc claims to describe.Checking residual summary claims that may still assert the old registration-only trigger.## Review: `docs/0.10.0-u1-post-cap-comments`

**Scope checked:** `git diff feat/0.10.0-decoy-u1-provisioning..docs/0.10.0-u1-post-cap-comments` against `DecoyAccountProvisioner`, `VaultRuntime.mutate` / `flushBeforeAck`, `DecoyState.isEmpty` / codec omit, plus a claim-level tree sweep.

**Production code:** none. Kotlin deltas are kdoc-only (0 non-comment lines). Remainder is spec, `failures.md`, and review archives.

---

### What holds

| Item | Verdict |
|---|---|
| **`DecoyRelayApi.kt` kdoc** | Matches code. Credential commit is one `mutate` + `flushBeforeAck`; `mutate` only schedules (`VaultRuntime` “no I/O here”). |
| **§4.1 user-facing v6** | True both ways under the deliberate **may**. Attempt (including failed/interrupted) → may break 0.9.x; cleanly-retired attempt is not forced into “affected”; never-touched vault is the only guaranteed safe set; “if unsure, assume affected” is the backstop. |
| **Crash / failed-retirement tag path** | Matches `reserveBackoff` (mutate+flush) → work → `clearBackoff` only when `!registrationSpent` (mutate+flush; throw leaves deferral) → empty holder omitted via `isEmpty`. |
| **No sneaky logic** | Clean. |

---

### Findings

### P2 — residual “register is the trigger” in the file that claims to be the truth table

**`VaultState.kt:289–293`**

Lead-in still asserts:

1. pre-`register` failure **always** retires the deferral and omits the tag;  
2. “the durable trigger is therefore **provisioning that reaches relay registration**”.

The bullets this branch added (`:299–302`) immediately falsify both under crash / failed retirement flush. Same defect class the pass exists to kill: list corrected, **summary sentence not re-derived**. This block also says “the precision is HERE” (`:308–309`), so the stale lead-in is the dangerous copy.

---

### P2 — same claim still live in the spec’s blast-radius note

**`docs/design/DECOY_TRAFFIC_0.10.0_SPEC.md:398–405`**

Still:

- “The trigger is setup that **REACHES THE RELAY**”
- “it is attached **from `register` onwards**”
- “a vault whose first attempt failed offline does **not**” [carry the tag]

No crash / failed-flush caveat. Tree-wide restatement of the corrected claim; not touched by this branch’s §4.1 rewrite, so it survived the pass.

---

### P3 — “has set up” still understates

**`DECOY_TRAFFIC_0.10.0_SPEC.md:390`**

> `TAG_DECOY` appears **only in a vault that has set up cover traffic.**

Present-perfect “set up” excludes write-ahead-only / crash-before-register vaults that **do** carry the tag. Same miscategorisation mode third-lens killed in v5.

---

### P3 — historical parenthetical states a false present fact

**`DECOY_TRAFFIC_0.10.0_SPEC.md:483–484`**

> the tag is written only once cover traffic has actually been **generated**

False as code description: tag is write-ahead / registration, not first decoy send. Framed as why the first draft was wrong, so it still teaches the wrong trigger.

---

### P3 — internal contradiction on whether §4.1 tracks the boundary

**`VaultState.kt:306–309`**

First sentence: move a path across `register` → **§4.1’s user-facing sentence changes**.  
Next sentences: §4.1 **deliberately no longer states a precise boundary**.  

Under v6 the first clause is false; only the internal table must move.

---

### P3 — process banner stale next to ratified v6

**`DECOY_TRAFFIC_0.10.0_SPEC.md:487–488`**

Table block still says “PENDING MAINTAINER RE-RATIFICATION” / “THIRD pass” while the disclosure above is “SIXTH PASS — RATIFIED … FINAL”. Table body itself (including crash row) is fine; banner is process-stale.

---

### Claim sweep (mutate durability / no-tag-before-register)

| Claim | Result |
|---|---|
| “`mutate` is durable” / “one durable mutate” as live assertion | **Gone** from decoy path. `DecoyRelayApi` corrected; `DecoyAccountProvisioner` / `DecoyCounterReservation` already correct. `DecoyAuthStore` says “ONE mutate” for atomicity only — not a durability claim. |
| “no tag before `register`” as absolute | **Still present** in `VaultState` lead-in and SPEC `:398–405` (findings above). Corrected forms exist in the same files’ bullets/table. |

---

### §4.1 specifically

User-facing sentence is sound. “May” correctly covers clean retirement without overclaiming. Behaviour mapping (register **or** unretired write-ahead) matches the provisioner.

Residual risk is not that sentence — it is parallel prose that still teaches the old absolute.

---

**VERDICT: NOT CLEAN** — two P2 residual restatements of “trigger = reach `register` / no tag on offline failure” (one in the authoritative `VaultState` lead-in, one in SPEC §4 blast-radius), plus minor P3s. `DecoyRelayApi`, §4.1 v6 wording, and “docs-only” claim check out.
