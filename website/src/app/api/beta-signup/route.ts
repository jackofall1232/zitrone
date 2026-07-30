// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import { NextResponse } from "next/server";
import { BETA_CONTACT_EMAIL } from "@/lib/links";

/**
 * Play-beta tester signup. Deliberately database-free: each signup is forwarded
 * as one email to the maintainer inbox (BETA_CONTACT_EMAIL), which IS the list.
 * The site stores nothing — that is the privacy promise the form makes, so no
 * storage may be added here without updating /privacy and the form copy.
 *
 * Delivery is Resend's plain HTTP API (no SDK dependency). Configure on Vercel:
 *   RESEND_API_KEY   — required; without it this route returns 503 and the form
 *                      falls back to a prefilled mailto link.
 *   BETA_SIGNUP_FROM — optional From header; defaults to Resend's onboarding
 *                      sender, which works before the domain is verified.
 */

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;

export async function POST(request: Request) {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: "bad_request" }, { status: 400 });
  }

  const { email, pledge, website } = (body ?? {}) as {
    email?: unknown;
    pledge?: unknown;
    website?: unknown;
  };

  // Honeypot: real users never see this field; bots that fill it get a
  // deliberately indistinguishable "success".
  if (typeof website === "string" && website.length > 0) {
    return NextResponse.json({ ok: true });
  }

  if (typeof email !== "string" || email.length > 254 || !EMAIL_RE.test(email)) {
    return NextResponse.json({ error: "invalid_email" }, { status: 400 });
  }
  if (pledge !== true) {
    return NextResponse.json({ error: "pledge_required" }, { status: 400 });
  }

  const apiKey = process.env.RESEND_API_KEY;
  if (!apiKey) {
    return NextResponse.json({ error: "not_configured" }, { status: 503 });
  }

  const sent = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      from: process.env.BETA_SIGNUP_FROM ?? "Zitrone Beta <onboarding@resend.dev>",
      // BETA_SIGNUP_TO exists because Resend's unverified-domain test sender can
      // only deliver to the Resend account owner's address — set it to that
      // address until the zitrone.app domain is verified, then remove it.
      to: [process.env.BETA_SIGNUP_TO ?? BETA_CONTACT_EMAIL],
      reply_to: email,
      subject: `Play beta signup: ${email}`,
      text:
        `New Play Store beta tester signup.\n\n` +
        `Email: ${email}\n` +
        `Signed up: ${new Date().toISOString()}\n\n` +
        `Pledge confirmed on the form:\n` +
        `- install the beta from Google Play when it goes live\n` +
        `- keep it installed for at least 21 days\n` +
        `- report improvements or issues\n`,
    }),
  }).catch(() => null);

  if (!sent || !sent.ok) {
    // Server-side only: Resend's status/body never reaches the client, and the
    // tester's address is deliberately not logged.
    console.error(
      "beta-signup delivery failed:",
      sent ? `${sent.status} ${await sent.text().catch(() => "")}` : "fetch error",
    );
    return NextResponse.json({ error: "delivery_failed" }, { status: 502 });
  }
  return NextResponse.json({ ok: true });
}
