// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package api

import (
	"net"
	"testing"

	"github.com/gofiber/fiber/v2"
	"github.com/valyala/fasthttp"
)

// keyFor derives a key from the two inputs that decide it: the socket peer
// (unspoofable) and the client-supplied X-Forwarded-For.
//
// NOTE: this deliberately does NOT go through fiber's app.Test. That helper
// serves over an in-memory connection whose RemoteAddr is always 0.0.0.0, so
// c.IP() ignores any RemoteAddr set on the request — every case would silently
// collapse to the untrusted path and the tests would pass without testing
// anything. TestClientKey_ReadsPeerAndHeaderFromRequest covers the wiring
// against a real fasthttp RequestCtx instead.
func keyFor(t *testing.T, k *clientKeyer, remoteAddr, xff string) string {
	t.Helper()
	return k.keyFrom(remoteAddr, xff)
}

const (
	gatewayIP = "172.18.0.1" // Caddy, via the published port — trusted
	torIP     = "172.18.0.5" // Tor sidecar container — NEVER trusted
)

// THE ONE THAT MATTERS MOST. The Tor hidden service forwards raw HTTP, so an
// overlay client can set X-Forwarded-For to anything. If the sidecar were ever
// trusted, every such client could mint its own bucket and the limiter would be
// gone — strictly worse than the shared-bucket bug this fixes. Two clients over
// Tor claiming different addresses must land in the SAME bucket.
func TestClientKey_SpoofedHeaderFromUntrustedPeerIsIgnored(t *testing.T) {
	k := newClientKeyer([]string{gatewayIP})

	honest := keyFor(t, k, torIP, "")
	spoofA := keyFor(t, k, torIP, "1.2.3.4")
	spoofB := keyFor(t, k, torIP, "5.6.7.8")

	if spoofA != honest || spoofB != honest {
		t.Fatalf("an untrusted peer escaped its bucket by setting X-Forwarded-For: honest=%s a=%s b=%s", honest, spoofA, spoofB)
	}
}

// Caddy APPENDS rather than overwrites, so a client-supplied prefix is present
// in the header the relay sees. Only the LAST element — the one Caddy wrote —
// may be believed. A client claiming "1.2.3.4" must be keyed on its real
// address, and must not be able to collide with another client by claiming it.
func TestClientKey_TrustedPeerUsesLastElementNotClientPrefix(t *testing.T) {
	k := newClientKeyer([]string{gatewayIP})

	real1 := keyFor(t, k, gatewayIP, "203.0.113.7")
	real2 := keyFor(t, k, gatewayIP, "198.51.100.9, 203.0.113.7")
	if real1 != real2 {
		t.Fatalf("prefix changed the key: a client can shift buckets by prepending to X-Forwarded-For")
	}

	other := keyFor(t, k, gatewayIP, "203.0.113.7, 198.51.100.9")
	if other == real1 {
		t.Fatalf("last element was ignored: keyed on the client-supplied prefix instead of Caddy's own value")
	}
}

// The defect being fixed: behind a trusted proxy, distinct clients must land in
// distinct buckets rather than collapsing into one.
func TestClientKey_TrustedPeerSeparatesClients(t *testing.T) {
	k := newClientKeyer([]string{gatewayIP})

	a := keyFor(t, k, gatewayIP, "203.0.113.7")
	b := keyFor(t, k, gatewayIP, "203.0.113.8")
	if a == b {
		t.Fatalf("distinct clients collapsed into one bucket — the bug is not fixed")
	}
}

// Unset config must behave exactly as before: socket peer only, header ignored.
// Degrading to "too coarse" is safe; degrading to "client-chosen" is not.
func TestClientKey_NoTrustedProxiesIgnoresHeaderEntirely(t *testing.T) {
	k := newClientKeyer(nil)
	if k.enabled() {
		t.Fatalf("empty config reported as enabled")
	}

	plain := keyFor(t, k, gatewayIP, "")
	claimed := keyFor(t, k, gatewayIP, "203.0.113.7")
	if plain != claimed {
		t.Fatalf("header was believed with no trusted proxies configured")
	}
}

// A CIDR must be rejected rather than expanded: a range covering the sidecars
// would reopen the full bypass. Junk entries must not enable anything either.
func TestClientKey_CIDRAndJunkEntriesAreRejected(t *testing.T) {
	for _, entry := range []string{"172.18.0.0/16", "172.18.0.1/32", "not-an-ip", ""} {
		k := newClientKeyer([]string{entry})
		if k.enabled() {
			t.Fatalf("entry %q was accepted as a trusted proxy; CIDRs and junk must be dropped", entry)
		}
		// And it must not accidentally trust the gateway either.
		if keyFor(t, k, gatewayIP, "203.0.113.7") != keyFor(t, k, gatewayIP, "") {
			t.Fatalf("entry %q caused X-Forwarded-For to be believed", entry)
		}
	}
}

// Absent or malformed last element falls back to the socket peer, never to
// something the client controls.
func TestClientKey_MalformedHeaderFallsBackToPeer(t *testing.T) {
	k := newClientKeyer([]string{gatewayIP})

	peer := keyFor(t, k, gatewayIP, "")
	for _, bad := range []string{"garbage", "203.0.113.7, garbage", ",", "   "} {
		if got := keyFor(t, k, gatewayIP, bad); got != peer {
			t.Fatalf("header %q did not fall back to the socket peer", bad)
		}
	}
}

// The key is an opaque digest, not the address. Several of these limiters guard
// deliberately anonymous endpoints (drops, QR drops, blob redeem), so the relay
// must not retain a raw client address as a bucket key.
func TestClientKey_DoesNotLeakTheAddress(t *testing.T) {
	k := newClientKeyer([]string{gatewayIP})
	key := keyFor(t, k, gatewayIP, "203.0.113.7")

	if key == "203.0.113.7" {
		t.Fatalf("key is the raw client address")
	}
	if len(key) != 64 { // hex-encoded HMAC-SHA256
		t.Fatalf("key = %q, want a 64-char hex digest", key)
	}
}

// The salt is per-process, so the same address does not produce a stable key
// across restarts and buckets cannot be correlated between them.
func TestClientKey_SaltIsPerProcess(t *testing.T) {
	a := newClientKeyer([]string{gatewayIP})
	b := newClientKeyer([]string{gatewayIP})

	if keyFor(t, a, gatewayIP, "203.0.113.7") == keyFor(t, b, gatewayIP, "203.0.113.7") {
		t.Fatalf("two keyers produced the same key — the salt is not per-process")
	}
}

// Wiring check against a real fasthttp RequestCtx: key(c) must read the socket
// peer and the header from the request, not from anywhere else.
func TestClientKey_ReadsPeerAndHeaderFromRequest(t *testing.T) {
	k := newClientKeyer([]string{gatewayIP})
	app := fiber.New()

	derive := func(peer, xff string) string {
		fctx := &fasthttp.RequestCtx{}
		fctx.SetRemoteAddr(&net.TCPAddr{IP: net.ParseIP(peer), Port: 40000})
		if xff != "" {
			fctx.Request.Header.Set(fiber.HeaderXForwardedFor, xff)
		}
		c := app.AcquireCtx(fctx)
		defer app.ReleaseCtx(c)
		return k.key(c)
	}

	// Trusted peer: the header decides, and distinct clients separate.
	if derive(gatewayIP, "203.0.113.7") == derive(gatewayIP, "203.0.113.8") {
		t.Fatalf("distinct clients behind the trusted proxy shared a bucket")
	}
	// Untrusted peer: the header is ignored.
	if derive(torIP, "203.0.113.7") != derive(torIP, "") {
		t.Fatalf("header was believed from an untrusted peer")
	}
	// And it agrees with the pure form.
	if derive(gatewayIP, "203.0.113.7") != k.keyFrom(gatewayIP, "203.0.113.7") {
		t.Fatalf("key(c) disagrees with keyFrom on the same inputs")
	}
}

// A Handlers assembled as a struct literal (as this package's other tests do)
// has no keyer. That must degrade to the socket peer rather than panic on the
// request path.
func TestClientKey_NilKeyerFallsBackToPeer(t *testing.T) {
	var k *clientKeyer
	app := fiber.New()

	fctx := &fasthttp.RequestCtx{}
	fctx.SetRemoteAddr(&net.TCPAddr{IP: net.ParseIP(gatewayIP), Port: 40000})
	fctx.Request.Header.Set(fiber.HeaderXForwardedFor, "203.0.113.7")
	c := app.AcquireCtx(fctx)
	defer app.ReleaseCtx(c)

	if got := k.key(c); got != gatewayIP {
		t.Fatalf("nil keyer = %q, want the socket peer %q", got, gatewayIP)
	}
}
