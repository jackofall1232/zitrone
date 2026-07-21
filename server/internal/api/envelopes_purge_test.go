// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package api

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"io"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"

	"github.com/zitrone/server/internal/config"
	"github.com/zitrone/server/internal/db"
)

// Validation / auth paths never touch the store — store may be nil.
func newEnvelopePurgeHandlers(t *testing.T, store *db.Store) *Handlers {
	t.Helper()
	return &Handlers{
		store:  store,
		issuer: blobTestIssuer(t),
		cfg:    &config.Config{RateLimitEnabled: false},
	}
}

func envelopePurgeApp(h *Handlers) *fiber.App {
	app := fiber.New()
	v1 := app.Group("/api/v1")
	v1.Delete("/envelopes/to/:peerId", h.RequireAuth, h.PurgeEnvelopesToPeer)
	return app
}

func deleteWithAuth(t *testing.T, app *fiber.App, path, authHeader string) (int, []byte) {
	t.Helper()
	req := httptest.NewRequest(fiber.MethodDelete, path, nil)
	if authHeader != "" {
		req.Header.Set("Authorization", authHeader)
	}
	resp, err := app.Test(req, -1)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	out, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, out
}

func TestPurgeEnvelopesToPeer_Unauthenticated(t *testing.T) {
	h := newEnvelopePurgeHandlers(t, nil)
	app := envelopePurgeApp(h)
	peer := uuid.New()
	status, _ := deleteWithAuth(t, app, "/api/v1/envelopes/to/"+peer.String(), "")
	if status != fiber.StatusUnauthorized {
		t.Fatalf("got %d, want 401", status)
	}
}

func TestPurgeEnvelopesToPeer_BadPeer(t *testing.T) {
	h := newEnvelopePurgeHandlers(t, nil)
	app := envelopePurgeApp(h)
	token := bearer(t, h.issuer)
	status, body := deleteWithAuth(t, app, "/api/v1/envelopes/to/not-a-uuid", token)
	if status != fiber.StatusBadRequest {
		t.Fatalf("got %d, want 400; body=%s", status, body)
	}
}

func TestPurgeEnvelopesToPeer_SelfIsNoop(t *testing.T) {
	// Self-purge must not require a store (no-op before store is touched).
	issuer := blobTestIssuer(t)
	self := uuid.New()
	tok, err := issuer.IssueAccessToken(self, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	h := &Handlers{
		store:  nil,
		issuer: issuer,
		cfg:    &config.Config{RateLimitEnabled: false},
	}
	app := envelopePurgeApp(h)
	status, _ := deleteWithAuth(t, app, "/api/v1/envelopes/to/"+self.String(), "Bearer "+tok)
	if status != fiber.StatusNoContent {
		t.Fatalf("self purge: got %d, want 204", status)
	}
}

// Integration: only the caller's envelopes to the peer are deleted; another
// sender's pending envelope to the same peer survives.
func TestPurgeEnvelopesToPeer_StoreScoped(t *testing.T) {
	dsn := os.Getenv("DATABASE_URL")
	if dsn == "" {
		t.Skip("DATABASE_URL not set; skipping envelope-purge store integration test")
	}
	ctx := context.Background()
	store, err := db.Open(ctx, dsn)
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	defer store.Close()

	sender := uuid.New()
	otherSender := uuid.New()
	peer := uuid.New()

	idKey := make([]byte, 32)
	if _, err := rand.Read(idKey); err != nil {
		t.Fatal(err)
	}
	if err := store.CreateAccount(ctx, sender, idKey); err != nil {
		t.Fatal(err)
	}
	if err := store.CreateAccount(ctx, otherSender, idKey); err != nil {
		t.Fatal(err)
	}
	// Peer need not be a registered account (envelopes have no FK) — leave it
	// as a bare UUID, which is the realistic decoy-friendly case.

	mineID := uuid.New()
	theirsID := uuid.New()
	minePayload, _ := json.Marshal(map[string]string{
		"id": mineID.String(), "sender_id": sender.String(), "recipient_id": peer.String(),
	})
	theirsPayload, _ := json.Marshal(map[string]string{
		"id": theirsID.String(), "sender_id": otherSender.String(), "recipient_id": peer.String(),
	})
	if err := store.StoreEnvelope(ctx, mineID, peer, minePayload); err != nil {
		t.Fatal(err)
	}
	if err := store.StoreEnvelope(ctx, theirsID, peer, theirsPayload); err != nil {
		t.Fatal(err)
	}

	n, err := store.PurgeEnvelopesToPeer(ctx, sender, peer)
	if err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Fatalf("deleted %d rows, want 1", n)
	}

	pending, err := store.PendingEnvelopes(ctx, peer)
	if err != nil {
		t.Fatal(err)
	}
	if len(pending) != 1 || pending[0].ID != theirsID {
		t.Fatalf("pending after purge = %+v, want only %s", pending, theirsID)
	}

	// Cleanup leftover envelope + accounts so the shared test DB stays tidy.
	_ = store.DeleteEnvelope(ctx, theirsID, peer)
	_ = store.DeleteAccount(ctx, sender)
	_ = store.DeleteAccount(ctx, otherSender)
}
