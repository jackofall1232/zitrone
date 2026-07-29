// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package api

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"net"
	"strings"

	"github.com/gofiber/fiber/v2"
)

// clientKeyer derives the per-client rate-limit key.
//
// THE PROBLEM IT SOLVES. The relay runs behind Caddy on the same host, so the
// socket peer of every clearnet request is Caddy, not the client. Keying a
// limiter on c.IP() therefore collapses every clearnet user worldwide into ONE
// bucket — a single client can exhaust the limit for everyone.
//
// WHY THE OBVIOUS FIX IS A VULNERABILITY. The tempting fix is Fiber's
// ProxyHeader, i.e. trust X-Forwarded-For. Against this deployment that is
// strictly worse than the collapse. The Caddyfile has no `header_up` override,
// so Caddy APPENDS to any client-supplied X-Forwarded-For rather than
// overwriting it; and the Tor/I2P hidden services forward raw HTTP straight to
// this server, so an overlay client can set the header to whatever it likes.
// Trusting the header as-is would let any client mint its own bucket and remove
// the limiter entirely.
//
// THE TWO RULES THAT MAKE IT SOUND.
//
//  1. Only consult X-Forwarded-For when the SOCKET PEER is a configured trusted
//     proxy. The socket peer cannot be spoofed. On this deployment Caddy reaches
//     the container through a published port and so appears as the docker bridge
//     GATEWAY (172.18.0.1), while the Tor and I2P sidecars are containers on the
//     same network and appear as their own addresses (172.18.0.x, x != 1). The
//     gateway is therefore distinguishable from every sidecar, which is what
//     makes this safe. Trusted entries are EXACT IPs — CIDRs are deliberately
//     NOT accepted, because a range like 172.18.0.0/16 would silently trust the
//     sidecars and reopen the full bypass.
//
//  2. Take the LAST element of X-Forwarded-For, never the first. Caddy appends
//     its own view of its peer, so the last element is the only one Caddy wrote;
//     everything before it is client-supplied and untrusted. A client sending
//     "1.2.3.4" gets "1.2.3.4, <its real address>" and is keyed on its real
//     address.
//
// Anything unexpected falls back to the socket peer — i.e. to the pre-existing
// collapsed bucket. The fallback direction is deliberate: degrading to "too
// coarse" is safe, degrading to "attacker-chosen" is not.
//
// WHY THE KEY IS HASHED. Several of these limiters guard deliberately anonymous
// endpoints — dead drops and QR drops are unauthenticated precisely so that no
// sender identity exists anywhere, and blob redemption is unauthenticated so a
// fetch cannot be linked to an account. Keying those on a raw client address
// would hand the relay a stable per-client identifier it does not currently
// possess, which is a privacy regression traded for a rate-limiting fix. So the
// address is HMAC'd under a salt generated fresh at process start and never
// persisted: buckets still separate per client, but the limiter holds opaque
// values rather than client addresses, and the mapping dies with the process and
// cannot be correlated across restarts.
type clientKeyer struct {
	trusted map[string]struct{}
	salt    []byte
}

// newClientKeyer builds a keyer trusting exactly the given proxy addresses.
// An empty list disables X-Forwarded-For entirely, which is the pre-existing
// behaviour — so an unset or wrong config degrades to the collapsed bucket
// rather than to something a client controls.
func newClientKeyer(trustedIPs []string) *clientKeyer {
	trusted := make(map[string]struct{}, len(trustedIPs))
	for _, raw := range trustedIPs {
		entry := strings.TrimSpace(raw)
		// Exact IPs only. A CIDR (or any other junk) is dropped rather than
		// widened — see rule 1 above.
		if entry == "" || net.ParseIP(entry) == nil {
			continue
		}
		trusted[entry] = struct{}{}
	}

	salt := make([]byte, 32)
	if _, err := rand.Read(salt); err != nil {
		// crypto/rand failing is not recoverable into "carry on with a weak
		// salt": that would make keys predictable. Fail loudly at construction.
		panic("api: cannot generate client-key salt: " + err.Error())
	}
	return &clientKeyer{trusted: trusted, salt: salt}
}

// enabled reports whether trusted-proxy keying is active, for a startup log so
// an operator can tell a working config from one that silently degraded.
func (k *clientKeyer) enabled() bool { return len(k.trusted) > 0 }

// key returns the opaque per-client rate-limit key for this request.
//
// A nil keyer keys on the socket peer — i.e. the pre-existing collapsed-bucket
// behaviour. New() always builds one, so this only covers Handlers assembled as
// a struct literal (the package's own tests do that), and it fails in the safe
// direction: too coarse, never client-controlled.
func (k *clientKeyer) key(c *fiber.Ctx) string {
	if k == nil {
		return c.IP()
	}
	return k.keyFrom(c.IP(), c.Get(fiber.HeaderXForwardedFor))
}

// keyFrom is the whole decision, split out from the fiber.Ctx so it can be
// tested directly on its two inputs: the socket peer, which cannot be spoofed,
// and the raw header, which can.
func (k *clientKeyer) keyFrom(peer, forwarded string) string {
	source := peer
	if _, trusted := k.trusted[peer]; trusted {
		if last := lastForwardedFor(forwarded); last != "" {
			source = last
		}
	}

	mac := hmac.New(sha256.New, k.salt)
	mac.Write([]byte(source))
	return hex.EncodeToString(mac.Sum(nil))
}

// lastForwardedFor returns the final X-Forwarded-For element — the one the
// trusted proxy appended — or "" when the header is absent or its last element
// is not an IP. Only ever consulted for a trusted peer.
func lastForwardedFor(header string) string {
	header = strings.TrimSpace(header)
	if header == "" {
		return ""
	}
	parts := strings.Split(header, ",")
	last := strings.TrimSpace(parts[len(parts)-1])
	if net.ParseIP(last) == nil {
		return ""
	}
	return last
}
