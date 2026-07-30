// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

"use client";

import { motion } from "framer-motion";
import Link from "next/link";
import { LemonSlice } from "@/components/LemonSlice";

// The shipped features that don't fit a hero blurb — vault deniability, the
// duress password, dead drops, cover traffic. Card pattern and motion configs
// mirror HowItWorks exactly; every claim is grounded in docs/FEATURES.md.
const CUTS = [
  {
    label: "Second vault",
    description:
      "A second passphrase can open a second, fully independent Zitrone — no button, no wizard, no cryptographic evidence it exists. The instructions live on this site, not in the app: the app showing nothing is the point.",
    href: "/how-to#second-vault",
  },
  {
    label: "Pucker Burn",
    description:
      "A duress password. Typed at the lock screen, it erases everything Zitrone holds on the device. The app can't even tell you one is set — a readback would itself prove it exists.",
    href: "/how-to#pucker-burn",
  },
  {
    label: "Lemon drops",
    description:
      "Seal a message into a one-time QR dead drop that hides in plain sight: anyone can scan it, only the device it was sealed for can open it. Opening consumes it, and a drop unclaimed by its deadline burns on the relay.",
    href: "/how-to#lemon-drops",
  },
  {
    label: "Cover traffic",
    description:
      "Every real send travels with a same-size cover frame at a randomized offset, so a network observer can't pick out the real one. No toggle, no indicator — and a real send is never delayed to make cover.",
    href: "/how-to#network",
  },
];

export function DeeperCuts() {
  return (
    <section id="deeper-cuts" className="border-y border-line bg-bg-secondary px-6 py-28">
      <div className="mx-auto max-w-6xl">
        <motion.div
          initial={{ opacity: 0, y: 32 }}
          whileInView={{ opacity: 1, y: 0 }}
          viewport={{ once: true, margin: "-80px" }}
          transition={{ duration: 0.6, ease: [0.16, 1, 0.3, 1] }}
        >
          <div className="flex items-center gap-3">
            <span className="h-px w-8 bg-lemon" aria-hidden />
            <span className="font-mono text-xs uppercase tracking-[0.22em] text-lemon">
              Deeper cuts
            </span>
          </div>
          <h2 className="mt-5 font-display text-3xl font-semibold tracking-display text-ink-primary sm:text-4xl">
            Built for the day it matters
          </h2>
          <p className="mt-4 max-w-md text-ink-secondary">
            Shipped, on Android, today. Some of it deliberately has no UI at all — the full
            walkthrough is in the how-to guide.
          </p>
        </motion.div>

        <div className="mt-14 grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-4">
          {CUTS.map((cut, i) => (
            <motion.div
              key={cut.label}
              initial={{ opacity: 0, y: 32 }}
              whileInView={{ opacity: 1, y: 0 }}
              viewport={{ once: true, margin: "-60px" }}
              transition={{ duration: 0.5, delay: i * 0.08, ease: [0.16, 1, 0.3, 1] }}
            >
              <Link
                href={cut.href}
                className="flex h-full flex-col gap-4 rounded-lg border border-line bg-bg-elevated p-6 shadow-card transition duration-base ease-brand hover:-translate-y-1 hover:border-lemon/50 hover:shadow-lemon-sm"
              >
                <div className="flex items-center gap-3">
                  <LemonSlice size={32} segments={i + 1} label="" />
                  <span className="font-mono text-xs text-ink-muted">0{i + 1}</span>
                </div>
                <h3 className="font-display text-lg font-semibold text-ink-primary">{cut.label}</h3>
                <p className="text-sm leading-relaxed text-ink-secondary">{cut.description}</p>
              </Link>
            </motion.div>
          ))}
        </div>
      </div>
    </section>
  );
}
