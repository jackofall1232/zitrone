// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import type { Metadata } from "next";
import { LemonSlice } from "@/components/LemonSlice";
import { AUDIT_LOG, GITHUB_URL, SECURITY_MODEL_DOC, SECURITY_POLICY } from "@/lib/links";

export const metadata: Metadata = {
  title: "Security — Zitrone",
  description:
    "How Zitrone works under the hood: Signal Protocol, a passphrase-sealed vault, a server that stores almost nothing, screenshot protection, cover traffic, and I2P/Tor routing.",
};

export default function SecurityPage() {
  return (
    <main className="bg-bg-primary px-6 pb-28 pt-32">
      <article className="prose-security mx-auto max-w-3xl">
        <header className="mb-4">
          <LemonSlice size={56} label="" />
          <h1 className="mt-8 font-display text-4xl font-bold tracking-display text-ink-primary sm:text-5xl">
            How Zitrone works
          </h1>
          <p className="mt-6 text-lg leading-relaxed text-ink-secondary">
            Zero-knowledge architecture means the server never sees, stores, or logs plaintext
            message content under any circumstances. Not as a policy. As a property of the design.
            Here&apos;s the whole thing, in plain language.
          </p>
        </header>

        <h2 id="signal-protocol">Signal Protocol</h2>
        <p>
          Zitrone uses the Signal Protocol — the X3DH key agreement for establishing a shared secret
          with someone you&apos;ve never messaged before, and the Double Ratchet for everything
          after. It&apos;s the most scrutinized messaging cryptography in existence. We didn&apos;t
          invent our own. You should be suspicious of anyone who does.
        </p>
        <p>
          Every single message is encrypted with its own AES-256-GCM message key, derived by the
          ratchet and discarded after use. Keys rotate with every message, which gives you forward
          secrecy: if a key were ever compromised, it unlocks one message, not your history — and
          your history is probably already gone anyway.
        </p>
        <p>
          The implementations are established libraries, not homemade: the shipped Android client
          uses <code>libsignal-android</code>, and the reference crypto for the in-development
          clients is built on <code>libsodium</code>.
        </p>

        <h2 id="keys">Key generation and storage</h2>
        <p>
          Your identity is a Curve25519 keypair generated on your device. The private half never
          leaves it. There is no account recovery, because there is nothing on our side to recover.
        </p>
        <ul>
          <li>
            <strong>Identity key</strong> — long-term Curve25519 keypair, generated on device, never
            leaves the device.
          </li>
          <li>
            <strong>Signed prekey</strong> — Curve25519, rotated every 7 days, signed by your
            identity key.
          </li>
          <li>
            <strong>One-time prekeys</strong> — uploaded in batches of 100 (public keys only), each
            consumed exactly once.
          </li>
          <li>
            <strong>Session keys</strong> — derived via X3DH on first contact, then advanced by the
            Double Ratchet.
          </li>
          <li>
            <strong>Message keys</strong> — AES-256-GCM, one per message, discarded after use.
          </li>
        </ul>
        <p>Where your keys live depends on your platform:</p>
        <ul>
          <li>
            <strong>Android</strong> (shipping) — everything lives inside the passphrase-sealed
            vault described in the next section. Biometric unlock, where enabled, wraps the vault
            key through the Android Keystore System, hardware-backed where the device supports it.
          </li>
          <li>
            <strong>iOS</strong> (in development, not yet released) — planned to use the Secure
            Enclave and Keychain, biometric-protected.
          </li>
          <li>
            <strong>Linux</strong> (in development, not yet released) — planned to use the Secret
            Service API (GNOME Keyring on GNOME, KWallet on KDE), with an Argon2id+AES-256-GCM
            encrypted-file fallback on minimal desktops. Either way the vault is encrypted before it
            reaches the storage layer.
          </li>
        </ul>

        <h2 id="vault">The vault — and plausible deniability</h2>
        <p>
          On Android, everything Zitrone holds — keys, contacts, messages — lives inside a single
          encrypted vault image, sealed under a passphrase you set at first launch. The unlock key
          is derived on-device with Argon2id; nothing about the vault ever reaches a server, which
          is also why the passphrase is <strong>unrecoverable</strong>: there is no reset and
          nothing on our side to recover.
        </p>
        <p>
          The vault image has a fixed number of slots, and an unused slot is uniformly random filler
          — byte-indistinguishable from a used one. The number of vaults on a device is never stored
          anywhere. That structure exists for a reason: Zitrone supports a{" "}
          <strong>second, plausibly-deniable vault</strong> — a fully independent identity behind a
          different passphrase, with no cryptographic evidence that it exists. There is deliberately
          no UI for it anywhere in the app; the complete instructions, and the sharp edges you must
          read before relying on it, live on <a href="/how-to#second-vault">the how-to page</a>.
        </p>

        <h2 id="pucker-burn">Pucker Burn — the duress password</h2>
        <p>
          A separate password that, entered at the lock screen, erases everything Zitrone holds on
          the device — every vault, preferences, keystore entries, caches — and closes the app. Set
          it under Settings → Account. Two properties are deliberate: the app offers{" "}
          <strong>no readback anywhere</strong> — it cannot tell you whether a burn password is set,
          because that answer would itself prove a duress credential exists — and consequently,{" "}
          <strong>forgetting it is unrecoverable</strong>. A burn is device-local: it does not
          delete your account on the relay. An armed device is byte-indistinguishable from an
          unarmed one.
        </p>

        <h2 id="server">What the server stores (and doesn&apos;t)</h2>
        <p>
          The server is a relay, not an archive. Store-and-forward only: a message sits on the
          server as an opaque encrypted blob until your recipient&apos;s device confirms delivery,
          at which point it is deleted immediately. Undelivered messages are purged after 72 hours
          and the sender is told so.
        </p>
        <p>The complete list of what the server stores:</p>
        <ul>
          <li>Your account ID — a random UUID, not a username</li>
          <li>Your public identity key (Curve25519)</li>
          <li>Your public prekeys (one-time and signed)</li>
          <li>Encrypted message envelopes in transit — blob only</li>
          <li>Encrypted attachment blobs — opaque, held under a token hash (see below)</li>
          <li>Delivery receipts — a hash of the message ID, nothing else</li>
          <li>Account creation timestamp</li>
        </ul>
        <p>And what it never stores:</p>
        <ul>
          <li>Plaintext messages, or message content of any kind</li>
          <li>IP addresses</li>
          <li>Device identifiers</li>
          <li>Contact lists</li>
          <li>Read receipts linked to identity</li>
          <li>Any logs that identify users — access logs are disabled outright</li>
        </ul>
        <p>
          Attachments are <strong>blind blobs</strong>: a file is encrypted with its own key on your
          device and uploaded to the relay, which stores it under a hash of a token it never sees.
          The token and the key travel inside the ratchet-encrypted message — the relay holds
          ciphertext it cannot decrypt and cannot even look up without the token.
        </p>
        <p>
          Transport is WebSocket over TLS 1.3, with certificate pinning in the shipped Android
          client. Typing indicators and read receipts travel as encrypted signals too — the server
          can&apos;t even tell whether you&apos;ve read something. Deleting your account purges
          everything: prekeys, pending envelopes, the account record. Irreversibly.
        </p>

        <h2 id="lemon-drops">Lemon drops — QR dead drops</h2>
        <p>
          A message can be sealed into a one-time QR &quot;drop&quot; hosted on the relay instead of
          sent to a contact: the QR image is the entire capability, opening it consumes it, and a
          drop unclaimed by its sender-chosen deadline is destroyed. Sealing a drop solves a small
          proof-of-work deposit, so the relay can&apos;t be flooded with them for free. The feature
          is off by default; the <a href="/how-to#lemon-drops">how-to page</a> covers enabling and
          using it.
        </p>

        <h2 id="cover-traffic">Cover traffic</h2>
        <p>
          The Android client emits synthetic traffic so that a passive network observer — an ISP, a
          hostile Wi-Fi network — cannot pick out a real send by its timing. Every real send is
          paired with a cover frame of the same length, in unpredictable order, with a randomized
          delay. Two things are deliberate about it. First, <strong>it has no UI at all</strong> —
          no toggle, no indicator, nothing in Settings — because a visible switch would itself be a
          signal. Second,{" "}
          <strong>
            a real message is never delayed, reordered, blocked, or made less durable to produce
            cover
          </strong>
          ; under any contention, cover yields and the real send proceeds. Honest limits: cover is
          paired with real sends, so periods when you send nothing are not covered — dead air is not
          disguised. And it does not hide who talks to whom from the relay itself, which sees
          envelope routing regardless.
        </p>

        <h2 id="screenshots">Screenshot protection, by platform</h2>
        <p>
          Each platform allows a different level of protection, so we&apos;re specific about it.
          Zitrone ships on <strong>Android</strong> today — the iOS and Linux clients below are in
          development and not yet available for download.
        </p>
        <ul>
          <li>
            <strong>Android</strong> (shipping) — <code>FLAG_SECURE</code> on every screen with
            message content. This is an OS-level hard block: screenshots and screen recordings come
            out black. The strongest protection of the three. On top of that, every chat carries an
            <strong>identity watermark</strong>: a faint, tiled lattice of <em>your own</em>{" "}
            identity fingerprint painted behind the messages, so anything photographed off the
            screen is visibly marked as yours. It is deliberately visible — a deterrent nobody can
            see deters nobody — and it is always on, with no toggle. It is drawn on your device and
            reported nowhere: it marks a leak for whoever later looks at the image, not for us. We
            have no telemetry and no way to know a screenshot happened.
          </li>
          <li>
            <strong>iOS</strong> (in development, not yet released) — screen recording is detected
            in real time and the message list is blurred immediately. Screenshots can&apos;t be
            prevented on iOS — the API only fires after the fact — so we detect them, warn you, and
            log the event locally on your device.
          </li>
          <li>
            <strong>Linux (desktop app)</strong> (in development, not yet released) — a focus-loss
            blur overlay: the moment the window loses focus or visibility, the message list is
            blurred and desaturated, plus the same identity watermark described under Android. This
            is best-effort: Linux exposes no universal API to hard-block screen capture on either
            Wayland or X11, and we won&apos;t pretend otherwise. Android remains the platform with a
            true OS-level hard block.
          </li>
        </ul>
        <p>
          Honesty clause: a compromised device with an OS-level keylogger, or someone pointing a
          second camera at the screen, is outside any app&apos;s power. We don&apos;t pretend
          otherwise.
        </p>

        <h2 id="network">The network layer: I2P, Tor, clearnet</h2>
        <p>
          Network-level metadata — who connected, from where — is the hardest thing for any
          messenger to hide. Zitrone keeps stored metadata minimal, and on the wire it resolves its
          transport in a fixed order:
        </p>
        <ul>
          <li>
            <strong>I2P first, on by default.</strong> When the official I2P app is installed, the
            Android client routes through its local proxy automatically. Without the I2P app the
            setting is inert — Settings shows an install link when it&apos;s missing.
          </li>
          <li>
            <strong>Tor, opt-in.</strong> Routing through Orbot&apos;s local SOCKS proxy. Slower,
            more private than clearnet; requires Orbot.
          </li>
          <li>
            <strong>Clearnet, as a warned fallback.</strong> When neither router is available, the
            client connects directly — and the connection status in Settings says so explicitly,
            including that clearnet exposes your IP address. Message content stays end-to-end
            encrypted on every transport; what the transport changes is who can see that you
            connected.
          </li>
        </ul>

        <h2 id="open-source">Open source and audit history</h2>
        <p>
          Everything — the encryption, the server, the shipped Android app and the in-development
          clients — is open source under AGPL-3.0 at{" "}
          <a href={GITHUB_URL}>github.com/jackofall1232/zitrone</a>. The AGPL means anyone running a
          modified Zitrone as a service must publish their changes. No silent forks with weakened
          crypto.
        </p>
        <p>
          Audit history: no third-party audits have been conducted yet. We&apos;d rather tell you
          that plainly than imply otherwise. The <a href={AUDIT_LOG}>audit log</a> updates as audits
          complete, and researchers who want to conduct one — or who find a vulnerability — should
          follow the <a href={SECURITY_POLICY}>responsible disclosure policy</a>: acknowledgement
          within 48 hours, fix target within 90 days, good-faith research explicitly authorized.
        </p>
        <p>
          For the full technical treatment — threat model, transport details, the works — read the{" "}
          <a href={SECURITY_MODEL_DOC}>security model documentation</a>. For a hands-on walkthrough
          of every shipped feature, see <a href="/how-to">the how-to guide</a>.
        </p>
      </article>
    </main>
  );
}
