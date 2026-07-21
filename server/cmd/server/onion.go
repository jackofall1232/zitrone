// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package main

import (
	"bytes"
	"html/template"
	"os"
	"path/filepath"
	"strings"

	"github.com/gofiber/fiber/v2"

	"github.com/zitrone/server/internal/config"
)

// registerOnionMirror registers the static no-JS APK mirror on the public and
// secret onion addresses. The relay onion address serves only the API — it
// intentionally has no mirror so the relay service is not correlated with the
// download service by an observer watching both onions.
//
// Host-gating logic:
//
//	Host == PUBLIC_ONION_ADDRESS  -> serve mirror
//	Host == SECRET_ONION_ADDRESS  -> serve mirror (identical content)
//	Host == RELAY_ONION_ADDRESS   -> fall through to API (no mirror)
//	Host == clearnet hostname     -> fall through to API (no mirror)
//
// This makes a hybrid deployment safe: the clearnet 8443 port can stay published
// for the API (behind a reverse proxy), yet ordinary clearnet / IP-scanner
// traffic (any non-mirror Host) never reaches the mirror and instead falls
// through to the API routes.
//
// Fails closed: an empty address never matches, so a misconfigured deployment
// serves no mirror rather than leaking it onto every Host.
//
// The download link degrades gracefully: the APK is gitignored and must be
// staged by the operator, so when no *.apk is present in the site directory the
// page hides the download/verify section and shows staging guidance instead of
// linking to a dead 404.
// currentAPK is the single, explicit source of truth for the APK the mirror
// advertises on the index page and treats as "the current build". It is
// deliberately a constant rather than globbing the directory: superseded builds
// are intentionally left in place (see mirrorAssets), and filepath.Glob returns
// matches in lexical order, which would keep advertising an older version
// (v0.7.6 sorts before v0.8.0). Bump this and mirrorAssets together each release.
const currentAPK = "zitrone-v0.8.2-beta.apk"

func registerOnionMirror(app *fiber.App, cfg *config.Config) {
	dir := cfg.OnionSiteDir
	if dir == "" || !cfg.TorEnabled {
		return
	}
	indexPath := filepath.Join(dir, "index.html")

	// renderIndex serves the index page as an html/template, toggling the
	// download section on whether an APK has been staged. Host-gated; non-mirror
	// requests fall through (c.Next -> ultimately a 404, no mirror).
	renderIndex := func(c *fiber.Ctx) error {
		if !isMirrorHost(c, cfg) {
			return c.Next()
		}
		raw, err := os.ReadFile(indexPath)
		if err != nil {
			return c.Next()
		}
		tmpl, err := template.New("index").Parse(string(raw))
		if err != nil {
			return c.Next()
		}
		apkName := currentAPK
		_, statErr := os.Stat(filepath.Join(dir, apkName))
		apkAvailable := statErr == nil
		var buf bytes.Buffer
		// The template displays the canonical (public) onion address. The secret
		// and relay addresses are NEVER passed to the template or any response.
		if err := tmpl.Execute(&buf, map[string]any{
			"OnionAddress": cfg.PublicOnionAddress,
			"APKName":      apkName,
			"APKAvailable": apkAvailable,
		}); err != nil {
			return c.Next()
		}
		c.Type("html")
		return c.Send(buf.Bytes())
	}

	app.Get("/", renderIndex)
	app.Get("/index.html", renderIndex)

	// mirrorAssets is a STRICT ALLOWLIST of the exact basenames the mirror will
	// serve. This is a literal whitelist, not path sanitising: a request is
	// served only if its basename is a key here; every other path falls through
	// to a 404. Because the keys contain no path separators and the response
	// path is filepath.Join(dir, <allowlisted key>), there is no way to escape
	// dir — traversal input (e.g. /../onion.go, /../../etc/passwd) simply is not
	// a key and 404s. To publish a new asset (e.g. a new APK), add its exact
	// filename here and rebuild.
	mirrorAssets := map[string]bool{
		"style.css":  true,
		"SHA256SUMS": true,
		currentAPK:   true, // zitrone-v0.8.2-beta.apk — the advertised build
		// Superseded builds are kept downloadable (left in place so existing
		// links keep working) but are no longer advertised or listed in
		// SHA256SUMS. Prune these when old links no longer need to resolve.
		"zitrone-v0.7.6-beta.apk": true,
		"zitrone-v0.8.0-beta.apk": true,
	}

	// Static asset handler. Same Host gate as the index; SendFile wraps
	// fasthttp's byte-range-aware file server, so large downloads (the APK over
	// a flaky Tor circuit) get Accept-Ranges: bytes and resumable 206 replies.
	app.Use("/", func(c *fiber.Ctx) error {
		if !isMirrorHost(c, cfg) {
			return c.Next()
		}
		name := strings.TrimPrefix(c.Path(), "/")
		if !mirrorAssets[name] {
			return c.Next() // unmatched -> 404 (no API route serves these paths)
		}
		if err := c.SendFile(filepath.Join(dir, name)); err != nil {
			return c.Next()
		}
		// fasthttp's extension table doesn't map .apk; set the canonical MIME
		// after SendFile so it wins over the auto-detected content type (kept on
		// 206 range replies too).
		if strings.HasSuffix(name, ".apk") {
			c.Set(fiber.HeaderContentType, "application/vnd.android.package-archive")
		}
		return nil
	})
}

// isMirrorHost returns true when the request Host matches the public or secret
// onion address. The relay onion address is explicitly excluded — it serves the
// API only, so the relay onion is never correlated with the download mirror.
func isMirrorHost(c *fiber.Ctx, cfg *config.Config) bool {
	host := normaliseHost(c.Hostname())
	if host == "" {
		return false
	}
	pub := normaliseHost(cfg.PublicOnionAddress)
	sec := normaliseHost(cfg.SecretOnionAddress)
	// relay address intentionally not matched here
	return (pub != "" && host == pub) || (sec != "" && host == sec)
}

// normaliseHost strips any scheme prefix and :port suffix and lowercases. An
// empty input yields an empty string, which never matches a configured address —
// the fail-closed case. Stripping the scheme first guards against an operator
// configuring *_ONION_ADDRESS with an "http://"/"https://" prefix, which would
// otherwise make IndexByte match the scheme's colon and truncate the host.
func normaliseHost(h string) string {
	h = strings.TrimSpace(h)
	h = strings.TrimPrefix(h, "https://")
	h = strings.TrimPrefix(h, "http://")
	if i := strings.IndexByte(h, ':'); i >= 0 {
		h = h[:i]
	}
	return strings.ToLower(h)
}
