// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// Platform status is stated honestly: Android is the only shipped client
// (sideloaded beta). Everything else is in development, behind Android —
// a badge must never imply a download that doesn't exist.
const PLATFORMS: { name: string; status: string; available: boolean }[] = [
  { name: "Android", status: "Available now · sideloaded beta", available: true },
  { name: "iOS", status: "In development", available: false },
  { name: "Desktop", status: "In development", available: false },
  { name: "Web", status: "In development", available: false },
];

export function PlatformBadges() {
  return (
    <section className="bg-bg-primary px-6 py-20">
      <div className="mx-auto flex max-w-4xl flex-wrap items-center justify-center gap-4">
        {PLATFORMS.map((platform) => (
          <span
            key={platform.name}
            className={`flex flex-col items-center rounded-pill border px-6 py-3 font-mono text-sm transition duration-base ease-brand ${
              platform.available
                ? "border-lemon/60 bg-bg-elevated text-ink-primary hover:border-lemon hover:text-lemon"
                : "border-line bg-bg-elevated text-ink-secondary opacity-80"
            }`}
          >
            {platform.name}
            <span
              className={`mt-0.5 text-[10px] uppercase tracking-wide ${
                platform.available ? "text-lemon" : "text-ink-muted"
              }`}
            >
              {platform.status}
            </span>
          </span>
        ))}
      </div>
      <p className="mx-auto mt-6 max-w-xl text-center text-sm leading-relaxed text-ink-muted">
        Android is the reference implementation and the only client you can install today. The other
        clients are in development, behind Android — they&apos;ll appear here when they ship, and
        not before.
      </p>
    </section>
  );
}
