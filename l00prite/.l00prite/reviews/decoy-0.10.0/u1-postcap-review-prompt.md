# REVIEW — Zitrone 0.10.0-beta: the U1 POST-CAP docs branch

Short, focused review. **Not** a re-review of the U1 unit — that reached clean convergence over six paired-blind rounds and is a separate branch.

## What this is

Branch `docs/0.10.0-u1-post-cap-comments`, on top of the reviewed unit. It exists because changes made *after* a review cap had not been reviewed, and the maintainer ruled that unreviewed changes must not ride inside a reviewed unit's merge — *"low risk is not the same as reviewed."* This is that pass.

See it with: `git diff feat/0.10.0-decoy-u1-provisioning..docs/0.10.0-u1-post-cap-comments`

## Contents — all documentation and comments; zero production logic

1. **`VaultState.kt` codec kdoc** — added the crash row to its `TAG_DECOY` truth list (a crash after the pre-network flush, or a failed retirement flush, leaves the tag with the relay never contacted).
2. **`DecoyRelayApi.kt` kdoc** — "one durable mutate" corrected to "one `mutate`, made durable by the `flushBeforeAck` that follows it". `mutate` only schedules a reseal.
3. **`DECOY_TRAFFIC_0.10.0_SPEC.md` §4.1** — user-facing storage-format disclosure, sixth and final wording, plus a summary note corrected to include the crash case.
4. **`l00prite/.l00prite/failures.md`** — process lessons. Not a contract; accuracy still matters.

## What to check

- **Do the comments now match the code?** Read each corrected claim against the actual source. This unit's recurring defect class was prose drifting from behaviour — five recurrences — so this is the whole point of the pass.
- **Is anything still stale?** Is any restatement of "`mutate` is durable", or of "no tag before `register`", still present anywhere in the tree? Sweep by **claim**, not by phrasing: search for paraphrases ("committed durably", "persisted", "written to disk") and for tables whose *omitted rows* carry the claim implicitly.
- **§4.1 specifically.** Final wording:

  > any vault on which cover traffic has ever been enabled or attempted — even once, even if the attempt failed, was interrupted, or never completed — **may** no longer be readable by 0.9.x… Only a vault on which cover traffic was **never enabled** is guaranteed to be unaffected. If you are unsure, assume the vault is affected.

  Behaviour: the tag lands on disk when provisioning reaches `register`, **or** when a pre-`register` failure cannot durably retire its own write-ahead marker (crash, failed flush). A cleanly-retired attempt leaves no tag. Is the sentence true in both directions — neither understating the break nor overstating it? The "may" is deliberate, to avoid claiming a cleanly-retired attempt is affected.
- **Did any code change sneak in?** The claim is documentation and comments only. Verify that.

## Output

Findings with severity (P1/P2/P3), file:line, and the concrete inaccuracy. If it is clean, say `VERDICT: CLEAN` plainly — do not pad. End with an explicit verdict line.
