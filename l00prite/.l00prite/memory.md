# Zitrone — Project Memory

Durable project facts and decisions future agents should preserve. Deeper rolling review detail
for the vault arc also lives (LOCAL to the box, not in-repo) at
`/root/l00prite/zitrone-vault-ledger.md` and in Claude auto-memory (`zitrone-*` files). This
file is the in-repo durable summary.

## Decisions
- **Zero-knowledge server is non-negotiable.** The relay stores only opaque ciphertext and can
  prove no linkage. Deleting a ciphertext row is the shred; there is no key-escrow, no plaintext.
- **Box-role separation.** CX23 = production relay ONLY (deploy/restart happens there, by a
  human). CX33 (`ubuntu-4gb-hel1-3`, this box) = dev/build ONLY. Never deploy/restart the prod
  relay from CX33; server code changes are pushed, and a human redeploys on CX23.
- **Platform honesty hierarchy** (what each platform may claim): Android (reference, strongest)
  → Linux desktop → Web → iOS (trails). No platform claims a guarantee it hasn't shipped and
  verified. The website must not over-claim (see open hygiene item).
- **Deliver-then-claim.** State something is done only after it is built AND verified; report
  the actual command/exit-code evidence. Never claim a check that didn't run.
- **Paired-blind independent review to clean convergence.** Security-sensitive work ships as
  small phased PRs; each is reviewed by TWO reviewers (Codex + Grok) blind to each other, and
  findings are adjudicated against source (never accepted on the reviewer's say-so). Clean
  convergence = both reviewers, no Critical/High/Medium, every finding verified. A single
  reviewer is empirically insufficient — across D2c and the 0.9.2 PR-1 arc each reviewer caught
  real defects the other missed, and each waved off the other's finding at least once. Fixes are
  NOT lower-risk than original code — re-review every fix delta the same way (PR-1's first round
  was rejected). Never self-re-read as the "final" review.
- **WRITER/READER invariant table first.** Before changing any durable multi-reader signal,
  enumerate every writer and what every reader assumes the signal MEANS. Direct countermeasure to
  the round-12→16 regression pattern AND the PR-1 B1 marker defect (see `failures.md`).
- **Two-marker account-delete state machine.** `vault.delete-intent` (delete initiated — NEVER
  authorizes destroy) vs `vault.delete-confirmed` (server provably gone — the SOLE auto-destroy
  authorization). Token-clear in `onSessionRevoked` is guarded by
  `deleteInFlight || intentMarkerPresent()`.
- **A plain LOCK is not a DELETE.** `UnlockController.lock()` reseals current state (RETAINS auth
  on disk) then wipes RAM only; writes NO delete marker, clears NO token. D3 auto-lock reuses this
  exact path, so it is not a new writer to the hardened surface.
- **Fail-closed by default on protection reads.** Use proven-absent tristate (`Files.notExists`),
  not `File.exists()` (which conflates absent/indeterminate). PR-1's B1 add-path fails CLOSED:
  it writes/clears NO delete marker and refuses to create while any marker is present.
- **l00prite scaffolding is TRACKED in-repo** (2026-07-24 layout migration): the `l00prite/`
  folder (pointers, adapters, `.l00prite/` memory) is committed — nothing under it is gitignored,
  or the cross-session/cross-provider memory can't be discovered. See `constraints.md`.
- **Reviewer-credit discipline.** Weekly credit limits: use review agents in moderation; cap any
  workflow at ~5 agents; prefer inline verification. Codex + Grok CLIs are on this box
  (`/root/.local/bin/codex`, `/root/.grok/bin/grok`, both authenticated). Invocation that works:
  Grok `grok --cwd <repo> --permission-mode bypassPermissions --output-format plain --prompt-file
  <f>`; Codex `codex exec -C <repo> --sandbox read-only -` (prompt on stdin). Both run headless
  and review a local diff/commit range.

## Facts (current — 2026-07-24)
- **main = `2de2bac`.** 0.9.1-beta shipped (vc17); 0.9.2 PR-1 merged on top (PR #51 squash).
- **0.9.1-beta: CUT + clearnet flip DONE** (vc17 / `55540e3`). Signed APK cert
  `6c7f92a7…892753`; GH Release live; `www.zitrone.app/download/beta` live. Onion staging +
  Vercel apex flip DEFERRED to the operator (see `todos.md`).
- **0.9.2-beta — second vault + Pucker Burn (Android):**
  - **PR-1 MERGED** (`2de2bac`): `VaultImageStore.attemptUnlockOrAdd` (fused unlock/burn/create),
    slot-0 burn reservation (`BURN_SLOT_INDEX`/`VAULT_SLOT_RANGE`/`randomVaultSlotIndex`),
    `IMAGE_VERSION` 2→3 + `LegacyImage`/`retireLegacyImage`, B1 fail-closed markers, B2 wrapped
    self-verify + G3 payload self-verify (`sealSlotSelfVerifying` + constant-time content
    compare), F4 wipe-on-throw, F9 `unlockWithKey`/biometric slot-0 guard. **Store-layer only —
    `create=true` has NO caller until PR-2, so nothing new is user-reachable post-merge.**
  - **PR-2 spec delivered** (`/root/l00prite/pr2-router-triple-entry-spec.md`), awaiting review:
    router fusion + triple-entry gate (RAM-only candidateHash/candidateCount, create only on 3
    uninterrupted identical entries) + uninterrupted-sequence guard.
  - **Sequencing (binding):** PR-2 before PR-3. PR-3 wiring without the triple-entry gate would
    make creation reachable on a SINGLE unrecognized passphrase (the OQ1 revision removed that).
- **App version: vc17 / 0.9.1-beta** — 0.9.2 stays UNBUMPED until the phase (PR-2+PR-3) completes.
- **Android signing:** release cert `6c7f92a7…892753`; `keystore.properties` (4 fields, mode 600).
  Verify any release APK against this cert. No NDK build path here; details in
  `zitrone-build-env.md` (Claude auto-memory).
- **Two parallel ledgers still exist** (housekeeping): this in-repo `l00prite/.l00prite/ledger.md`
  and the local rolling `/root/l00prite/zitrone-vault-ledger.md` (deep review detail).

## Avoid
- Do not deploy/restart the production relay from CX33.
- Do not bump a version, push, or merge without explicit human approval.
- Do not gitignore anything under `l00prite/` — it breaks the cross-session memory.
- Do not move WHEN a durable signal is written without re-deriving what every reader assumes it
  MEANS, via the WRITER/READER table (round-12 + PR-1-B1 lesson — see `failures.md`).
- Do not wire a `create=true` caller (PR-3) before the triple-entry gate (PR-2) exists.
- Do not store transient debugging notes here; keep this durable-only.
