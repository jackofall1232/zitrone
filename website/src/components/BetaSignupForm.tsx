// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

"use client";

import { useState, type FormEvent } from "react";
import { BETA_CONTACT_EMAIL } from "@/lib/links";

type Status = "idle" | "sending" | "done" | "error";

/**
 * Play-beta qualification signup. Submits to /api/beta-signup, which forwards
 * the address straight to the maintainer inbox — the site keeps no database
 * (see the route handler; both promises must change together). If delivery is
 * unavailable, falls back to a prefilled mailto so the page never dead-ends.
 */
export function BetaSignupForm() {
  const [email, setEmail] = useState("");
  const [pledge, setPledge] = useState(false);
  const [status, setStatus] = useState<Status>("idle");

  const mailtoFallback = `mailto:${BETA_CONTACT_EMAIL}?subject=${encodeURIComponent(
    "Play beta signup",
  )}&body=${encodeURIComponent(
    "I want to test the Zitrone Play Store beta.\n\n" +
      "I commit to installing it from Google Play when it goes live, keeping it installed " +
      "for at least 21 days, and reporting improvements or issues.\n\n" +
      "Notify me at this address.",
  )}`;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (status === "sending") return;
    setStatus("sending");
    try {
      const res = await fetch("/api/beta-signup", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email: email.trim(), pledge, website: "" }),
      });
      setStatus(res.ok ? "done" : "error");
    } catch {
      setStatus("error");
    }
  }

  if (status === "done") {
    return (
      <div className="mt-6 rounded-md border border-lemon/40 bg-bg-elevated p-5">
        <p className="font-display text-base font-semibold text-lemon">You&apos;re on the list.</p>
        <p className="mt-2 leading-relaxed text-ink-secondary">
          One email is on its way to the maintainer — nothing was stored anywhere else. You&apos;ll
          hear from <span className="text-ink-primary">{BETA_CONTACT_EMAIL}</span> when the Play
          Store beta goes live.
        </p>
      </div>
    );
  }

  return (
    <form onSubmit={submit} className="mt-6 rounded-md border border-line bg-bg-elevated p-5">
      <label
        htmlFor="beta-email"
        className="block font-mono text-[11px] uppercase tracking-wide text-ink-muted"
      >
        Email address
      </label>
      <input
        id="beta-email"
        type="email"
        required
        autoComplete="email"
        value={email}
        onChange={(e) => setEmail(e.target.value)}
        placeholder="you@example.com"
        className="mt-2 w-full rounded-md border border-line bg-bg-secondary px-4 py-3 text-ink-primary placeholder:text-ink-muted"
      />

      {/* Honeypot — hidden from real users, tempting to bots. */}
      <input
        type="text"
        name="website"
        tabIndex={-1}
        autoComplete="off"
        aria-hidden="true"
        className="hidden"
      />

      <label className="mt-4 flex items-start gap-3 leading-relaxed text-ink-secondary">
        <input
          type="checkbox"
          required
          checked={pledge}
          onChange={(e) => setPledge(e.target.checked)}
          className="mt-1.5 h-4 w-4 shrink-0 accent-lemon"
        />
        <span>
          When the Play Store beta goes live, I&apos;ll install it from Google Play, keep it
          installed for at least <span className="text-ink-primary">21 days</span>, and report
          improvements or issues.
        </span>
      </label>

      <button type="submit" disabled={status === "sending"} className="btn-lemon mt-5">
        {status === "sending" ? "Signing up…" : "Sign up"}
      </button>

      {status === "error" && (
        <p className="mt-4 leading-relaxed text-ink-secondary">
          That didn&apos;t go through. You can sign up directly instead:{" "}
          <a
            href={mailtoFallback}
            className="text-lemon underline decoration-lemon/40 underline-offset-4 transition duration-base hover:decoration-lemon"
          >
            email {BETA_CONTACT_EMAIL}
          </a>{" "}
          with the same pledge.
        </p>
      )}
    </form>
  );
}
