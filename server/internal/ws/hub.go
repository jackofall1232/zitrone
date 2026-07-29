// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// Package ws is the real-time delivery hub. It relays opaque encrypted
// envelopes between connected clients and deletes each envelope from storage
// the instant delivery is acknowledged. Nothing here ever inspects, stores, or
// logs message content.
package ws

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"log"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/zitrone/server/internal/db"
	"github.com/zitrone/server/internal/ratelimit"
)

const prekeyLowWatermark = 20

// Store is the subset of the storage layer the hub depends on. Kept as an
// interface so the hub can be unit-tested with an in-memory fake — *db.Store
// satisfies it. Note there is deliberately no method to look up a message's
// sender: the server never learns who sent an envelope (zero-knowledge).
type Store interface {
	PendingEnvelopes(ctx context.Context, recipientID uuid.UUID, cutoff time.Time) ([]db.PendingEnvelope, error)
	CountOneTimePrekeys(ctx context.Context, accountID uuid.UUID) (int, error)
	StoreEnvelope(ctx context.Context, id, recipientID uuid.UUID, payload []byte) error
	DeleteEnvelope(ctx context.Context, id, recipientID uuid.UUID) error
	RecordDeliveryReceipt(ctx context.Context, messageIDHash []byte) error
}

type Hub struct {
	mu        sync.RWMutex
	clients   map[uuid.UUID]*Client
	store     Store
	sendLimit *ratelimit.Limiter
	// envelopeTTL is the undelivered-message TTL, used as the delivery cutoff so a
	// reconnecting client is not handed envelopes the janitor has not swept yet
	// (0.10.2 item 3). Same value the janitor purges by — one source of truth.
	envelopeTTL time.Duration
}

func NewHub(store Store, sendLimit *ratelimit.Limiter, envelopeTTL time.Duration) *Hub {
	return &Hub{
		clients:     make(map[uuid.UUID]*Client),
		store:       store,
		sendLimit:   sendLimit,
		envelopeTTL: envelopeTTL,
	}
}

func (h *Hub) register(c *Client) {
	h.mu.Lock()
	if old, ok := h.clients[c.accountID]; ok {
		// One live connection per account — revoke the older session.
		old.send(serverEvent{Type: "session.revoked"})
		old.close()
	}
	h.clients[c.accountID] = c
	h.mu.Unlock()

	h.deliverPending(c)
	h.checkPrekeyStock(c)
}

func (h *Hub) unregister(c *Client) {
	h.mu.Lock()
	if h.clients[c.accountID] == c {
		delete(h.clients, c.accountID)
	}
	h.mu.Unlock()
}

func (h *Hub) online(accountID uuid.UUID) *Client {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return h.clients[accountID]
}

// deliverPending flushes stored envelopes to a freshly connected client.
// Envelopes stay in storage until the client acks each one.
func (h *Hub) deliverPending(c *Client) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	// The same cutoff the janitor purges by (0.10.2 item 3): an envelope past its
	// TTL is not delivered even though the sweep has not reached it yet.
	pending, err := h.store.PendingEnvelopes(ctx, c.accountID, time.Now().Add(-h.envelopeTTL))
	if err != nil {
		log.Printf("ws: pending envelope fetch failed: %v", err)
		return
	}
	for _, env := range pending {
		c.send(serverEvent{Type: "message.deliver", Envelope: env.Payload})
	}
}

func (h *Hub) checkPrekeyStock(c *Client) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	count, err := h.store.CountOneTimePrekeys(ctx, c.accountID)
	if err == nil && count < prekeyLowWatermark {
		c.send(serverEvent{Type: "prekey.low", Remaining: &count})
	}
}

// envelopeHeader is the minimal routing view of an envelope. The payload is
// stored and relayed as the raw bytes the sender produced — opaque to us.
type envelopeHeader struct {
	ID          string `json:"id"`
	RecipientID string `json:"recipient_id"`
	SenderID    string `json:"sender_id"`
}

type clientEvent struct {
	Type      string          `json:"type"`
	Envelope  json.RawMessage `json:"envelope,omitempty"`
	MessageID string          `json:"message_id,omitempty"`
	PeerID    string          `json:"peer_id,omitempty"`
	Cipher    string          `json:"ciphertext,omitempty"`
}

type serverEvent struct {
	Type      string          `json:"type"`
	Envelope  json.RawMessage `json:"envelope,omitempty"`
	MessageID string          `json:"message_id,omitempty"`
	PeerID    string          `json:"peer_id,omitempty"`
	Cipher    string          `json:"ciphertext,omitempty"`
	Remaining *int            `json:"remaining,omitempty"`
	Code      string          `json:"code,omitempty"`
}

func (h *Hub) handleEvent(c *Client, raw []byte) {
	var ev clientEvent
	if err := json.Unmarshal(raw, &ev); err != nil {
		c.send(serverEvent{Type: "error", Code: "bad_event"})
		return
	}
	switch ev.Type {
	case "message.send":
		h.handleSend(c, ev)
	case "message.ack":
		h.handleAck(c, ev)
	case "message.burn":
		h.relayToPeer(c, ev, "message.burned")
	case "message.received":
		// Recipient-originated delivery receipt: relayed to the sender by the
		// peer_id the recipient supplied. The server never learns the sender —
		// it only routes to the account the recipient addressed.
		h.relayToPeer(c, ev, "message.delivered")
	case "typing.start", "typing.stop", "presence.update", "contact.info":
		h.relaySignal(c, ev)
	default:
		c.send(serverEvent{Type: "error", Code: "unknown_event"})
	}
}

func (h *Hub) handleSend(c *Client, ev clientEvent) {
	// The header is parsed BEFORE the budget check so a rejection can name the
	// message it rejected. A per-message rejection that carries no id cannot be
	// attributed by the client, which leaves the message displayed as SENDING
	// forever — not failed, not retried, nothing surfaced to the user. Echoing
	// the id is not a disclosure: it is the sender's own id on the sender's own
	// connection, the same reasoning that already applies to message.stored.
	//
	// The cost is that a frame rejected by the limiter is now unmarshalled
	// first. That is bounded by the read limit the transport already imposes,
	// and there is no way to name a message without reading its id.
	var header envelopeHeader
	parseErr := json.Unmarshal(ev.Envelope, &header)

	// Every send attempt consumes a permit, well-formed or not: a malformed
	// frame must not be a free pass through the limiter.
	allowed := h.sendLimit.Allow(c.accountID.String())

	// Echoed only when it is a well-formed UUID, so a malformed header cannot
	// make the relay reflect arbitrary client-supplied bytes back.
	id, idErr := uuid.Parse(header.ID)
	msgID := ""
	if parseErr == nil && idErr == nil {
		msgID = id.String()
	}

	// rate_limited keeps precedence over bad_envelope, as before.
	if !allowed {
		c.send(serverEvent{Type: "error", Code: "rate_limited", MessageID: msgID})
		return
	}
	if parseErr != nil {
		// No id here by construction: msgID is empty whenever parseErr != nil,
		// so this frame carries none. The bad_envelope below can carry one.
		c.send(serverEvent{Type: "error", Code: "bad_envelope"})
		return
	}
	recipient, err2 := uuid.Parse(header.RecipientID)
	if idErr != nil || err2 != nil || header.SenderID != c.accountID.String() {
		c.send(serverEvent{Type: "error", Code: "bad_envelope", MessageID: msgID})
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := h.store.StoreEnvelope(ctx, id, recipient, ev.Envelope); err != nil {
		c.send(serverEvent{Type: "error", Code: "store_failed", MessageID: msgID})
		return
	}
	// SENT tick: acknowledge to the sending connection that the relay has the
	// envelope. Reveals nothing new (the sender already knows its own message
	// id) and persists nothing. Sent whether or not the recipient is online.
	c.send(serverEvent{Type: "message.stored", MessageID: header.ID})
	if peer := h.online(recipient); peer != nil {
		peer.send(serverEvent{Type: "message.deliver", Envelope: ev.Envelope})
	}
}

// handleAck deletes the envelope immediately — store-and-forward only — and
// records a content-free delivery receipt (hash of the message ID).
func (h *Hub) handleAck(c *Client, ev clientEvent) {
	id, err := uuid.Parse(ev.MessageID)
	if err != nil {
		c.send(serverEvent{Type: "error", Code: "bad_ack"})
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := h.store.DeleteEnvelope(ctx, id, c.accountID); err != nil {
		log.Printf("ws: envelope delete failed: %v", err)
		return
	}
	hash := sha256.Sum256([]byte(ev.MessageID))
	_ = h.store.RecordDeliveryReceipt(ctx, hash[:])
}

func (h *Hub) relayToPeer(c *Client, ev clientEvent, outType string) {
	peer, err := uuid.Parse(ev.PeerID)
	if err != nil {
		c.send(serverEvent{Type: "error", Code: "bad_peer"})
		return
	}
	if target := h.online(peer); target != nil {
		target.send(serverEvent{
			Type:      outType,
			MessageID: ev.MessageID,
			PeerID:    c.accountID.String(),
		})
	}
}

// relaySignal forwards encrypted typing/presence signals verbatim.
func (h *Hub) relaySignal(c *Client, ev clientEvent) {
	peer, err := uuid.Parse(ev.PeerID)
	if err != nil {
		return
	}
	if target := h.online(peer); target != nil {
		target.send(serverEvent{
			Type:   ev.Type,
			PeerID: c.accountID.String(),
			Cipher: ev.Cipher,
		})
	}
}
