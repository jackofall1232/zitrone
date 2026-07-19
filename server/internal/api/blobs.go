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

	"github.com/zitrone/server/internal/config"
	"github.com/zitrone/server/internal/db"
)

// Blind blob store (attachments). Attachment bytes never ride in a message
// envelope: a sender AEAD-encrypts them with a fresh random key, uploads the
// ciphertext here under blob_id = SHA-256(token), and references the blob from
// the (ratchet-encrypted) message. The store is blind by construction — there
// is no sender/recipient/account column anywhere (see schema.sql), so the relay
// cannot link a deposit to an account, nor a redemption to a deposit.
//
// Upload is JWT-authenticated as spam control (the same middleware messaging
// REST endpoints use). Redemption is deliberately UNauthenticated: the one-time
// token IS the capability, exactly like dead-drop redemption. Requiring an
// account to fetch would let the relay link a fetch to an identity — the very
// linkage the design exists to prevent.

const blobIDBytes = sha256.Size // 32
const blobTokenBytes = 32       // one-time redemption token; blob_id = SHA-256(token)

// blobOverheadSlack is added to the configured plaintext cap to obtain the
// ciphertext cap the server actually enforces. A blob's ciphertext is the
// plaintext bucket-padded to a 64 KiB boundary and AEAD-sealed, so the wire
// size can legitimately exceed the plaintext cap: 65536 (one padding bucket) +
// 32 (AEAD nonce+tag) + 4 (length prefix) = 65572 bytes of headroom.
const blobOverheadSlack = 65536 + 32 + 4 // 65572

// maxDefaultBody is the pre-attachment app-wide Content-Length ceiling. The
// Fiber BodyLimit is raised to fit a base64 blob upload, so BodyLimitGuard
// re-imposes this cap on every route EXCEPT the blob upload (see below).
const maxDefaultBody = 512 * 1024

// blobUploadPath is the one route exempt from BodyLimitGuard — large uploads are
// expected here and here only. Redeem (/api/v1/blobs/redeem) carries a tiny
// token body and stays under the default cap like everything else.
const blobUploadPath = "/api/v1/blobs"

// BlobEffectiveCap is the maximum ciphertext byte length the server accepts for
// an upload: the configured plaintext cap plus padding/AEAD/length-prefix slack.
func BlobEffectiveCap(cfg *config.Config) int {
	return cfg.BlobMaxBytes + blobOverheadSlack
}

// BlobBodyLimit is the Fiber app-level body limit needed to accept a blob upload
// as a base64 JSON body: ceil(effectiveCap * 4 / 3) for the base64 expansion,
// plus 1 KiB of slack for the surrounding JSON envelope and field.
func BlobBodyLimit(cfg *config.Config) int {
	cap := BlobEffectiveCap(cfg)
	return (cap*4+2)/3 + 1024
}

// BodyLimitGuard rejects any request whose Content-Length exceeds the default
// 512 KiB cap, EXCEPT the blob upload route. Raising the Fiber BodyLimit to fit
// blob uploads would otherwise let a large body reach any endpoint; this guard
// keeps the pre-attachment DoS posture unchanged for everything else. The check
// is Content-Length based — a chunked request without a declared length is still
// ultimately bounded by the (raised) app-level BodyLimit, which is inherent to
// Fiber v2 having no per-route body limit.
func (h *Handlers) BodyLimitGuard(c *fiber.Ctx) error {
	if c.Path() == blobUploadPath {
		return c.Next()
	}
	if c.Request().Header.ContentLength() > maxDefaultBody {
		return errJSON(c, fiber.StatusRequestEntityTooLarge, "payload_too_large")
	}
	return c.Next()
}

type blobUploadRequest struct {
	BlobID     string `json:"blob_id"`
	Ciphertext string `json:"ciphertext"`
}

// DepositBlob accepts an encrypted attachment for anonymous one-shot pickup.
// JWT-authenticated (RequireAuth runs first); the account is used only to gate
// admission and is never associated with the stored blob.
func (h *Handlers) DepositBlob(c *fiber.Ctx) error {
	if !h.blobLimit.Allow(c.IP()) {
		return errJSON(c, fiber.StatusTooManyRequests, "rate_limited")
	}
	var req blobUploadRequest
	if err := c.BodyParser(&req); err != nil {
		return errJSON(c, fiber.StatusBadRequest, "bad_request")
	}
	blobID, err := base64.StdEncoding.DecodeString(req.BlobID)
	if err != nil || len(blobID) != blobIDBytes {
		return errJSON(c, fiber.StatusBadRequest, "bad_blob_id")
	}
	ciphertext, err := base64.StdEncoding.DecodeString(req.Ciphertext)
	if err != nil || len(ciphertext) == 0 {
		return errJSON(c, fiber.StatusBadRequest, "bad_ciphertext")
	}
	// The app-wide BodyLimit bounds the raw request, but the decoded ciphertext
	// must still fit the configured cap (plaintext cap + padding/AEAD slack).
	if len(ciphertext) > BlobEffectiveCap(h.cfg) {
		return errJSON(c, fiber.StatusRequestEntityTooLarge, "payload_too_large")
	}

	expiresAt := time.Now().Add(time.Duration(h.cfg.BlobTTLHours) * time.Hour)
	if err := h.store.StoreBlob(c.Context(), blobID, ciphertext, expiresAt); err != nil {
		if errors.Is(err, db.ErrBlobExists) {
			return errJSON(c, fiber.StatusConflict, "blob_exists")
		}
		return errJSON(c, fiber.StatusInternalServerError, "store_failed")
	}
	return c.Status(fiber.StatusCreated).JSON(fiber.Map{
		"expires_at": expiresAt.UTC().Format(time.RFC3339),
	})
}

type blobRedeemRequest struct {
	Token string `json:"token"`
}

// RedeemBlob returns an attachment and DESTROYS the blob in the same statement
// (fetch-and-burn). No auth: possession of the one-time token is the entire
// capability — the relay derives the blob ID from the token preimage, so it
// cannot link this fetch to any account. Single use: a second attempt with the
// same token returns 404. Unfetched blobs are purged by the janitor at the
// configured BlobTTLHours fallback (default 1 week) — the server never held the
// AEAD key, so deletion is the shred.
func (h *Handlers) RedeemBlob(c *fiber.Ctx) error {
	if !h.blobLimit.Allow(c.IP()) {
		return errJSON(c, fiber.StatusTooManyRequests, "rate_limited")
	}
	var req blobRedeemRequest
	if err := c.BodyParser(&req); err != nil {
		return errJSON(c, fiber.StatusBadRequest, "bad_request")
	}
	token, err := base64.StdEncoding.DecodeString(req.Token)
	if err != nil || len(token) != blobTokenBytes {
		return errJSON(c, fiber.StatusBadRequest, "bad_token")
	}
	// The relay derives the blob ID from the token preimage — knowing the ID
	// alone (which is public) is not enough to redeem.
	blobID := sha256.Sum256(token)
	ciphertext, err := h.store.RedeemBlob(c.Context(), blobID[:])
	if err != nil {
		// Missing, already redeemed, or expired are all 404 and indistinguishable
		// (token validity stays opaque). A real store failure is a 500 so genuine
		// incidents are not hidden behind a "not found".
		if errors.Is(err, db.ErrNoRows) {
			return errJSON(c, fiber.StatusNotFound, "not_found")
		}
		return errJSON(c, fiber.StatusInternalServerError, "store_failed")
	}
	return c.JSON(fiber.Map{
		"ciphertext": base64.StdEncoding.EncodeToString(ciphertext),
	})
}
