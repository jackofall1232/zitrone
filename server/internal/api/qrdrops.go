// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package api

import (
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"time"

	"github.com/gofiber/fiber/v2"

	"github.com/zitrone/server/internal/db"
	"github.com/zitrone/server/internal/pow"
)

// QR dead-drop endpoints ("lemon drops"). A creator deposits an encrypted message
// under a random qr_id and shares that id out-of-band (typically as a QR code).
// Anyone who scans the code can FETCH the ciphertext, but only the intended
// recipient — the one who can decrypt it — learns the burn-token preimage carried
// inside, so only they can BURN (destroy) the drop. Like /drops and /blobs the
// store is blind by construction: no sender/recipient column exists (schema.sql).
//
// All three endpoints are unauthenticated by design — requiring an account would
// defeat the anonymity that is the entire point. Deposit admission is a hashcash
// proof-of-work bound to the qr_id at cfg.DropPoWDifficulty, exactly as /drops
// gates deposit; fetch and burn carry no PoW and lean on the per-IP rate limit.
//
// Wire note: qr_id is UNPADDED BASE64URL — the one canonical form shared with
// the QR sticker URL's path segment (packages/protocol lemondrop.ts), so clients
// never juggle two encodings of the same 16 bytes. Every other byte field
// (ciphertext, pow_nonce, burn_hash, burn_token) is standard base64 like the
// sibling drop/blob endpoints.

const (
	qrIDBytes        = 16          // creator-random drop id; NOT a hash of anything
	qrBurnHashBytes  = sha256.Size // 32; SHA-256(burn token), stored on deposit
	qrBurnTokenBytes = 32          // burn token preimage; revealed only inside the plaintext
	// qrMaxCiphertextBytes caps the deposited ciphertext. QR dead drops are a
	// message-only feature — attachments ride the blind blob store instead — so a
	// 64 KiB ceiling is ample for any text payload and keeps deposits cheap.
	qrMaxCiphertextBytes = 64 * 1024
)

// qrTTLBuckets is the closed allowlist of deposit lifetimes (in hours). Arbitrary
// TTLs are rejected on purpose: a creator-chosen odd lifetime (say 37h) would
// fingerprint the drop and, across many deposits, help correlate them. Pinning
// every deposit to one of five coarse buckets keeps expiries uniform and
// unremarkable. (A Go map cannot be a compile-time const, so this is a var.)
var qrTTLBuckets = map[int]bool{
	24:  true, // 1 day
	48:  true, // 2 days
	72:  true, // 3 days
	168: true, // 1 week
	336: true, // 2 weeks
}

type qrDropDepositRequest struct {
	QrID       string `json:"qr_id"`
	Ciphertext string `json:"ciphertext"`
	TTLHours   int    `json:"ttl_hours"`
	PoWNonce   string `json:"pow_nonce"`
	BurnHash   string `json:"burn_hash"`
}

// DepositQrDrop accepts an encrypted message for QR-based anonymous pickup. No
// auth; a valid proof-of-work over the qr_id stands in for it, exactly as
// DepositDrop does. The burn_hash is stored so a later /burn can match the token
// preimage without the server ever seeing the preimage on deposit.
func (h *Handlers) DepositQrDrop(c *fiber.Ctx) error {
	if !h.qrDropLimit.Allow(c.IP()) {
		return errJSON(c, fiber.StatusTooManyRequests, "rate_limited")
	}
	var req qrDropDepositRequest
	if err := c.BodyParser(&req); err != nil {
		return errJSON(c, fiber.StatusBadRequest, "bad_request")
	}
	qrID, err := base64.RawURLEncoding.DecodeString(req.QrID)
	if err != nil || len(qrID) != qrIDBytes {
		return errJSON(c, fiber.StatusBadRequest, "bad_qr_id")
	}
	burnHash, err := base64.StdEncoding.DecodeString(req.BurnHash)
	if err != nil || len(burnHash) != qrBurnHashBytes {
		return errJSON(c, fiber.StatusBadRequest, "bad_burn_hash")
	}
	nonce, err := base64.StdEncoding.DecodeString(req.PoWNonce)
	if err != nil || len(nonce) != pow.NonceBytes {
		return errJSON(c, fiber.StatusBadRequest, "bad_pow")
	}
	ciphertext, err := base64.StdEncoding.DecodeString(req.Ciphertext)
	if err != nil || len(ciphertext) == 0 {
		return errJSON(c, fiber.StatusBadRequest, "bad_ciphertext")
	}
	if len(ciphertext) > qrMaxCiphertextBytes {
		return errJSON(c, fiber.StatusRequestEntityTooLarge, "payload_too_large")
	}
	// Only the five allowed TTL buckets are accepted — an arbitrary lifetime
	// would fingerprint the drop (see qrTTLBuckets).
	if !qrTTLBuckets[req.TTLHours] {
		return errJSON(c, fiber.StatusBadRequest, "bad_ttl")
	}
	// The proof-of-work must be bound to THIS qr_id — no precomputation.
	if !pow.Verify(qrID, nonce, h.cfg.DropPoWDifficulty) {
		return errJSON(c, fiber.StatusBadRequest, "bad_pow")
	}

	expiresAt := time.Now().Add(time.Duration(req.TTLHours) * time.Hour)
	if err := h.store.DepositQrDrop(c.Context(), qrID, ciphertext, burnHash, expiresAt); err != nil {
		if errors.Is(err, db.ErrQrDropExists) {
			return errJSON(c, fiber.StatusConflict, "qr_drop_exists")
		}
		return errJSON(c, fiber.StatusInternalServerError, "store_failed")
	}
	return c.Status(fiber.StatusCreated).JSON(fiber.Map{
		"expires_at": expiresAt.UTC().Format(time.RFC3339),
	})
}

type qrDropFetchRequest struct {
	QrID string `json:"qr_id"`
}

// FetchQrDrop returns the opaque ciphertext for a qr_id and DOES NOT destroy the
// drop — fetch is deliberately non-destructive and repeatable. The relay is
// blind: it cannot know whether a given scanner is the intended recipient (that
// is decided client-side by whether the ciphertext decrypts), so destroying on
// fetch would let a wrong-recipient scan burn the drop for the real recipient.
// Recipient-matching therefore happens on the client via decrypt success; the
// drop is destroyed only by an explicit /burn (which needs the token preimage) or
// by the TTL janitor. Missing, expired, and already-burned are all 404 and
// indistinguishable — a prober must not learn whether a drop exists.
func (h *Handlers) FetchQrDrop(c *fiber.Ctx) error {
	if !h.qrDropLimit.Allow(c.IP()) {
		return errJSON(c, fiber.StatusTooManyRequests, "rate_limited")
	}
	var req qrDropFetchRequest
	if err := c.BodyParser(&req); err != nil {
		return errJSON(c, fiber.StatusBadRequest, "bad_request")
	}
	qrID, err := base64.RawURLEncoding.DecodeString(req.QrID)
	if err != nil || len(qrID) != qrIDBytes {
		return errJSON(c, fiber.StatusBadRequest, "bad_qr_id")
	}
	ciphertext, err := h.store.FetchQrDrop(c.Context(), qrID)
	if err != nil {
		// Missing, expired, or already burned are all 404 and indistinguishable. A
		// real store failure is a 500 so genuine incidents are not hidden as a
		// "not found".
		if errors.Is(err, db.ErrNoRows) {
			return errJSON(c, fiber.StatusNotFound, "not_found")
		}
		return errJSON(c, fiber.StatusInternalServerError, "store_failed")
	}
	return c.JSON(fiber.Map{
		"ciphertext": base64.StdEncoding.EncodeToString(ciphertext),
	})
}

type qrDropBurnRequest struct {
	QrID      string `json:"qr_id"`
	BurnToken string `json:"burn_token"`
}

// BurnQrDrop destroys a drop on claim. The server hashes the presented burn token
// and deletes the row only where qr_id AND SHA-256(burn_token) both match, in a
// single statement — the same hash-match-consume precedent ConsumeRefreshToken
// uses (comparing hashes of a high-entropy secret in SQL is the established
// pattern here). Only a client that successfully decrypted the payload can know
// the burn-token preimage, so wrong recipients can fetch but never burn; unclaimed
// drops are crypto-shredded by the TTL janitor. A match is 204; no match, missing,
// and expired are all 404 and indistinguishable — a prober must not learn whether
// a drop exists.
func (h *Handlers) BurnQrDrop(c *fiber.Ctx) error {
	if !h.qrDropLimit.Allow(c.IP()) {
		return errJSON(c, fiber.StatusTooManyRequests, "rate_limited")
	}
	var req qrDropBurnRequest
	if err := c.BodyParser(&req); err != nil {
		return errJSON(c, fiber.StatusBadRequest, "bad_request")
	}
	qrID, err := base64.RawURLEncoding.DecodeString(req.QrID)
	if err != nil || len(qrID) != qrIDBytes {
		return errJSON(c, fiber.StatusBadRequest, "bad_qr_id")
	}
	burnToken, err := base64.StdEncoding.DecodeString(req.BurnToken)
	if err != nil || len(burnToken) != qrBurnTokenBytes {
		return errJSON(c, fiber.StatusBadRequest, "bad_burn_token")
	}
	// The relay derives the stored burn_hash from the presented preimage — knowing
	// the qr_id alone (which is public) is not enough to burn.
	burnHash := sha256.Sum256(burnToken)
	if err := h.store.BurnQrDrop(c.Context(), qrID, burnHash[:]); err != nil {
		// No match, missing, or expired are all 404 and indistinguishable — a prober
		// learns nothing about whether a drop exists. A real store failure is a 500.
		if errors.Is(err, db.ErrNoRows) {
			return errJSON(c, fiber.StatusNotFound, "not_found")
		}
		return errJSON(c, fiber.StatusInternalServerError, "store_failed")
	}
	return c.SendStatus(fiber.StatusNoContent)
}
