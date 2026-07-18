// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

"use client";

import { motion } from "framer-motion";
import { LemonSlice } from "@/components/LemonSlice";

// Each audience is keyed by the ONE guarantee they actually optimize for —
// the mono label is a real structural device, not decorative numbering.
// Copy is grounded in docs/SECURITY_MODEL.md and the shipped known-limitations
// list; the private-investigator card deliberately states the forensic
// boundary rather than claiming "unrecoverable" (matching the doc's own
// VeraCrypt-analogous honesty).
interface Audience {
  concern: string;
  who: string;
  body: string;
  /** Lit segments on the card's slice — a quiet brand tick, kept equal so it
   *  reads as an anchor, never a ranking. */
  segments: number;
}

const AUDIENCES: Audience[] = [
  {
    concern: "Auditable",
    who: "Privacy enthusiasts",
    body: "You don't trust claims — you read the source. All of Zitrone is AGPL-licensed and public: the encryption, the relay, the apps. Zero-knowledge isn't marketing here — the server only ever holds opaque envelopes, and deletes them on delivery. And where something isn't finished, we say so; a known-limitations list ships with every beta.",
    segments: 8,
  },
  {
    concern: "Self-hosted",
    who: "IT professionals",
    body: "No vendor in the loop. The relay is a Go binary, a Postgres database, and a Docker Compose file — stand it up on your own box in minutes. The protocol is open, the source is public, and every release ships a SHA-256 checksum, so you can verify exactly what you're installing before it touches your network.",
    segments: 8,
  },
  {
    concern: "Data posture",
    who: "Enterprise executives",
    body: "The safest data is the data you never keep. Messages are store-and-forward only — deleted the instant they're delivered, with burn-on-read and expiry timers on top. No analytics pipeline, no ad model, no logs to subpoena. “Nothing lasts” is a data-minimization posture, not just a tagline.",
    segments: 8,
  },
  {
    concern: "Ephemeral",
    who: "Private investigators",
    body: "Communication built to vanish: burn-on-read, timed destruction, and plausible-deniability vaults where a second passphrase opens a second world — with no cryptographic trace the first exists. Everything at rest is AES-256-GCM inside a fixed-size image that never records what it holds. We're honest about the boundary, too: no app can promise “unrecoverable” against a forensic lab holding your unlocked device — so we minimize what's left to find instead of pretending nothing is.",
    segments: 8,
  },
];

export function Audiences() {
  return (
    <section id="who" className="bg-bg-primary px-6 py-28">
      <div className="mx-auto max-w-6xl">
        <motion.div
          initial={{ opacity: 0, y: 24 }}
          whileInView={{ opacity: 1, y: 0 }}
          viewport={{ once: true, margin: "-80px" }}
          transition={{ duration: 0.6, ease: [0.16, 1, 0.3, 1] }}
        >
          <div className="flex items-center gap-3">
            <span className="h-px w-8 bg-lemon" aria-hidden />
            <span className="font-mono text-xs uppercase tracking-[0.22em] text-lemon">
              Who it&apos;s for
            </span>
          </div>
          <h2 className="mt-5 max-w-3xl font-display text-3xl font-semibold tracking-display text-ink-primary sm:text-5xl">
            Different reasons. Same guarantees.
          </h2>
          <p className="mt-5 max-w-2xl text-lg leading-relaxed text-ink-secondary">
            Zitrone doesn&apos;t have a target demographic — it has a threat model. Four kinds of
            people lean on the same zero-knowledge, ephemeral architecture, each for a reason of
            their own.
          </p>
        </motion.div>

        {/* Hairline "spec-sheet" grid: gap-px over a line-colored bed reads as an
            engineered ledger — the precision the brand keeps promising. */}
        <div className="mt-14 grid grid-cols-1 gap-px overflow-hidden rounded-xl border border-line bg-line sm:grid-cols-2">
          {AUDIENCES.map((a, i) => (
            <motion.article
              key={a.who}
              className="group relative bg-bg-primary p-8 transition-colors duration-base ease-brand hover:bg-bg-secondary sm:p-10"
              initial={{ opacity: 0, y: 28 }}
              whileInView={{ opacity: 1, y: 0 }}
              viewport={{ once: true, margin: "-60px" }}
              transition={{ duration: 0.5, delay: i * 0.08, ease: [0.16, 1, 0.3, 1] }}
            >
              {/* Left rule that lights on hover — the cell "selecting". */}
              <span
                aria-hidden
                className="absolute inset-y-0 left-0 w-px bg-transparent transition-colors duration-base group-hover:bg-lemon"
              />
              <div className="flex items-center justify-between">
                <span className="font-mono text-xs uppercase tracking-[0.18em] text-lemon/80">
                  {a.concern}
                </span>
                <LemonSlice
                  size={30}
                  segments={a.segments}
                  label=""
                  className="opacity-60 transition duration-slow ease-brand group-hover:rotate-45 group-hover:opacity-100"
                />
              </div>
              <h3 className="mt-6 font-display text-2xl font-semibold tracking-display text-ink-primary">
                {a.who}
              </h3>
              <p className="mt-4 leading-relaxed text-ink-secondary">{a.body}</p>
            </motion.article>
          ))}
        </div>
      </div>
    </section>
  );
}
