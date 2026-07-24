# Zitrone — open TODOs (as of 2026-07-20, post-0.8.0 flip)

## 0.8.1-beta build-out (branch `feat/0.8.1-lemon-ui`; plan approved by HoboJoe 2026-07-20)
Two features: (1) lemon-drop reach/affordance — web droplet identity + coachmark +
save-QR-for-print, plus FULL Android creation (own PR, re-triggers crypto gate);
(2) always-on "security paper" fingerprint watermark (viewer's own 60-hex fingerprint,
ghost-faint, NO opt-out — HoboJoe decision) on web/desktop + Android (+veil) + iOS.
Key premise corrections vs the original brief are recorded in the session plan file.
- [x] **P0** visual mock `docs/design/watermark-tile-preview.html` (commit b1ab7bf) —
      HoboJoe signed off **G2 "faint"** 2026-07-20: `WATERMARK_TILE_DEFAULTS =`
      `{ tileSize: 512, rotationDeg: -24, alpha: 0.045, fontPx: 10.5, rowGapPx: 28,`
      `brickOffset: 0.5, color: "#F5E642", background: "#0D0C00" }`; bubbles
      sent α 0.92 / received α 0.85. These values ship verbatim in P2–P4.
- [x] **P1** DONE (a8327c1): droplet glyph + coachmark + save-QR-for-print
      (print PNG composer apps/web/src/lib/dropPrint.ts; Tauri `save_drop_image`
      command; no new JS deps). 161 tests green.
- [x] **P2** DONE (f4a0f69): fingerprintOf port (fixture-pinned), stego-preserving
      composed tile on ChatView+ChatList, fingerprintTile.ts (G2 defaults),
      translucent bubbles. 170 tests green.
- [x] **P3** DONE (aa122cc): Modifier.fingerprintWatermark (transparent-ground tile,
      ImageShader repeat), chat + chat list + BOTH veil variants (covered even when
      app-locked; ensureIdentity kept behind the app gate so a fresh-install scan
      can't mint keys). Translucent bubbles. Framestats check → HoboJoe device.
- [x] **P4** DONE (4b2f496): iOS watermark (512-pt tile re-wrapped at device scale
      for 1:1 pixels) + translucent bubbles. NO local verify possible — manual Xcode
      build + visual pass vs the rig = HoboJoe release-checklist item.
- [~] **P5** CODE-COMPLETE, NOT REVIEWED — branch `feat/0.8.1-android-drop-create`
      (local only, NOT pushed): 5a `abcc015` (sender_key_family + family-aware opens,
      lockstep parsers incl. explicit-null parity, Montgomery-sender web test) +
      5b `5b76dba` [PRE-REVIEW SNAPSHOT — implementing agent stopped at session close
      during its final report]. Build/test green both stacks (Android suite + TS 176,
      incl. Kotlin-fixture-opens-on-web cross-stack proof). STILL REQUIRED before PR:
      (1) architect review of LemonDropCreate/LemonDropCreator/QrDropDialogs (esp.
      secret-zeroing inventory, HKDF/AAD label pairs vs LemonDropOneShot, PoW preimage,
      AndroidManifest/file_paths.xml addition — share-sheet FileProvider, check scope);
      (2) live e2e vs local server+PG16 (deposit→fetch→open→burn; rig containers
      zitrone-test-pg + zitrone-server were up); (3) push + PR + crypto gate
      (/gemini review + @codex review, expect convergence rounds like PR #4).
      If gate stalls → slips to 0.8.2; 0.8.1 ships from PR #8 alone.
- [x] **RELEASE CUT 2026-07-21**: PR #8 (UI track) + PR #9 (round-2 fixes+docs+versions)
      both MERGED to main @ `c78a606`. GitHub release **v0.8.1-beta** LIVE (tag @c78a606,
      prerelease, signed cert `6c7f92a7…`, APK sha `322fea9b72127a37369473eddf62038d2913a3545ea805b8572ba7476251cd30`,
      byte-verified against live download; onion-site/SHA256SUMS uploaded as asset too).
      Website flip = **PR #10** (`release/flip-website-081`): links.ts v0.8.1-beta + sha,
      onion SHA256SUMS — OPEN, waiting on CI then merge (Vercel redeploys /download/beta).
- [ ] **P6** docs close-out (DONE in PR #9 — SECURITY_MODEL watermark subsection
      (deterrence-not-guarantee, always-on rationale, composed-with-stego note,
      save-for-print honest cost) + lemon-drop additions (Android creation,
      sender_key_family, ≥0.8.1-recipient limit); CHANGELOG; versions → 0.8.1-beta/vc10
      (NOT links.ts / SHA256SUMS — GH-release-cut items).
- [ ] **PR #8** (UI track, pushed @ 625d995): round-1 bot findings all fixed
      (native-owned Tauri save path, data:-URL mark vs CSP, fingerprint wipe-teardown,
      loop guards, iOS ConversationList occlusion); re-review requested from both bots —
      CHECK FOR ROUND-2 COMMENTS, then HoboJoe merge decision. Manual items on merge
      checklist: iOS Xcode build + visual pass vs docs/design/watermark-tile-preview.html,
      Android framestats scroll check, print-a-sticker scan test.

Ground truth: `origin/main` = `b6abd23`. Version **0.8.0-beta** everywhere (Android
vc9). PR #4 (Android lemon-drop bridge) + PR #5 (version bump) both merged. Apex
domain flipped; App Links verify passes (Google DAL `linked:true`). Crypto-review
gate on the bridge was satisfied over 4 rounds. See `ledger.md` for the full record.

## Release-ops to finish the 0.8.0 ship (HoboJoe — classifier-blocked for the agent)
- [x] GitHub release v0.8.0-beta CUT (signed on-box, cert 6C:7F:92:A7…, apk sha aa645e2c…). DONE 2026-07-20.
- [x] Website download flip DONE (PR #7 `19c0b29`): links.ts → v0.8.0-beta + real sha; onion-site/SHA256SUMS staged.
- [ ] **CX23 onion mirror**: swap in the 0.8.0-beta APK + relay redeploy (still no SSH
      from CX33 — needs HoboJoe's path to CX23).
- [ ] **On-device scan test**: web-create a drop → Android scan → biometric
      unlock → message renders → auto-burn → re-scan shows advocacy/unavailable.

## Decisions to confirm
- [ ] Version string is **`0.8.0-beta`** (CONFIRMED with HoboJoe — keeps the `-beta` suffix all prior
      releases carried; corrected from PR #5's literal `0.8.0` via PR #6). Resolved —
      no action needed (repo AUDIT.md flags crypto as
      unaudited).

## Known limitations shipped in 0.8.0-beta (documented, not bugs)
- iOS cannot yet be a lemon-drop recipient — wire-indistinguishable from Android,
  no platform tag; a drop to an iOS contact expires unopened (no leak). A
  capability-signal to refuse iOS up front is deferred (needs a protocol field).
- No-OTP drops: read-once rests on best-effort burn + TTL, not crypto (protocol
  property shared with web).

## Older, still-open
- [ ] Consider SSH-key rotation (Grok had box access) — long-standing.
