// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package api

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"io"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"

	"github.com/zitrone/server/internal/auth"
	"github.com/zitrone/server/internal/config"
	"github.com/zitrone/server/internal/db"
	"github.com/zitrone/server/internal/ratelimit"
)

// blobTestIssuer builds a real RSA issuer the same way internal/auth's tests do,
// so DepositBlob's RequireAuth middleware can validate a genuine access token.
func blobTestIssuer(t *testing.T) *auth.Issuer {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	privPath := filepath.Join(dir, "jwt.pem")
	pubPath := filepath.Join(dir, "jwt.pub.pem")
	privDER, _ := x509.MarshalPKCS8PrivateKey(key)
	if err := os.WriteFile(privPath, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: privDER}), 0o600); err != nil {
		t.Fatal(err)
	}
	pubDER, _ := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err := os.WriteFile(pubPath, pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: pubDER}), 0o644); err != nil {
		t.Fatal(err)
	}
	issuer, err := auth.NewIssuer(privPath, pubPath)
	if err != nil {
		t.Fatal(err)
	}
	return issuer
}

// blobTestConfig is a minimal config with rate limiting disabled so the limiter
// never interferes with assertions.
func blobTestConfig() *config.Config {
	return &config.Config{
		BlobMaxBytes:     8 * 1024 * 1024,
		BlobTTLHours:     72,
		RateLimitEnabled: false,
	}
}

// newBlobHandlers builds a Handlers whose blob endpoints are reachable without a
// database. store is left nil: every test here asserts on a path that returns
// before touching the store (validation, auth, guard), so the store is never
// dereferenced. Round-trip/replay/duplicate live in the DATABASE_URL-gated test.
func newBlobHandlers(t *testing.T, store *db.Store) *Handlers {
	t.Helper()
	cfg := blobTestConfig()
	return &Handlers{
		store:     store,
		issuer:    blobTestIssuer(t),
		cfg:       cfg,
		blobLimit: ratelimit.New(1000, time.Minute, false),
	}
}

// blobTestApp wires the real blob routes and guard the same way main.go does.
func blobTestApp(t *testing.T, h *Handlers) *fiber.App {
	t.Helper()
	app := fiber.New(fiber.Config{BodyLimit: BlobBodyLimit(h.cfg)})
	app.Use(h.BodyLimitGuard)
	v1 := app.Group("/api/v1")
	v1.Post("/blobs", h.RequireAuth, h.DepositBlob)
	v1.Post("/blobs/redeem", h.RedeemBlob)
	return app
}

func bearer(t *testing.T, issuer *auth.Issuer) string {
	t.Helper()
	token, err := issuer.IssueAccessToken(uuid.New(), time.Now())
	if err != nil {
		t.Fatal(err)
	}
	return "Bearer " + token
}

func postJSON(t *testing.T, app *fiber.App, path string, body any, auth string) (int, []byte) {
	t.Helper()
	raw, err := json.Marshal(body)
	if err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest(fiber.MethodPost, path, bytes.NewReader(raw))
	req.Header.Set("Content-Type", "application/json")
	if auth != "" {
		req.Header.Set("Authorization", auth)
	}
	// -1 disables app.Test's default 1s timeout — large blob bodies stream slowly
	// through the in-memory pipe.
	resp, err := app.Test(req, -1)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	out, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, out
}

func b64(b []byte) string { return base64.StdEncoding.EncodeToString(b) }

// ── validation / auth (no database required) ─────────────────────────────────

// A blob upload without a bearer token is rejected by RequireAuth before the
// handler runs.
func TestBlobDeposit_Unauthenticated(t *testing.T) {
	h := newBlobHandlers(t, nil)
	app := blobTestApp(t, h)
	blobID := sha256.Sum256([]byte("token"))
	status, _ := postJSON(t, app, "/api/v1/blobs", fiber.Map{
		"blob_id":    b64(blobID[:]),
		"ciphertext": b64([]byte("x")),
	}, "")
	if status != fiber.StatusUnauthorized {
		t.Fatalf("unauthenticated upload: got %d, want 401", status)
	}
}

// A wrong-length blob_id is a 400, before the store is touched.
func TestBlobDeposit_BadBlobIDLength(t *testing.T) {
	h := newBlobHandlers(t, nil)
	app := blobTestApp(t, h)
	status, _ := postJSON(t, app, "/api/v1/blobs", fiber.Map{
		"blob_id":    b64([]byte("too-short")),
		"ciphertext": b64([]byte("x")),
	}, bearer(t, h.issuer))
	if status != fiber.StatusBadRequest {
		t.Fatalf("bad blob_id length: got %d, want 400", status)
	}
}

// Ciphertext beyond the effective cap is rejected with 413 before the store.
func TestBlobDeposit_OversizeCiphertext(t *testing.T) {
	h := newBlobHandlers(t, nil)
	app := blobTestApp(t, h)
	blobID := sha256.Sum256([]byte("token"))
	oversize := make([]byte, BlobEffectiveCap(h.cfg)+1)
	status, _ := postJSON(t, app, "/api/v1/blobs", fiber.Map{
		"blob_id":    b64(blobID[:]),
		"ciphertext": b64(oversize),
	}, bearer(t, h.issuer))
	if status != fiber.StatusRequestEntityTooLarge {
		t.Fatalf("oversize ciphertext: got %d, want 413", status)
	}
}

// Redemption requires no auth: a request with no Authorization header reaches
// the handler and fails only on the malformed token (400), never 401.
func TestBlobRedeem_NoAuthRequired(t *testing.T) {
	h := newBlobHandlers(t, nil)
	app := blobTestApp(t, h)
	status, _ := postJSON(t, app, "/api/v1/blobs/redeem", fiber.Map{
		"token": b64([]byte("short")),
	}, "")
	if status == fiber.StatusUnauthorized {
		t.Fatal("redeem must not require authentication")
	}
	if status != fiber.StatusBadRequest {
		t.Fatalf("wrong-length token: got %d, want 400", status)
	}
}

// ── body-limit guard (no database required) ──────────────────────────────────

// bigBody builds a request body larger than the default 512 KiB cap.
func bigBody() []byte { return bytes.Repeat([]byte("A"), maxDefaultBody+1024) }

// The guard rejects an oversized Content-Length on a non-blob route with 413,
// even though the app-level BodyLimit is raised for blob uploads.
func TestBodyLimitGuard_RejectsLargeNonBlobRoute(t *testing.T) {
	h := newBlobHandlers(t, nil)
	app := fiber.New(fiber.Config{BodyLimit: BlobBodyLimit(h.cfg)})
	app.Use(h.BodyLimitGuard)
	app.Post("/api/v1/register", func(c *fiber.Ctx) error { return c.SendStatus(fiber.StatusOK) })

	req := httptest.NewRequest(fiber.MethodPost, "/api/v1/register", bytes.NewReader(bigBody()))
	resp, err := app.Test(req, -1)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != fiber.StatusRequestEntityTooLarge {
		t.Fatalf("large body on non-blob route: got %d, want 413", resp.StatusCode)
	}
}

// The guard exempts the blob upload route: an oversized body passes through to
// the handler. Uses a stub terminal handler so no store or auth is needed — this
// isolates the guard, proving it (not the BodyLimit) enforces the default cap.
func TestBodyLimitGuard_AllowsLargeBlobUpload(t *testing.T) {
	h := newBlobHandlers(t, nil)
	app := fiber.New(fiber.Config{BodyLimit: BlobBodyLimit(h.cfg)})
	app.Use(h.BodyLimitGuard)
	app.Post("/api/v1/blobs", func(c *fiber.Ctx) error { return c.SendStatus(fiber.StatusOK) })

	req := httptest.NewRequest(fiber.MethodPost, "/api/v1/blobs", bytes.NewReader(bigBody()))
	resp, err := app.Test(req, -1)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != fiber.StatusOK {
		t.Fatalf("large blob upload blocked by guard: got %d, want 200", resp.StatusCode)
	}
}

// ── round-trip / replay / duplicate (integration, needs Postgres) ────────────

// These exercise the store, so they run only when DATABASE_URL points at a live
// Postgres and skip otherwise — mirroring the rest of the suite, which is
// DB-less. When run, they cover deposit+redeem round-trip, single-use replay
// (404), and duplicate blob_id (409).
func TestBlobStore_RoundTripReplayDuplicate(t *testing.T) {
	dsn := os.Getenv("DATABASE_URL")
	if dsn == "" {
		t.Skip("DATABASE_URL not set; skipping blob store integration test")
	}
	ctx := context.Background()
	store, err := db.Open(ctx, dsn)
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	defer store.Close()

	h := newBlobHandlers(t, store)
	app := blobTestApp(t, h)

	token := make([]byte, blobTokenBytes)
	if _, err := rand.Read(token); err != nil {
		t.Fatal(err)
	}
	blobID := sha256.Sum256(token)
	plaintext := bytes.Repeat([]byte("Z"), 4096)

	// Deposit.
	status, _ := postJSON(t, app, "/api/v1/blobs", fiber.Map{
		"blob_id":    b64(blobID[:]),
		"ciphertext": b64(plaintext),
	}, bearer(t, h.issuer))
	if status != fiber.StatusCreated {
		t.Fatalf("deposit: got %d, want 201", status)
	}

	// Duplicate blob_id → 409.
	status, _ = postJSON(t, app, "/api/v1/blobs", fiber.Map{
		"blob_id":    b64(blobID[:]),
		"ciphertext": b64(plaintext),
	}, bearer(t, h.issuer))
	if status != fiber.StatusConflict {
		t.Fatalf("duplicate deposit: got %d, want 409", status)
	}

	// Redeem returns the exact ciphertext.
	status, body := postJSON(t, app, "/api/v1/blobs/redeem", fiber.Map{"token": b64(token)}, "")
	if status != fiber.StatusOK {
		t.Fatalf("redeem: got %d, want 200", status)
	}
	var out struct {
		Ciphertext string `json:"ciphertext"`
	}
	if err := json.Unmarshal(body, &out); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(mustDecodeB64(t, out.Ciphertext), plaintext) {
		t.Fatal("redeemed ciphertext does not match deposited bytes")
	}

	// Replay → 404 (single-use).
	status, _ = postJSON(t, app, "/api/v1/blobs/redeem", fiber.Map{"token": b64(token)}, "")
	if status != fiber.StatusNotFound {
		t.Fatalf("replayed redeem: got %d, want 404", status)
	}
}

// Guard against accidental removal of the 512 KiB exemption boundary.
func TestBlobUploadPathConstant(t *testing.T) {
	if !strings.HasPrefix(blobUploadPath, "/api/v1/") {
		t.Fatalf("unexpected blob upload path %q", blobUploadPath)
	}
}
