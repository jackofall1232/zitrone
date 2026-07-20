// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

//! Tauri application host for the Zitrone Linux desktop app.
//!
//! The UI is the existing `apps/web` React build — this crate adds a native
//! host: a libsecret keystore, Tor-first SOCKS5 detection, and a window focus
//! signal that drives the best-effort screenshot blur overlay (Linux has no
//! OS-level screenshot hard block; see `screenshot.rs`). No message content and
//! no secret bytes are ever logged here.

mod i2p;
mod keystore;
mod pinning;
mod screenshot;
mod tor;
mod transport;
mod window;

use tauri::Manager;

/// Build, configure, and run the Tauri application.
pub fn run() {
    init_tracing();

    // Build the pinned TLS config once and share it across the REST client and
    // every WebSocket dial. Building before the app starts means a broken pin
    // setup fails to launch rather than silently running unpinned.
    let tls = transport::pinned_tls_config();
    let http = transport::build_http_client(tls.clone());
    // I2P-proxied client — routes http:// requests through i2pd at 127.0.0.1:4444.
    let i2p_http = i2p::build_i2p_http_client();

    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_notification::init())
        .manage(transport::PinnedHttp(http))
        .manage(transport::PinnedTls(tls))
        .manage(transport::WsRegistry::default())
        .manage(i2p::I2pHttp(i2p_http))
        .setup(|app| {
            let handle = app.handle().clone();

            // Harden the main window and wire the focus-loss blur signal (the
            // best-effort screenshot defense — Linux has no OS-level hard block).
            if let Some(main) = app.get_webview_window("main") {
                window::harden(&main);
            }

            // Fixed fallback chain: I2P first, then Tor, then clearnet.
            // i2p::detect_and_announce returns true and emits "i2p" if the local
            // i2pd proxy is up; otherwise we fall through to the Tor probe.
            tauri::async_runtime::spawn(async move {
                if !i2p::detect_and_announce(handle.clone()).await {
                    tor::detect_and_announce(handle).await;
                }
            });

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            save_drop_image,
            i2p::check_i2p_connectivity,
            i2p::i2p_request,
            i2p::ws_open_i2p,
            keystore::store_vault,
            keystore::load_vault,
            keystore::delete_vault,
            tor::get_proxy_config,
            tor::set_proxy_config,
            tor::check_tor_connectivity,
            transport::pinned_request,
            transport::ws_open,
            transport::ws_send,
            transport::ws_close,
        ])
        .run(tauri::generate_context!())
        .expect("error while running Zitrone desktop application");
}

/// Save a print-grade QR-drop PNG. The native side owns the WHOLE flow —
/// save dialog AND write — so the renderer never supplies a filesystem path:
/// a compromised WebView can at most ask the user to pick a location. (An
/// earlier renderer-supplied-path version of this command was an
/// arbitrary-file-overwrite primitive — PR #8 review.) The PNG rides the raw
/// IPC body (no JSON number-array blowup); the suggested filename rides a
/// header and only seeds the dialog. The extension is forced to `.png` no
/// matter what the user types. Returns false when the dialog is canceled.
#[tauri::command]
fn save_drop_image(
    app: tauri::AppHandle,
    request: tauri::ipc::Request<'_>,
) -> Result<bool, String> {
    use tauri_plugin_dialog::DialogExt;

    let tauri::ipc::InvokeBody::Raw(bytes) = request.body() else {
        return Err("expected raw PNG bytes in the request body".to_string());
    };
    let suggested = request
        .headers()
        .get("x-suggested-filename")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("zitrone-drop.png")
        .to_string();

    let Some(picked) = app
        .dialog()
        .file()
        .add_filter("PNG image", &["png"])
        .set_file_name(&suggested)
        .blocking_save_file()
    else {
        return Ok(false);
    };
    let mut path = picked.into_path().map_err(|e| e.to_string())?;
    if path
        .extension()
        .map(|e| e.eq_ignore_ascii_case("png"))
        != Some(true)
    {
        path.set_extension("png");
    }
    std::fs::write(&path, bytes).map_err(|e| e.to_string())?;
    Ok(true)
}

/// Structured logging only — errors and system events. Never message content,
/// never secret bytes. Level is controlled by `RUST_LOG` (defaults to `info`).
fn init_tracing() {
    use tracing_subscriber::{fmt, EnvFilter};
    let filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"));
    // `try_init` so a double-init in tests is a no-op rather than a panic.
    let _ = fmt().with_env_filter(filter).try_init();
}
