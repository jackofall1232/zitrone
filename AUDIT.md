# Security Audit History

No audits conducted yet. This file updates as audits complete.

Contact us via [SECURITY.md](SECURITY.md) if you want to conduct an audit.

| Date | Auditor | Scope | Report | Findings resolved |
| --- | --- | --- | --- | --- |
| — | — | — | — | — |

---

## Release maturity and the `-beta` version labels

**Every release so far has been a complete, working build that could have been published as a beta
at any time.** No release shipped with a known defect, and each passed its own review gates — the
security-sensitive ones through independent paired-blind adversarial review to clean convergence.
The quality bar has been release-grade throughout.

**What the `-beta` label was hedging is the FEATURE LIST, not the quality.** The plausible-deniability
vault is uncharted work with no reference implementation anywhere to borrow from, so how long it
would take to finish the intended feature set was genuinely unknowable. Labelling releases `-beta`
from the start meant the project could **flip to a declared beta at any moment** if a deadline made
that necessary, without having to relabel anything or pretend a decision had been planned. The label
bought optionality against schedule risk. It was never a claim that the feature set was finished.

**In the project's own terms, these are alpha builds**, and are treated as such internally — with
one honest qualification that matters more than the label: **the on-disk vault format is not yet
stable, and a future release may require a fresh install that erases every vault on the device.**
There is no migration and no export. That limitation, not the version string, is the reason these
builds are not yet recommended for data you cannot afford to lose. It is documented in full in
[`docs/design/DECOY_TRAFFIC_0.10.0_SPEC.md`](docs/design/DECOY_TRAFFIC_0.10.0_SPEC.md) §4.1, and it
is the condition that has to clear before a genuine beta.

**The plan from here:** `0.10.0` adds decoy traffic; `0.11.0` is the polish round — the most detailed
UI/UX pass the project has had — and is the **final alpha**. At that point the label flips to a
**true beta**: a V1 stable candidate, distributable for real testing. Android is the security
reference client and carries that release; Linux and iOS are deliberately deferred until after V1
Android testing.
