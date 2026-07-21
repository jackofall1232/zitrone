// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// v1.5 client preferences: connection mode, cover-traffic intensity, and privacy
// view. These are UI-layer settings — they never touch the crypto or the message
// envelope. Persisted in localStorage; they carry no key material, no message
// content, and nothing that reveals vault count or existence.

import {
  CONNECTION_MODES,
  DEFAULT_CONNECTION_MODE,
  DEFAULT_PRIVACY_VIEW,
  privacyViewActive,
  type ConnectionMode,
  type CoverTrafficIntensity,
  type PrivacyViewSettings,
  type RevealMode,
  type TransportState,
} from "@zitrone/protocol";
import { isTauri } from "@zitrone/crypto";
import { create } from "zustand";

const STORAGE_KEY = "zitrone.settings.v1_5";

interface PersistedSettings {
  connectionMode: ConnectionMode;
  coverTraffic: CoverTrafficIntensity;
  /**
   * When false, the app refuses to connect over clearnet — the transport
   * resolver reports "offline" instead of "clearnet_fallback". Default true.
   */
  allowClearnetFallback: boolean;
  privacyView: PrivacyViewSettings;
  /**
   * Whether the one-time lemon-drop (QR) coachmark has been dismissed. Defaults
   * to false so it shows once for users upgrading from a build without the flag
   * — the fallback is spread UNDER the parsed partial, so an older persisted
   * blob that lacks this key still reads as false rather than undefined.
   */
  qrDropCoachmarkSeen: boolean;
  /**
   * When true, the compose bar shows the lemon-drop (💧) create affordance.
   * Default false: lemon-drop creation is rarely used, so the toolbar stays
   * clean unless the user opts in under Settings → Privacy. Opening/redeeming
   * a drop (paste link) is unrelated and stays available in Settings.
   */
  lemonDropComposeEnabled: boolean;
}

function fallbackSettings(): PersistedSettings {
  return {
    connectionMode: DEFAULT_CONNECTION_MODE,
    coverTraffic: CONNECTION_MODES[DEFAULT_CONNECTION_MODE].decoyIntensity,
    allowClearnetFallback: true,
    privacyView: DEFAULT_PRIVACY_VIEW,
    qrDropCoachmarkSeen: false,
    lemonDropComposeEnabled: false,
  };
}

/**
 * Merge a persisted settings blob over the defaults. Pure (no storage access)
 * so the fallback-under-partial behavior is unit-testable: a blob missing a key
 * inherits the default, never `undefined`.
 */
export function parsePersisted(raw: string | null): PersistedSettings {
  const fallback = fallbackSettings();
  if (!raw) return fallback;
  try {
    return { ...fallback, ...(JSON.parse(raw) as Partial<PersistedSettings>) };
  } catch {
    return fallback;
  }
}

function load(): PersistedSettings {
  try {
    return parsePersisted(localStorage.getItem(STORAGE_KEY));
  } catch {
    return fallbackSettings();
  }
}

function save(s: PersistedSettings): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(s));
  } catch {
    /* storage unavailable — settings stay in memory only */
  }
}

interface SettingsState extends PersistedSettings {
  /** Live transport state — I2P is primary, Tor is the fallback, clearnet is flagged. */
  transport: TransportState;
  /**
   * Human-readable reason the resolver fell back to clearnet, shown in the
   * warning banner. Transient (not persisted) — null when no fallback is active.
   */
  fallbackReason: string | null;
  setConnectionMode: (mode: ConnectionMode) => void;
  setCoverTraffic: (intensity: CoverTrafficIntensity) => void;
  setAllowClearnetFallback: (allow: boolean) => void;
  setTransport: (transport: TransportState) => void;
  setFallbackReason: (reason: string | null) => void;
  setGlobalPrivacyView: (on: boolean) => void;
  setRevealMode: (mode: RevealMode) => void;
  setTapTimedSeconds: (seconds: number) => void;
  togglePrivacyForConversation: (peerId: string) => void;
  isPrivacyActive: (peerId: string) => boolean;
  setQrDropCoachmarkSeen: () => void;
  setLemonDropComposeEnabled: (on: boolean) => void;
}

export const useSettings = create<SettingsState>((set, get) => {
  const initial = load();
  const persist = () => {
    const {
      connectionMode,
      coverTraffic,
      allowClearnetFallback,
      privacyView,
      qrDropCoachmarkSeen,
      lemonDropComposeEnabled,
    } = get();
    save({
      connectionMode,
      coverTraffic,
      allowClearnetFallback,
      privacyView,
      qrDropCoachmarkSeen,
      lemonDropComposeEnabled,
    });
  };

  return {
    ...initial,
    // A browser reaches the server over HTTPS/WSS; true Tor routing means using
    // the Tor Browser with the deployment's .onion address. We surface Tor as
    // active when the page is served from an .onion host, else a clearnet
    // fallback — honest about the browser's limits.
    // Fail closed at startup: until resolveTransport runs, a fallback-disabled
    // client must read as "offline" so no REST/WS leaks over clearnet during the
    // bootstrap/unlock window. An .onion host is genuine Tor regardless.
    transport:
      typeof location !== "undefined" && location.hostname.endsWith(".onion")
        ? "tor"
        : initial.allowClearnetFallback
          ? "clearnet_fallback"
          : "offline",
    fallbackReason: null,

    setConnectionMode(mode) {
      // Selecting a mode sets its bundled cover-traffic intensity too.
      set({ connectionMode: mode, coverTraffic: CONNECTION_MODES[mode].decoyIntensity });
      persist();
    },
    setCoverTraffic(intensity) {
      set({ coverTraffic: intensity });
      persist();
    },
    setAllowClearnetFallback(allow) {
      set({ allowClearnetFallback: allow });
      persist();
    },
    setTransport(transport) {
      set({ transport });
    },
    setFallbackReason(reason) {
      set({ fallbackReason: reason });
    },
    setGlobalPrivacyView(on) {
      set((s) => ({ privacyView: { ...s.privacyView, globalEnabled: on } }));
      persist();
    },
    setRevealMode(mode) {
      set((s) => ({ privacyView: { ...s.privacyView, revealMode: mode } }));
      persist();
    },
    setTapTimedSeconds(seconds) {
      set((s) => ({ privacyView: { ...s.privacyView, tapTimedSeconds: seconds } }));
      persist();
    },
    togglePrivacyForConversation(peerId) {
      set((s) => {
        const current = privacyViewActive(s.privacyView, peerId);
        return {
          privacyView: {
            ...s.privacyView,
            perConversation: { ...s.privacyView.perConversation, [peerId]: !current },
          },
        };
      });
      persist();
    },
    isPrivacyActive(peerId) {
      return privacyViewActive(get().privacyView, peerId);
    },
    setQrDropCoachmarkSeen() {
      // One-way latch: once dismissed, the coachmark never returns.
      set({ qrDropCoachmarkSeen: true });
      persist();
    },
    setLemonDropComposeEnabled(on) {
      set({ lemonDropComposeEnabled: on });
      persist();
    },
  };
});

// In the Tauri desktop app the Rust backend probes for a local Tor SOCKS proxy
// on startup and emits `connection-mode-changed` ({ mode: "tor" | "clearnet" }).
// Mirror it into the transport state so the connection-mode badge reflects real
// routing — in a browser this listener is never registered and the existing
// `.onion`-hostname heuristic stands.
if (isTauri()) {
  interface TauriEventApi {
    listen: (
      event: string,
      handler: (event: { payload: { mode?: string } }) => void,
    ) => Promise<unknown>;
  }
  const tauri = (window as unknown as { __TAURI__?: { event?: TauriEventApi } }).__TAURI__;
  void tauri?.event
    ?.listen("connection-mode-changed", (event) => {
      const transport: TransportState = event.payload?.mode === "tor" ? "tor" : "clearnet_fallback";
      useSettings.getState().setTransport(transport);
    })
    .catch(() => {
      /* event bridge unavailable — keep the default transport state */
    });
}
