// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Shown when a lemon-drop redeem does NOT surface a message for us — either the
 * drop was sealed for another device ("not-for-us") or the relay has nothing to
 * hand back ("unavailable"). A "message" outcome needs no screen: the chat opens
 * (activePeer is set) and the drop lands as a received bubble.
 *
 * This is a MARKETING moment, not an error. A scan that isn't ours is turned
 * into "you're part of the network — keep it spreading", so it is styled warm
 * and lemon-bright: the signature slice glowing on the dark ground, with NO
 * error color and NO warning iconography (contrast the burn-red Danger section).
 *
 * The copy is deliberately honest. It does NOT report a decryption "failure" as
 * an error, does NOT name or imply who a drop's real recipient is, and does NOT
 * call this scan "anonymous". It states only the plain, true fact: a drop can be
 * opened solely by the device it was sealed for. See LemonDropAdvocacyScreen.kt
 * for the Android sibling this mirrors.
 */

import { LemonSlice } from "@zitrone/ui";

export type LemonDropOutcomeVariant = "not-for-us" | "unavailable";

const COPY: Record<LemonDropOutcomeVariant, { headline: string; body: string }> = {
  "not-for-us": {
    headline: "Sealed for someone else's device",
    body: "Only the device a lemon drop was sealed for can open it — the relay can't read it, and neither can this one.",
  },
  unavailable: {
    headline: "Nothing left to open here",
    body: "This drop can't be opened here — it may already have been claimed, or it expired and the relay shredded it.",
  },
};

// The same closing line on both variants — the drop is a piece of the network in
// the reader's hands either way.
const CLOSING_LINE = "You're holding a piece of the network. Pass it on.";

export function LemonDropOutcome({
  variant,
  onClose,
}: {
  variant: LemonDropOutcomeVariant;
  onClose: () => void;
}) {
  const { headline, body } = COPY[variant];

  return (
    // z-50 so this sits above the Settings modal (z-40) when opened from there.
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/70"
      role="dialog"
      aria-modal
    >
      <div className="flex w-full max-w-md flex-col items-center gap-5 rounded-xl border border-line bg-bg-secondary p-8 text-center">
        {/* Warm lemon halo behind the signature slice — the same "part of the
            network" glow the send button wears. Deliberately no error red and no
            warning icon: an unopenable drop is advocacy, not a failure. */}
        <div
          className="relative flex items-center justify-center"
          style={{ width: 168, height: 168 }}
        >
          <div
            aria-hidden
            className="absolute inset-0 rounded-full"
            style={{
              background:
                "radial-gradient(circle at center, rgba(245,230,66,0.22) 0%, transparent 70%)",
            }}
          />
          <LemonSlice variant="logo_mark" size={92} label="Zitrone" />
        </div>

        <h2 className="font-display text-xl font-semibold text-ink-primary">{headline}</h2>
        <p className="text-sm text-ink-secondary">{body}</p>
        <p className="text-sm font-medium text-lemon">{CLOSING_LINE}</p>

        <button
          onClick={onClose}
          className="mt-1 rounded-full bg-lemon px-5 py-2 text-sm font-medium text-ink-on-lemon"
        >
          Got it
        </button>
      </div>
    </div>
  );
}
