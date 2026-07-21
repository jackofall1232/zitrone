// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// Application state + messaging service. Decrypted messages live in memory
// only — nothing content-bearing is ever persisted. The keystore persists
// inside the fixed-size multi-vault image (see lib/storage.ts): padded to a
// constant payload size and AES-256-GCM-encrypted under this vault's key.

import {
  createLemonDrop,
  decryptAttachmentBlob,
  encryptAttachmentBlob,
  fingerprintOf,
  fromBase64,
  generateDropToken,
  generateIdentityKeyPair,
  generateOneTimePrekeys,
  generateSignedPrekey,
  identityKeyToX25519,
  openLemonDrop,
  openSealed,
  pad,
  ratchetDecrypt,
  ratchetEncrypt,
  safetyNumber,
  sealTo,
  signWithIdentity,
  solveProofOfWork,
  toBase64,
  unpad,
  utf8Decode,
  utf8Encode,
  wipe,
  x3dhInitiate,
  x3dhRespond,
  type KeyStore,
} from "@zitrone/crypto";
import {
  DROP_POW_DIFFICULTY,
  encodeQrDropId,
  ONE_TIME_PREKEY_BATCH,
  parseEnvelope,
  parseQrDropUrl,
  parseReadReceipt,
  PROTOCOL_VERSION,
  serializeAttachment,
  type AttachmentKind,
  type AttachmentPayload,
  type MessageEnvelope,
  type QrDropTTLHours,
  type ServerEvent,
} from "@zitrone/protocol";
import { DecoyScheduler } from "@zitrone/relay-client";
import { create } from "zustand";
import { useSettings } from "./settings.js";
import { api, ApiError } from "./lib/api.js";
import { classifyIncoming } from "./lib/incoming.js";
import { bytesToBlob, type PreparedAttachment } from "./lib/attachments.js";
import { b64, unb64 } from "./lib/bytes.js";
import { IMAGE_REVEAL_MS, isRevealableImage } from "./lib/reveal.js";
import {
  deserializeIdentity,
  deserializeOneTimePrekey,
  deserializeSession,
  deserializeSignedPrekey,
  serializeIdentity,
  serializeOneTimePrekey,
  serializeSession,
  serializeSignedPrekey,
  type SerializedIdentity,
  type SerializedOneTimePrekey,
  type SerializedSession,
  type SerializedSignedPrekey,
} from "./lib/serialization.js";
import {
  createVault,
  destroyVaultImage,
  destroyVaultSlot,
  hasVault,
  persistVault,
  retireVaultSession,
  unlockVault,
  type VaultSession,
} from "./lib/storage.js";
import { WsClient, type WsStatus } from "./lib/ws.js";

export interface ContactRecord {
  displayName: string;
  identityKey: string; // peer's Ed25519 public key, base64
  // Null ONLY for a mobile (Curve25519) lemon-drop sender we learned from an
  // opened drop: web cannot build an ordinary session across the key-family
  // wall, and a drop is one-way, so there is no session to store. Such a
  // contact renders received drops but cannot be sent ordinary messages —
  // every ordinary-send path guards on this being non-null.
  session: SerializedSession | null;
  // X3DH header data repeated on every message until the peer first replies
  pendingEphemeralKey: string | null;
  pendingPrekeyId: number | null;
}

/**
 * In-memory view of an attachment on a message. Decrypted bytes live only in
 * the `blob` here — the same memory-only policy as message plaintext; nothing
 * attachment-bearing is ever persisted.
 */
export interface AttachmentView {
  kind: AttachmentKind;
  mimetype: string;
  filename: string | null;
  size: number;
  caption: string | null;
  /** loading → redeeming/decrypting; ready → renderable; expired → gone. */
  status: "loading" | "ready" | "expired";
  /** Decrypted bytes, present only when status is "ready". */
  blob?: Blob;
  /**
   * Reveal-and-burn state for a RECEIVED image. Received images render covered
   * (the bytes are never drawn) until the recipient taps to reveal; the reveal
   * arms a hard 10s timer after which the image re-covers and the message burns
   * on both ends (reusing the ordinary `message.burn` signal). Undefined/false =
   * covered. Meaningless for sent images and files.
   */
  revealed?: boolean;
}

/**
 * Send-state for an outgoing message. Honest per-frame:
 *   sending   composed, awaiting the relay's "stored" ack (or offline queue)
 *   sent      relay stored the envelope (message.stored) — one tick
 *   delivered recipient decrypted+stored it (message.delivered) — two ticks
 *   read      recipient opened it (peer read receipt) — two ticks, accent
 *   failed    the send could not be handed to the relay — "!" + retry
 * Received messages carry "delivered" and never render ticks.
 */
export type SendStatus = "sending" | "sent" | "delivered" | "read" | "failed";

export interface Message {
  id: string;
  peerId: string;
  direction: "sent" | "received";
  text: string;
  timestamp: string;
  ttlSeconds: number | null;
  burnOnRead: boolean;
  /** Outgoing send-state (see SendStatus). Not rendered for received messages. */
  status: SendStatus;
  /**
   * When the self-destruct timer starts. Incoming: on arrival. Outgoing: only
   * once DELIVERED (a real receipt) — null until then, so the countdown never
   * runs against a message the recipient hasn't got yet.
   */
  deliveredAt: number | null;
  burning: boolean;
  opened: boolean;
  /** Present when this message carries an attachment (image/file). */
  attachment?: AttachmentView;
  /** A control payload this client can't render — shown as a generic notice. */
  unsupported?: boolean;
}

type Phase = "loading" | "setup" | "unlock" | "ready";

interface AppState {
  phase: Phase;
  unlockError?: string;
  accountId: string | null;
  /**
   * This device's OWN identity-key self-fingerprint (fingerprintOf), used as
   * the always-on "security paper" watermark tiled behind the chat surfaces.
   * Null until an identity is unlocked/created; a compute failure also leaves
   * it null (never blocks unlock).
   */
  localFingerprint: string | null;
  keyStore: KeyStore | null;
  vault: VaultSession | null;
  accessToken: string | null;
  refreshToken: string | null;
  ws: WsClient | null;
  wsStatus: WsStatus;
  contacts: Record<string, ContactRecord>;
  messages: Record<string, Message[]>;
  activePeer: string | null;

  bootstrap: () => Promise<void>;
  createAccount: (passphrase: string) => Promise<void>;
  unlock: (passphrase: string) => Promise<void>;
  addContact: (peerAccountId: string, displayName: string) => Promise<void>;
  sendMessage: (
    peerId: string,
    text: string,
    opts: { ttlSeconds: number | null; burnOnRead: boolean },
  ) => Promise<void>;
  /** Encrypt + upload an attachment blob, then send its control payload through
   *  the ordinary send path. Throws (blob upload failed) → message not sent. */
  sendAttachment: (
    peerId: string,
    prepared: PreparedAttachment,
    opts: { ttlSeconds: number | null; burnOnRead: boolean },
  ) => Promise<void>;
  /** Re-run the send for a FAILED outgoing message (re-encrypt + re-upload). */
  retryMessage: (peerId: string, messageId: string) => Promise<void>;
  openMessage: (peerId: string, messageId: string) => void;
  finishBurn: (peerId: string, messageId: string) => void;
  /** Reveal a received image and arm its hard 10s reveal-and-burn timer. */
  revealAttachment: (peerId: string, messageId: string) => void;
  /** Encrypt a message to a contact and deposit it as a dead drop; returns the
   *  base64 one-time token to share out of band (QR / separate channel). */
  sendDeadDrop: (peerId: string, text: string) => Promise<string>;
  /**
   * Encrypt a one-shot "lemon drop" addressed to a contact and deposit it under
   * a random qr_id. Returns the sticker URL to print/share and the drop's expiry
   * for display. Unlike sendDeadDrop this never advances the contact's session —
   * a lemon drop is encrypt-and-forget (a fresh ephemeral X3DH inside
   * createLemonDrop), so it must never write to the contact record.
   */
  sendQrDrop: (
    peerId: string,
    text: string,
    ttlHours: QrDropTTLHours,
  ) => Promise<{ url: string; expiresAt: string }>;
  /** Redeem a dead drop by token: fetch, decrypt, and surface the message. */
  redeemDeadDrop: (tokenB64: string) => Promise<void>;
  /**
   * Redeem a "lemon drop" from a scanned or pasted link. Three honest outcomes:
   *   "message"     — the drop was for us: decrypted, appended to the sender's
   *                   conversation (as a received message), the sender set as
   *                   the active peer, and the drop burned best-effort.
   *   "not-for-us"  — the seal was not ours, OR it opened but the payload would
   *                   not decrypt, OR the claimed sender failed the identity
   *                   cross-check. V1 shows the same warm advocacy screen for all
   *                   three; the crypto-layer distinction is preserved in code.
   *   "unavailable" — the relay returned 404: already claimed, expired, or never
   *                   existed. The blind relay keeps these indistinguishable.
   * A MALFORMED link is not a drop outcome — it throws Error("bad link") for the
   * caller to surface as inline input validation, never as an advocacy screen.
   */
  redeemQrDrop: (input: string) => Promise<"message" | "not-for-us" | "unavailable">;
  expireMessage: (peerId: string, messageId: string) => void;
  setActivePeer: (peerId: string | null) => void;
  markVerified: (peerId: string) => Promise<void>;
  getSafetyNumber: (peerId: string) => Promise<string>;
  deleteAccount: () => Promise<void>;
  /** Erase the entire vault image — every vault, real or filler — and return to
   *  setup. The recovery path from the unlock screen (a forgotten passphrase
   *  and an all-filler image are indistinguishable, by design). */
  resetDevice: () => Promise<void>;
  lock: () => void;
}

export const useApp = create<AppState>((set, get) => {
  // ── internals ──────────────────────────────────────────────────────────────

  const persist = async (): Promise<void> => {
    const { keyStore, vault, contacts } = get();
    if (!keyStore || !vault) return;
    keyStore.sessions = contacts as unknown as Record<string, unknown>;
    await persistVault(vault, keyStore);
  };

  // Derive this device's own identity self-fingerprint for the security-paper
  // watermark, from the SAME 32-byte Ed25519 public key peers pin and the
  // safety number uses. Fire-and-forget with an internal guard: it must never
  // block or break the unlock/create path, so a failure just leaves the
  // fingerprint null (the watermark falls back to invisible-only).
  const refreshLocalFingerprint = (publicKey: Uint8Array): void => {
    void fingerprintOf(publicKey)
      .then((fp) => set({ localFingerprint: fp }))
      .catch(() => set({ localFingerprint: null }));
  };

  const login = async (): Promise<void> => {
    const { keyStore } = get();
    if (!keyStore) throw new Error("locked");
    const identity = deserializeIdentity(keyStore.identityKey as unknown as SerializedIdentity);
    const timestamp = Math.floor(Date.now() / 1000);
    // TODO(zitrone-cutover): shared wire contract with the live relay — rename only in lockstep with the server.
    const challenge = utf8Encode(`sublemonable-login:${keyStore.accountId}:${timestamp}`);
    const signature = b64(await signWithIdentity(challenge, identity));
    const tokens = await api.createSession(keyStore.accountId, timestamp, signature);
    set({ accessToken: tokens.access_token, refreshToken: tokens.refresh_token });
  };

  const freshToken = async (): Promise<string> => {
    const { refreshToken } = get();
    if (refreshToken) {
      try {
        const tokens = await api.refreshSession(refreshToken);
        set({ accessToken: tokens.access_token, refreshToken: tokens.refresh_token });
        return tokens.access_token;
      } catch {
        // Rotation chain broken (expired/revoked) — fall through to re-login.
      }
    }
    await login();
    return get().accessToken!;
  };

  // Cover-traffic generator. Submits decoy envelopes over the same WebSocket path
  // as real messages so that, on the wire, sending and idle are indistinguishable.
  let decoy: DecoyScheduler | null = null;
  let unsubSettings: (() => void) | null = null;

  const startDecoy = (ws: WsClient, senderId: string): void => {
    const intensity = useSettings.getState().coverTraffic;
    decoy = new DecoyScheduler({
      senderId,
      submit: (envelope) => ws.send({ type: "message.send", envelope }),
      intensity,
    });
    decoy.start();
    // React to live cover-traffic changes from Settings.
    unsubSettings = useSettings.subscribe((s) => decoy?.setIntensity(s.coverTraffic));
  };

  const stopDecoy = (): void => {
    decoy?.stop();
    decoy = null;
    unsubSettings?.();
    unsubSettings = null;
  };

  const connect = (): void => {
    const ws = new WsClient(freshToken, handleServerEvent, (wsStatus) => set({ wsStatus }));
    set({ ws });
    void ws.connect();
    const accountId = get().accountId;
    if (accountId) startDecoy(ws, accountId);
  };

  // Reconnect whenever the live transport changes — Tor recovering, the desktop
  // connection-mode listener flipping, or the user toggling clearnet fallback.
  // Tear down the socket bound to the old transport and re-dial over the new one
  // so traffic never keeps riding clearnet while the badge claims Tor. An
  // "offline" transport stays down (connect()'s guard would no-op anyway).
  useSettings.subscribe((s, prev) => {
    if (s.transport === prev.transport) return;
    if (get().phase !== "ready") return;
    stopDecoy();
    get().ws?.close();
    if (s.transport === "offline") {
      // Drop the closed client. Leaving it in state would let sendMessage's
      // `!ws` check pass and queue composes into a dead socket that is then
      // abandoned (messages silently lost) when transport re-enables. Nulling
      // it makes sendMessage no-op while offline.
      set({ ws: null });
    } else {
      connect();
    }
  });

  const appendMessage = (peerId: string, message: Message): void => {
    set((s) => ({
      messages: { ...s.messages, [peerId]: [...(s.messages[peerId] ?? []), message] },
    }));
  };

  const removeMessage = (peerId: string, messageId: string): void => {
    set((s) => ({
      messages: {
        ...s.messages,
        [peerId]: (s.messages[peerId] ?? []).filter((m) => m.id !== messageId),
      },
    }));
  };

  const setBurning = (peerId: string, messageId: string): void => {
    set((s) => ({
      messages: {
        ...s.messages,
        [peerId]: (s.messages[peerId] ?? []).map((m) =>
          m.id === messageId ? { ...m, burning: true } : m,
        ),
      },
    }));
  };

  // ── outgoing send-state ──────────────────────────────────────────────────────
  // Monotonic ranking of the positive transitions. FAILED is off-ladder: it is
  // terminal until an explicit user retry, so a late frame never un-fails it.
  const RANK: Record<SendStatus, number> = {
    sending: 0,
    sent: 1,
    delivered: 2,
    read: 3,
    failed: -1,
  };

  // How to re-drive a FAILED send. Memory-only (like the plaintext); keyed by
  // message id. Populated when a message is composed, dropped once terminal.
  const retries = new Map<string, () => Promise<void>>();

  // Mutate a single OUTGOING message wherever it lives, without touching others.
  const patchOutgoing = (messageId: string, patch: (m: Message) => Message): void => {
    set((s) => {
      for (const [peerId, list] of Object.entries(s.messages)) {
        const current = list.find((m) => m.id === messageId && m.direction === "sent");
        if (!current) continue;
        return {
          messages: {
            ...s.messages,
            [peerId]: list.map((m) => (m === current ? patch(current) : m)),
          },
        };
      }
      return s;
    });
  };

  // Apply a server-driven positive transition (sent/delivered/read). Monotonic
  // (never regresses on out-of-order frames), a no-op on an unknown/burned id
  // (never resurrects a removed message) and on a FAILED message, and starts the
  // sender-side TTL the first time we reach DELIVERED/READ (a real receipt).
  const advanceStatus = (messageId: string, next: "sent" | "delivered" | "read"): void => {
    patchOutgoing(messageId, (m) => {
      if (m.status === "failed" || RANK[m.status] >= RANK[next]) return m;
      const startsTtl = next !== "sent" && m.deliveredAt == null;
      return { ...m, status: next, deliveredAt: startsTtl ? Date.now() : m.deliveredAt };
    });
  };

  const markFailed = (messageId: string): void => {
    patchOutgoing(messageId, (m) => (m.status === "failed" ? m : { ...m, status: "failed" }));
  };

  const updateAttachment = (
    peerId: string,
    messageId: string,
    patch: Partial<AttachmentView>,
  ): void => {
    set((s) => ({
      messages: {
        ...s.messages,
        [peerId]: (s.messages[peerId] ?? []).map((m) =>
          m.id === messageId && m.attachment
            ? { ...m, attachment: { ...m.attachment, ...patch } }
            : m,
        ),
      },
    }));
  };

  // Redeem an attachment blob and decrypt it into the placeholder bubble. Runs
  // as soon as the envelope arrives: one-shot redeem semantics mean "online for
  // the envelope" is "online for the blob". A 404 (expired / already redeemed)
  // and a decrypt/verify failure both land on the same persistent "expired or
  // unavailable" state — never an error toast, never a crash. We never log the
  // blob or key material.
  const redeemAttachment = async (
    peerId: string,
    messageId: string,
    payload: AttachmentPayload,
  ): Promise<void> => {
    try {
      const { ciphertext } = await api.redeemBlob(payload.blob_token);
      const box = await fromBase64(ciphertext);
      const plain = await decryptAttachmentBlob(
        unb64(payload.key),
        box,
        unb64(payload.sha256),
        payload.size,
      );
      const blob = bytesToBlob(plain, payload.mimetype);
      updateAttachment(peerId, messageId, { status: "ready", blob });
    } catch {
      // Redeem 404 (expired / already redeemed) and decrypt/verify failure both
      // land here — a persistent "expired or unavailable" state, never a crash
      // or toast. We never log the blob or key material.
      updateAttachment(peerId, messageId, { status: "expired" });
    }
  };

  // Encrypt (ratchet), build, and submit an ordinary envelope for `plaintext`.
  // The single wire path for BOTH text and attachments: media_type stays "text"
  // (an attachment is indistinguishable from a message on the wire — see
  // packages/protocol attachments.ts). Returns the envelope id/timestamp, or
  // null when there's nothing to send over (no contact/account/socket).
  const buildAndSend = async (
    peerId: string,
    plaintext: string,
    opts: { ttlSeconds: number | null; burnOnRead: boolean },
    id: string,
  ): Promise<{ id: string; timestamp: string } | null> => {
    const { contacts, accountId, ws } = get();
    const contact = contacts[peerId];
    if (!contact || !accountId || !ws) return null;
    // A session-less contact is a mobile lemon-drop sender we can't ordinary-
    // message across the key-family wall — nothing to send over.
    if (contact.session === null) return null;

    const session = deserializeSession(contact.session);
    // Pad the plaintext to a 256-byte block before encrypting so ciphertext
    // length leaks nothing — and so decoy traffic is the same size as real.
    const encrypted = await ratchetEncrypt(session, await pad(utf8Encode(plaintext)));
    contact.session = serializeSession(session);
    set((s) => ({ contacts: { ...s.contacts, [peerId]: { ...contact } } }));

    const envelope: MessageEnvelope = {
      id,
      sender_id: accountId,
      recipient_id: peerId,
      ciphertext: b64(encrypted.blob),
      ephemeral_key: contact.pendingEphemeralKey,
      prekey_id: contact.pendingPrekeyId,
      message_number: encrypted.messageNumber,
      previous_chain_length: encrypted.previousChainLength,
      timestamp: new Date().toISOString(),
      ttl_seconds: opts.ttlSeconds,
      burn_on_read: opts.burnOnRead,
      media_type: "text",
      version: PROTOCOL_VERSION,
    };
    ws.send({ type: "message.send", envelope });
    return { id: envelope.id, timestamp: envelope.timestamp };
  };

  // Post-decryption dispatch for a delivered message. Follows the canonical
  // parse order (see lib/incoming.ts): receipts are swallowed, attachments show
  // a placeholder and redeem immediately, unrecognized control payloads render
  // a generic notice (NEVER raw text — a near-miss may carry key material), and
  // everything else is conversation text.
  const deliverIncoming = (envelope: MessageEnvelope, text: string): void => {
    const classified = classifyIncoming(text);
    if (classified.kind === "receipt") {
      // A read receipt names the outgoing messages the peer has now opened —
      // advance each to READ. The receipt is still acked by the caller so the
      // server deletes its copy.
      const receipt = parseReadReceipt(text);
      if (receipt) for (const id of receipt.message_ids) advanceStatus(id, "read");
      return;
    }
    const base = {
      id: envelope.id,
      peerId: envelope.sender_id,
      direction: "received" as const,
      timestamp: envelope.timestamp,
      ttlSeconds: envelope.ttl_seconds,
      burnOnRead: envelope.burn_on_read,
      // Incoming TTL starts on arrival (unchanged). status is unused for
      // received messages (no ticks) — a terminal value keeps the type honest.
      status: "delivered" as const,
      deliveredAt: Date.now(),
      burning: false,
      opened: false,
    };
    if (classified.kind === "attachment") {
      const p = classified.payload;
      appendMessage(envelope.sender_id, {
        ...base,
        text: "",
        attachment: {
          kind: p.kind,
          mimetype: p.mimetype,
          filename: p.filename,
          size: p.size,
          caption: p.caption,
          status: "loading",
        },
      });
      void redeemAttachment(envelope.sender_id, envelope.id, p);
    } else if (classified.kind === "unsupported") {
      appendMessage(envelope.sender_id, { ...base, text: "", unsupported: true });
    } else {
      appendMessage(envelope.sender_id, { ...base, text: classified.text });
    }
  };

  // X3DH responder handshake for an initial-message envelope, given the
  // sender's identity key. Tries our signed prekeys newest-first (the initiator
  // used whichever was current when they fetched our bundle), probes each
  // candidate session by decrypting the envelope, and consumes the one-time
  // prekey only on success. The sender's identity key participates in the X3DH
  // DH mix, so a successful decrypt PROVES the envelope was built by whoever
  // holds that key's private half — callers choose how the key is trusted
  // (pinned contact key vs freshly fetched bundle).
  const respondToX3DH = async (
    envelope: MessageEnvelope,
    senderIdentityKey: Uint8Array,
  ): Promise<{ session: ReturnType<typeof deserializeSession>; text: string }> => {
    const { keyStore } = get();
    if (!keyStore || !envelope.ephemeral_key) throw new Error("bad initial message");
    const identity = deserializeIdentity(keyStore.identityKey as unknown as SerializedIdentity);

    const otps = keyStore.oneTimePrekeys as unknown as SerializedOneTimePrekey[];
    const usedOtp =
      envelope.prekey_id == null ? null : (otps.find((p) => p.id === envelope.prekey_id) ?? null);

    const spks = [...(keyStore.signedPrekeys as unknown as SerializedSignedPrekey[])].sort(
      (a, c) => c.createdAt - a.createdAt,
    );
    let lastError: unknown = new Error("no signed prekeys");
    for (const spk of spks) {
      try {
        const session = await x3dhRespond(
          identity,
          deserializeSignedPrekey(spk),
          usedOtp ? deserializeOneTimePrekey(usedOtp) : null,
          senderIdentityKey,
          unb64(envelope.ephemeral_key),
        );
        const plaintextProbe = await ratchetDecrypt(session, {
          blob: unb64(envelope.ciphertext),
          messageNumber: envelope.message_number,
          previousChainLength: envelope.previous_chain_length,
        });
        // Success — consume the one-time prekey (single-use by design).
        if (usedOtp) {
          keyStore.oneTimePrekeys = otps.filter(
            (p) => p.id !== usedOtp.id,
          ) as unknown as KeyStore["oneTimePrekeys"];
        }
        return { session, text: utf8Decode(unpad(plaintextProbe)) };
      } catch (e) {
        lastError = e;
      }
    }
    throw lastError;
  };

  // Establish a responder session from an X3DH initial message sent by an
  // UNKNOWN sender. Returns the decrypted plaintext; the caller runs the shared
  // post-decryption dispatch so a first message that is a receipt/attachment/
  // control payload is handled the same way as any subsequent one.
  const respondToInitialMessage = async (
    envelope: MessageEnvelope,
  ): Promise<{ contact: ContactRecord; text: string }> => {
    // The envelope doesn't carry the sender's identity key — fetch it.
    const token = await freshToken();
    const senderBundle = await api.fetchPrekeyBundle(envelope.sender_id, token);

    const { session, text } = await respondToX3DH(envelope, unb64(senderBundle.identity_key));
    const contact: ContactRecord = {
      displayName: `Contact ${envelope.sender_id.slice(0, 8)}`,
      identityKey: senderBundle.identity_key,
      session: serializeSession(session),
      pendingEphemeralKey: null,
      pendingPrekeyId: null,
    };
    set((s) => ({ contacts: { ...s.contacts, [envelope.sender_id]: contact } }));
    return { contact, text };
  };

  const handleServerEvent = (event: ServerEvent): void => {
    void (async () => {
      switch (event.type) {
        case "message.deliver": {
          const envelope = parseEnvelope(event.envelope);
          const { contacts, ws } = get();
          const contact = contacts[envelope.sender_id];
          try {
            let text: string;
            if (!contact) {
              ({ text } = await respondToInitialMessage(envelope));
            } else if (contact.session === null) {
              // A session-less contact is a mobile (Curve25519) lemon-drop
              // sender: no ordinary web↔mobile session exists, so an inbound
              // ordinary envelope for them is neither expected nor decryptable.
              // Drop it rather than run the guarded session-reset against an
              // impossible cross-family session.
              return;
            } else {
              const storedSession = contact.session;
              try {
                const session = deserializeSession(storedSession);
                const plaintext = await ratchetDecrypt(session, {
                  blob: unb64(envelope.ciphertext),
                  messageNumber: envelope.message_number,
                  previousChainLength: envelope.previous_chain_length,
                });
                contact.session = serializeSession(session);
                // First message from them: our X3DH header is no longer needed.
                contact.pendingEphemeralKey = null;
                contact.pendingPrekeyId = null;
                set((s) => ({ contacts: { ...s.contacts, [envelope.sender_id]: { ...contact } } }));
                text = utf8Decode(unpad(plaintext));
              } catch (err) {
                // GUARDED SESSION RESET (maintainer decision 2a). Two independent
                // initiators deadlock: each side holds its own outbound session
                // and neither can decrypt the other's first message. Lemon drops
                // manufacture this systematically (the recipient's redeem creates
                // a fresh outbound session while we still hold our original one);
                // mutual addContact can hit it too. The guard is deliberately
                // narrow — ALL of the following must hold, else the envelope is
                // dropped exactly as before:
                //   1. the stored session failed to decrypt, AND
                //   2. the envelope carries an X3DH initial-message header
                //      (ephemeral_key) — ordinary ratchet traffic never does, AND
                //   3. the X3DH responder handshake keyed on the PINNED contact
                //      identity key yields a session that decrypts this envelope.
                // (3) is the authentication: the sender's identity key is mixed
                // into the X3DH secret, so only the holder of the pinned key's
                // private half can produce an envelope this reset accepts. The
                // pinned key — never a freshly fetched bundle — is what we mix,
                // so a relay swapping bundles cannot steer the reset. Replaying
                // the contact's ORIGINAL initial message is inert whenever it
                // consumed a one-time prekey (deleted on first use, so the
                // re-derivation fails); the no-OTP corner is accepted V1 surface,
                // documented in SECURITY_MODEL.md.
                if (!envelope.ephemeral_key) throw err;
                const { session, text: freshText } = await respondToX3DH(
                  envelope,
                  unb64(contact.identityKey),
                );
                const reset: ContactRecord = {
                  ...contact,
                  session: serializeSession(session),
                  pendingEphemeralKey: null,
                  pendingPrekeyId: null,
                };
                set((s) => ({ contacts: { ...s.contacts, [envelope.sender_id]: reset } }));
                text = freshText;
              }
            }
            // Shared post-decryption dispatch: receipts swallowed, attachments
            // redeemed, unknown control payloads shown as a generic notice,
            // everything else rendered as text (see lib/incoming.ts).
            deliverIncoming(envelope, text);
            // Ack triggers immediate server-side deletion of the envelope.
            ws?.send({ type: "message.ack", message_id: envelope.id });
            // Delivery receipt: tell the ORIGINAL sender we got it, addressed
            // back to them by peer_id (their account id, from the decrypted
            // envelope). The relay peer-routes it as message.delivered without
            // ever having stored who the sender was (zero-knowledge preserved).
            ws?.send({
              type: "message.received",
              message_id: envelope.id,
              peer_id: envelope.sender_id,
            });
            await persist();
          } catch {
            // Undecryptable envelope: never ack what we couldn't deliver to
            // the user, and never log payloads.
          }
          break;
        }
        case "message.stored": {
          // The relay persisted our envelope — one tick (SENT).
          advanceStatus(event.message_id, "sent");
          break;
        }
        case "message.delivered": {
          // The recipient decrypted+stored it — two ticks (DELIVERED). This is
          // where the sender-side self-destruct timer starts (see advanceStatus).
          advanceStatus(event.message_id, "delivered");
          break;
        }
        case "message.burned": {
          // Peer destroyed a message — destroy our copy with the burn animation.
          setBurning(event.peer_id, event.message_id);
          break;
        }
        case "prekey.low": {
          const { keyStore } = get();
          if (!keyStore) break;
          const existing = keyStore.oneTimePrekeys as unknown as SerializedOneTimePrekey[];
          const nextId = existing.reduce((max, p) => Math.max(max, p.id), 0) + 1;
          const fresh = await generateOneTimePrekeys(
            ONE_TIME_PREKEY_BATCH - event.remaining,
            nextId,
          );
          keyStore.oneTimePrekeys = [
            ...existing,
            ...fresh.map(serializeOneTimePrekey),
          ] as unknown as KeyStore["oneTimePrekeys"];
          const token = await freshToken();
          await api.uploadPrekeys(
            { one_time_prekeys: fresh.map((p) => ({ id: p.id, public_key: b64(p.publicKey) })) },
            token,
          );
          await persist();
          break;
        }
        case "session.revoked": {
          get().ws?.close();
          set({ wsStatus: "closed" });
          break;
        }
        default:
          break;
      }
    })();
  };

  // ── public actions ─────────────────────────────────────────────────────────

  return {
    phase: "loading",
    accountId: null,
    localFingerprint: null,
    keyStore: null,
    vault: null,
    accessToken: null,
    refreshToken: null,
    ws: null,
    wsStatus: "closed",
    contacts: {},
    messages: {},
    activePeer: null,

    async bootstrap() {
      set({ phase: (await hasVault()) ? "unlock" : "setup" });
    },

    async createAccount(passphrase) {
      const identity = await generateIdentityKeyPair();
      const signedPrekey = await generateSignedPrekey(identity, 1);
      const oneTimePrekeys = await generateOneTimePrekeys(ONE_TIME_PREKEY_BATCH);

      const { account_id } = await api.register({
        identity_key: b64(identity.publicKey),
        signed_prekey: {
          id: signedPrekey.id,
          public_key: b64(signedPrekey.publicKey),
          signature: b64(signedPrekey.signature),
        },
        one_time_prekeys: oneTimePrekeys.map((p) => ({ id: p.id, public_key: b64(p.publicKey) })),
      });

      const keyStore: KeyStore = {
        version: 1,
        accountId: account_id,
        identityKey: serializeIdentity(identity) as unknown as KeyStore["identityKey"],
        signedPrekeys: [
          serializeSignedPrekey(signedPrekey),
        ] as unknown as KeyStore["signedPrekeys"],
        oneTimePrekeys: oneTimePrekeys.map(
          serializeOneTimePrekey,
        ) as unknown as KeyStore["oneTimePrekeys"],
        sessions: {},
        verifiedContacts: {},
      };
      // Seals the keystore into its slot and persists the image in one step.
      const vault = await createVault(passphrase, keyStore);
      set({ keyStore, vault, accountId: account_id, contacts: {} });
      refreshLocalFingerprint(identity.publicKey);
      await login();
      connect();
      set({ phase: "ready" });
    },

    async unlock(passphrase) {
      if (!(await hasVault())) {
        set({ phase: "setup" });
        return;
      }
      // Off-thread tryPassphrase: every slot derived and tried, no early exit.
      // A corrupt payload throws and is shown as a wrong passphrase — a
      // clobbered vault must stay indistinguishable from a bad guess.
      const unlocked = await unlockVault(passphrase).catch(() => null);
      if (!unlocked) {
        set({ unlockError: "Wrong passphrase" });
        return;
      }
      const { keyStore, session } = unlocked;
      try {
        set({
          keyStore,
          vault: session,
          accountId: keyStore.accountId,
          contacts: keyStore.sessions as unknown as Record<string, ContactRecord>,
          unlockError: undefined,
        });
        // Same 32-byte identity public key the safety number / peer pinning use.
        refreshLocalFingerprint(
          unb64((keyStore.identityKey as unknown as SerializedIdentity).publicKey),
        );
        await login();
        connect();
        set({ phase: "ready" });
      } catch {
        // The vault opened but the relay is unreachable. Don't claim the
        // passphrase was wrong, and don't leave key material sitting behind
        // the lock screen.
        wipe(session.vaultKey);
        set({
          keyStore: null,
          vault: null,
          accountId: null,
          localFingerprint: null,
          contacts: {},
          unlockError: "Unlocked, but the server is unreachable — try again",
        });
      }
    },

    async addContact(peerAccountId, displayName) {
      const { keyStore, contacts } = get();
      if (!keyStore || contacts[peerAccountId]) return;
      const identity = deserializeIdentity(keyStore.identityKey as unknown as SerializedIdentity);
      const token = await freshToken();
      const bundle = await api.fetchPrekeyBundle(peerAccountId, token);
      const result = await x3dhInitiate(identity, {
        identityKey: unb64(bundle.identity_key),
        signedPrekey: {
          id: bundle.signed_prekey.id,
          publicKey: unb64(bundle.signed_prekey.public_key),
          signature: unb64(bundle.signed_prekey.signature),
        },
        oneTimePrekey: bundle.one_time_prekey
          ? { id: bundle.one_time_prekey.id, publicKey: unb64(bundle.one_time_prekey.public_key) }
          : null,
      });
      const contact: ContactRecord = {
        displayName,
        identityKey: bundle.identity_key,
        session: serializeSession(result.session),
        pendingEphemeralKey: b64(result.ephemeralPublicKey),
        pendingPrekeyId: result.usedPrekeyId,
      };
      set((s) => ({ contacts: { ...s.contacts, [peerAccountId]: contact } }));
      await persist();
    },

    async sendMessage(peerId, text, opts) {
      // Show the bubble immediately as SENDING, then hand it to the relay. The
      // envelope keeps this id so message.stored/delivered can find it, and a
      // send that never reaches the relay lands on FAILED with a retry.
      const id = crypto.randomUUID();
      appendMessage(peerId, {
        id,
        peerId,
        direction: "sent",
        text,
        timestamp: new Date().toISOString(),
        ttlSeconds: opts.ttlSeconds,
        burnOnRead: opts.burnOnRead,
        status: "sending",
        deliveredAt: null,
        burning: false,
        opened: true,
      });
      const attempt = async (): Promise<void> => {
        try {
          const sent = await buildAndSend(peerId, text, opts, id);
          // null = no contact/account/socket — nothing was encrypted or sent.
          if (!sent) markFailed(id);
          else await persist();
        } catch {
          markFailed(id);
        }
      };
      retries.set(id, attempt);
      await attempt();
    },

    async sendAttachment(peerId, prepared, opts) {
      // Guard BEFORE showing anything: no contact/account/socket means there is
      // nowhere to send, so don't paint a bubble that could only ever fail.
      const { contacts, accountId, ws } = get();
      if (!contacts[peerId] || !accountId || !ws) return;

      // Render our own copy immediately from the in-memory bytes (same
      // memory-only policy as message plaintext — nothing persisted), as
      // SENDING, then run the upload+send. On failure the bubble goes to FAILED
      // and retry re-encrypts and re-uploads from these retained bytes.
      const id = crypto.randomUUID();
      appendMessage(peerId, {
        id,
        peerId,
        direction: "sent",
        text: "",
        timestamp: new Date().toISOString(),
        ttlSeconds: opts.ttlSeconds,
        burnOnRead: opts.burnOnRead,
        status: "sending",
        deliveredAt: null,
        burning: false,
        opened: true,
        attachment: {
          kind: prepared.kind,
          mimetype: prepared.mimetype,
          filename: prepared.filename,
          size: prepared.bytes.length,
          caption: prepared.caption,
          status: "ready",
          blob: bytesToBlob(prepared.bytes, prepared.mimetype),
        },
      });

      const attempt = async (): Promise<void> => {
        try {
          // Encrypt under a fresh random key and upload the blob FIRST. Only
          // once the relay holds it do we send the envelope that references it.
          const blob = await encryptAttachmentBlob(prepared.bytes);
          const token = await freshToken();
          await api.depositBlob(
            { blob_id: b64(blob.blobId), ciphertext: await toBase64(blob.box) },
            token,
          );
          // The envelope plaintext is the control payload — routed through the
          // ordinary send path (padding + ratchet + media_type "text").
          const plaintext = serializeAttachment({
            kind: prepared.kind,
            blobToken: b64(blob.token),
            key: b64(blob.key),
            mimetype: prepared.mimetype,
            filename: prepared.filename,
            size: blob.size,
            sha256: b64(blob.sha256),
            caption: prepared.caption,
          });
          const sent = await buildAndSend(peerId, plaintext, opts, id);
          if (!sent) markFailed(id);
          else await persist();
        } catch {
          // Blob upload (or any step) threw — mark FAILED; retry stays available.
          markFailed(id);
        }
      };
      retries.set(id, attempt);
      await attempt();
    },

    async sendDeadDrop(peerId, text) {
      const { contacts, accountId } = get();
      const contact = contacts[peerId];
      if (!contact || !accountId) throw new Error("unknown contact");
      // A session-less mobile lemon-drop contact has no ordinary session to
      // encrypt against (cross-family wall) — a token dead-drop to them is
      // impossible, so refuse rather than crash on a null session.
      if (contact.session === null) throw new Error("no session for this contact");

      // Encrypt exactly as a normal message — the dead drop carries a full
      // envelope so the recipient decrypts it with the established session.
      const session = deserializeSession(contact.session);
      // Pad the plaintext to a 256-byte block before encrypting so ciphertext
      // length leaks nothing — and so decoy traffic is the same size as real.
      const encrypted = await ratchetEncrypt(session, await pad(utf8Encode(text)));
      contact.session = serializeSession(session);
      set((s) => ({ contacts: { ...s.contacts, [peerId]: { ...contact } } }));

      const envelope: MessageEnvelope = {
        id: crypto.randomUUID(),
        sender_id: accountId,
        recipient_id: peerId,
        ciphertext: b64(encrypted.blob),
        ephemeral_key: contact.pendingEphemeralKey,
        prekey_id: contact.pendingPrekeyId,
        message_number: encrypted.messageNumber,
        previous_chain_length: encrypted.previousChainLength,
        timestamp: new Date().toISOString(),
        ttl_seconds: null,
        burn_on_read: false,
        media_type: "text",
        version: PROTOCOL_VERSION,
      };

      // Seal the ENTIRE envelope — sender_id, recipient_id, ratchet headers and
      // all — to the recipient's identity key, then pad. The relay (and anyone
      // who reads the stored row) sees only an opaque sealed box: no metadata
      // links the two parties. The recipient is the only one who can open it.
      const recipientX25519 = await identityKeyToX25519(unb64(contact.identityKey));
      const sealed = await sealTo(recipientX25519, utf8Encode(JSON.stringify(envelope)));
      const padded = await pad(sealed);
      const { token, dropId } = await generateDropToken();
      const powNonce = await solveProofOfWork(dropId, DROP_POW_DIFFICULTY);
      await api.depositDrop({
        drop_id: await toBase64(dropId),
        ciphertext: await toBase64(padded),
        pow_nonce: await toBase64(powNonce),
      });

      appendMessage(peerId, {
        id: envelope.id,
        peerId,
        direction: "sent",
        text,
        timestamp: envelope.timestamp,
        ttlSeconds: null,
        burnOnRead: false,
        // Deposited to the relay — the honest state for a dead drop. We can't
        // learn when (or if) it's redeemed out of band, so this stays SENT.
        status: "sent",
        deliveredAt: null,
        burning: false,
        opened: true,
      });
      await persist();
      // The token is the capability — shared out of band, never with the server.
      return await toBase64(token);
    },

    async sendQrDrop(peerId, text, ttlHours) {
      const { keyStore, contacts, accountId } = get();
      const contact = contacts[peerId];
      // Same admission as sendDeadDrop: our keys, our account, and an existing
      // contact to address the drop to. A lemon drop is recipient-targeted by
      // design — never anonymous — so a known contact is required.
      if (!keyStore || !accountId || !contact) throw new Error("unknown contact");

      const identity = deserializeIdentity(keyStore.identityKey as unknown as SerializedIdentity);

      // Fetch a FRESH prekey bundle: a lemon drop runs a brand-new one-shot X3DH
      // against whatever the recipient publishes right now, independent of any
      // live session we may already hold with this peer. Decode it exactly as
      // addContact does (base64 → bytes) for x3dhInitiate inside createLemonDrop.
      const token = await freshToken();
      const bundle = await api.fetchPrekeyBundle(peerId, token);

      // TRUST BOUNDARY (the creation-side mirror of redeemQrDrop's check): the
      // drop must seal to the identity key we PINNED for this contact, not to
      // whatever bundle the relay serves today. A substituted bundle — malicious
      // relay, account re-registration, corruption — would otherwise be silently
      // readable by someone other than the person this UI names as the
      // recipient. x3dhInitiate verifies the bundle's internal prekey signature,
      // but only this comparison ties the bundle to the identity we trust.
      if (bundle.identity_key !== contact.identityKey) {
        throw new Error("identity changed");
      }

      // CRITICAL: this is one-shot encrypt-and-forget. The ephemeral X3DH session
      // that createLemonDrop spins up encrypts EXACTLY ONE message and is then
      // discarded — it never touches, advances, or is confused with
      // contact.session. This action therefore MUST NOT write back to the contact
      // record (contrast sendDeadDrop, which deliberately advances the persistent
      // ratchet). Reusing the live session here would fork ratchet state.
      const drop = await createLemonDrop({
        senderAccountId: accountId,
        senderIdentity: identity,
        recipientAccountId: peerId,
        recipientBundle: {
          identityKey: unb64(bundle.identity_key),
          signedPrekey: {
            id: bundle.signed_prekey.id,
            publicKey: unb64(bundle.signed_prekey.public_key),
            signature: unb64(bundle.signed_prekey.signature),
          },
          oneTimePrekey: bundle.one_time_prekey
            ? { id: bundle.one_time_prekey.id, publicKey: unb64(bundle.one_time_prekey.public_key) }
            : null,
        },
        text,
      });

      // Deposit is unauthenticated — the hashcash PoW solved inside
      // createLemonDrop is the only admission, so the request itself carries no
      // account. HONEST LIMIT: the authenticated prekey fetch just above rides
      // the same transport moments earlier, so a relay watching traffic can
      // correlate the two by connection/timing and infer who created a drop for
      // whom. That metadata correlation is disclosed in SECURITY_MODEL.md;
      // fetching prekeys on an unlinkable schedule is tracked follow-up work,
      // not something this request shape can fix. qr_id rides as unpadded
      // base64url (the same form the sticker URL encodes); the sealed box,
      // nonce, and burn hash as plain base64.
      const { expires_at } = await api.depositQrDrop({
        qr_id: encodeQrDropId(drop.qrId),
        ciphertext: b64(drop.ciphertext),
        ttl_hours: ttlHours,
        pow_nonce: b64(drop.powNonce),
        burn_hash: b64(drop.burnHash),
      });

      // Local sent bubble, exactly like sendDeadDrop: status stays SENT because a
      // drop's redemption is unknowable — the blind relay can't tell us whether
      // (or when) anyone scanned and read it.
      appendMessage(peerId, {
        id: crypto.randomUUID(),
        peerId,
        direction: "sent",
        text,
        timestamp: new Date().toISOString(),
        ttlSeconds: null,
        burnOnRead: false,
        status: "sent",
        deliveredAt: null,
        burning: false,
        opened: true,
      });
      // Local-record persistence must not gate the URL: the relay has already
      // accepted the deposit, so failing here would strand a live drop the
      // creator never sees — and a retry would mint a SECOND drop and consume
      // another of the recipient's prekeys. Nothing above wrote to the contact
      // record, so there is no session state at risk; the bubble simply catches
      // up on the next successful persist.
      try {
        await persist();
      } catch {
        /* deposit accepted — the sticker URL still goes back to the creator */
      }

      return { url: drop.url, expiresAt: expires_at };
    },

    async redeemDeadDrop(tokenB64) {
      const { keyStore } = get();
      if (!keyStore) throw new Error("locked");
      const { ciphertext } = await api.redeemDrop(tokenB64.trim());
      // Unpad, then open the sealed box with our identity's X25519 keypair.
      const sealed = unpad(await fromBase64(ciphertext));
      const identity = deserializeIdentity(keyStore.identityKey as unknown as SerializedIdentity);
      const opened = await openSealed(identity.x25519PublicKey, identity.x25519PrivateKey, sealed);
      const envelope = parseEnvelope(JSON.parse(utf8Decode(opened)));
      // Reuse the normal inbound path to establish/advance the session and
      // surface the message.
      handleServerEvent({ type: "message.deliver", envelope });
    },

    async redeemQrDrop(input) {
      const { keyStore, contacts } = get();
      if (!keyStore) throw new Error("locked");

      // A malformed link is a UI validation error, not a drop outcome — the
      // caller surfaces it inline; it never reaches the advocacy screen.
      const qrId = parseQrDropUrl(input);
      if (!qrId) throw new Error("bad link");

      // Fetch is unauthenticated and non-destructive. A 404 means claimed,
      // expired, or never-existed — the blind relay keeps these three
      // indistinguishable on purpose, so we collapse them into one honest
      // "unavailable". Any other failure (transport, etc.) propagates.
      const fetched = await api.fetchQrDrop(encodeQrDropId(qrId)).catch((e: unknown) => {
        if (e instanceof ApiError && e.status === 404) return null;
        throw e;
      });
      if (fetched === null) return "unavailable";

      const identity = deserializeIdentity(keyStore.identityKey as unknown as SerializedIdentity);
      const mySignedPrekeys = (keyStore.signedPrekeys as unknown as SerializedSignedPrekey[]).map(
        deserializeSignedPrekey,
      );
      const myOneTimePrekeys = (
        keyStore.oneTimePrekeys as unknown as SerializedOneTimePrekey[]
      ).map(deserializeOneTimePrekey);

      // Decode the sealed blob (deposited as plain base64, the mirror of
      // sendQrDrop's b64(drop.ciphertext)) and try to open it as the recipient.
      const result = await openLemonDrop({
        myIdentity: identity,
        mySignedPrekeys,
        myOneTimePrekeys,
        ciphertext: unb64(fetched.ciphertext),
      });

      // "not-recipient" (the seal never opened — not ours, or garbage) and
      // "invalid" (the seal opened but the payload was malformed/undecryptable)
      // are KEPT DISTINCT at the crypto layer, but V1 shows the same warm
      // advocacy screen for both: from the reader's seat neither is a message
      // they can open, and we will not dress either up as an error.
      if (result.outcome !== "message") return "not-for-us";

      const { text, senderAccountId, senderKeyFamily, senderIdentityKey, burnToken, usedOneTimePrekeyId } =
        result;
      // The sender's identity key is CLAIMED (opening the seal proves who the box
      // was addressed TO, never who wrote it). Compare against the base64 form we
      // store on contacts / receive in bundles.
      const claimedIdentityB64 = b64(senderIdentityKey);

      // TRUST BOUNDARY — openLemonDrop's JSDoc demands this cross-check before we
      // trust the sender or render anything.
      const known = contacts[senderAccountId];
      if (known) {
        // A drop that claims to come from a contact we already hold, but under a
        // DIFFERENT identity key, must not render — treat it exactly as "not for
        // us". Rendering it would let an impersonator borrow a trusted name and
        // put words in a known contact's mouth.
        if (known.identityKey !== claimedIdentityB64) return "not-for-us";
      } else {
        // Unknown sender: establish the contact the way addContact does, but ONLY
        // after verifying the freshly fetched bundle's identity key equals the
        // claimed one. If the relay-served bundle disagrees with the sealed
        // claim, neither can be trusted, so we refuse to render (same surface).
        // A 404 — the claimed sender deleted their account, or never existed —
        // is a FAILED cross-check (the claim is unverifiable), not a transport
        // error: it maps to "not-for-us" like any other identity mismatch.
        // Genuine transport failures still propagate.
        const token = await freshToken();
        const bundle = await api.fetchPrekeyBundle(senderAccountId, token).catch((e: unknown) => {
          if (e instanceof ApiError && e.status === 404) return null;
          throw e;
        });
        if (bundle === null) return "not-for-us";
        if (bundle.identity_key !== claimedIdentityB64) return "not-for-us";

        if (senderKeyFamily === "curve25519") {
          // A mobile (Android/iOS) creator. We CANNOT stand up an ordinary
          // web↔mobile session (the key families are wire-incompatible), and a
          // lemon drop is one-way anyway — so we establish a SESSION-LESS
          // contact: the sender's identity is now pinned (a later drop from them
          // is recognized, and impersonation is caught by the same key compare),
          // the drop renders below, but no reply session is attempted. Trying to
          // x3dhInitiate against their Curve25519 bundle would throw. This is the
          // reviewed cross-family fix — the drop no longer decrypts-then-dies.
          const contact: ContactRecord = {
            displayName: `Contact ${senderAccountId.slice(0, 8)}`,
            identityKey: bundle.identity_key,
            session: null,
            pendingEphemeralKey: null,
            pendingPrekeyId: null,
          };
          set((s) => ({ contacts: { ...s.contacts, [senderAccountId]: contact } }));
        } else {
          // The ephemeral responder session openLemonDrop used to decrypt is gone
          // by design (encrypt-and-forget). Spin up a normal OUTBOUND session
          // against their current bundle so any reply rides an ordinary ratchet —
          // exactly the shape addContact stores.
          const init = await x3dhInitiate(identity, {
            identityKey: unb64(bundle.identity_key),
            signedPrekey: {
              id: bundle.signed_prekey.id,
              publicKey: unb64(bundle.signed_prekey.public_key),
              signature: unb64(bundle.signed_prekey.signature),
            },
            oneTimePrekey: bundle.one_time_prekey
              ? {
                  id: bundle.one_time_prekey.id,
                  publicKey: unb64(bundle.one_time_prekey.public_key),
                }
              : null,
          });
          const contact: ContactRecord = {
            displayName: `Contact ${senderAccountId.slice(0, 8)}`,
            identityKey: bundle.identity_key,
            session: serializeSession(init.session),
            pendingEphemeralKey: b64(init.ephemeralPublicKey),
            pendingPrekeyId: init.usedPrekeyId,
          };
          set((s) => ({ contacts: { ...s.contacts, [senderAccountId]: contact } }));
        }
      }

      // Surface the drop as a received message — mirrors deliverIncoming's
      // `base`: delivered, TTL timer armed now, unopened, no TTL/burn-on-read (a
      // lemon drop's envelope carries neither). openLemonDrop returns no envelope
      // id/timestamp, so we mint a local id and stamp arrival now.
      const now = Date.now();
      appendMessage(senderAccountId, {
        id: crypto.randomUUID(),
        peerId: senderAccountId,
        direction: "received",
        text,
        timestamp: new Date(now).toISOString(),
        ttlSeconds: null,
        burnOnRead: false,
        status: "delivered",
        deliveredAt: now,
        burning: false,
        opened: false,
      });

      // Consume the one-time prekey the sender used (single-use by design — the
      // same consumption respondToInitialMessage does). Null means they addressed
      // us on the signed prekey alone, so there is nothing to delete.
      if (usedOneTimePrekeyId != null) {
        const otps = keyStore.oneTimePrekeys as unknown as SerializedOneTimePrekey[];
        keyStore.oneTimePrekeys = otps.filter(
          (p) => p.id !== usedOneTimePrekeyId,
        ) as unknown as KeyStore["oneTimePrekeys"];
      }

      set({ activePeer: senderAccountId });
      // Persist BEFORE burning, and let a persist failure propagate: the burn
      // irreversibly deletes the relay's only copy, so firing it while the
      // received message / new contact / consumed prekey are still unsaved
      // would make a subsequent reload lose the message with no drop left to
      // redeem again. If persist throws here, nothing has been burned — the
      // drop is still on the shelf and a fresh scan (or reload + retry, which
      // restores the unconsumed prekey from disk) can redeem it again.
      await persist();

      // Best-effort burn: present the token preimage so the blind relay shreds
      // the blob now. Swallowed on failure — burn-on-claim is a COURTESY shred,
      // not a correctness requirement; the drop's TTL is the real backstop, and a
      // failed burn just means it lingers until then.
      try {
        await api.burnQrDrop({ qr_id: encodeQrDropId(qrId), burn_token: b64(burnToken) });
      } catch {
        // Intentionally ignored — TTL guarantees eventual destruction.
      }

      return "message";
    },

    async retryMessage(peerId, messageId) {
      const message = (get().messages[peerId] ?? []).find((m) => m.id === messageId);
      const attempt = retries.get(messageId);
      // Only a FAILED message with a retained send is retryable.
      if (!message || message.status !== "failed" || !attempt) return;
      patchOutgoing(messageId, (m) => ({ ...m, status: "sending" }));
      await attempt();
    },

    openMessage(peerId, messageId) {
      const message = (get().messages[peerId] ?? []).find((m) => m.id === messageId);
      if (!message || message.opened) return;
      // An attachment isn't "read" until its bytes have arrived (or failed to):
      // hold off opening while it's still loading. This matters for burn-on-read
      // — ChatView's auto-open effect fires the moment the placeholder bubble
      // mounts, and burning then would destroy the message before the recipient
      // ever saw the image (the async redeem hasn't resolved yet). redeemAttachment
      // updates the message once the blob lands, which re-runs that effect, and we
      // open (and burn) then — with content on screen.
      if (message.attachment && message.attachment.status === "loading") return;
      set((s) => ({
        messages: {
          ...s.messages,
          [peerId]: (s.messages[peerId] ?? []).map((m) =>
            m.id === messageId ? { ...m, opened: true } : m,
          ),
        },
      }));
      if (message.burnOnRead && message.direction === "received") {
        // Destroyed everywhere after first open: burn locally, tell the peer.
        setBurning(peerId, messageId);
        get().ws?.send({ type: "message.burn", message_id: messageId, peer_id: peerId });
      }
    },

    finishBurn(peerId, messageId) {
      removeMessage(peerId, messageId);
    },

    expireMessage(peerId, messageId) {
      setBurning(peerId, messageId);
    },

    revealAttachment(peerId, messageId) {
      const message = (get().messages[peerId] ?? []).find((m) => m.id === messageId);
      if (!message || message.burning) return;
      if (!isRevealableImage(message.attachment, message.direction)) return;
      // Uncover the image and arm a HARD 10s window. The timer is a store-level
      // timeout, not a React effect, so it survives recomposition; a backgrounded
      // tab may fire it late (timer throttling) but the in-memory bytes are lost
      // if the tab is discarded anyway — at least as safe as the burn it defers.
      updateAttachment(peerId, messageId, { revealed: true });
      setTimeout(() => {
        const current = (get().messages[peerId] ?? []).find((m) => m.id === messageId);
        if (!current || current.burning) return;
        // Re-cover, then burn on both ends via the SAME message.burn signal used
        // by every other burn — no new wire message, no server logic.
        updateAttachment(peerId, messageId, { revealed: false });
        setBurning(peerId, messageId);
        get().ws?.send({ type: "message.burn", message_id: messageId, peer_id: peerId });
      }, IMAGE_REVEAL_MS);
    },

    setActivePeer(peerId) {
      set({ activePeer: peerId });
    },

    async markVerified(peerId) {
      const { keyStore, contacts } = get();
      const contact = contacts[peerId];
      if (!keyStore || !contact) return;
      keyStore.verifiedContacts[peerId] = contact.identityKey;
      await persist();
      set((s) => ({ contacts: { ...s.contacts } }));
    },

    async getSafetyNumber(peerId) {
      const { keyStore, contacts } = get();
      const contact = contacts[peerId];
      if (!keyStore || !contact) return "";
      const mine = (keyStore.identityKey as unknown as SerializedIdentity).publicKey;
      return safetyNumber(unb64(mine), unb64(contact.identityKey));
    },

    async deleteAccount() {
      const token = await freshToken();
      await api.deleteAccount(token);
      stopDecoy();
      get().ws?.close();
      // Turn this vault's slot back into random filler. The image keeps its
      // exact size and shape — other vaults (if any) are untouched, and the
      // deletion leaves no trace that a vault was ever here.
      const { vault } = get();
      try {
        if (vault) await destroyVaultSlot(vault);
      } finally {
        // The server account is already gone and destroyVaultSlot wiped the
        // key even on failure — staying in "ready" would strand a dead
        // session, so the teardown must run unconditionally.
        set({
          phase: "setup",
          keyStore: null,
          vault: null,
          accountId: null,
          localFingerprint: null,
          accessToken: null,
          refreshToken: null,
          contacts: {},
          messages: {},
          activePeer: null,
        });
      }
    },

    async resetDevice() {
      // Normally reached only from the locked gate, but tear everything down
      // anyway — a device reset must never leave decrypted state in memory or
      // a socket/decoy scheduler running, whatever state it was called from.
      stopDecoy();
      get().ws?.close();
      const { vault } = get();
      if (vault) wipe(vault.vaultKey);
      await destroyVaultImage();
      set({
        phase: "setup",
        unlockError: undefined,
        keyStore: null,
        vault: null,
        accountId: null,
        localFingerprint: null,
        accessToken: null,
        refreshToken: null,
        ws: null,
        contacts: {},
        messages: {},
        activePeer: null,
      });
    },

    lock() {
      stopDecoy();
      get().ws?.close();
      // Drop key material and decrypted messages from memory. The key wipe is
      // queued behind any in-flight persist (which seals with this very
      // buffer) so locking never drops a mutation whose side effects — an
      // advanced ratchet, a sent message — already happened.
      const { vault } = get();
      if (vault) void retireVaultSession(vault);
      set({
        phase: "unlock",
        keyStore: null,
        vault: null,
        localFingerprint: null,
        accessToken: null,
        refreshToken: null,
        messages: {},
        contacts: {},
        activePeer: null,
      });
    },
  };
});
