// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package ws

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/zitrone/server/internal/db"
	"github.com/zitrone/server/internal/ratelimit"
)

// fakeStore is an in-memory stand-in for *db.Store. It deliberately keeps no
// notion of a message's sender — the hub never asks for one, and the honest
// send-state receipts must not introduce such a lookup (zero-knowledge).
type fakeStore struct {
	storeErr error
	stored   map[uuid.UUID]uuid.UUID // envelope id -> recipient
	deleted  []uuid.UUID
	receipts [][]byte
}

func newFakeStore() *fakeStore {
	return &fakeStore{stored: make(map[uuid.UUID]uuid.UUID)}
}

func (f *fakeStore) PendingEnvelopes(ctx context.Context, recipientID uuid.UUID) ([]db.PendingEnvelope, error) {
	return nil, nil
}

func (f *fakeStore) CountOneTimePrekeys(ctx context.Context, accountID uuid.UUID) (int, error) {
	// Above the low watermark so register() never emits prekey.low in tests.
	return prekeyLowWatermark + 1, nil
}

func (f *fakeStore) StoreEnvelope(ctx context.Context, id, recipientID uuid.UUID, payload []byte) error {
	if f.storeErr != nil {
		return f.storeErr
	}
	f.stored[id] = recipientID
	return nil
}

func (f *fakeStore) DeleteEnvelope(ctx context.Context, id, recipientID uuid.UUID) error {
	f.deleted = append(f.deleted, id)
	return nil
}

func (f *fakeStore) RecordDeliveryReceipt(ctx context.Context, messageIDHash []byte) error {
	f.receipts = append(f.receipts, messageIDHash)
	return nil
}

// newTestHub builds a hub over a fake store with rate limiting disabled.
func newTestHub(store Store) *Hub {
	return NewHub(store, ratelimit.New(1000, time.Minute, false))
}

// newTestClient creates a client whose send() path only touches its outbox
// (no websocket conn), so tests can read emitted frames directly.
func newTestClient(id uuid.UUID) *Client {
	return &Client{
		accountID: id,
		outbox:    make(chan serverEvent, 64),
		done:      make(chan struct{}),
	}
}

// add registers a client in the hub without triggering the DB-backed
// register() side effects (deliverPending / checkPrekeyStock).
func (h *Hub) add(c *Client) {
	h.mu.Lock()
	h.clients[c.accountID] = c
	h.mu.Unlock()
}

// drainType returns the first buffered frame of the given type, or fails.
func drainType(t *testing.T, c *Client, typ string) serverEvent {
	t.Helper()
	for {
		select {
		case ev := <-c.outbox:
			if ev.Type == typ {
				return ev
			}
		default:
			t.Fatalf("no %q frame emitted to %s", typ, c.accountID)
			return serverEvent{}
		}
	}
}

func mustNoFrame(t *testing.T, c *Client) {
	t.Helper()
	select {
	case ev := <-c.outbox:
		t.Fatalf("unexpected frame emitted: %+v", ev)
	default:
	}
}

func sendEnvelope(t *testing.T, id, sender, recipient uuid.UUID) clientEvent {
	t.Helper()
	env, err := json.Marshal(envelopeHeader{
		ID:          id.String(),
		RecipientID: recipient.String(),
		SenderID:    sender.String(),
	})
	if err != nil {
		t.Fatal(err)
	}
	return clientEvent{Type: "message.send", Envelope: env}
}

// (a) After a valid message.send, the SENDER connection receives a
// message.stored carrying the envelope's own id.
func TestHandleSend_EmitsStoredToSender(t *testing.T) {
	store := newFakeStore()
	h := newTestHub(store)

	sender := uuid.New()
	recipient := uuid.New() // offline
	msgID := uuid.New()

	c := newTestClient(sender)
	h.add(c)

	h.handleSend(c, sendEnvelope(t, msgID, sender, recipient))

	ev := drainType(t, c, "message.stored")
	if ev.MessageID != msgID.String() {
		t.Fatalf("message.stored id = %q, want %q", ev.MessageID, msgID.String())
	}
	// Emitted even though the recipient is offline.
	if _, ok := store.stored[msgID]; !ok {
		t.Fatalf("envelope was not stored")
	}
}

// A failed store must NOT emit message.stored — only the error frame.
func TestHandleSend_StoreFailure_NoStored(t *testing.T) {
	store := newFakeStore()
	store.storeErr = context.DeadlineExceeded
	h := newTestHub(store)

	sender := uuid.New()
	c := newTestClient(sender)
	h.add(c)

	h.handleSend(c, sendEnvelope(t, uuid.New(), sender, uuid.New()))

	ev := drainType(t, c, "error")
	if ev.Code != "store_failed" {
		t.Fatalf("error code = %q, want store_failed", ev.Code)
	}
	// No message.stored should be buffered.
	for {
		select {
		case e := <-c.outbox:
			if e.Type == "message.stored" {
				t.Fatalf("message.stored emitted despite store failure")
			}
		default:
			return
		}
	}
}

// (b) A recipient's message.received is relayed to the addressed peer (the
// sender) as message.delivered, carrying the same message_id and peer_id set
// to the relayer's (recipient's) account id.
func TestHandleReceived_RelaysDeliveredToSender(t *testing.T) {
	h := newTestHub(newFakeStore())

	sender := uuid.New()
	recipient := uuid.New()
	msgID := uuid.New()

	senderClient := newTestClient(sender)
	recipientClient := newTestClient(recipient)
	h.add(senderClient)
	h.add(recipientClient)

	// Recipient reports it received msgID, addressing the receipt to the sender.
	received := clientEvent{
		Type:      "message.received",
		MessageID: msgID.String(),
		PeerID:    sender.String(),
	}
	raw, _ := json.Marshal(received)
	h.handleEvent(recipientClient, raw)

	ev := drainType(t, senderClient, "message.delivered")
	if ev.MessageID != msgID.String() {
		t.Fatalf("delivered id = %q, want %q", ev.MessageID, msgID.String())
	}
	if ev.PeerID != recipient.String() {
		t.Fatalf("delivered peer_id = %q, want recipient %q", ev.PeerID, recipient.String())
	}
	// The recipient itself receives nothing back.
	mustNoFrame(t, recipientClient)
}

// (c) message.received addressed to an OFFLINE peer is a silent no-op.
func TestHandleReceived_OfflinePeer_NoOp(t *testing.T) {
	h := newTestHub(newFakeStore())

	sender := uuid.New() // never registered → offline
	recipient := uuid.New()

	recipientClient := newTestClient(recipient)
	h.add(recipientClient)

	received := clientEvent{
		Type:      "message.received",
		MessageID: uuid.New().String(),
		PeerID:    sender.String(),
	}
	raw, _ := json.Marshal(received)

	// Must not panic and must emit nothing to the reporting client.
	h.handleEvent(recipientClient, raw)
	mustNoFrame(t, recipientClient)
}
