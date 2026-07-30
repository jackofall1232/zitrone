# Reconstructed screenshots

These are **code-reconstructed screenshots**, not device captures — and they cannot be device
captures. Zitrone sets `FLAG_SECURE` before any content exists, app-wide and unconditionally
(see `docs/FEATURES.md` §11), so screenshots and screen recording are blocked at the OS level
on every screen. That is a shipped security property, not an inconvenience to work around.

Instead, each image is a pixel-faithful HTML/CSS transcription of the real Jetpack Compose
screen code, rendered with headless Chrome:

- **Layout, spacing, radii, copy and iconography** are transcribed from
  `apps/android/app/src/main/java/com/zitrone/app/ui/screens/*.kt` and `ui/components/*.kt`.
- **Colors and typography** use the exact design tokens from `ui/theme/{Color,Type,Shape}.kt`
  (Lemon `#F5E642`, BackgroundPrimary `#0D0C00`, TextPrimary `#FAFAF2`, TextSecondary
  `#A8A070`, …) and the app's own bundled fonts (`res/font`: Space Grotesk, Inter,
  JetBrains Mono). Dark theme only — the app has no light mode.
- **The identity watermark** in the chat surfaces is a real re-implementation of
  `ui/components/FingerprintWatermark.kt` (treatment G2: 512·d px tile, density capped at 2,
  12.5·d px JetBrains Mono, lemon at alpha 0.07, −24° rotation, 28·d px row gap, brick offset
  0.5, toroidal nine-offset wrap), painted with a **fake** fingerprint. Message bubbles use the
  translucent fills (`0xEB` sent / `0xD9` received) so the paper bleeds through, as shipped.
- **Geometry:** rendered at a 432×768 dp viewport with `deviceScaleFactor 2.5` → 1080×1920 px,
  i.e. 1 CSS px = 1 dp on a 2.5x-density phone.

## The images

| File | Screen | Source of truth |
|---|---|---|
| `01-chat-list.png` | ChatListScreen: wordmark header, search pill, conversations with unread/verified states, lemon FAB | `ChatListScreen.kt`, `ConversationList.kt` |
| `02-chat.png` | ChatScreen: sent/received bubbles, honest delivery states (`…` `✓` `✓✓`, read in accent), encryption micro-badge, compose bar (burn, TTL, attach, droplet, lemon send), watermark lattice | `ChatScreen.kt`, `MessageBubble.kt`, `ComposeBar.kt`, `FingerprintWatermark.kt` |
| `03-lock-screen.png` | LockScreen: passphrase entry + biometric fallback. Deliberately shows *nothing else* — there is no second-vault UI by design (`FEATURES.md` §2: "there is no button for the second vault") | `LockScreen.kt` |
| `04-add-contact.png` | AddContactScreen: your contact QR (ContactExchangePayload) + scan button + the paste field ("Contact ID, invite link, or QR payload") | `AddContactScreen.kt`, `QrCode.kt` |
| `05-lemon-drop.png` | QrDropResultDialog over the chat: sealed drop QR with the lemon-slice mark, recipient-addressed honesty copy, drop link, burn deadline | `QrDropDialogs.kt` |
| `06-settings-network.png` | Settings → Network: I2P on by default (no router present), Tor off (Orbot missing, install paths), clearnet connection status with the IP-visibility warning | `SettingsScreen.kt` |
| `07-settings-privacy.png` | Settings → Security tail + Privacy: disappearing-timer default (Off), burn-on-read (off), read receipts (on), lemon-drop compose toggle | `SettingsScreen.kt` |
| `08-onboarding.png` | OnboardingScreen slide 1 ("End-to-end encrypted"), the animated lemon-slice layers visual as a static frame | `OnboardingScreen.kt` |

## Fake data — deliberately

Every name, message, timestamp, fingerprint, account ID, identity key and drop link in these
images is **fabricated sample data**. "Mika", "Ari S." etc. are not real people; the 60-hex
fingerprint, the UUID account ID, the base64 identity key and the
`https://zitrone.app/d/…` drop ID are format-correct fakes. Nothing here is a real credential
or capability, and no real conversation was harmed (or possible — see `FLAG_SECURE` above).

## Play Store spec

All eight images comply with the phone-screenshot requirements: PNG (24-bit, **no alpha
channel**), portrait 1080×1920 (9:16), minimum side ≥1080 px, each file well under 8 MB.
The same set serves the README, the website, and a store listing (2–8 images allowed).

## Regenerating

The HTML sources live in `src/` and are generated + rendered by two Node scripts:

```sh
# one-time deps (anywhere; NODE_PATH points at them)
npm install puppeteer qrcode sharp

cd docs/screenshots/src
NODE_PATH=/path/to/node_modules node build.js    # writes src/pages/*.html
NODE_PATH=/path/to/node_modules node render.js   # writes ../0X-*.png (1080×1920, no alpha)
```

Notes:
- `build.js` reads the app's bundled fonts from `apps/android/app/src/main/res/font/` by
  relative path and the Material icon SVGs from `src/assets/icons/`.
- `src/assets/NotoColorEmoji.ttf` (used for the 🔒/🔥 glyphs, as Android renders them) is
  downloaded at setup time and gitignored; fetch it from
  `https://github.com/googlefonts/noto-emoji/raw/main/fonts/NotoColorEmoji.ttf` if missing.
- If you change a screen in the app, change the transcription in `build.js` to match —
  **a screenshot that doesn't match the shipped UI is a false claim in picture form.**

## What is deliberately absent

Per `docs/FEATURES.md`, nothing here depicts: registration proof-of-work (reversed — does not
ship), any second-vault or hidden-vault UI (its absence *is* the feature), Pucker Burn readback
(none exists), multi-hop/STEALTH/GHOST modes, sealed sender, group chats, calls, light mode,
or any web/desktop/iOS client.
