# Adversarial review — Zitrone 0.10.2, item 5 (orphaned blobs), round 1 — SEND-PATH ADJACENT

**READ-ONLY.** Do not modify, create or delete any file. Do not run mutating commands. Report only.

You are one of two reviewers working **blind to each other**. Do not assume anything has already been
found. Repo root: `/root/zitrone`. Branch: `feat/0.10.2-capacity-fixes`.

**This gets its own pass because it touches the send path.** The send path was hardened over U3 (cover
traffic) and 0.10.1 (send-failure surfacing), and **in that 0.10.1 stream every single fix delta
produced a finding, four rounds running.** Treat this as guilty until proven otherwise.

## Context

Zitrone is a zero-knowledge, plausible-deniability messenger. **The relay is CONCEDED.** The message
store is **RAM-only**. **R-U3-1 is absolute:** a real send is never blocked, failed, delayed,
reordered, or made less durable to produce cover — **and a retry IS a real send.**

**The defect:** an attachment blob is uploaded BEFORE the envelope is published, so three routes leave
a blob nothing will ever fetch: **(a)** a non-durable ratchet flush → `markFailed`; **(b)** contact
deleted mid-upload → `publishOutgoing` drops the envelope; **(c)** any throw/transport error. And
0.10.1's retry amplified it: `AttachmentCrypto.encrypt` drew a fresh token per call and
`blobId = sha256(token)`, so **every retry deposited a NEW blob and orphaned the previous one** —
N retries × up to 8 MiB, each held the full TTL. One blob ≈ 545 accounts' worth of disk.

**Deferring the upload until after the durable flush was REJECTED** by the maintainer, and the
reasoning is part of what you should sanity-check: it would put an 8 MiB Tor upload inside the
`flushSendRatchet → publishOutgoing` gap that U3 spent weeks emptying, so a process death there loses
a message whose ratchet already advanced. Trading an orphan for a lost message is the worse currency.

## 5a — one blob per message (MEMOIZE, do not derive)

`AttachmentCrypto.encrypt(plain, reuseToken, reuseKey)`; `MessagingCoordinator.attachmentDeposits`
holds `(token, key)` per message id, released on three terminal outcomes.

Two hazards shaped this and **both should be re-verified, not assumed:**

1. **The token must NOT be derived from anything the relay sees.** `blobId = sha256(token)` and the
   token IS the redemption capability, while the message id is **cleartext to the relay** for routing.
   A relay-computable token would let the relay redeem the attachment. Hence memoise a random draw.
2. **Deriving only the token would ship a BROKEN attachment.** `DepositBlob` is
   `ON CONFLICT (blob_id) DO NOTHING`, so a retry keeps attempt 1's ciphertext — while a fresh AES key
   per call would mean attempt 2's envelope carries a key that cannot open attempt 1's bytes. Reusing
   the **key** is what makes it safe: each box carries its own nonce, so a stable key opens either.
   The nonce is still freshly drawn — forcing byte-identity would need a deterministic nonce over
   `MessagePadding`'s random fill, i.e. **key+nonce reuse over differing plaintext**, the one GCM
   failure that is catastrophic.

**Attack:** is reusing an AES-GCM key across attempts genuinely safe here, or does it weaken anything
(multi-target, forward secrecy, tag collision)? Can the memo be reused for the WRONG message, leak, or
grow unbounded? Are the three release points complete — and what happens on a route that releases
nothing? Only ~96 bytes are held, never the 8 MiB box: is that reasoning correct, or is the box
actually needed?

## 5b — an authenticated abandon endpoint

`POST /api/v1/blobs/abandon`, authenticated, blob-bucket rate-limited, **204 whether or not a row
existed** so it cannot probe liveness. Client: `ApiClient.abandonBlob` via
`MessagingCoordinator.abandonBlobQuietly`, called on routes **(a)** and **(c)**.

**Keyed on the TOKEN, not the blob id — a deliberate deviation from the original spec.** The blob id is
**public** (`RedeemBlob`: knowing it "is not enough to redeem"), so an id-keyed delete would hand a
destruction capability to a public value. **Attack that reasoning** — and whether the token-keyed form
introduces anything new by sending the token to the relay.

**ATTACK THIS SPECIFICALLY, it is the sequence I am least sure of:** route (a) abandons the blob, then
the user retries. Item 5a makes the retry re-deposit under the **same** blobId. **Can the in-flight
abandon delete the retry's fresh deposit**, leaving an envelope pointing at a blob that no longer
exists — a message bubble with a permanently "unavailable" attachment? `abandonBlobQuietly` launches on
`scope` and is not awaited, so consider the ordering honestly. If the race is real, say so and say
whether the fix belongs at the client (don't abandon while a retry is possible), the relay
(conditional delete), or the design (don't abandon on (a) at all).

**Also attack:** is `abandonBlobQuietly` swallowing failures correct, or does it hide a real error
class? Can it throw into an already-failing send, or delay the user's failure indicator? **Route (b) is
knowingly NOT covered** — the check is inside non-suspending `publishOutgoing` (D2c) — is that
correctly classed as a residual rather than a defect? Does any of this weaken **R-U3-1**?

## Files

`apps/android/.../crypto/AttachmentCrypto.kt`, `MessagingCoordinator.kt` (`deliverAttachment`,
`attachmentDeposits`, `releaseDeposit`, `abandonBlobQuietly`, `publishOutgoing`, `retry`),
`net/ApiClient.kt`, `server/internal/api/blobs.go` (`AbandonBlob`, `RedeemBlob`, `DepositBlob`),
`server/internal/db/store.go` (`AbandonBlob`, `StoreBlob`), `server/cmd/server/main.go`;
tests `AttachmentDepositReuseTest.kt`, `server/internal/api/blobs_test.go`.

## Declared residuals — judge the classing, don't re-report as new

- **Route (b) uncovered** (non-suspending `publishOutgoing`); (b) and crash stay TTL-bounded at 96 h.
- **The store-touching abandon path is not unit-tested** — `Handlers` holds a concrete `*db.Store`.
- **The coordinator wiring for 5a is asserted, not tested** (no constructible `MessagingCoordinator`);
  a mutation deleting the reuse initially SURVIVED, which is why a wiring tripwire exists.

## Calibration

- **P1** — a real message is lost, corrupted, double-delivered, or arrives with a permanently dead
  attachment; crypto is weakened; the relay gains a capability it should not have.
- **P2** — an orphan route stays open, or a fix does not hold in a reachable case.
- **P3** — a guard that does not guard its claim, a doc/comment inaccuracy, hygiene.

Every finding needs file and line, a **concrete reachable sequence**, the wrong outcome, and why the
tests miss it.

## Output

```
# FINDINGS
# CONFIRM-OR-REFUTE   (5a's two hazards, 5b's token-keying, the abandon-vs-retry race, R-U3-1)
# RESIDUAL CLASSING
# MISSING CONTEXT

VERDICT: CLEAN | FINDINGS (n P1, n P2, n P3)
```
