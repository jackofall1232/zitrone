// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// Package regpow implements the 0.9.4-beta registration proof-of-work: a
// server-issued, HMAC-signed, stateless challenge followed by a two-stage
// verification ladder (cheap hashcash pre-stage, then memory-hard Argon2id).
// See l00prite/.l00prite/todos.md, "NEXT — 0.9.4-beta: REGISTRATION
// PROOF-OF-WORK" for the full spec this implements.
//
// NOT WIRED TO ENFORCE ANYTHING BY DEFAULT. A relay that requires
// challenge+proof rejects every client that doesn't yet send one, including
// the shipped 0.9.3 build — enforcement is a breaking change. Config's
// RegistrationPoWEnabled gates it and defaults to false; it must stay false
// in production until the 0.9.4 client has replaced 0.9.3 in the field.
//
// Verification ladder, cheapest check first — each gate must pass before the
// next, more expensive one runs:
//  1. HMAC + expiry check on the challenge token (Verify's first step, via
//     verifyChallenge) — microseconds, rejects garbage and replay before any
//     real work happens.
//  2. SHA-256 hashcash pre-stage via pow.Verify — the same primitive dead
//     drops already use in production. Closes the gap where an attacker
//     holding a validly issued (but unauthenticated-to-issue) challenge could
//     otherwise force Argon2id verification on arbitrary wrong proofs.
//  3. Argon2id, only for submissions that clear both of the above.
//
// The proof binds to the identity key being registered (spec's settled
// decision 4), by folding the identity key into the input of both the
// hashcash and Argon2id stages — a solved proof cannot be replayed for a
// different identity key under the same challenge.
package regpow

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"time"

	"golang.org/x/crypto/argon2"

	"github.com/zitrone/server/internal/pow"
)

// ArgonNonceBytes is the length of the client-chosen Argon2id salt/nonce a
// proof submits — the value the client searched over to find a passing
// output, mirroring pow.NonceBytes' role in the hashcash stage.
const ArgonNonceBytes = 16

// Argon2id tuning for the registration proof-of-work main stage.
//
// DELIBERATELY DIFFERENT from the client vault's Argon2id derivation
// (packages/crypto/src/kdf.ts ARGON2ID_PARAMS: memLimitBytes=64MiB,
// iterations=3, parallelism=4). That derivation optimizes for maximum
// brute-force resistance on a passphrase the user typed once; this gate
// optimizes for a few seconds of phone CPU, repeated at relay-verification
// volume. Do not "harmonise" the two constants — the spec brief locks them
// as intentionally distinct, different problems.
//
// Threads is fixed at 1, not configurable: per the same locked vault
// decision on cross-platform determinism, parallelism>1 Argon2id does not
// reproduce identically across every implementation client code must target.
const (
	Argon2Threads = 1
	Argon2KeyLen  = 32

	// TODO(pow-calibration, unmeasured): the spec brief requires flooring
	// difficulty on a Revvl 6x IN BATTERY SAVER (client cost), balanced
	// against relay verification cost at registration volume (server cost).
	// Neither side has been measured — this session cannot build or run the
	// Android client. These are placeholders only, not a decided number: do
	// not enable RegistrationPoWEnabled in production until both are measured
	// and this constant (plus Config.RegistrationArgon2DifficultyBits) is
	// updated with the real values.
	Argon2TimeCostDefault  = 1
	Argon2MemoryKiBDefault = 19 * 1024 // 19 MiB — TODO(pow-calibration, unmeasured)
)

const (
	challengeNonceBytes = 16
	challengeBodyBytes  = 8 + challengeNonceBytes // timestamp(8) || nonce(16)
	challengeMACBytes   = sha256.Size
	challengeTokenBytes = challengeBodyBytes + challengeMACBytes
)

// ErrInvalidChallenge covers every way a challenge token can fail stage 1:
// malformed, forged, or expired. Deliberately one error — which reason it was
// is not attacker-observable.
var ErrInvalidChallenge = errors.New("regpow: invalid or expired challenge")

// IssueChallenge returns a base64url-encoded, HMAC-signed, self-contained
// challenge token. Stateless: nothing is persisted, so there is no challenge
// table to exhaust and no cleanup job to run (spec's settled decision 2).
// Rate-limit issuance at the call site — that is this scheme's second DoS
// layer, per settled decision 3.
func IssueChallenge(secret []byte) (string, error) {
	body := make([]byte, challengeBodyBytes)
	binary.BigEndian.PutUint64(body[:8], uint64(time.Now().Unix()))
	if _, err := rand.Read(body[8:]); err != nil {
		return "", err
	}
	tag := hmacTag(secret, body)

	token := make([]byte, 0, challengeTokenBytes)
	token = append(token, body...)
	token = append(token, tag...)
	return base64.RawURLEncoding.EncodeToString(token), nil
}

// verifyChallenge is ladder stage 1: HMAC validity and expiry only. No
// Argon2id, no hashcash, no allocation beyond the decode — this must stay
// microseconds-cheap since it runs on every submission, valid or garbage,
// before anything expensive does. Returns the raw body (timestamp||nonce) so
// later stages can bind to the same challenge value.
func verifyChallenge(secret []byte, token string, maxAge time.Duration) ([]byte, error) {
	raw, err := base64.RawURLEncoding.DecodeString(token)
	if err != nil || len(raw) != challengeTokenBytes {
		return nil, ErrInvalidChallenge
	}
	body, tag := raw[:challengeBodyBytes], raw[challengeBodyBytes:]

	if subtle.ConstantTimeCompare(hmacTag(secret, body), tag) != 1 {
		return nil, ErrInvalidChallenge
	}

	issuedAt := time.Unix(int64(binary.BigEndian.Uint64(body[:8])), 0)
	if age := time.Since(issuedAt); age < 0 || age > maxAge {
		return nil, ErrInvalidChallenge
	}
	return body, nil
}

func hmacTag(secret, body []byte) []byte {
	mac := hmac.New(sha256.New, secret)
	mac.Write(body)
	return mac.Sum(nil)
}

// Proof is what a client submits alongside a registration request once
// Config.RegistrationPoWEnabled is true.
type Proof struct {
	ChallengeToken string
	HashcashNonce  []byte // pow.NonceBytes (8) bytes
	ArgonNonce     []byte // ArgonNonceBytes (16) bytes — the salt the client found
}

// Params bundles the tunables Verify needs. Operators own the actual numbers
// via Config, not this package — see server/internal/config.
type Params struct {
	Secret               []byte
	ChallengeMaxAge      time.Duration
	HashcashDifficulty   int // leading zero bits, hashcash pre-stage (pow.Verify)
	Argon2TimeCost       uint32
	Argon2MemoryKiB      uint32
	Argon2DifficultyBits int // leading zero bits required of the Argon2id output
}

// Verify runs the full ladder and reports whether proof is a valid
// registration proof-of-work for identityKey. Each stage must pass before the
// next runs — see the package doc for why the ordering matters.
func Verify(p Params, identityKey []byte, proof Proof) bool {
	body, err := verifyChallenge(p.Secret, proof.ChallengeToken, p.ChallengeMaxAge)
	if err != nil {
		return false
	}

	// Bind to this challenge AND this identity key (settled decision 4): the
	// same bound input feeds both stages below, so a solution can't be
	// replayed for a different identity key under the same challenge.
	bound := bindChallenge(body, identityKey)

	if !pow.Verify(bound, proof.HashcashNonce, p.HashcashDifficulty) {
		return false
	}

	if len(proof.ArgonNonce) != ArgonNonceBytes {
		return false
	}
	out := argon2.IDKey(bound, proof.ArgonNonce, p.Argon2TimeCost, p.Argon2MemoryKiB, Argon2Threads, Argon2KeyLen)
	// Reuse pow.HasLeadingZeroBits for the Argon2id admission rule too — one
	// difficulty semantics across both stages instead of a second bespoke
	// comparison.
	return pow.HasLeadingZeroBits(out, p.Argon2DifficultyBits)
}

func bindChallenge(body, identityKey []byte) []byte {
	bound := make([]byte, 0, len(body)+len(identityKey))
	bound = append(bound, body...)
	bound = append(bound, identityKey...)
	return bound
}
