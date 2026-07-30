// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import type { Metadata } from "next";
import Link from "next/link";
import { LemonSlice } from "@/components/LemonSlice";
import { ANDROID_BETA_PUBLISHED, GITHUB_URL, SELF_HOSTING_DOC } from "@/lib/links";

export const metadata: Metadata = {
  title: "Download — Zitrone",
  description:
    "Get Zitrone for Android — a sideloaded beta with a verifiable checksum. Other clients are in development. Or self-host the relay — it's open source.",
};

function StoreBadge({ store, line1 }: { store: string; line1: string }) {
  return (
    <div
      className="flex w-52 cursor-not-allowed items-center gap-3 rounded-md border border-line bg-bg-elevated px-5 py-3 opacity-80"
      aria-disabled
    >
      <LemonSlice size={28} label="" segments={0} />
      <div className="text-left">
        <div className="font-mono text-[10px] uppercase tracking-wide text-ink-muted">{line1}</div>
        <div className="font-display text-base font-semibold text-ink-primary">{store}</div>
      </div>
    </div>
  );
}

export default function DownloadPage() {
  return (
    <main className="bg-bg-primary px-6 pb-28 pt-32">
      <div className="mx-auto max-w-3xl">
        <LemonSlice size={56} label="" />
        <h1 className="mt-8 font-display text-4xl font-bold tracking-display text-ink-primary sm:text-5xl">
          Get the app
        </h1>
        <p className="mt-6 text-lg leading-relaxed text-ink-secondary">
          One client ships today: <strong className="text-ink-primary">Android</strong>, as a
          sideloaded beta you can verify byte-for-byte. Everything else is in development — we list
          it that way rather than pretend. And if you count running the entire relay yourself,
          there&apos;s one more way — which we encourage.
        </p>

        {/* Android — the shipped client */}
        <section className="mt-16">
          <h2 className="font-display text-2xl font-semibold tracking-display text-ink-primary">
            Android — available now (beta)
          </h2>
          <p className="mt-3 leading-relaxed text-ink-secondary">
            Zitrone isn&apos;t in any app store yet. The Android app is distributed as a signed,
            sideloaded APK — from GitHub Releases or a Tor onion mirror — with a SHA-256 checksum so
            you can verify exactly what you&apos;re installing.
          </p>
          {/* Deliberately version-agnostic: the beta page owns version and
              checksum details (they come from lib/links.ts there). Label and
              copy reflect whether a build actually exists yet — no overclaim. */}
          <Link
            href="/download/beta"
            className="mt-6 block rounded-md border border-lemon/40 bg-bg-elevated px-5 py-4 transition duration-base ease-brand hover:border-lemon"
          >
            <div className="font-mono text-[10px] uppercase tracking-wide text-lemon">
              {ANDROID_BETA_PUBLISHED ? "Available now" : "Coming soon"}
            </div>
            <div className="mt-1 font-display text-base font-semibold text-ink-primary">
              {ANDROID_BETA_PUBLISHED
                ? "Android beta — sideloaded, not in any app store yet"
                : "Android beta — not yet available to download"}
            </div>
            <div className="mt-1 text-sm leading-relaxed text-ink-secondary">
              {ANDROID_BETA_PUBLISHED
                ? "A signed APK with SHA-256 checksum and verification steps. It's a pre-release — expect rough edges."
                : "No public build has been cut yet. See what a beta install will involve, and track releases on GitHub."}
            </div>
          </Link>
          <div className="mt-6 flex flex-wrap gap-4">
            <StoreBadge line1="Not yet on" store="Google Play" />
          </div>
        </section>

        {/* Other platforms — honest tiers */}
        <section className="mt-16">
          <h2 className="font-display text-2xl font-semibold tracking-display text-ink-primary">
            iOS, desktop, and web
          </h2>
          <p className="mt-3 leading-relaxed text-ink-secondary">
            In development, behind Android — none of them is available to download today. Android is
            the reference implementation; the other clients follow it, and this page will offer them
            only when they actually ship. If a site or store offers you a Zitrone build for these
            platforms today, it isn&apos;t ours.
          </p>
        </section>

        {/* Self-host */}
        <section className="mt-16">
          <h2 className="font-display text-2xl font-semibold tracking-display text-ink-primary">
            Self-host it
          </h2>
          <p className="mt-3 leading-relaxed text-ink-secondary">
            Don&apos;t want to trust our server? Good instinct. Run your own — it&apos;s a Go
            binary, a Postgres database, and a Docker Compose file:
          </p>
          <pre className="mt-6 overflow-x-auto rounded-md border border-line bg-bg-secondary p-5 font-mono text-sm leading-relaxed text-pulp">
            <code>{`git clone ${GITHUB_URL}.git
cd zitrone
cp .env.example .env   # set DATABASE_URL, JWT key paths, TLS paths
docker compose up -d   # server + postgres
# optional Tor hidden service:
docker compose -f docker-compose.yml -f docker-compose.tor.yml up -d`}</code>
          </pre>
          <p className="mt-5 leading-relaxed text-ink-secondary">
            Full instructions — TLS setup, environment variables, the optional Tor hidden service,
            backups — are in the{" "}
            <a
              href={SELF_HOSTING_DOC}
              className="text-lemon underline decoration-lemon/40 underline-offset-4 transition duration-base hover:decoration-lemon"
            >
              self-hosting guide
            </a>
            .
          </p>
        </section>
      </div>
    </main>
  );
}
