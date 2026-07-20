// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import { useEffect, useState } from "react";
import { ClearnetWarningBanner, LemonSlice, LemonSpinner, ZitroneStyles } from "@zitrone/ui";
import { isTauri } from "@zitrone/crypto";
import { parseQrDropUrl } from "@zitrone/protocol";
import { resolveTransport } from "./lib/transportResolver.js";
import { ScreenshotShield, useDevToolsWarning } from "./components/ScreenshotShield.js";
import { LemonDropOutcome, type LemonDropOutcomeVariant } from "./components/LemonDropOutcome.js";
import { ChatList } from "./screens/ChatList.js";
import { ChatView } from "./screens/ChatView.js";
import { Gate } from "./screens/Gate.js";
import { Settings } from "./screens/Settings.js";
import { VerifyKeys } from "./screens/VerifyKeys.js";
import { useSettings } from "./settings.js";
import { useApp } from "./store.js";

export default function App() {
  const phase = useApp((s) => s.phase);
  const bootstrap = useApp((s) => s.bootstrap);
  const activePeer = useApp((s) => s.activePeer);
  const redeemQrDrop = useApp((s) => s.redeemQrDrop);
  const devTools = useDevToolsWarning();
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [verifyPeer, setVerifyPeer] = useState<string | null>(null);
  const [bootDropOutcome, setBootDropOutcome] = useState<LemonDropOutcomeVariant | null>(null);

  const transport = useSettings((s) => s.transport);
  const allowClearnetFallback = useSettings((s) => s.allowClearnetFallback);
  const setTransport = useSettings((s) => s.setTransport);
  const fallbackReason = useSettings((s) => s.fallbackReason);
  const setFallbackReason = useSettings((s) => s.setFallbackReason);
  // Dismissal is per-session: the banner re-appears on the next resolve while
  // clearnet is still active, because the trade-off is still in effect.
  const [warningDismissed, setWarningDismissed] = useState(false);

  useEffect(() => {
    void bootstrap();
  }, [bootstrap]);

  // Resolve the active transport along the fixed I2P -> Tor -> clearnet
  // fallback chain on startup and whenever the clearnet-fallback preference
  // changes. A clearnet result un-dismisses the banner.
  useEffect(() => {
    let cancelled = false;
    void resolveTransport(isTauri(), allowClearnetFallback).then((res) => {
      if (cancelled) return;
      // Update the live transport. The store reconnects (or tears down, for
      // "offline") in response — see the transport subscription in store.ts —
      // so a clearnet socket is never left running while Tor is active. The
      // api.ts/ws.ts guards are the hard backstops against any clearnet leak.
      setTransport(res.transport);
      setFallbackReason(res.fallbackReason ?? null);
      if (res.showClearnetWarning) setWarningDismissed(false);
    });
    return () => {
      cancelled = true;
    };
  }, [allowClearnetFallback, setTransport, setFallbackReason]);

  // Boot-time /d/{id} redeem. Once the vault is open (phase "ready"), check the
  // address bar for a lemon-drop link and, if one is there, redeem it.
  //
  // NOTE: the PWA is NOT currently served on zitrone.app, so parseQrDropUrl(href)
  // is inert in practice — this path stays dormant until the operator wires
  // /d/* hosting (deferred by the deliver-then-claim rule). It runs anyway so the
  // flow lights up the moment that hosting exists. Pasted-URL redeem in Settings
  // already works everywhere today.
  useEffect(() => {
    if (phase !== "ready") return;
    const href = window.location.href;
    if (!parseQrDropUrl(href)) return;
    // Strip the id from the URL BEFORE touching the network, so a reload or a
    // shoulder-surfed address bar can't re-expose it. history.replaceState leaves
    // no new entry to navigate back into.
    window.history.replaceState(null, "", window.location.origin + "/");
    void redeemQrDrop(href)
      .then((outcome) => {
        // "message" opens the chat (activePeer is set) — no overlay needed.
        if (outcome !== "message") setBootDropOutcome(outcome);
      })
      .catch(() => {
        // parseQrDropUrl already gated a well-formed link, so a throw here is a
        // fetch/transport failure. Stay silent at boot — we surface no error UI
        // for a URL the user didn't explicitly paste.
      });
  }, [phase, redeemQrDrop]);

  const showClearnetBanner =
    phase === "ready" && transport === "clearnet_fallback" && !warningDismissed;

  return (
    <div className="relative h-full">
      <ZitroneStyles />

      {showClearnetBanner && (
        <ClearnetWarningBanner
          reason={fallbackReason ?? undefined}
          onOpenSettings={() => setSettingsOpen(true)}
          onDismiss={() => setWarningDismissed(true)}
        />
      )}

      {devTools && phase === "ready" && (
        <div
          role="alert"
          className="flex items-center justify-center gap-2 bg-burn-orange px-4 py-1.5 text-xs font-medium text-bg-primary"
        >
          Developer tools appear to be open. Anything on screen can be read by extensions or
          inspected — close DevTools for sensitive conversations.
        </div>
      )}

      {phase === "loading" && (
        <div className="flex h-full items-center justify-center">
          <LemonSpinner size={64} />
        </div>
      )}

      {(phase === "setup" || phase === "unlock") && <Gate mode={phase} />}

      {phase === "ready" && (
        <main className="flex h-full">
          <ChatList onOpenSettings={() => setSettingsOpen(true)} />
          {activePeer ? (
            <ChatView peerId={activePeer} onVerify={() => setVerifyPeer(activePeer)} />
          ) : (
            <section className="flex flex-1 flex-col items-center justify-center gap-4 bg-bg-primary">
              <LemonSlice variant="logo_mark" size={96} />
              <p className="font-display text-lg text-ink-secondary">
                Nothing lasts. That's the point.
              </p>
            </section>
          )}
          <ScreenshotShield />
        </main>
      )}

      {settingsOpen && <Settings onClose={() => setSettingsOpen(false)} />}
      {verifyPeer && <VerifyKeys peerId={verifyPeer} onClose={() => setVerifyPeer(null)} />}
      {bootDropOutcome && (
        <LemonDropOutcome variant={bootDropOutcome} onClose={() => setBootDropOutcome(null)} />
      )}
    </div>
  );
}
