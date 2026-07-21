// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import { useMemo, useState } from "react";
import { ConversationList, LemonSlice } from "@zitrone/ui";
import { isUuid } from "@zitrone/protocol";
import { useApp } from "../store.js";
import { composeChatWatermark } from "../lib/watermark.js";

export function ChatList({ onOpenSettings }: { onOpenSettings: () => void }) {
  const contacts = useApp((s) => s.contacts);
  const keyStore = useApp((s) => s.keyStore);
  const messages = useApp((s) => s.messages);
  const activePeer = useApp((s) => s.activePeer);
  const setActivePeer = useApp((s) => s.setActivePeer);
  const addContact = useApp((s) => s.addContact);
  const deleteContact = useApp((s) => s.deleteContact);
  const accountId = useApp((s) => s.accountId);
  const localFingerprint = useApp((s) => s.localFingerprint);
  const [adding, setAdding] = useState(false);
  const [peerId, setPeerId] = useState("");
  const [name, setName] = useState("");
  const [addError, setAddError] = useState<string | undefined>();
  const [pendingDelete, setPendingDelete] = useState<string | null>(null);
  const [deleteBusy, setDeleteBusy] = useState(false);

  const conversations = Object.entries(contacts).map(([id, c]) => ({
    id,
    displayName: c.displayName,
    verified: keyStore?.verifiedContacts[id] === c.identityKey,
    unreadCount: (messages[id] ?? []).filter((m) => m.direction === "received" && !m.opened).length,
  }));

  const pendingName =
    pendingDelete != null ? (contacts[pendingDelete]?.displayName ?? "this contact") : "";

  // Same security-paper watermark as ChatView, behind the conversation list. A
  // fixed conversation id ("chat-list") stands in for the per-peer id in the
  // stego payload.
  const watermark = useMemo(
    () => (accountId ? composeChatWatermark(accountId, "chat-list", localFingerprint) : null),
    [accountId, localFingerprint],
  );

  return (
    <aside className="flex h-full w-80 flex-col border-r border-line bg-bg-primary">
      <header className="flex items-center justify-between px-4 py-4">
        <div className="flex items-center gap-2">
          <LemonSlice variant="logo_mark" size={26} />
          <span className="font-display text-lg font-bold tracking-wide text-lemon">ZITRONE</span>
        </div>
        <button
          onClick={onOpenSettings}
          aria-label="Settings"
          className="text-ink-secondary transition-colors hover:text-lemon"
        >
          ⚙
        </button>
      </header>

      {/* One background image at 1:1 scale — the visible tile carries the LSB
          stego watermark in its pixels; overlaying or rescaling would corrupt a
          screenshot's stego layer (see composeChatWatermark). */}
      <div
        className="flex-1 overflow-y-auto"
        style={
          watermark
            ? {
                backgroundImage: `url(${watermark.url})`,
                backgroundSize: `${watermark.sizePx}px ${watermark.sizePx}px`,
              }
            : undefined
        }
      >
        <ConversationList
          conversations={conversations}
          activeId={activePeer ?? undefined}
          onSelect={setActivePeer}
          onDelete={(id) => setPendingDelete(id)}
        />
        {conversations.length === 0 && (
          <p className="px-4 py-8 text-center text-sm text-ink-muted">
            No conversations. Add a contact to start.
          </p>
        )}
      </div>

      {pendingDelete && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="delete-contact-title"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
        >
          <div className="w-full max-w-sm rounded-xl border border-line bg-bg-elevated p-5 shadow-lg">
            <h2 id="delete-contact-title" className="font-display text-base font-semibold text-ink-primary">
              Delete {pendingName}?
            </h2>
            <p className="mt-2 text-sm text-ink-secondary">
              This permanently destroys the encryption session and keys for this
              contact. Past messages become permanently undecryptable. You cannot
              undo this. Re-adding the same person starts a completely fresh
              handshake.
            </p>
            <div className="mt-4 flex gap-2">
              <button
                type="button"
                disabled={deleteBusy}
                onClick={() => {
                  setDeleteBusy(true);
                  void deleteContact(pendingDelete)
                    .then(() => {
                      setPendingDelete(null);
                    })
                    .finally(() => setDeleteBusy(false));
                }}
                className="flex-1 rounded-full bg-burn-red py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
              >
                {deleteBusy ? "Deleting…" : "Delete permanently"}
              </button>
              <button
                type="button"
                disabled={deleteBusy}
                onClick={() => setPendingDelete(null)}
                className="rounded-full px-4 text-sm text-ink-secondary hover:text-ink-primary disabled:opacity-50"
              >
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}

      {adding ? (
        <form
          className="flex flex-col gap-2 border-t border-line p-4"
          onSubmit={(e) => {
            e.preventDefault();
            if (!isUuid(peerId.trim())) {
              setAddError("That's not a valid contact ID");
              return;
            }
            void addContact(peerId.trim(), name.trim() || "Contact")
              .then(() => {
                setAdding(false);
                setPeerId("");
                setName("");
                setAddError(undefined);
              })
              .catch(() => setAddError("Contact not found"));
          }}
        >
          <input
            value={peerId}
            onChange={(e) => setPeerId(e.target.value)}
            placeholder="Contact ID (UUID)"
            className="rounded-md border border-line bg-bg-elevated px-3 py-2 font-mono text-xs text-ink-primary outline-none focus:border-line-active"
          />
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Display name (only you see this)"
            className="rounded-md border border-line bg-bg-elevated px-3 py-2 text-sm text-ink-primary outline-none focus:border-line-active"
          />
          {addError && <span className="text-xs text-burn-red">{addError}</span>}
          <div className="flex gap-2">
            <button
              type="submit"
              className="flex-1 rounded-full bg-lemon py-2 text-sm font-medium text-ink-on-lemon hover:bg-lemon-bright"
            >
              Add
            </button>
            <button
              type="button"
              onClick={() => setAdding(false)}
              className="rounded-full px-4 text-sm text-ink-secondary hover:text-ink-primary"
            >
              Cancel
            </button>
          </div>
        </form>
      ) : (
        <div className="border-t border-line p-4">
          <button
            onClick={() => setAdding(true)}
            className="w-full rounded-full bg-lemon py-2.5 text-sm font-medium text-ink-on-lemon shadow-lemon-sm transition-shadow hover:bg-lemon-bright hover:shadow-lemon-md"
          >
            + New conversation
          </button>
          {accountId && (
            <p className="mt-3 select-text break-all text-center font-mono text-[10px] text-ink-muted">
              Your ID: {accountId}
            </p>
          )}
        </div>
      )}
    </aside>
  );
}
