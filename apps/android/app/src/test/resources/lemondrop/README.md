# Lemon-drop cross-stack test fixtures

**Test fixtures only — none of this key material is, or must ever be, a real
account's.** They exist so the Android one-shot responder
(`crypto/LemonDropOneShot.kt`) is tested against bytes produced by the REAL
web creation stack, not by a Kotlin re-implementation that could share its
bugs — deliberately mirroring how the server's XEdDSA verifier is tested only
against real libsignal-client signatures (`server/internal/auth/xeddsa_test.go`).

- `recipient-keys.json` — an Android-family recipient generated with
  libsignal-client (the exact library the app signs with):
  Curve25519 identity pair, signed prekey with a genuine **XEdDSA signature
  over the 33-byte `serialize()` form**, and one one-time prekey. Private
  scalars are included — that is the point: the JVM test plays the recipient.
- `cross-stack-fixture.json` — a lemon drop created by
  `packages/crypto/src/lemondrop.ts` `createLemonDrop` (the production web
  path, family-aware sealing included) addressed to those keys, plus the
  plaintext and `burn_hash` the test asserts against. Consumed by
  `LemonDropOneShotTest.kt` (web-creation → Android-open direction).
- `montgomery-sender-fixture.json` — the OTHER direction (sub-phase 5b): a lemon
  drop created by the production **Kotlin** path
  (`crypto/LemonDropCreate.kt` `create`) with a libsignal Curve25519
  (Montgomery) SENDER — `sender_key_family: "curve25519"` — addressed to the
  same `recipient-keys.json`. Consumed by two tests over the identical bytes:
  `packages/crypto/src/lemondrop-kotlin-fixture.test.ts` (web `openLemonDrop`
  opens it) and, generated fresh in-process, `LemonDropCreateTest.kt`
  (Android-open opens it). Fields: `sender_account_id`, `sender_identity_pub`
  (the raw-32 Montgomery key), `sender_key_family`, `text`, `url`, `ciphertext`,
  `burn_hash`.

## Regenerating

Both files are static; regenerate only when the wire format changes, in two
stages (each is a throwaway test file, run once and deleted — same convention
as the Go vector suite's documented-but-not-committed generator):

1. **Keys (JVM, libsignal):** a temporary JUnit test in `app/src/test` that
   calls `IdentityKeyPair.generate()`, `Curve.generateKeyPair()`,
   `Curve.calculateSignature(identityPrivate, spkPublic.serialize())` for the
   SPK signature, and one more `Curve.generateKeyPair()` for the OTP; write
   the base64 fields of `recipient-keys.json` (raw 32-byte public keys via
   `getPublicKeyBytes()`, private scalars via `serialize()`), ids 7/42.
   Run: `./gradlew :app:testDebugUnitTest --tests "<the temp class>"`.
2. **Drop (Node, packages/crypto):** a temporary vitest file in
   `packages/crypto/src` that reads `recipient-keys.json`, builds a
   `DecodedPreKeyBundle` from the public halves, and calls `createLemonDrop`
   with a fresh `generateIdentityKeyPair()` sender; write
   `cross-stack-fixture.json` with the sender's public key, the text, and the
   drop's `ciphertext`/`burn_hash`/`url`.
   Run: `npx vitest run src/<the temp file>` in `packages/crypto`.

For `montgomery-sender-fixture.json` (sub-phase 5b, Kotlin creation), ONE stage
instead of two — the creator is Android, not Node:

3. **Drop (JVM, Kotlin):** a temporary JUnit test in `app/src/test` that reads
   `recipient-keys.json`, builds a `LemonDropCreate.RecipientBundle` from the
   public halves + `spk_sig`, and calls `LemonDropCreate.create` with a fresh
   libsignal `IdentityKeyPair.generate()` sender (raw-32 public via
   `getPublicKeyBytes()`, scalar via `serialize()`); write the fields above from
   the returned `Created`. Run:
   `./gradlew :app:testDebugUnitTest --tests "<the temp class>"`, then delete it.
