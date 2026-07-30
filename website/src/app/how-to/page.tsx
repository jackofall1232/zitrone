// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import type { Metadata } from "next";
import Link from "next/link";
import type { ReactNode } from "react";
import { LemonSlice } from "@/components/LemonSlice";
import { GITHUB_URL, SECURITY_MODEL_DOC } from "@/lib/links";

export const metadata: Metadata = {
  title: "How to use Zitrone — Zitrone",
  description:
    "A complete walkthrough of every shipped Zitrone feature: passphrase vault, contacts, burning messages, lemon drops, I2P and Tor routing, Pucker Burn, and the second vault.",
};

// Screenshots are reconstructed app screens, copied into /public/screenshots
// at deploy time. Annotation lives in captions and adjacent text — never baked
// into the images.
function Shot({ src, alt, caption }: { src: string; alt: string; caption: string }) {
  return (
    <figure className="my-8">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src={`/screenshots/${src}`}
        alt={alt}
        loading="lazy"
        className="w-full max-w-xs rounded-md border border-line bg-bg-elevated"
      />
      <figcaption className="mt-3 max-w-xs font-mono text-xs leading-relaxed text-ink-muted">
        {caption}
      </figcaption>
    </figure>
  );
}

// A bordered aside for the caveats that protect people's data. Same tokens as
// the beta page's pre-release warning box.
function Callout({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="my-8 rounded-md border border-lemon/40 bg-bg-elevated p-5">
      <p className="font-display text-base font-semibold text-lemon">{title}</p>
      <div className="mt-3 leading-relaxed text-ink-secondary">{children}</div>
    </div>
  );
}

export default function HowToPage() {
  return (
    <main className="bg-bg-primary px-6 pb-28 pt-32">
      <article className="prose-security mx-auto max-w-3xl">
        <header className="mb-4">
          <LemonSlice size={56} label="" />
          <h1 className="mt-8 font-display text-4xl font-bold tracking-display text-ink-primary sm:text-5xl">
            How to use Zitrone
          </h1>
          <p className="mt-6 text-lg leading-relaxed text-ink-secondary">
            Everything the app can do, in the order you&apos;ll meet it. Zitrone ships on{" "}
            <strong>Android</strong> today, as a sideloaded beta — no other client is available yet.
            Some of what&apos;s below is deliberately undiscoverable inside the app, so this page is
            worth reading to the end: for one feature, it is the only documentation that exists
            anywhere.
          </p>
        </header>

        <h2 id="first-launch">First launch: your passphrase</h2>
        <p>
          On first launch Zitrone asks you to create a passphrase. That passphrase seals a local
          encrypted vault holding everything the app knows — your identity keys, your contacts, your
          messages. It never leaves your device and it is never sent to any server.
        </p>
        <Shot
          src="08-onboarding.png"
          alt="Zitrone onboarding screen asking the user to create a passphrase"
          caption="First launch. The passphrase you set here seals the vault — choose it like it's the only copy of the key, because it is."
        />
        <Callout title="Your passphrase is unrecoverable. Full stop.">
          There is no reset, no recovery email, no support desk that can help. Nothing about your
          vault exists on our side to recover. If you forget the passphrase, the vault and
          everything in it is gone. That is the cost of the app genuinely not being able to read
          your data — pay it deliberately.
        </Callout>

        <h2 id="lock">Lock, auto-lock, and biometrics</h2>
        <p>
          When the vault is locked, the app shows only a lock screen — keys stay sealed and nothing
          is readable until you unlock. Three controls shape this, all under{" "}
          <strong>Settings → Security</strong>:
        </p>
        <ul>
          <li>
            <strong>Biometric unlock</strong> — fingerprint or face instead of typing. On by
            default.
          </li>
          <li>
            <strong>Auto-lock when backgrounded</strong> — re-locks after the app has been in the
            background for a chosen period: immediate, 1, 5, or 15 minutes. Default is 5 minutes.
          </li>
          <li>
            <strong>Your identity key fingerprint</strong> — the code contacts compare against what
            they see for you (more under verification below).
          </li>
        </ul>
        <Shot
          src="03-lock-screen.png"
          alt="Zitrone lock screen with passphrase entry"
          caption="The lock screen. Biometric prompt with a passphrase fallback — until it opens, the vault's keys stay sealed."
        />
        <p>
          There is no in-app &quot;lock now&quot; button. To lock, background the app and let
          auto-lock fire — set it to <em>Immediate</em> if you want backgrounding to mean locked.
          That absence is deliberate, and it matters later on this page.
        </p>

        <h2 id="contacts">Adding contacts</h2>
        <p>
          There are no phone numbers, emails, or usernames. Your identity is a key pair generated on
          your device, and contacts connect to it three ways — from the chat list, tap the lemon
          button (<em>New encrypted chat</em>):
        </p>
        <ul>
          <li>
            <strong>QR code</strong> — your contact code is shown as a QR for the other person to
            scan; scanning theirs adds them. The code contains only your contact ID.
          </li>
          <li>
            <strong>Pasted code or invite link</strong> — the no-camera path. Paste a contact ID,
            invite link, or raw QR payload into the add-contact field.
          </li>
          <li>
            <strong>Naming</strong> — after scanning or pasting you name the contact. The name is
            local-only: never sent, never synced.
          </li>
        </ul>
        <Shot
          src="04-add-contact.png"
          alt="Zitrone add-contact screen showing a QR code and a paste field"
          caption="Add a contact: show your QR, scan theirs, or use the paste field when no camera is handy."
        />
        <Callout title="Trust on first use — then verify.">
          A bare pasted ID or invite link carries no key to pin, so those contacts start on a
          trust-on-first-use basis: the first key the relay hands you is the one you get. To close
          that gap, open the chat, tap the contact&apos;s name, and choose <strong>Verify</strong>.
          Compare the safety number over a channel you already trust — in person, on a call you
          recognize. If it matches, you know there is no one in the middle. Do this for anything
          that matters.
        </Callout>

        <h2 id="messaging">Messaging and delivery states</h2>
        <p>
          Chats are 1:1 and end-to-end encrypted with the Signal Protocol — every message gets its
          own key. The chat list shows your conversations with a search pill at the top; each
          message bubble carries a delivery state:
        </p>
        <ul>
          <li>
            <code>…</code> — sending
          </li>
          <li>
            <code>✓</code> — the relay stored it
          </li>
          <li>
            <code>✓✓</code> — the recipient&apos;s device received it
          </li>
          <li>
            <code>✓✓</code> in lemon — read (read signals are encrypted; the relay never learns read
            status)
          </li>
          <li>
            <code>!</code> — failed. Tap the message to retry the send.
          </li>
        </ul>
        <Shot
          src="01-chat-list.png"
          alt="Zitrone chat list with conversations and the new-chat button"
          caption="The chat list: search at the top, the lemon button starts a new encrypted chat, the scan icon opens lemon drops."
        />
        <Shot
          src="02-chat.png"
          alt="A Zitrone conversation showing message bubbles with delivery states"
          caption="A conversation. Ticks are delivery states; the faint pattern behind the bubbles is your own identity watermark — it renders on your device and reports nothing."
        />
        <p>
          Read receipts can be turned off under <strong>Settings → Privacy</strong> (on by default).
          Typing indicators are automatic. To rename a contact, tap their name in the chat.
        </p>

        <h2 id="attachments">Attachments</h2>
        <p>From the paperclip in the compose bar:</p>
        <ul>
          <li>
            <strong>Take photo</strong> — captures inside the app through a secure capture screen.
          </li>
          <li>
            <strong>Photo library</strong> — the Android photo picker, images only.
          </li>
          <li>
            <strong>File</strong> — any document, not just images.
          </li>
        </ul>
        <p>
          Every attachment is encrypted with its own key and uploaded as a <em>blind blob</em>: the
          relay stores it under a hash and never sees the key or the token needed to fetch it — both
          travel inside the encrypted message. The relay cannot decrypt an attachment.
        </p>
        <p>
          <strong>Reveal-and-burn images:</strong> a received image arrives covered. Tapping it
          reveals it — and arms a hard burn timer. Look before you tap; the reveal is what starts
          the clock.
        </p>

        <h2 id="burning">Disappearing messages and burning</h2>
        <p>Three separate mechanisms, from gentle to total:</p>
        <ul>
          <li>
            <strong>Disappearing timer</strong> — a time-to-live on messages. Set a default under{" "}
            <strong>Settings → Privacy → Default disappearing timer</strong> (off, 30s, 1m, 5m, 1h,
            1d, 7d — off by default), or override it per message with the timer icon in the compose
            bar.
          </li>
          <li>
            <strong>Burn on read</strong> — the message destroys itself after its first open.{" "}
            <strong>Settings → Privacy → Burn on read by default</strong>, off by default.
          </li>
          <li>
            <strong>Burn a whole chat</strong> — the burn icon in a conversation&apos;s top bar
            destroys every message in it, at once.
          </li>
        </ul>
        <Shot
          src="07-settings-privacy.png"
          alt="Zitrone privacy settings showing disappearing timer, burn on read, and read receipt controls"
          caption="Settings → Privacy: default timer, burn-on-read, read receipts, and the lemon-drop compose button all live here."
        />
        <p>
          Messages visibly dissolve into particles as they burn. That&apos;s not decoration —
          it&apos;s confirmation.
        </p>

        <h2 id="lemon-drops">Lemon drops: QR dead drops</h2>
        <p>
          A lemon drop is a message sealed into a one-time QR code instead of sent to a contact. The
          relay hosts the encrypted drop; whoever scans the QR with the right device opens it —
          once.
        </p>
        <p>
          The feature is <strong>off by default</strong>. Enable it under{" "}
          <strong>Settings → Privacy → Lemon-drop compose button</strong>, and a droplet button
          appears in the compose bar.
        </p>
        <ul>
          <li>
            <strong>Sealing</strong> — compose, tap the droplet, pick a deadline. Sealing solves a
            small proof-of-work deposit before the relay accepts the drop.
          </li>
          <li>
            <strong>The QR image IS the capability.</strong> Whoever holds that image holds the
            drop. Print it, hand it over, send it through another channel — but treat the image
            itself as the secret it is.
          </li>
          <li>
            <strong>One-time open</strong> — opening consumes the drop, and the app then asks the
            relay to destroy it. There is no second read.
          </li>
          <li>
            <strong>Deadline burn</strong> — a drop unclaimed by its deadline is destroyed by the
            relay.
          </li>
          <li>
            <strong>Scanning</strong> — the scan icon at the top of the chat list. A drop not meant
            for your device shows an explanatory screen, not an error.
          </li>
        </ul>
        <Shot
          src="05-lemon-drop.png"
          alt="A sealed lemon drop shown as a QR code with its burn deadline"
          caption="A sealed drop. The QR is the whole capability — whoever holds this image can claim the message, once, before the deadline."
        />

        <h2 id="network">Network: I2P, Tor, and the clearnet warning</h2>
        <p>
          Message content is end-to-end encrypted regardless of transport. What the transport
          decides is who can see <em>that</em> you connected, and from where. Zitrone resolves its
          route in a fixed order — I2P, then Tor, then clearnet:
        </p>
        <ul>
          <li>
            <strong>I2P</strong> — <em>on by default</em> (Settings → Network →{" "}
            <em>Use I2P when available</em>), but it only takes effect when the official I2P app is
            installed; without it the setting is inert. Settings shows an install link, including
            F-Droid, when the app is missing.
          </li>
          <li>
            <strong>Tor</strong> — opt-in (Settings → Network → <em>Route through Tor</em>),
            requires Orbot. Slower, more private than clearnet.
          </li>
          <li>
            <strong>Clearnet fallback</strong> — when neither router is available, Zitrone connects
            directly, and the <strong>Connection</strong> row in Settings → Network says so plainly:
            clearnet exposes your IP address to the relay and to anyone watching the wire.
          </li>
        </ul>
        <Shot
          src="06-settings-network.png"
          alt="Zitrone network settings showing I2P, Tor and connection status"
          caption="Settings → Network. The Connection row states the live transport — and warns you when you've fallen back to clearnet."
        />
        <p>
          If network privacy matters to you, install the I2P app or Orbot and check the Connection
          row before saying anything you care about.
        </p>

        <h2 id="pucker-burn">Pucker Burn: the duress password</h2>
        <p>
          Pucker Burn is a <em>separate</em> password that erases instead of unlocking. Set it under{" "}
          <strong>Settings → Account → Pucker Burn password</strong>. From then on, typing it at the
          lock screen wipes everything Zitrone holds on the device — every vault, all preferences,
          keystore entries, caches — and the app closes. The next launch looks like a fresh install.
        </p>
        <ul>
          <li>
            <strong>There is no readback, anywhere, by design.</strong> The app cannot tell you
            whether a burn password is set — a readback would itself be the artifact proving a
            duress credential exists.
          </li>
          <li>
            <strong>Consequently, forgetting it is unrecoverable.</strong> The setup dialog says so;
            believe it.
          </li>
          <li>
            <strong>It wipes everything.</strong> Not one chat, not one vault — the entire device
            image. Anyone who learns the password can do the same, with no confirmation step.
          </li>
          <li>
            <strong>It is device-local.</strong> A burn erases this device. It does not delete your
            account on the relay — for that, use account deletion below.
          </li>
        </ul>

        <h2 id="second-vault">The second vault: plausible deniability</h2>
        <p>
          Zitrone can hold more than one vault on a device — fully independent identities, each with
          its own passphrase, contacts, and messages — built so that nothing on the device proves a
          second vault exists.{" "}
          <strong>There is no button, menu, wizard, or setting for this anywhere in the app</strong>
          , and there never will be: a dedicated flow would itself be the discoverable evidence that
          defeats the feature. This page is the only place the instructions exist. Read all of it,
          including the sharp edges — with no in-app warnings possible, this text is the only thing
          standing between you and a mistake that costs data.
        </p>

        <h3 id="second-vault-create">Creating one</h3>
        <p>
          At the normal lock screen, type the{" "}
          <strong>
            same never-before-used passphrase three times, consecutively and uninterrupted
          </strong>
          . The third identical entry creates the vault and unlocks straight into it — to anyone
          watching, it looks like you mistyped twice and got in on the third try. (One documented
          nuance: a creating third entry also writes to disk, which a plain unlock does not. It
          shares the ordinary unlock&apos;s screen and key-derivation cost, but Zitrone does not
          claim the two are indistinguishable to an adversary instrumenting the device at that
          moment.)
        </p>
        <ul>
          <li>
            A passphrase that matches an existing vault always just unlocks it — you cannot trigger
            creation with a passphrase you already use. Only a passphrase matching <em>no</em>{" "}
            existing vault can accumulate the three-count.
          </li>
          <li>
            <em>Uninterrupted</em> is enforced: backgrounding the app, auto-lock firing, a biometric
            unlock, or the process being killed all reset the count. The three entries must happen
            in one sitting at one lock screen.
          </li>
          <li>
            The new vault starts empty: its own identity, no contacts, no messages. It is not a
            window into your other vault — it is a second, separate Zitrone.
          </li>
        </ul>

        <h3 id="second-vault-open">Opening and switching</h3>
        <p>
          Opening is just unlocking: type that vault&apos;s passphrase at the lock screen. Which
          vault opens depends only on which passphrase you type — the app draws no distinction
          between them, ever.
        </p>
        <p>
          Switching has no control either, because a &quot;switch vault&quot; button would be the
          same tell. Switching is <em>lock, then unlock with the other passphrase</em>: background
          the app so auto-lock fires — remember, there is no in-app &quot;lock now&quot; button —
          then reopen and type the other passphrase. For practical switching, set{" "}
          <strong>Settings → Security → Auto-lock when backgrounded</strong> to <em>Immediate</em>,
          so backgrounding always locks. On every lock, the live vault&apos;s session is fully torn
          down before anything can be unlocked again.
        </p>

        <h3 id="second-vault-why">Why the instructions live here, in public</h3>
        <p>
          Publishing the ceremony does not weaken it. The capability was never the secret — every
          copy of Zitrone has it, and anyone can read this page or the source code. The secret is
          whether <em>this particular device</em> has a second vault, and nothing on the device
          answers that: unused vault slots are indistinguishable from used ones, the unlock does the
          same work whether a passphrase matches or not, and the app contains no reference to any of
          this. You read this website before, or away from, the device. The device — the thing an
          adversary might actually be holding — shows nothing.
        </p>

        <h3 id="second-vault-edges">Sharp edges — read these before you rely on it</h3>
        <ul>
          <li>
            <strong>The passphrase is unrecoverable, and nothing will warn you at creation.</strong>{" "}
            Deliberately: a &quot;you are creating a hidden vault&quot; dialog would out the
            feature. The moment the third entry lands, that passphrase is the only key that will
            ever open that vault.
          </li>
          <li>
            <strong>Creation can overwrite an existing vault.</strong> A new vault is blind-placed
            into a random slot, and the app cannot check whether the slot is in use — knowing would
            break the deniability. A creation can therefore destroy a vault whose passphrase
            isn&apos;t the one being typed. This is the same accepted risk class as writing to a
            VeraCrypt outer volume: keep every vault you care about backed up in your head, and its
            contents either expendable or exported.
          </li>
          <li>
            <strong>Biometric unlock binds to one vault only.</strong> First enable wins, and the
            binding is never repointed while it exists. Every other vault is passphrase-only.
          </li>
          <li>
            <strong>There is no way to destroy one vault alone.</strong> The only destruction that
            ships is whole-image: account deletion (or Pucker Burn) takes every vault with it.
          </li>
          <li>
            <strong>Deniability targets a single seizure, not ongoing forensic access.</strong> An
            adversary who images the device twice can diff the snapshots and see that a slot
            changed. One snapshot — the compelled-disclosure scenario — reveals nothing; two reveal
            activity. This is an accepted, documented limit.
          </li>
          <li>
            <strong>Don&apos;t keep only incriminating material in a second vault.</strong> A vault
            whose contents look like a decoy acts like one. The deniability comes from the
            vault&apos;s existence being unprovable, not from its contents being boring — use it
            like a real, ordinary vault.
          </li>
          <li>
            <strong>The ceremony&apos;s shape is public knowledge.</strong> A coercer who forces you
            to type one chosen wrong passphrase three times in a row creates a new, empty vault.
            (Trying many <em>different</em> wrong guesses never creates one — any change resets the
            count.)
          </li>
        </ul>

        <h2 id="deleting">Deleting things — and what deletion means</h2>
        <ul>
          <li>
            <strong>Delete a contact:</strong> long-press the conversation in the chat list, then
            confirm. This is <strong>immediate and permanently irreversible</strong> — it removes
            the conversation, its messages, and the contact&apos;s cryptographic records in one
            stroke. There is no undo, no trash, no grace period. Re-adding the contact later starts
            from zero.
          </li>
          <li>
            <strong>Delete your account:</strong> Settings → Account → <em>Delete account</em>.
            Purges every key, prekey, and pending message envelope from the relay, and unlinks this
            device. Irreversible.
          </li>
          <li>
            <strong>Pucker Burn</strong> (above) wipes the device but leaves the relay account
            registered — the two are different tools for different threats.
          </li>
        </ul>

        <h2 id="platforms">Where Zitrone runs today</h2>
        <p>Stated plainly, because a feature you can&apos;t download isn&apos;t a feature:</p>
        <ul>
          <li>
            <strong>Android</strong> — the reference implementation and the only shipped client.
            Everything on this page describes it. Distributed as a sideloaded APK via{" "}
            <Link href="/download/beta">the beta page</Link> and a Tor onion mirror — not in any app
            store yet.
          </li>
          <li>
            <strong>iOS, desktop, web</strong> — in development, behind Android, not available to
            download. We&apos;ll say so here when that changes, and not before.
          </li>
        </ul>
        <p>
          For the deeper technical treatment of everything above, see{" "}
          <Link href="/security">how Zitrone works</Link> and the{" "}
          <a href={SECURITY_MODEL_DOC}>security model documentation</a>. The source — all of it — is
          at <a href={GITHUB_URL}>github.com/jackofall1232/zitrone</a>.
        </p>
      </article>
    </main>
  );
}
