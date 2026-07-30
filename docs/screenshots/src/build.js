#!/usr/bin/env node
/*
 * Zitrone — reconstructed-screenshot page generator.
 *
 * Screen capture is blocked in the app (FLAG_SECURE, unconditional), so the
 * screenshots in docs/screenshots/ are pixel-faithful HTML/CSS transcriptions
 * of the real Jetpack Compose screens, filled with FAKE sample data and
 * rendered with headless Chrome (see render.js).
 *
 * Every color, dimension, radius, string and font below is transcribed from:
 *   apps/android/app/src/main/java/com/zitrone/app/ui/theme/{Color,Type,Shape}.kt
 *   apps/android/app/src/main/java/com/zitrone/app/ui/screens/*.kt
 *   apps/android/app/src/main/java/com/zitrone/app/ui/components/*.kt
 * The fonts are the app's own bundled TTFs (res/font). 1 CSS px = 1 dp; the
 * renderer uses a 432x768 viewport at deviceScaleFactor 2.5 => 1080x1920.
 *
 * Usage: NODE_PATH=<dir with puppeteer/qrcode/sharp> node build.js && node render.js
 */
'use strict';

const fs = require('fs');
const path = require('path');
const QRCode = require('qrcode');

const OUT = path.join(__dirname, 'pages');
fs.mkdirSync(OUT, { recursive: true });

// ---------------------------------------------------------------------------
// Design tokens — Color.kt / Type.kt / Shape.kt (exact values)
// ---------------------------------------------------------------------------
const C = {
  lemon: '#F5E642',
  lemonBright: '#FFE500',
  lemonDeep: '#D4C200',
  lemonPale: '#FFFDE0',
  lemonZest: '#E8B800',
  rind: '#2A2500',
  bgPrimary: '#0D0C00',
  bgSecondary: '#1A1800',
  bgElevated: '#242100',
  sentTranslucent: 'rgba(245,230,66,0.922)',   // 0xEBF5E642
  recvTranslucent: 'rgba(36,33,0,0.851)',      // 0xD9242100
  textPrimary: '#FAFAF2',
  textSecondary: '#A8A070',
  textOnLemon: '#0D0C00',
  textMuted: '#5A5630',
  border: '#2E2B00',
  burnRed: '#FF4444',
  burnOrange: '#FF8C00',
  verifiedGreen: '#4ADE80',
};

// ---------------------------------------------------------------------------
// FAKE sample data — no real people, keys, accounts or drops.
// ---------------------------------------------------------------------------
// 60 hex chars => SafetyNumber.formatFingerprint output: 15 groups of 4.
const FINGERPRINT =
  '3F7A 91C4 0B6E 82D5 F1A9 4C07 D3E8 5B26 A470 9E1D C58B 306F E2D4 7A19 B8C3';
const ACCOUNT_ID = 'a3f1c2e4-7b96-4d28-9c05-e1b8a4d7f632';
const IDENTITY_KEY_B64 = 'BS1mYXrLk2p9QdC7eTz4NwJh8gRuV5oAiK3EbP0fDxs='; // fake
// buildContactExchangePayload(): {"version":"1","account_id":...,"identity_key":...}
const CONTACT_PAYLOAD = JSON.stringify({
  version: '1',
  account_id: ACCOUNT_ID,
  identity_key: IDENTITY_KEY_B64,
});
// Lemon-drop sticker URL: https://zitrone.app/d/{22 base64url chars} (QrDropLink.kt)
const DROP_URL = 'https://zitrone.app/d/kT9mQx2LwZ7uYc4RfB8n_A';
// QrDropSticker.burnsByLabel => DateFormat.getDateTimeInstance() default locale.
const DROP_BURNS_BY = 'Aug 1, 2026, 6:00:14 PM';

const CONVERSATIONS = [
  { name: 'Mika', verified: true, time: '14:32', unread: 2 },
  { name: 'Ari S.', verified: false, time: '12:07', unread: 0 },
  { name: 'Jonas', verified: true, time: '09:41', unread: 0 },
  { name: 'Priya', verified: false, time: '08:15', unread: 1 },
  { name: 'Theo', verified: false, time: '07:58', unread: 0 },
  { name: 'Lena', verified: false, time: '07:12', unread: 0 },
];

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
const esc = (s) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

/** Inline a Material icon SVG (assets/icons/*.svg) at a size and color. */
function icon(name, size, color) {
  let svg = fs.readFileSync(path.join(__dirname, 'assets', 'icons', `${name}.svg`), 'utf8');
  svg = svg.replace(
    '<svg ',
    `<svg style="width:${size}px;height:${size}px;display:block" fill="${color}" `,
  );
  return svg.replace(/height="24"/, '').replace(/width="24"/, '');
}

/**
 * The lemon slice (LemonSlice.kt drawLemonSlice + LemonSliceMath):
 * 8 annular wedges, 7deg gaps, start -90deg, rind ring 10% stroke, centre pip.
 */
function lemonSlice(size, opts = {}) {
  const {
    filled = 8,
    seg = C.lemon,
    empty = C.border,
    rind = C.lemonDeep,
    pip = null,
  } = opts;
  const S = size;
  const cx = S / 2, cy = S / 2;
  const R = S / 2;
  const rindStroke = Math.max(R * 0.10, 1);
  const wedgeOuter = R - rindStroke * 1.9;
  const wedgeInner = R * 0.18;
  const sweep = 360 / 8 - 7; // 38deg
  const pol = (r, deg) => {
    const a = (deg * Math.PI) / 180;
    return [cx + r * Math.cos(a), cy + r * Math.sin(a)];
  };
  let wedges = '';
  for (let i = 0; i < 8; i++) {
    const start = -90 + i * 45 + 3.5;
    const end = start + sweep;
    const [x1, y1] = pol(wedgeOuter, start);
    const [x2, y2] = pol(wedgeOuter, end);
    const [x3, y3] = pol(wedgeInner, end);
    const [x4, y4] = pol(wedgeInner, start);
    const color = i < filled ? seg : empty;
    wedges += `<path d="M${x1.toFixed(2)} ${y1.toFixed(2)} A${wedgeOuter.toFixed(2)} ${wedgeOuter.toFixed(2)} 0 0 1 ${x2.toFixed(2)} ${y2.toFixed(2)} L${x3.toFixed(2)} ${y3.toFixed(2)} A${wedgeInner.toFixed(2)} ${wedgeInner.toFixed(2)} 0 0 0 ${x4.toFixed(2)} ${y4.toFixed(2)} Z" fill="${color}"/>`;
  }
  const pipR = Math.max(wedgeInner * 0.45, 0.5);
  return `<svg width="${S}" height="${S}" viewBox="0 0 ${S} ${S}" style="display:block">
<circle cx="${cx}" cy="${cy}" r="${(R - rindStroke / 2).toFixed(2)}" fill="none" stroke="${rind}" stroke-width="${rindStroke.toFixed(2)}"/>
${wedges}<circle cx="${cx}" cy="${cy}" r="${pipR.toFixed(2)}" fill="${pip || seg}"/></svg>`;
}

/** QR as SVG rects (zxing-style: quiet zone 1 module, like MARGIN=1). */
function qrSvg(text, ecLevel, px, dark, light) {
  const q = QRCode.create(text, { errorCorrectionLevel: ecLevel });
  const n = q.modules.size;
  const quiet = 1;
  const total = n + quiet * 2;
  const cell = px / total;
  let rects = '';
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      if (q.modules.get(x, y)) {
        rects += `<rect x="${((x + quiet) * cell).toFixed(2)}" y="${((y + quiet) * cell).toFixed(2)}" width="${(cell + 0.35).toFixed(2)}" height="${(cell + 0.35).toFixed(2)}" fill="${dark}"/>`;
      }
    }
  }
  const bg = light ? `<rect width="${px}" height="${px}" fill="${light}"/>` : '';
  return `<svg width="${px}" height="${px}" viewBox="0 0 ${px} ${px}" shape-rendering="crispEdges" style="display:block">${bg}${rects}</svg>`;
}

/** The lemon-drop droplet outline (ComposeBar.kt DROPLET_PATH, 24x24 viewBox). */
function droplet(size, color) {
  return `<svg width="${size}" height="${size}" viewBox="0 0 24 24" style="display:block"><path d="M12 3C8.2 8 5.8 11.4 5.8 14.6a6.2 6.2 0 0 0 12.4 0C18.2 11.4 15.8 8 12 3z" fill="none" stroke="${color}" stroke-width="1.8" stroke-linejoin="round"/></svg>`;
}

// Fonts: the app's own bundled TTFs, referenced relative to pages/.
const FONT_DIR = '../../../../apps/android/app/src/main/res/font';
const BASE_CSS = `
@font-face{font-family:'Space Grotesk';src:url('${FONT_DIR}/space_grotesk_medium.ttf');font-weight:500}
@font-face{font-family:'Space Grotesk';src:url('${FONT_DIR}/space_grotesk_semibold.ttf');font-weight:600}
@font-face{font-family:'Space Grotesk';src:url('${FONT_DIR}/space_grotesk_bold.ttf');font-weight:700}
@font-face{font-family:'Inter';src:url('${FONT_DIR}/inter_regular.ttf');font-weight:400}
@font-face{font-family:'Inter';src:url('${FONT_DIR}/inter_medium.ttf');font-weight:500}
@font-face{font-family:'JetBrains Mono';src:url('${FONT_DIR}/jetbrains_mono_regular.ttf');font-weight:400}
@font-face{font-family:'Noto Color Emoji';src:url('../assets/NotoColorEmoji.ttf')}
*{margin:0;padding:0;box-sizing:border-box;-webkit-font-smoothing:antialiased;text-rendering:optimizeLegibility}
html,body{width:432px;height:768px;overflow:hidden}
body{background:${C.bgPrimary};font-family:Inter,'Noto Color Emoji',sans-serif;color:${C.textPrimary};position:relative}
.screen{position:relative;width:432px;height:768px;overflow:hidden;background:${C.bgPrimary}}
.wm{position:absolute;inset:0;pointer-events:none}
.row{display:flex;flex-direction:row}
.col{display:flex;flex-direction:column}
.center{align-items:center;justify-content:center}
.icon-btn{width:48px;height:48px;display:flex;align-items:center;justify-content:center;flex:none}
.display{font-family:'Space Grotesk',Inter,sans-serif;letter-spacing:-0.03em}
.mono{font-family:'JetBrains Mono',monospace}
`;

// The security-paper watermark (FingerprintWatermark.kt, treatment G2):
// tile 512*d px (d capped at 2), font 12.5*d px JetBrains Mono, lemon at
// alpha 0.07, rows every 28*d px, rotated -24deg, alternate rows brick-shifted
// by pitch/2, pitch = runWidth + fontPx*4, toroidal nine-offset wrap.
// The renderer runs at deviceScaleFactor 2.5, so the 1024px (physical) tile is
// 1024/2.5 CSS px — identical physical geometry to a >=2x-density device.
const WATERMARK_JS = `
async function paintWatermark(fp){
  const el=document.getElementById('wm'); if(!el){window.__wmDone=true;return;}
  await document.fonts.ready;
  const d=2;                       // density, coerceAtMost(2f)
  const s=Math.round(512*d);       // tile edge, physical px
  const c=document.createElement('canvas'); c.width=s; c.height=s;
  const ctx=c.getContext('2d');
  ctx.font=(12.5*d)+'px "JetBrains Mono", monospace';
  ctx.fillStyle='#F5E642';
  ctx.globalAlpha=0.07;
  ctx.textAlign='left'; ctx.textBaseline='middle';
  const runWidth=ctx.measureText(fp).width;
  const pitch=runWidth+12.5*d*4;
  const rowGap=28*d;
  const theta=-24*Math.PI/180, cos=Math.cos(theta), sin=Math.sin(theta);
  const span=s*1.6, half=s/2;
  const anchors=[];
  let eta=-span,row=0;
  while(eta<span){
    const off=(row%2===1)?pitch*0.5:0;
    let xi=-span+off;
    while(xi<span){
      const ax=half+xi*cos-eta*sin, ay=half+xi*sin+eta*cos;
      if(ax>=0&&ax<s&&ay>=0&&ay<s) anchors.push([ax,ay]);
      xi+=pitch;
    }
    eta+=rowGap; row++;
  }
  for(const [ax,ay] of anchors){
    for(let dx=-1;dx<=1;dx++)for(let dy=-1;dy<=1;dy++){
      ctx.save(); ctx.translate(ax+dx*s,ay+dy*s); ctx.rotate(theta);
      ctx.fillText(fp,0,0); ctx.restore();
    }
  }
  el.style.backgroundImage='url('+c.toDataURL()+')';
  const cssTile=(512*d)/2.5;       // physical px -> CSS px at dsf 2.5
  el.style.backgroundSize=cssTile+'px '+cssTile+'px';
  window.__wmDone=true;
}`;

function page(title, bodyHtml, { watermark = false, extraCss = '' } = {}) {
  const wmScript = watermark
    ? `<script>${WATERMARK_JS}\npaintWatermark(${JSON.stringify(FINGERPRINT)});</script>`
    : `<script>window.__wmDone=true;</script>`;
  return `<!doctype html>
<html><head><meta charset="utf-8"><title>${esc(title)}</title>
<style>${BASE_CSS}${extraCss}</style></head>
<body>${bodyHtml}
${wmScript}
</body></html>`;
}

// ---------------------------------------------------------------------------
// Shared components
// ---------------------------------------------------------------------------

/** LemonAvatar (ConversationList.kt): 44dp lemon->zest gradient circle; verified = 2dp green ring + 3dp pad. */
function avatar(name, verified) {
  const initial = esc(name[0].toUpperCase());
  const inner = `<div style="width:100%;height:100%;border-radius:50%;background:linear-gradient(135deg,${C.lemon},${C.lemonZest});display:flex;align-items:center;justify-content:center"><span style="font-size:16px;font-weight:500;color:${C.textOnLemon}">${initial}</span></div>`;
  if (verified) {
    return `<div style="width:44px;height:44px;flex:none;border:2px solid ${C.verifiedGreen};border-radius:50%;padding:3px">${inner}</div>`;
  }
  return `<div style="width:44px;height:44px;flex:none;border-radius:50%">${inner}</div>`;
}

/** M3 Switch with SettingsScreen's SwitchDefaults colors. */
function m3switch(checked, enabled = true) {
  const dim = enabled ? '' : 'opacity:.38;';
  if (checked) {
    return `<div style="${dim}width:52px;height:32px;flex:none;border-radius:16px;background:${C.lemon};position:relative"><div style="position:absolute;top:4px;left:24px;width:24px;height:24px;border-radius:50%;background:${C.textOnLemon}"></div></div>`;
  }
  return `<div style="${dim}width:52px;height:32px;flex:none;border-radius:16px;background:${C.rind};border:2px solid ${C.border};position:relative"><div style="position:absolute;top:6px;left:6px;width:16px;height:16px;border-radius:50%;background:${C.textSecondary}"></div></div>`;
}

// ===========================================================================
// 01 — ChatListScreen
// ===========================================================================
function chatListBody({ forOverlay = false } = {}) {
  const rows = CONVERSATIONS.map((c) => {
    const unread = c.unread > 0
      ? `<div style="background:${C.lemon};border-radius:999px;padding:2px 8px"><span style="font-size:12px;font-weight:500;color:${C.textOnLemon}">${c.unread}</span></div>`
      : '';
    const time = `<span class="mono" style="font-size:12px;color:${C.textMuted}">${c.time}</span>`;
    return `<div class="row" style="padding:10px 16px;align-items:center;gap:12px">
      ${avatar(c.name, c.verified)}
      <div class="col" style="flex:1;min-width:0">
        <span style="font-size:16px;font-weight:500;color:${C.textPrimary};white-space:nowrap">${esc(c.name)}</span>
        <span style="font-size:14px;color:${C.textMuted};white-space:nowrap">Encrypted message</span>
      </div>
      <div class="col" style="align-items:flex-end;gap:4px">${time}${unread}</div>
    </div>`;
  }).join('\n');

  return `<div class="screen">
  <div id="wm" class="wm"></div>
  <div class="col" style="position:relative;height:100%">
    <div class="row" style="padding:12px 16px;align-items:center">
      <span class="display" style="flex:1;font-size:20px;font-weight:600;letter-spacing:0.18em;color:${C.lemon}">ZITRONE</span>
      <div class="icon-btn">${icon('qr_code_scanner', 24, C.lemon)}</div>
      <div class="icon-btn">${icon('settings', 24, C.lemon)}</div>
    </div>
    <div class="row" style="margin:0 16px;min-height:40px;background:${C.bgElevated};border:1px solid ${C.border};border-radius:999px;padding:8px 14px;align-items:center">
      ${icon('search', 18, C.textSecondary)}
      <span style="font-size:15px;color:${C.textMuted};padding-left:8px">Search</span>
    </div>
    <div class="col" style="flex:1;padding-top:8px">${rows}</div>
  </div>
  <div style="position:absolute;right:24px;bottom:24px;width:56px;height:56px;border-radius:50%;background:${C.lemon};display:flex;align-items:center;justify-content:center;box-shadow:0 0 16px rgba(245,230,66,.35),0 6px 14px rgba(245,230,66,.28)">
    ${icon('add', 24, C.textOnLemon)}
  </div>
</div>`;
}

// ===========================================================================
// 02 — ChatScreen (also the backdrop of 05)
// ===========================================================================
function bubble({ mine, text, time, tick, tickRead = false, tickDim = true, flame = false, ttlTimer = false }) {
  const bg = mine
    ? `background:${C.sentTranslucent};border-radius:18px 18px 4px 18px`
    : `background:${C.recvTranslucent};border:1px solid ${C.border};border-radius:18px 18px 18px 4px`;
  const textColor = mine ? C.textOnLemon : C.textPrimary;
  const timeColor = mine ? 'rgba(13,12,0,.6)' : C.textMuted;
  let meta = `<span class="mono" style="font-size:12px;color:${timeColor}">${time}</span>`;
  if (ttlTimer) {
    meta += lemonSlice(14, { filled: 8, seg: C.lemon, rind: 'rgba(245,230,66,.5)' });
  }
  if (flame) {
    meta += `<span style="display:flex">${icon('local_fire_department', 13, C.burnOrange)}</span>`;
  }
  if (mine && tick) {
    const tickColor = tickRead ? C.textOnLemon : (tickDim ? 'rgba(13,12,0,.45)' : C.textOnLemon);
    meta += `<span class="mono" style="font-size:12px;color:${tickColor}">${tick}</span>`;
  }
  return `<div class="row" style="justify-content:${mine ? 'flex-end' : 'flex-start'}">
    <div style="max-width:72%;padding:10px 14px;${bg}">
      <div style="font-size:15px;line-height:1.35;color:${textColor}">${esc(text)}</div>
      <div class="row" style="justify-content:flex-end;align-items:center;gap:4px;padding-top:4px">${meta}</div>
    </div>
  </div>`;
}

function chatBody({ draft, dropDialog = false }) {
  const hasDraft = draft.length > 0;
  const messages = [
    `<div style="padding:8px 0;text-align:center"><span class="mono" style="font-size:12px;color:${C.textMuted}">30 Jul 2026</span></div>`,
    bubble({ mine: false, text: 'Did the shipment clear?', time: '16:58' }),
    bubble({ mine: true, text: "Cleared this morning. Paperwork's in the vault.", time: '17:00', tick: '✓✓', tickRead: true }),
    bubble({ mine: false, text: 'Perfect. Market at six?', time: '17:01' }),
    bubble({ mine: true, text: "Works — I'll bring the crates.", time: '17:02', tick: '✓✓', ttlTimer: true }),
    bubble({ mine: true, text: 'Gate code is 4471.', time: '17:03', tick: '✓', flame: true }),
    bubble({ mine: true, text: 'Heading out now.', time: '17:05', tick: '…' }),
  ].join('\n');

  // ComposeBar (ComposeBar.kt): [flame][timer][pill: attach|field][droplet][send]
  const pillBorder = hasDraft ? C.lemon : C.border; // BorderActive on focus
  const field = hasDraft
    ? `<span style="font-size:15px;color:${C.textPrimary}">${esc(draft)}</span><span style="display:inline-block;width:1.5px;height:17px;background:${C.lemon};vertical-align:-3px;margin-left:1px"></span>`
    : `<span style="font-size:15px;color:${C.textMuted}">Message</span>`;
  const dropletColor = hasDraft ? C.lemon : C.textSecondary;
  const sendBg = hasDraft ? C.lemon : C.border;
  const sendMark = hasDraft ? C.textOnLemon : C.bgPrimary;
  const composeBar = `
  <div style="border-top:1px solid ${C.border}"></div>
  <div class="row" style="background:${C.bgSecondary};padding:8px;gap:4px;align-items:flex-end">
    <div class="icon-btn">${icon('local_fire_department', 24, C.textSecondary)}</div>
    <div class="icon-btn"><div class="col" style="align-items:center">${icon('timer', 24, C.lemon)}<span class="mono" style="font-size:12px;color:${C.lemon}">1h</span></div></div>
    <div class="row" style="flex:1;min-height:48px;background:${C.bgElevated};border:1px solid ${pillBorder};border-radius:24px;padding:2px 12px 2px 2px;align-items:center">
      <div class="icon-btn">${icon('attach_file', 24, C.textSecondary)}</div>
      <div style="flex:1;padding:10px 0">${field}</div>
    </div>
    <div class="icon-btn">${droplet(20, dropletColor)}</div>
    <div style="width:40px;height:40px;flex:none;margin:4px 0;border-radius:50%;background:${sendBg};display:flex;align-items:center;justify-content:center;box-shadow:0 0 10px rgba(245,230,66,${hasDraft ? '.30' : '0'})">
      ${lemonSlice(22, { filled: 8, seg: sendMark, rind: sendMark, pip: sendMark })}
    </div>
  </div>`;

  const dialog = dropDialog ? dropDialogHtml() : '';

  return `<div class="screen">
  <div id="wm" class="wm"></div>
  <div class="col" style="position:relative;height:100%">
    <div class="row" style="background:${C.bgSecondary};padding:6px 4px;align-items:center">
      <div class="icon-btn">${icon('arrow_back', 24, C.lemon)}</div>
      ${avatar('Mika', true)}
      <div class="col" style="flex:1;padding:0 10px;min-width:0">
        <span style="font-size:16px;font-weight:500;color:${C.textPrimary}">Mika</span>
        <span class="mono" style="font-size:12px;color:${C.textMuted}">Encrypted · tap name to edit</span>
      </div>
      <div class="row" style="background:${C.bgElevated};border-radius:999px;padding:5px 10px;align-items:center;gap:6px;flex:none">
        ${lemonSlice(14, { filled: 8, seg: C.verifiedGreen, rind: 'rgba(74,222,128,.6)' })}
        <span style="font-size:12px;font-weight:500;color:${C.verifiedGreen};white-space:nowrap">Keys verified</span>
      </div>
      <div class="icon-btn">${icon('local_fire_department', 24, C.burnOrange)}</div>
    </div>
    <div style="padding:6px 0;text-align:center">
      <span style="font-size:12px;font-weight:500;color:rgba(245,230,66,.55)">🔒 End-to-end encrypted</span>
    </div>
    <div class="col" style="flex:1;padding:8px 12px;gap:6px;justify-content:flex-end;overflow:hidden">
      ${messages}
    </div>
    ${composeBar}
  </div>
  ${dialog}
</div>`;
}

// ===========================================================================
// 05 — QrDropResultDialog (QrDropDialogs.kt) over the chat
// ===========================================================================
function dropDialogHtml() {
  const qr = qrSvg(DROP_URL, 'H', 240, C.bgPrimary, '#FFFFFF'); // INK on PAPER, EC H
  const backing = 240 * 0.26; // white backing ~26%
  const mark = 240 * 0.2;     // lemon-slice mark ~20%
  const pill = (label) =>
    `<div style="border-radius:999px;background:${C.lemon};padding:0 4px"><div style="height:40px;display:flex;align-items:center;padding:0 12px"><span style="font-size:14px;font-weight:500;color:${C.textOnLemon};white-space:nowrap">${label}</span></div></div>`;
  return `
  <div style="position:absolute;inset:0;background:rgba(0,0,0,.6);display:flex;align-items:center;justify-content:center">
    <div class="col" style="width:384px;border-radius:16px;border:1px solid ${C.border};background:${C.bgSecondary};padding:24px;gap:16px">
      <span class="display" style="font-size:20px;font-weight:600;color:${C.textPrimary}">QR drop sealed</span>
      <div style="display:flex;justify-content:center">
        <div style="position:relative;border-radius:12px;background:#FFFFFF;padding:12px;display:flex;align-items:center;justify-content:center">
          ${qr}
          <div style="position:absolute;width:${backing}px;height:${backing}px;border-radius:8px;background:#FFFFFF;display:flex;align-items:center;justify-content:center">
            ${lemonSlice(mark, { filled: 8 })}
          </div>
        </div>
      </div>
      <span style="font-size:15px;line-height:1.4;color:${C.textSecondary}">Anyone who scans this can fetch the sealed blob from the relay, but only Mika's device holds the key to open it. This drop is addressed to them by name — it is not anonymous.</span>
      <div class="mono" style="font-size:12px;color:${C.lemon};border:1px solid ${C.border};border-radius:8px;padding:10px;word-break:break-all">${DROP_URL}</div>
      <span style="font-size:12px;font-weight:500;color:${C.textMuted}">🔥 Burns by ${DROP_BURNS_BY} if unclaimed.</span>
      <div class="row" style="justify-content:flex-end;align-items:center;gap:8px">
        ${pill('Copy link')}${pill('Save image')}${pill('Share')}
        <div style="height:40px;display:flex;align-items:center;padding:0 12px"><span style="font-size:14px;font-weight:500;color:${C.textSecondary}">Done</span></div>
      </div>
      <span style="font-size:12px;font-weight:500;color:${C.textMuted};line-height:1.4">The saved image contains the drop link — treat it like the printed sticker itself.</span>
    </div>
  </div>`;
}

// ===========================================================================
// 03 — LockScreen
// ===========================================================================
function lockBody() {
  return `<div class="screen">
  <div class="col" style="height:100%;padding:0 32px;align-items:center;justify-content:center">
    ${lemonSlice(96)}
    <span class="display" style="font-size:24px;font-weight:600;color:${C.lemon};margin-top:24px">Locked</span>
    <span style="font-size:15px;color:${C.textSecondary};text-align:center;margin-top:8px;margin-bottom:24px">Your keys stay sealed until you unlock.</span>
    <div style="position:relative;width:100%;height:56px;border:2px solid ${C.lemon};border-radius:6px;display:flex;align-items:center;padding:0 16px">
      <span style="position:absolute;top:-8px;left:12px;background:${C.bgPrimary};padding:0 4px;font-size:12px;color:${C.lemon}">Passphrase</span>
      <span style="font-size:16px;letter-spacing:3px;color:${C.textPrimary}">••••••••••••</span><span style="display:inline-block;width:1.5px;height:18px;background:${C.lemon};margin-left:2px"></span>
    </div>
    <div style="margin-top:16px;height:40px;border-radius:20px;background:${C.lemon};display:flex;align-items:center;padding:0 24px">
      <span style="font-size:14px;font-weight:500;color:${C.textOnLemon}">Unlock</span>
    </div>
    <div style="margin-top:12px;height:40px;border-radius:20px;border:1px solid ${C.border};display:flex;align-items:center;padding:0 24px">
      <span style="font-size:14px;font-weight:500;color:${C.lemon}">Use biometrics</span>
    </div>
  </div>
</div>`;
}

// ===========================================================================
// 04 — AddContactScreen
// ===========================================================================
function addContactBody() {
  const sectionLabel = (t) =>
    `<div class="mono" style="font-size:12px;color:${C.textMuted};padding:24px 16px 8px 16px;text-transform:uppercase">${t}</div>`;
  const fieldBox = (placeholder) =>
    `<div style="width:100%;background:${C.bgElevated};border:1px solid ${C.border};border-radius:12px;padding:10px 12px"><span style="font-size:14px;color:${C.textMuted}">${esc(placeholder)}</span></div>`;
  // QrCode.kt: 220dp total, 2dp lemon border r16, 6dp pad, LemonPale r12, 10dp pad, dark modules.
  const qrInner = 220 - 2 * (2 + 6 + 10);
  const qr = qrSvg(CONTACT_PAYLOAD, 'L', qrInner, C.bgPrimary, null);
  return `<div class="screen">
  <div class="col" style="height:100%">
    <div class="row" style="padding:6px 4px;align-items:center">
      <div class="icon-btn">${icon('arrow_back', 24, C.lemon)}</div>
      <span class="display" style="font-size:20px;font-weight:600;color:${C.textPrimary}">Add contact</span>
    </div>
    ${sectionLabel('Your code')}
    <div class="col" style="padding:0 16px;align-items:center">
      <div style="border:2px solid ${C.lemon};border-radius:16px;padding:6px">
        <div style="background:${C.lemonPale};border-radius:12px;padding:10px">${qr}</div>
      </div>
      <span style="font-size:14px;color:${C.textSecondary};margin-top:12px;line-height:1.4">Have them scan this — or send them the code. It contains only your routing ID and public key.</span>
      <div style="margin-top:12px;height:40px;border-radius:20px;border:1px solid ${C.border};display:flex;align-items:center;padding:0 24px">
        <span style="font-size:14px;font-weight:500;color:${C.lemon}">Copy contact code</span>
      </div>
    </div>
    ${sectionLabel('Add someone')}
    <div class="col" style="padding:0 16px;gap:12px">
      <div style="width:100%;height:40px;border-radius:20px;background:${C.lemon};display:flex;align-items:center;justify-content:center">
        <span style="display:flex;padding-right:8px">${icon('qr_code_scanner', 24, C.textOnLemon)}</span>
        <span style="font-size:14px;font-weight:500;color:${C.textOnLemon}">Scan their code</span>
      </div>
      <span style="font-size:14px;color:${C.textMuted}">or paste their code / invite link</span>
      ${fieldBox('Contact ID, invite link, or QR payload')}
      ${fieldBox('Name (only stored on this device)')}
      <div style="width:100%;height:40px;border-radius:20px;background:rgba(250,250,242,.12);display:flex;align-items:center;justify-content:center">
        <span style="font-size:14px;font-weight:500;color:rgba(250,250,242,.38)">Add</span>
      </div>
    </div>
  </div>
</div>`;
}

// ===========================================================================
// 06 / 07 — SettingsScreen (full list; render.js scrolls per shot)
// ===========================================================================
function settingsBody() {
  const sectionHeader = (t, id) =>
    `<div id="${id}" class="mono" style="font-size:12px;color:${C.textMuted};padding:24px 16px 8px 16px;text-transform:uppercase;background:${C.bgPrimary}">${t}</div>`;
  const toggleRow = (title, subtitle, checked, { enabled = true } = {}) =>
    `<div class="row" style="background:${C.bgElevated};padding:12px 16px;align-items:center;gap:12px">
      <div class="col" style="flex:1">
        <span style="font-size:14px;font-weight:500;color:${C.textPrimary}">${title}</span>
        <span style="font-size:14px;color:${C.textMuted};line-height:1.35">${subtitle}</span>
      </div>${m3switch(checked, enabled)}</div>`;
  const clickRow = (title, subtitle, { titleColor = C.textPrimary, trailing = '', subMono = false } = {}) =>
    `<div class="row" style="background:${C.bgElevated};padding:12px 16px;align-items:center;gap:12px">
      <div class="col" style="flex:1">
        <span style="font-size:14px;font-weight:500;color:${titleColor}">${title}</span>
        <span class="${subMono ? 'mono' : ''}" style="font-size:${subMono ? 12 : 14}px;color:${C.textMuted};line-height:1.35">${subtitle}</span>
      </div>${trailing}</div>`;
  const trailingMono = (t, color = C.lemon) =>
    `<span class="mono" style="font-size:14px;color:${color};flex:none">${t}</span>`;

  // Fingerprint card (KeyFingerprintDisplay.kt): groups of 4, 4 per line, JBM.
  const groups = FINGERPRINT.split(' ');
  const fpLines = [];
  for (let i = 0; i < groups.length; i += 4) {
    fpLines.push(
      `<div class="row" style="gap:12px">${groups.slice(i, i + 4).map((g) => `<span class="mono" style="font-size:16px;letter-spacing:.05em;color:${C.textSecondary}">${g}</span>`).join('')}</div>`,
    );
  }
  const fingerprintBlock = `
    <div class="col" style="padding:8px 16px">
      <span style="font-size:14px;font-weight:500;color:${C.textPrimary}">Your identity key fingerprint</span>
      <span style="font-size:14px;color:${C.textMuted};margin-bottom:8px">Contacts can compare this against what their device shows for you.</span>
      <div class="col" style="background:${C.bgElevated};border:1px solid ${C.border};border-radius:12px;padding:16px;align-items:center;gap:4px">${fpLines.join('')}</div>
    </div>`;

  // Connection block (clearnet fallback state, as coded).
  const connectionBlock = `
    <div class="row" style="padding:12px 16px;align-items:center;gap:12px">
      <div class="col" style="flex:1">
        <span style="font-size:14px;font-weight:500;color:${C.textPrimary}">Connection</span>
        <span style="font-size:14px;color:${C.burnOrange}">Connected over clearnet</span>
        <span style="font-size:14px;color:${C.textMuted};line-height:1.35">Still end-to-end encrypted, but your IP may be visible. Enable Tor above for full anonymity.</span>
      </div>
      ${lemonSlice(24, { filled: 6, seg: C.lemon, rind: 'rgba(245,230,66,.6)' })}
    </div>`;

  return `<div class="screen">
  <div id="scroller" class="col" style="height:100%;overflow:hidden">
   <div id="scrollcol" class="col">
    <div class="row" style="padding:6px 4px;align-items:center">
      <div class="icon-btn">${icon('arrow_back', 24, C.lemon)}</div>
      <span class="display" style="font-size:20px;font-weight:600;color:${C.textPrimary}">Settings</span>
    </div>
    ${sectionHeader('Security', 'sec-security')}
    ${toggleRow('Biometric unlock', 'Unlock with a fingerprint or face instead of your passphrase', true)}
    ${clickRow('Auto-lock when backgrounded', 'Locks the vault after 5 minutes in the background. Zitrone has no push notifications; messages only arrive while the app is open and unlocked. A shorter auto-lock is more private but means messages may not arrive until you next open the app.', { trailing: trailingMono('5 minutes') })}
    ${fingerprintBlock}
    ${sectionHeader('Privacy', 'sec-privacy')}
    ${clickRow('Default disappearing timer', 'Applied to new messages: Off', { trailing: trailingMono('Off') })}
    ${toggleRow('Burn on read by default', 'New messages destroy themselves after the first open', false)}
    ${toggleRow('Send read receipts', 'Encrypted signal — the server never knows read status', true)}
    ${toggleRow('Lemon-drop compose button', 'Show the droplet in chat so you can seal a QR drop. Off by default — rare action.', true)}
    ${sectionHeader('Notifications', 'sec-notifications')}
    ${toggleRow('Repeat unread reminders', 'While new messages keep arriving in an unread chat, re-alert roughly every 2 minutes until you open it. Off: new messages still alert (at most one per chat every 2 minutes), but never repeat for the same unread pile.', true)}
    ${clickRow('Notification sound', 'Plays the Zitrone tone by default. Tap to pick your own sound or silence it.', { trailing: trailingMono('Change') })}
    ${sectionHeader('Account', 'sec-account')}
    ${clickRow('Account ID', ACCOUNT_ID, { subMono: true })}
    ${clickRow('Pucker Burn password', 'A separate password that erases everything Zitrone holds on this device when entered at the lock screen.', { titleColor: C.burnRed })}
    ${clickRow('Delete account', 'Purges every key, prekey and pending envelope. Irreversible.', { titleColor: C.burnRed })}
    ${sectionHeader('Network', 'sec-network')}
    ${toggleRow('Use I2P when available', 'On, but no I2P app found — Zitrone will use your normal connection. Install the official I2P app to upgrade automatically.', true)}
    ${clickRow('Get the I2P app', 'Install the official I2P app, then come back — routing turns on automatically.', { titleColor: C.lemon })}
    ${clickRow('…or get the I2P app on F-Droid', 'https://f-droid.org/packages/net.i2p.android.router/', { subMono: true })}
    ${toggleRow('Route through Tor', 'Requires Orbot — install it first, then enable this.', false, { enabled: false })}
    ${clickRow('Get Orbot', 'Install the Tor proxy app, then come back and enable Tor.', { titleColor: C.lemon })}
    ${clickRow('…or get Orbot on F-Droid', 'https://f-droid.org/packages/org.torproject.android/', { subMono: true })}
    ${connectionBlock}
    ${sectionHeader('Diagnostics', 'sec-diagnostics')}
    ${clickRow('Connection diagnostics', 'On-device log of registration &amp; connection attempts. Open it if you’re stuck on “Connecting…”, and share it in a bug report.')}
    ${sectionHeader('Appearance', 'sec-appearance')}
    ${clickRow('Theme', 'Dark. There is no light mode — lemons grow in the dark here.', { trailing: trailingMono('Dark', C.textSecondary) })}
    <div style="height:24px"></div>
   </div>
  </div>
</div>`;
}

// ===========================================================================
// 08 — OnboardingScreen, slide 1 (copy verbatim from Slides[0])
// ===========================================================================
function onboardingBody() {
  const dots = [0, 1, 2, 3].map((i) =>
    i === 0
      ? `<div style="width:24px;height:8px;border-radius:999px;background:${C.lemon};margin:0 4px"></div>`
      : `<div style="width:8px;height:8px;border-radius:999px;background:${C.rind};margin:0 4px"></div>`,
  ).join('');
  return `<div class="screen">
  <div class="col" style="height:100%">
    <div class="col" style="flex:1;padding:0 32px;align-items:center;justify-content:center">
      <div style="width:200px;height:200px;display:flex;align-items:center;justify-content:center">
        ${lemonSlice(160, { filled: 6 })}
      </div>
      <span class="display" style="font-size:30px;font-weight:600;color:${C.textPrimary};text-align:center;margin-top:40px">End-to-end encrypted</span>
      <span style="font-size:16px;color:${C.textSecondary};text-align:center;margin-top:12px;line-height:1.45">Your messages are locked before they leave your device. We can't read them. Nobody can.</span>
    </div>
    <div class="row" style="justify-content:center;padding:16px 0">${dots}</div>
    <div class="row" style="justify-content:space-between;align-items:center;padding:0 24px 32px 24px">
      <div style="height:40px;display:flex;align-items:center;padding:0 12px"><span style="font-size:14px;font-weight:500;color:${C.textSecondary}">Skip</span></div>
      <div style="height:40px;border-radius:20px;background:${C.lemon};display:flex;align-items:center;padding:0 24px"><span style="font-size:14px;font-weight:500;color:${C.textOnLemon}">Next</span></div>
    </div>
  </div>
</div>`;
}

// ---------------------------------------------------------------------------
// Emit pages
// ---------------------------------------------------------------------------
const pages = {
  '01-chat-list.html': page('Zitrone — chat list', chatListBody(), { watermark: true }),
  '02-chat.html': page('Zitrone — chat', chatBody({ draft: 'See you at the stand' }), { watermark: true }),
  '03-lock-screen.html': page('Zitrone — lock screen', lockBody()),
  '04-add-contact.html': page('Zitrone — add contact', addContactBody()),
  '05-lemon-drop.html': page('Zitrone — lemon drop sealed', chatBody({ draft: '', dropDialog: true }), { watermark: true }),
  '06-settings-network.html': page('Zitrone — settings network', settingsBody()),
  '07-settings-privacy.html': page('Zitrone — settings privacy', settingsBody()),
  '08-onboarding.html': page('Zitrone — onboarding', onboardingBody()),
};

for (const [name, html] of Object.entries(pages)) {
  fs.writeFileSync(path.join(OUT, name), html);
  console.log('wrote', name);
}
