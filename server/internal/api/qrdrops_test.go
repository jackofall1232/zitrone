// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package api

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"os"
	"testing"
	"time"

	"github.com/gofiber/fiber/v2"

	"github.com/zitrone/server/internal/config"
	"github.com/zitrone/server/internal/db"
	"github.com/zitrone/server/internal/pow"
	"github.com/zitrone/server/internal/ratelimit"
)

// qrTestDifficulty keeps the deposit proof-of-work cheap enough to brute-force
// inside a unit test while still exercising the real pow.Verify path.
const qrTestDifficulty = 12

// qrTestConfig is a minimal config with rate limiting disabled so the limiter
// never interferes with assertions.
func qrTestConfig() *config.Config {
	return &config.Config{
		DropPoWDifficulty: qrTestDifficulty,
		RateLimitEnabled:  false,
	}
}

// newQrHandlers builds a Handlers whose QR-drop endpoints are reachable. All
// three are unauthenticated, so no issuer is needed. store is left nil for the
// validation tests — each asserts on a path that returns before touching the
// store; round-trip/fetch/burn behaviour lives in the DATABASE_URL-gated test.
func newQrHandlers(store *db.Store) *Handlers {
	return &Handlers{
		store:       store,
		cfg:         qrTestConfig(),
		qrDropLimit: ratelimit.New(1000, time.Minute, false),
	}
}

// qrTestApp wires the real QR-drop routes the same way main.go does.
func qrTestApp(h *Handlers) *fiber.App {
	app := fiber.New()
	v1 := app.Group("/api/v1")
	v1.Post("/qr-drops", h.DepositQrDrop)
	v1.Post("/qr-drops/fetch", h.FetchQrDrop)
	v1.Post("/qr-drops/burn", h.BurnQrDrop)
	return app
}

// solveQrPoW brute-forces a nonce that solves the deposit puzzle for qrID, the
// way a client would (mirrors pow_test.go's TestVerifyRoundTrip loop).
func solveQrPoW(t *testing.T, qrID []byte) []byte {
	t.Helper()
	nonce := make([]byte, pow.NonceBytes)
	for counter := uint64(0); ; counter++ {
		binary.BigEndian.PutUint64(nonce, counter)
		if pow.Verify(qrID, nonce, qrTestDifficulty) {
			return nonce
		}
		if counter > 1<<28 {
			t.Fatal("failed to solve QR-drop PoW within budget")
		}
	}
}

// b64url encodes the way clients put a qr_id on the wire: unpadded base64url,
// the same canonical form the QR sticker URL's path segment carries.
func b64url(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

// randBytes returns n cryptographically random bytes.
func randBytes(t *testing.T, n int) []byte {
	t.Helper()
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		t.Fatal(err)
	}
	return b
}

// validDeposit builds a deposit body with a freshly solved PoW and a matching
// burn_hash, returning the body plus the burn token preimage the caller needs to
// burn it later.
func validDeposit(t *testing.T, qrID, ciphertext []byte, ttlHours int) (fiber.Map, []byte) {
	t.Helper()
	burnToken := randBytes(t, qrBurnTokenBytes)
	burnHash := sha256.Sum256(burnToken)
	nonce := solveQrPoW(t, qrID)
	return fiber.Map{
		"qr_id":      b64url(qrID),
		"ciphertext": b64(ciphertext),
		"ttl_hours":  ttlHours,
		"pow_nonce":  b64(nonce),
		"burn_hash":  b64(burnHash[:]),
	}, burnToken
}

// ── deposit validation (no database required) ────────────────────────────────

// A well-formed deposit whose ttl_hours is not one of the five allowed buckets is
// rejected with 400 before the store is touched.
func TestQrDeposit_BadTTL(t *testing.T) {
	h := newQrHandlers(nil)
	app := qrTestApp(h)
	qrID := randBytes(t, qrIDBytes)
	for _, ttl := range []int{0, 25, 720} {
		body, _ := validDeposit(t, qrID, []byte("ciphertext"), ttl)
		status, _ := postJSON(t, app, "/api/v1/qr-drops", body, "")
		if status != fiber.StatusBadRequest {
			t.Fatalf("ttl_hours=%d: got %d, want 400", ttl, status)
		}
	}
}

// A deposit whose PoW does not solve the puzzle is rejected with 400.
func TestQrDeposit_BadPoW(t *testing.T) {
	h := newQrHandlers(nil)
	app := qrTestApp(h)
	qrID := randBytes(t, qrIDBytes)
	burnHash := sha256.Sum256(randBytes(t, qrBurnTokenBytes))
	// A nonce that does NOT solve the puzzle. At difficulty 12 a random nonce
	// essentially always fails; the loop-guard keeps the test deterministic.
	nonce := randBytes(t, pow.NonceBytes)
	for pow.Verify(qrID, nonce, h.cfg.DropPoWDifficulty) {
		nonce = randBytes(t, pow.NonceBytes)
	}
	status, _ := postJSON(t, app, "/api/v1/qr-drops", fiber.Map{
		"qr_id":      b64url(qrID),
		"ciphertext": b64([]byte("ciphertext")),
		"ttl_hours":  24,
		"pow_nonce":  b64(nonce),
		"burn_hash":  b64(burnHash[:]),
	}, "")
	if status != fiber.StatusBadRequest {
		t.Fatalf("bad PoW: got %d, want 400", status)
	}
}

// A wrong-length qr_id is a 400, before the store is touched.
func TestQrDeposit_BadQrIDLength(t *testing.T) {
	h := newQrHandlers(nil)
	app := qrTestApp(h)
	burnHash := sha256.Sum256(randBytes(t, qrBurnTokenBytes))
	status, _ := postJSON(t, app, "/api/v1/qr-drops", fiber.Map{
		"qr_id":      b64url([]byte("too-short")),
		"ciphertext": b64([]byte("ciphertext")),
		"ttl_hours":  24,
		"pow_nonce":  b64(make([]byte, pow.NonceBytes)),
		"burn_hash":  b64(burnHash[:]),
	}, "")
	if status != fiber.StatusBadRequest {
		t.Fatalf("bad qr_id length: got %d, want 400", status)
	}
}

// A wrong-length burn_hash is a 400, before the store is touched.
func TestQrDeposit_BadBurnHashLength(t *testing.T) {
	h := newQrHandlers(nil)
	app := qrTestApp(h)
	qrID := randBytes(t, qrIDBytes)
	status, _ := postJSON(t, app, "/api/v1/qr-drops", fiber.Map{
		"qr_id":      b64url(qrID),
		"ciphertext": b64([]byte("ciphertext")),
		"ttl_hours":  24,
		"pow_nonce":  b64(make([]byte, pow.NonceBytes)),
		"burn_hash":  b64([]byte("too-short")),
	}, "")
	if status != fiber.StatusBadRequest {
		t.Fatalf("bad burn_hash length: got %d, want 400", status)
	}
}

// Ciphertext beyond the message-only cap is rejected with 413 before the store.
func TestQrDeposit_OversizeCiphertext(t *testing.T) {
	h := newQrHandlers(nil)
	app := qrTestApp(h)
	qrID := randBytes(t, qrIDBytes)
	oversize := make([]byte, qrMaxCiphertextBytes+1)
	body, _ := validDeposit(t, qrID, oversize, 24)
	status, _ := postJSON(t, app, "/api/v1/qr-drops", body, "")
	if status != fiber.StatusRequestEntityTooLarge {
		t.Fatalf("oversize ciphertext: got %d, want 413", status)
	}
}

// Fetch and burn require no auth: a request with no Authorization header reaches
// the handler and fails only on the malformed body (400), never 401.
func TestQrFetchBurn_NoAuthRequired(t *testing.T) {
	h := newQrHandlers(nil)
	app := qrTestApp(h)
	for _, path := range []string{"/api/v1/qr-drops/fetch", "/api/v1/qr-drops/burn"} {
		status, _ := postJSON(t, app, path, fiber.Map{"qr_id": b64url([]byte("short"))}, "")
		if status == fiber.StatusUnauthorized {
			t.Fatalf("%s must not require authentication", path)
		}
		if status != fiber.StatusBadRequest {
			t.Fatalf("%s wrong-length qr_id: got %d, want 400", path, status)
		}
	}
}

// ── round-trip / fetch / burn (integration, needs Postgres) ──────────────────

// These exercise the store, so they run only when DATABASE_URL points at a live
// Postgres and skip otherwise — mirroring the rest of the suite, which is DB-less.
func TestQrDropStore_RoundTrip(t *testing.T) {
	dsn := os.Getenv("DATABASE_URL")
	if dsn == "" {
		t.Skip("DATABASE_URL not set; skipping QR-drop store integration test")
	}
	ctx := context.Background()
	store, err := db.Open(ctx, dsn)
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	defer store.Close()

	h := newQrHandlers(store)
	app := qrTestApp(h)

	fetch := func(qrID []byte) (int, []byte) {
		return postJSON(t, app, "/api/v1/qr-drops/fetch", fiber.Map{"qr_id": b64url(qrID)}, "")
	}
	ciphertextOf := func(t *testing.T, body []byte) []byte {
		var out struct {
			Ciphertext string `json:"ciphertext"`
		}
		if err := json.Unmarshal(body, &out); err != nil {
			t.Fatal(err)
		}
		return mustDecodeB64(t, out.Ciphertext)
	}

	// ── deposit happy path: 201 with expires_at ≈ now+ttl ────────────────────
	qrID := randBytes(t, qrIDBytes)
	plaintext := bytes.Repeat([]byte("Z"), 4096)
	body, burnToken := validDeposit(t, qrID, plaintext, 24)
	before := time.Now()
	status, resp := postJSON(t, app, "/api/v1/qr-drops", body, "")
	if status != fiber.StatusCreated {
		t.Fatalf("deposit: got %d, want 201", status)
	}
	var dep struct {
		ExpiresAt string `json:"expires_at"`
	}
	if err := json.Unmarshal(resp, &dep); err != nil {
		t.Fatal(err)
	}
	expiresAt, err := time.Parse(time.RFC3339, dep.ExpiresAt)
	if err != nil {
		t.Fatalf("parse expires_at: %v", err)
	}
	wantExpiry := before.Add(24 * time.Hour)
	if delta := expiresAt.Sub(wantExpiry); delta < -time.Minute || delta > time.Minute {
		t.Fatalf("expires_at %v not within a minute of now+24h (%v)", expiresAt, wantExpiry)
	}

	// ── duplicate qr_id → 409 ────────────────────────────────────────────────
	dupBody, _ := validDeposit(t, qrID, plaintext, 24)
	status, _ = postJSON(t, app, "/api/v1/qr-drops", dupBody, "")
	if status != fiber.StatusConflict {
		t.Fatalf("duplicate qr_id: got %d, want 409", status)
	}

	// ── fetch returns the ciphertext, and fetching AGAIN returns the same bytes:
	// fetch is non-destructive by design ──────────────────────────────────────
	status, b1 := fetch(qrID)
	if status != fiber.StatusOK {
		t.Fatalf("fetch: got %d, want 200", status)
	}
	if !bytes.Equal(ciphertextOf(t, b1), plaintext) {
		t.Fatal("fetched ciphertext does not match deposited bytes")
	}
	status, b2 := fetch(qrID)
	if status != fiber.StatusOK {
		t.Fatalf("second fetch: got %d, want 200 (fetch must be non-destructive)", status)
	}
	if !bytes.Equal(ciphertextOf(t, b2), plaintext) {
		t.Fatal("second fetch returned different bytes; fetch must be repeatable")
	}

	// ── unknown qr_id → 404 ──────────────────────────────────────────────────
	if status, _ := fetch(randBytes(t, qrIDBytes)); status != fiber.StatusNotFound {
		t.Fatalf("fetch unknown qr_id: got %d, want 404", status)
	}

	// ── expired-but-not-yet-purged row is not served → 404 ───────────────────
	expiredID := randBytes(t, qrIDBytes)
	expiredHash := sha256.Sum256(randBytes(t, qrBurnTokenBytes))
	if err := store.DepositQrDrop(ctx, expiredID, []byte("stale"), expiredHash[:], time.Now().Add(-time.Hour)); err != nil {
		t.Fatalf("seed expired drop: %v", err)
	}
	if status, _ := fetch(expiredID); status != fiber.StatusNotFound {
		t.Fatalf("fetch expired drop: got %d, want 404", status)
	}

	// ── burn with the WRONG preimage → 404, and the drop stays fetchable ─────
	burn := func(qrID, token []byte) (int, []byte) {
		return postJSON(t, app, "/api/v1/qr-drops/burn", fiber.Map{"qr_id": b64url(qrID), "burn_token": b64(token)}, "")
	}
	if status, _ := burn(qrID, randBytes(t, qrBurnTokenBytes)); status != fiber.StatusNotFound {
		t.Fatalf("burn wrong preimage: got %d, want 404", status)
	}
	if status, _ := fetch(qrID); status != fiber.StatusOK {
		t.Fatalf("fetch after failed burn: got %d, want 200 (a wrong burn must not destroy the drop)", status)
	}

	// ── burn with the CORRECT preimage → 204, then fetch → 404 ───────────────
	if status, _ := burn(qrID, burnToken); status != fiber.StatusNoContent {
		t.Fatalf("burn correct preimage: got %d, want 204", status)
	}
	if status, _ := fetch(qrID); status != fiber.StatusNotFound {
		t.Fatalf("fetch after burn: got %d, want 404", status)
	}

	// ── second burn with the same token → 404 (already destroyed) ────────────
	if status, _ := burn(qrID, burnToken); status != fiber.StatusNotFound {
		t.Fatalf("second burn: got %d, want 404", status)
	}
}
