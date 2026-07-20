// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

import SwiftUI
import UIKit

/// The always-on "security paper" watermark: a faint, toroidally-tiled diagonal
/// lattice of the VIEWER'S OWN 60-hex identity fingerprint painted behind the
/// chat surfaces. It is a deterrence layer — anyone photographing the screen is
/// consciously reminded that what they capture is marked as *theirs* — and it is
/// deliberately ALWAYS ON, with no settings toggle (HoboJoe sign-off: a toggle
/// turns a deterrent into a checkbox nobody finds; its value is that it is never
/// negotiable). The exact geometry is treatment "G2", mirrored from the web
/// carrier (packages/ui/src/fingerprintTile.ts) and the Android carrier
/// (FingerprintWatermark.kt) so every client paints the same paper.
///
/// Performance contract: the tile is rendered to a single `UIImage` ONCE per
/// (fingerprint, screen scale) and cached; SwiftUI then repeats that one image
/// via `.resizable(resizingMode: .tile)`. Nothing allocates while scrolling the
/// chat over the paper — the framework tiles the cached texture the compositor
/// already holds.

/// Tile parameters for treatment "G2" — do not tune without a new design
/// sign-off. Values are in tile-space points BEFORE the screen-scale multiply.
enum FingerprintWatermarkDefaults {
    /// Fundamental tile edge, in tile-space points before the density scale.
    static let tileSize: CGFloat = 512
    static let rotationDeg: CGFloat = -24
    static let alpha: CGFloat = 0.045
    static let fontPx: CGFloat = 10.5
    static let rowGapPx: CGFloat = 28
    static let brickOffset: CGFloat = 0.5

    /// Lemon #F5E642; the per-run [alpha] is applied on the text colour.
    static let color = UIColor(red: 245 / 255, green: 230 / 255, blue: 66 / 255, alpha: alpha)

    // DEVIATION FROM WEB (identical to Android): the tile ground is TRANSPARENT
    // here. The web bakes an opaque #0D0C00 ground into the tile only because
    // that same tile doubles as the LSB stego carrier and must be a complete
    // image on its own. iOS has no stego layer: the screen already paints
    // Color.backgroundPrimary beneath this view, and an opaque ground would
    // (a) be redundant and (b) hide any surface meant to sit above the ground
    // but below the paper. So we render runs onto transparency and let the real
    // background show through.
}

/// The pure geometry half of the tile, kept free of UIKit drawing so it could
/// be unit-tested on a plain Swift toolchain later. Given the measured pixel
/// width of one fingerprint run and the tile parameters, it returns every run
/// anchor that lands INSIDE the fundamental tile domain [0,tileSize)². Rows
/// march along the rotated η axis (alternate rows brick-shifted by
/// `pitch * brickOffset`); repeats march along the ξ axis by `pitch`. Anchors
/// outside the tile are dropped here; the toroidal edge-wrap (drawing each kept
/// run at all nine lattice offsets) is the renderer's job, not the geometry's.
///
/// Mirrors Android's `FingerprintTileGeometry.anchors` exactly.
enum FingerprintTileGeometry {
    // `runWidth` is documented context (the caller derives `pitch` from it as
    // `runWidth + fontPx*scale*4`), not used directly: `pitch` is authoritative
    // for the ξ step. Retained in the signature so callers document the run the
    // pitch was derived from, exactly as the Android/web carriers do.
    static func anchors(
        runWidth: CGFloat,
        tileSize: CGFloat,
        rotationDeg: CGFloat,
        rowGap: CGFloat,
        pitch: CGFloat,
        brickOffset: CGFloat
    ) -> [(x: CGFloat, y: CGFloat, brickRow: Bool)] {
        _ = runWidth
        let theta = rotationDeg * .pi / 180
        let cosT = cos(theta)
        let sinT = sin(theta)
        let span = tileSize * 1.6
        let half = tileSize / 2
        var out: [(x: CGFloat, y: CGFloat, brickRow: Bool)] = []
        var eta = -span
        var row = 0
        while eta < span {
            let brickRow = row % 2 == 1
            let off = brickRow ? pitch * brickOffset : 0
            var xi = -span + off
            while xi < span {
                let ax = half + xi * cosT - eta * sinT // anchor, tile space
                let ay = half + xi * sinT + eta * cosT
                if ax >= 0, ax < tileSize, ay >= 0, ay < tileSize {
                    out.append((x: ax, y: ay, brickRow: brickRow))
                }
                xi += pitch
            }
            eta += rowGap
            row += 1
        }
        return out
    }
}

/// Render the G2 fingerprint tile to a `UIImage` the caller can hand to a
/// repeating SwiftUI tile. Pure function of (fingerprint, scale) — no SwiftUI,
/// no shared state — so it is trivially cacheable.
///
/// PIXEL-SPACE TREATMENT (mirrors how Android hands the compositor a
/// (512·density)² bitmap and tiles it 1 texel : 1 pixel): the whole tile is
/// computed in PIXEL units — `S = 512 · min(scale, 2)` — rendered at
/// `format.scale = 1`, then RE-WRAPPED to advertise the device scale
/// (`UIImage(cgImage:scale:orientation:)`), so the image is 512×512 POINTS
/// backed by S×S pixels. SwiftUI's `.resizable(resizingMode: .tile)` repeats
/// it at 512 pt — the same visual period as web and Android — with device
/// pixels mapped 1:1 (crisp, never upscaled). The density cap at 2 keeps the
/// texture ≤ 1024² so it never costs more than ~4 MB, matching Android.
enum FingerprintWatermarkRenderer {
    static func renderTile(fingerprint: String, scale: CGFloat) -> UIImage {
        let cappedScale = min(scale, 2)
        // The image edge, in PIXELS. The geometry and the nine-offset wrap use
        // this SAME value so the repeat is seamless — any mismatch between the
        // tile domain and the texture edge would show as a seam.
        let s = FingerprintWatermarkDefaults.tileSize * cappedScale

        let font = UIFont.monospacedSystemFont(
            ofSize: FingerprintWatermarkDefaults.fontPx * cappedScale, weight: .medium)
        let attributes: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: FingerprintWatermarkDefaults.color,
        ]
        let attributed = NSAttributedString(string: fingerprint, attributes: attributes)

        let runWidth = attributed.size().width
        let pitch = runWidth + FingerprintWatermarkDefaults.fontPx * cappedScale * 4
        let rowGap = FingerprintWatermarkDefaults.rowGapPx * cappedScale
        let theta = FingerprintWatermarkDefaults.rotationDeg * .pi / 180

        // Android centres each run on its anchor by shifting the BASELINE up by
        // half the text's vertical extent (-((ascent + descent) / 2)). With
        // NSAttributedString.draw(at:) the y is the TOP of the line box, so the
        // equivalent centring is a top-left at y = -lineHeight/2. The visual
        // delta versus the web/Android baseline math is sub-pixel at 10.5px and
        // acceptable.
        let baselineTop = -font.lineHeight / 2

        let anchors = FingerprintTileGeometry.anchors(
            runWidth: runWidth,
            tileSize: s,
            rotationDeg: FingerprintWatermarkDefaults.rotationDeg,
            rowGap: rowGap,
            pitch: pitch,
            brickOffset: FingerprintWatermarkDefaults.brickOffset)

        // format.scale = 1: we already worked in pixel space, so one image point
        // must equal one image pixel (see the PIXEL-SPACE TREATMENT note above).
        // opaque = false: transparent ground (the deliberate deviation above).
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false

        let renderer = UIGraphicsImageRenderer(
            size: CGSize(width: s, height: s), format: format)
        let rendered = renderer.image { context in
            let cg = context.cgContext
            for anchor in anchors {
                for dx in -1...1 {
                    for dy in -1...1 {
                        cg.saveGState()
                        cg.translateBy(
                            x: anchor.x + CGFloat(dx) * s,
                            y: anchor.y + CGFloat(dy) * s)
                        cg.rotate(by: theta)
                        attributed.draw(at: CGPoint(x: 0, y: baselineTop))
                        cg.restoreGState()
                    }
                }
            }
        }
        // Re-wrap at the device scale so the S-pixel render tiles as a 512-pt
        // image with 1:1 pixel mapping (see PIXEL-SPACE TREATMENT above). If
        // the CGImage is somehow unavailable, fall back to the soft 1x wrap
        // rather than crash a purely decorative layer.
        guard let cgImage = rendered.cgImage else { return rendered }
        return UIImage(cgImage: cgImage, scale: cappedScale, orientation: .up)
    }
}

/// Paint the security-paper watermark. Layer it ABOVE the surface's background
/// ground and BELOW the content — e.g. inside a `ZStack` behind the screen's
/// `Color.backgroundPrimary`. A nil/blank fingerprint (identity not yet
/// unlocked) renders nothing. The tile is rendered once per (fingerprint,
/// screen scale) and cached — see the perf contract in the file doc.
struct FingerprintWatermark: View {
    let fingerprint: String?

    // One cached image per (fingerprint, scale). NSCache evicts under memory
    // pressure on its own, which is exactly the eviction policy we want for a
    // regenerable texture.
    private static let cache = NSCache<NSString, UIImage>()

    var body: some View {
        if let fingerprint, !fingerprint.isEmpty {
            Image(uiImage: Self.tile(for: fingerprint))
                .resizable(resizingMode: .tile)
                .ignoresSafeArea()
                .allowsHitTesting(false)
        } else {
            Color.clear
        }
    }

    private static func tile(for fingerprint: String) -> UIImage {
        // Scale comes from UIScreen.main (as MessageBubble already reads
        // UIScreen.main for width): the tile is a DPI-fidelity texture, not a
        // laid-out view, so it needs the display's pixel density rather than a
        // trait-collection-plumbed scale. Capped at 2 to bound the texture size.
        let scale = min(UIScreen.main.scale, 2)
        let key = "\(fingerprint)|\(scale)" as NSString
        if let cached = cache.object(forKey: key) { return cached }
        let image = FingerprintWatermarkRenderer.renderTile(
            fingerprint: fingerprint, scale: scale)
        cache.setObject(image, forKey: key)
        return image
    }
}
