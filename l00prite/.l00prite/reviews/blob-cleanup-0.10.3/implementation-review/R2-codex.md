## Findings

### P2 — `ApiClient.abandonBlob` / `server DepositBlob`: account-linkage claim contradicts server design

Concrete trigger: the new KDoc says “`DepositBlob` already links account to blob id.” However `server/internal/api/blobs.go:DepositBlob` explicitly states that authentication only gates admission and the account is never associated with the blob. The schema/store records only `blob_id`, ciphertext, and expiry.

A malicious relay can correlate the authenticated request with its body transiently, but that is not the durable account-to-blob linkage the claim implies. The documentation needs to distinguish request-time observability from stored linkage.

User-visible consequence: future privacy reasoning may incorrectly assume the server retains an account/blob association. This blocks the claim-correction commit as written.

The narrower “relay gains no destructive capability” conclusion is true: it already controls the row and can delete it unilaterally.

### P2 — `AttachmentDepositWiringTest`: new site assertions remain source-text false greens

Concrete trigger: commenting out the contact-deleted call as:

```kotlin
// settleAttachment(messageId)
```

still satisfies `"settleAttachment(messageId)" in code`. The release count and RELEASE_ONLY regex also remain green. The intended reclamation path is now absent.

The RELEASE_ONLY assertion similarly matches comments and is tightly coupled to exact whitespace/bracing. Harmless changes such as `settleAttachment( messageId )`, an expression branch, or extracting a helper can fail it.

User-visible consequence: a regression can leave contact-deleted blobs until TTL, increasing relay disk pressure, while tests remain green.

### P3 — `settleDecision` KDoc: “fresh random, hence different” is too strong

`AttachmentCrypto.encrypt` performs a fresh 32-byte `SecureRandom` draw whenever `reuseToken == null`, and every `EncryptedBlob.blobId` is `SHA-256(token)`. No path recreates a memo using a previously cleared token.

However, an independent random draw is not guaranteed to differ; collision probability is negligible, not zero. “Hence a different blobId” should say “with cryptographically negligible collision probability.”

This does not create a practical bug, but it is another absolute invariant stated more strongly than the implementation proves.

## Claims confirmed

1. Fresh-token chain: apart from the absolute collision wording, confirmed. `AttachmentCrypto.encrypt` is the only constructor path; reuse comes exclusively from the current memo; `blobId` is always `sha256(token)`. A cleared old token cannot be reinserted from a stale memo.

2. Clearing: all removals are in `releaseDeposit` and `settleAttachment`. The ABANDON statements are separate, but there is no suspension between them and decision readers run on `confined`; no application-observable decision window exists. Callback-thread `releaseDeposit` removes the memo first, causing conservative SKIP if observed.

3. Bound: confirmed. Neither `MessagingCoordinator.stop` nor any `MessageRepository.clearAll` call clears either coordinator map. Retention is bounded by message IDs created during that process.

4. 429: confirmed. `AbandonBlob` checks `blobLimit.Allow` before `BodyParser` and deletion. The request body has reached the server, but the handler neither parses the token nor deletes the row. The route uses `RequireAuth`.

5. Contact-deleted retry: confirmed. A handoff can time out to FAILED, retry, and then reach the contact-missing branch while the earlier handoff record remains.

6. `NonCancellable`: confirmed. Its `Job` replaces the scope job in the launch context; the new coroutine is not cancelled merely because the original scope job already is.

7. Decision test: no output-changing deterministic mutation of `settleDecision` remains uncovered; its finite input domain is exhaustively checked. `expected()` duplicates the policy structurally, but it is a separate oracle and the literal enum-size pins prevent vacuity.

8. Wiring test: finding above.

9. Behaviour: `f730cc48` changes production comments/KDocs only. Executable changes are confined to tests; no application or server behaviour changed.

10. Merge readiness: not ready until the false server-linkage wording and brittle wiring assertions are corrected. I found no additional behavioural blocker in the full branch within the permitted source scope. Tests were not executed because the review was strictly read-only.
tokens used
64,114
## Findings

### P2 — `ApiClient.abandonBlob` / `server DepositBlob`: account-linkage claim contradicts server design

Concrete trigger: the new KDoc says “`DepositBlob` already links account to blob id.” However `server/internal/api/blobs.go:DepositBlob` explicitly states that authentication only gates admission and the account is never associated with the blob. The schema/store records only `blob_id`, ciphertext, and expiry.

A malicious relay can correlate the authenticated request with its body transiently, but that is not the durable account-to-blob linkage the claim implies. The documentation needs to distinguish request-time observability from stored linkage.

User-visible consequence: future privacy reasoning may incorrectly assume the server retains an account/blob association. This blocks the claim-correction commit as written.

The narrower “relay gains no destructive capability” conclusion is true: it already controls the row and can delete it unilaterally.

### P2 — `AttachmentDepositWiringTest`: new site assertions remain source-text false greens

Concrete trigger: commenting out the contact-deleted call as:

```kotlin
// settleAttachment(messageId)
```

still satisfies `"settleAttachment(messageId)" in code`. The release count and RELEASE_ONLY regex also remain green. The intended reclamation path is now absent.

The RELEASE_ONLY assertion similarly matches comments and is tightly coupled to exact whitespace/bracing. Harmless changes such as `settleAttachment( messageId )`, an expression branch, or extracting a helper can fail it.

User-visible consequence: a regression can leave contact-deleted blobs until TTL, increasing relay disk pressure, while tests remain green.

### P3 — `settleDecision` KDoc: “fresh random, hence different” is too strong

`AttachmentCrypto.encrypt` performs a fresh 32-byte `SecureRandom` draw whenever `reuseToken == null`, and every `EncryptedBlob.blobId` is `SHA-256(token)`. No path recreates a memo using a previously cleared token.

However, an independent random draw is not guaranteed to differ; collision probability is negligible, not zero. “Hence a different blobId” should say “with cryptographically negligible collision probability.”

This does not create a practical bug, but it is another absolute invariant stated more strongly than the implementation proves.

## Claims confirmed

1. Fresh-token chain: apart from the absolute collision wording, confirmed. `AttachmentCrypto.encrypt` is the only constructor path; reuse comes exclusively from the current memo; `blobId` is always `sha256(token)`. A cleared old token cannot be reinserted from a stale memo.

2. Clearing: all removals are in `releaseDeposit` and `settleAttachment`. The ABANDON statements are separate, but there is no suspension between them and decision readers run on `confined`; no application-observable decision window exists. Callback-thread `releaseDeposit` removes the memo first, causing conservative SKIP if observed.

3. Bound: confirmed. Neither `MessagingCoordinator.stop` nor any `MessageRepository.clearAll` call clears either coordinator map. Retention is bounded by message IDs created during that process.

4. 429: confirmed. `AbandonBlob` checks `blobLimit.Allow` before `BodyParser` and deletion. The request body has reached the server, but the handler neither parses the token nor deletes the row. The route uses `RequireAuth`.

5. Contact-deleted retry: confirmed. A handoff can time out to FAILED, retry, and then reach the contact-missing branch while the earlier handoff record remains.

6. `NonCancellable`: confirmed. Its `Job` replaces the scope job in the launch context; the new coroutine is not cancelled merely because the original scope job already is.

7. Decision test: no output-changing deterministic mutation of `settleDecision` remains uncovered; its finite input domain is exhaustively checked. `expected()` duplicates the policy structurally, but it is a separate oracle and the literal enum-size pins prevent vacuity.

8. Wiring test: finding above.

9. Behaviour: `f730cc48` changes production comments/KDocs only. Executable changes are confined to tests; no application or server behaviour changed.

10. Merge readiness: not ready until the false server-linkage wording and brittle wiring assertions are corrected. I found no additional behavioural blocker in the full branch within the permitted source scope. Tests were not executed because the review was strictly read-only.
