// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import type { MessageEnvelope } from "./envelope.js";

// ── Client → server ──────────────────────────────────────────────────────────

export interface MessageSendEvent {
  type: "message.send";
  envelope: MessageEnvelope;
}

/** Delivery confirmation — triggers immediate server-side deletion of the envelope. */
export interface MessageAckEvent {
  type: "message.ack";
  message_id: string;
}

/** Request early destruction of a message on all devices. */
export interface MessageBurnEvent {
  type: "message.burn";
  message_id: string;
  peer_id: string;
}

/**
 * Delivery receipt (DELIVERED tick). Sent by the RECIPIENT once it has
 * decrypted and stored an inbound message — right where it acks. `peer_id` is
 * the ORIGINAL sender's account id (read from the decrypted envelope), so the
 * relay can route the receipt back to them without ever having stored who the
 * sender was: it is peer-routed exactly like `message.burn`, preserving the
 * server's zero-knowledge of the sender.
 */
export interface MessageReceivedEvent {
  type: "message.received";
  message_id: string;
  peer_id: string;
}

/**
 * Typing and presence signals carry an encrypted payload, not plaintext state —
 * the server relays them without learning what they say.
 */
export interface TypingStartEvent {
  type: "typing.start";
  peer_id: string;
  ciphertext: string;
}

export interface TypingStopEvent {
  type: "typing.stop";
  peer_id: string;
  ciphertext: string;
}

export interface PresenceUpdateEvent {
  type: "presence.update";
  ciphertext: string;
}

/**
 * Encrypted contact/conversation info signal (v1.5). Carries the sender's
 * current platform so the recipient can show the platform-warning badge, plus
 * any other conversation metadata. The payload is encrypted — the server relays
 * it verbatim and never learns the platform from message content. Used for the
 * real-time platform-warning update when a contact switches clients.
 */
export interface ContactInfoEvent {
  type: "contact.info";
  peer_id: string;
  ciphertext: string;
}

export type ClientEvent =
  | MessageSendEvent
  | MessageAckEvent
  | MessageBurnEvent
  | MessageReceivedEvent
  | TypingStartEvent
  | TypingStopEvent
  | PresenceUpdateEvent
  | ContactInfoEvent;

// ── Server → client ──────────────────────────────────────────────────────────

export interface MessageDeliverEvent {
  type: "message.deliver";
  envelope: MessageEnvelope;
}

/**
 * The relay stored a sent envelope (SENT tick). Emitted on the sending
 * connection after the envelope is persisted; `message_id` is the envelope's
 * own id, so this reveals nothing the sender didn't already know.
 */
export interface MessageStoredEvent {
  type: "message.stored";
  message_id: string;
}

/**
 * The recipient received a message (DELIVERED tick). The relay's peer-routed
 * relay of the recipient's `message.received`; `peer_id` is the recipient's
 * account id. Best-effort — dropped if the sender is offline.
 */
export interface MessageDeliveredEvent {
  type: "message.delivered";
  message_id: string;
  peer_id: string;
}

/** The recipient destroyed a message (burn-on-read or manual burn). */
export interface MessageBurnedEvent {
  type: "message.burned";
  message_id: string;
  peer_id: string;
}

/** One-time prekey stock is low — client should upload a fresh batch. */
export interface PrekeyLowEvent {
  type: "prekey.low";
  remaining: number;
}

/** Force logout — the session was revoked. */
export interface SessionRevokedEvent {
  type: "session.revoked";
}

export interface ErrorEvent {
  type: "error";
  /** Machine-readable code; never contains message content or user data. */
  code: string;
}

export type ServerEvent =
  | MessageDeliverEvent
  | MessageStoredEvent
  | MessageDeliveredEvent
  | MessageBurnedEvent
  | PrekeyLowEvent
  | SessionRevokedEvent
  | ErrorEvent
  // typing/presence/contact signals are relayed to the peer verbatim
  | TypingStartEvent
  | TypingStopEvent
  | PresenceUpdateEvent
  | ContactInfoEvent;

export const CLIENT_EVENT_TYPES = [
  "message.send",
  "message.ack",
  "message.burn",
  "message.received",
  "typing.start",
  "typing.stop",
  "presence.update",
  "contact.info",
] as const;

export const SERVER_EVENT_TYPES = [
  "message.deliver",
  "message.stored",
  "message.delivered",
  "message.burned",
  "prekey.low",
  "session.revoked",
  "error",
  "typing.start",
  "typing.stop",
  "presence.update",
  "contact.info",
] as const;
