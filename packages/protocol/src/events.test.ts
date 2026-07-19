// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from "vitest";
import {
  CLIENT_EVENT_TYPES,
  SERVER_EVENT_TYPES,
  type ClientEvent,
  type MessageDeliveredEvent,
  type MessageReceivedEvent,
  type MessageStoredEvent,
  type ServerEvent,
} from "./events.js";

const uuid = "0b9f8c1e-4f2a-4d8b-9c3e-7a6b5d4c3b2a";
const account = "a1b2c3d4e5f6";

describe("send-state event types", () => {
  it("registers the delivery-receipt frame as a client event", () => {
    expect(CLIENT_EVENT_TYPES).toContain("message.received");
  });

  it("registers the sent/delivered ticks as server events", () => {
    expect(SERVER_EVENT_TYPES).toContain("message.stored");
    expect(SERVER_EVENT_TYPES).toContain("message.delivered");
  });

  it("keeps the flat snake_case wire shape (message_id / peer_id)", () => {
    // message.received — recipient → server, addressed back to the sender.
    const received: MessageReceivedEvent = {
      type: "message.received",
      message_id: uuid,
      peer_id: account,
    };
    // message.stored — server → sender, no peer (own message id only).
    const stored: MessageStoredEvent = { type: "message.stored", message_id: uuid };
    // message.delivered — server → sender, relayed by recipient peer_id.
    const delivered: MessageDeliveredEvent = {
      type: "message.delivered",
      message_id: uuid,
      peer_id: account,
    };

    // JSON round-trip must preserve the exact snake_case field names so all
    // clients and the server interoperate on the wire.
    expect(JSON.parse(JSON.stringify(received))).toEqual({
      type: "message.received",
      message_id: uuid,
      peer_id: account,
    });
    expect(JSON.parse(JSON.stringify(stored))).toEqual({
      type: "message.stored",
      message_id: uuid,
    });
    expect(JSON.parse(JSON.stringify(delivered))).toEqual({
      type: "message.delivered",
      message_id: uuid,
      peer_id: account,
    });
  });

  it("admits the new frames into the discriminated unions", () => {
    const client: ClientEvent = { type: "message.received", message_id: uuid, peer_id: account };
    const server: ServerEvent[] = [
      { type: "message.stored", message_id: uuid },
      { type: "message.delivered", message_id: uuid, peer_id: account },
    ];
    expect(client.type).toBe("message.received");
    expect(server.map((e) => e.type)).toEqual(["message.stored", "message.delivered"]);
  });
});
