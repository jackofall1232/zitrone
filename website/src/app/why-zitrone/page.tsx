// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import type { Metadata } from "next";
import { LemonSlice } from "@/components/LemonSlice";

export const metadata: Metadata = {
  title: "Why Zitrone? — Zitrone",
  description:
    "Long before modern encryption, people trusted lemons to hide their words. The story behind the lemon — and behind Privacy, with zest.",
};

// The brand story is HoboJoe's own writing, used verbatim — layout and type
// scale are ours, the words are not. It ends on the tagline deliberately:
// this page is where "Privacy, with zest." comes from.
export default function WhyZitronePage() {
  return (
    <main className="bg-bg-primary px-6 pb-32 pt-36">
      <article className="mx-auto max-w-2xl">
        <header>
          <LemonSlice size={56} label="" />
          <h1 className="mt-10 font-display text-4xl font-bold tracking-display text-ink-primary sm:text-5xl">
            Why Zitrone?
          </h1>
        </header>

        <div className="mt-12 space-y-7 text-lg leading-relaxed text-ink-secondary">
          <p className="text-xl leading-relaxed text-ink-primary">
            Long before modern encryption, people trusted lemons to hide their words.
          </p>

          <p>
            For centuries, lemon juice was one of the most trusted ways to hide a message. Write
            with it, let it dry, and the page looks blank. Hold it to heat, and the words rise up
            out of nothing. Spies used it for generations — a message hiding in plain sight,
            invisible until the right person knew to look.
          </p>

          <p className="font-display text-xl font-medium text-ink-primary">We loved that history.</p>

          <p>
            Because a lemon has always been two things at once: an ordinary fruit sitting on a
            kitchen counter, and a tool capable of hiding a secret in plain sight.
          </p>

          <p className="font-display text-xl font-medium text-ink-primary">
            A lemon also protects what&apos;s inside.
          </p>

          <p>
            Beneath the rind is the pith. Beneath the pith are the segments. Layer by layer,
            everything at its center is protected. Nothing is exposed by accident.
          </p>

          <p>That&apos;s not a bad way to describe how Zitrone is built.</p>

          <p>
            Layer after layer, each designed with the assumption that another layer could someday
            fail. End-to-end encryption. Secure key exchange. Ephemeral messages. Optional privacy
            networks. Defense in depth. Every layer exists for one reason:
          </p>

          <p className="font-display text-xl font-medium text-ink-primary">
            To protect what you said.
          </p>

          <p className="font-display text-xl font-medium text-ink-primary">
            And who you said it to.
          </p>

          <p>
            We&apos;re not interested in the black hoodies, skull logos, and bunker aesthetics that
            privacy software often embraces. Surveillance is serious enough. We don&apos;t believe
            defending yourself has to look intimidating.
          </p>

          <p className="font-display text-2xl font-semibold text-ink-primary">
            Privacy should feel normal.
          </p>

          <p className="font-display text-2xl font-semibold text-ink-primary">Fresh.</p>

          <p className="font-display text-2xl font-semibold text-ink-primary">Bright.</p>

          <p className="pt-6 font-display text-3xl font-bold tracking-display text-lemon sm:text-4xl">
            Privacy, with zest. <span aria-hidden>🍋</span>
          </p>
        </div>
      </article>
    </main>
  );
}
