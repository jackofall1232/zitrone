// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
)

type Config struct {
	DatabaseURL                string
	JWTPrivateKeyPath          string
	JWTPublicKeyPath           string
	ServerPort                 int
	TLSCertPath                string
	TLSKeyPath                 string
	MaxPrekeysPerUser          int
	MessageTTLUndeliveredHours int
	RateLimitEnabled           bool
	// SendRatePerMinute is the per-account WebSocket send budget. The default
	// assumes cover traffic: since 0.10.0-beta a covered send is TWO frames on
	// the same authenticated socket (the real envelope plus its decoy), so the
	// budget is charged twice per real message and the old 100 exhausted an
	// account at ~50 real sends. 200 keeps the nominal 100 real sends a minute
	// reachable with pairing on. Cover frames are not exempted: distinguishing
	// them would mean either trusting a client-set flag (which would let a
	// client mark everything cover and escape the budget) or recording which
	// account is whose synthetic peer — a stored linkage the relay must not
	// hold.
	SendRatePerMinute int
	// TrustedProxyIPs are the EXACT socket addresses whose X-Forwarded-For the
	// relay will believe when deriving rate-limit keys. Empty (the default)
	// disables the header entirely and keys on the socket peer, which is the
	// pre-existing behaviour — so an unset or stale value degrades to one shared
	// bucket rather than to a bucket the client chooses. CIDRs are rejected on
	// purpose: a range covering the Tor/I2P sidecars would let overlay clients
	// spoof the header and escape the limiter. See api/clientkey.go.
	TrustedProxyIPs []string
	TorEnabled      bool
	// OnionSiteDir, when set and TorEnabled, is served as a static no-JS mirror
	// site (APK download + checksums + self-hosting instructions) at the root of
	// the hidden service. Empty disables it — clearnet deployments serve no site.
	OnionSiteDir string
	// v1.5 — Tor-first + dead drops + multi-hop relay.
	OnionAddress string // legacy single .onion address (still parsed; superseded by the three below)
	// Three separate hidden services share one box, distinguished by Host header
	// (see server/cmd/server/onion.go). Public + secret serve the mirror; relay
	// serves the API only. Empty values fail closed — they never match a Host.
	PublicOnionAddress string // published in docs + sublemonable.com — serves the static APK mirror
	SecretOnionAddress string // unpublished, word-of-mouth — same mirror content, separate address
	RelayOnionAddress  string // unpublished, baked into app binary — serves the API relay only
	// I2P skeleton — parsed but unused in v1.5 (see docs/TOR_ARCHITECTURE.md §7).
	I2PEnabled        bool   // future master switch for live I2P traffic
	I2PEepsiteDest    string // future: base64 I2P destination
	DropTTLHours      int    // dead-drop lifetime, collected or not
	DropPoWDifficulty int    // leading zero bits required on deposit proof-of-work
	// Blind blob store (attachments). BlobMaxBytes caps the *plaintext-equivalent*
	// attachment size; the server enforces a slightly larger ciphertext cap that
	// accounts for bucket padding + AEAD overhead (see api.BlobEffectiveCap).
	BlobMaxBytes int // max attachment plaintext bytes (ciphertext cap adds slack)
	// ⚠️ INVARIANT — BlobTTLHours >= MessageTTLUndeliveredHours + janitor period +
	// max upload→send delay. DO NOT "tidy" this to equal the envelope TTL: that
	// introduces a bug rather than closing waste (0.10.2 item 2).
	//
	// Three reasons, all structural. (1) THE ANCHORS DIFFER: a blob's expires_at is
	// set at UPLOAD (api/blobs.go), while envelope TTL is anchored at SEND
	// (created_at) — and upload strictly precedes send by design ("blob to the
	// blind store FIRST"), with flushSendRatchet's suspending retry backoff sitting
	// in the gap. At equal TTLs the blob therefore always dies first, by
	// (send − upload). (2) ENFORCEMENT IS ASYMMETRIC: RedeemBlob requires
	// expires_at > now(), so a blob is unfetchable the instant it expires, whereas
	// PendingEnvelopes only became TTL-filtered in 0.10.2 and the janitor sweeps on
	// a 10-minute period. (3) The net window is (send − upload) + janitor lag, and a
	// recipient arriving inside it gets a message bubble with a permanently dead
	// attachment — a 404 surfaced as "unavailable".
	//
	// 96 h (was 168 h) keeps a comfortable margin over the 72 h envelope TTL while
	// cutting the worst-case retention of an 8 MB blob by 43%.
	//
	// BlobTTLHours is the unfetched-blob fallback TTL. Successful redemption
	// deletes the blob immediately (fetch-and-burn); this only bounds the max
	// lifetime of ciphertext that is never redeemed. Default 1 week (168h).
	BlobTTLHours    int
	RelayPrivateKey string   // base64 Curve25519 private key; enables /relay/forward when set
	RelayPublicKey  string   // base64 Curve25519 public key advertised in the relay registry
	RelayPeers      []string // allowlist of next-hop forward URLs; forwarding fails closed otherwise
}

// blobTTLMarginHours is how far a blob's TTL must exceed the undelivered-envelope
// TTL: enough for the janitor's 10-minute period plus slack for the upload→send
// gap. See the enforcement in [Load].
const blobTTLMarginHours = 24

func Load() (*Config, error) {
	cfg := &Config{
		DatabaseURL:                os.Getenv("DATABASE_URL"),
		JWTPrivateKeyPath:          os.Getenv("JWT_PRIVATE_KEY_PATH"),
		JWTPublicKeyPath:           os.Getenv("JWT_PUBLIC_KEY_PATH"),
		ServerPort:                 envInt("SERVER_PORT", 8443),
		TLSCertPath:                os.Getenv("TLS_CERT_PATH"),
		TLSKeyPath:                 os.Getenv("TLS_KEY_PATH"),
		MaxPrekeysPerUser:          envInt("MAX_PREKEYS_PER_USER", 100),
		MessageTTLUndeliveredHours: envInt("MESSAGE_TTL_UNDELIVERED_HOURS", 72),
		RateLimitEnabled:           envBool("RATE_LIMIT_ENABLED", true),
		SendRatePerMinute:          envInt("SEND_RATE_PER_MINUTE", 200),
		TrustedProxyIPs:            splitCSV(os.Getenv("TRUSTED_PROXY_IPS")),
		TorEnabled:                 envBool("TOR_ENABLED", false),
		OnionSiteDir:               os.Getenv("ONION_SITE_DIR"),
		OnionAddress:               os.Getenv("ONION_ADDRESS"),
		PublicOnionAddress:         os.Getenv("PUBLIC_ONION_ADDRESS"),
		SecretOnionAddress:         os.Getenv("SECRET_ONION_ADDRESS"),
		RelayOnionAddress:          os.Getenv("RELAY_ONION_ADDRESS"),
		I2PEnabled:                 envBool("I2P_ENABLED", false),
		I2PEepsiteDest:             os.Getenv("I2P_EEPSITE_DEST"),
		DropTTLHours:               envInt("DROP_TTL_HOURS", 72),
		DropPoWDifficulty:          envInt("DROP_POW_DIFFICULTY", 20),
		BlobMaxBytes:               envInt("BLOB_MAX_BYTES", 8*1024*1024),
		// 1-week fallback for unfetched attachment blobs (fetch-and-burn deletes
		// on successful redeem; this only bounds never-collected ciphertext).
		BlobTTLHours:    envInt("BLOB_TTL_HOURS", 96),
		RelayPrivateKey: os.Getenv("RELAY_PRIVATE_KEY"),
		RelayPublicKey:  os.Getenv("RELAY_PUBLIC_KEY"),
		RelayPeers:      splitCSV(os.Getenv("RELAY_PEERS")),
	}
	// Backward compatibility: a pre-v1.5 deployment set only ONION_ADDRESS. Treat
	// it as the public mirror address so single-onion deployments keep serving the
	// mirror without a config change. PUBLIC_ONION_ADDRESS wins when both are set.
	if cfg.PublicOnionAddress == "" {
		cfg.PublicOnionAddress = cfg.OnionAddress
	}
	// A negative proof-of-work difficulty would make every nonce "valid" — never
	// trust a misconfigured value; fall back to the secure default.
	if cfg.DropPoWDifficulty < 0 {
		cfg.DropPoWDifficulty = 20
	}
	// A <=0 BLOB_TTL_HOURS makes every deposit store an already-expired row: the
	// upload returns 201 but every recipient fetch then deterministically 404s
	// (RedeemBlob's `expires_at > now()` guard matches nothing) — a silent,
	// trust-breaking attachment failure. Clamp to the secure default (1 week).
	if cfg.BlobTTLHours <= 0 {
		cfg.BlobTTLHours = 96
	}
	// A <=0 MESSAGE_TTL_UNDELIVERED_HOURS SILENTLY DROPS EVERY OFFLINE MESSAGE
	// (0.10.2 review round 1, P1 — a defect introduced by item 3's own delivery
	// cutoff). PendingEnvelopes selects `created_at >= now() - ttl`, so at 0 a
	// recipient reconnecting the instant after an envelope was stored matches
	// nothing and the next janitor pass deletes it; a negative value excludes even
	// future-skewed rows. Nothing surfaces — the sender saw a successful send.
	if cfg.MessageTTLUndeliveredHours <= 0 {
		cfg.MessageTTLUndeliveredHours = 72
	}
	// ⚠️ THE BLOB/ENVELOPE TTL RELATIONSHIP IS NOW ENFORCED, NOT ASSERTED
	// (0.10.2 review round 1, found by BOTH lenses). It previously lived only in
	// the comment on BlobTTLHours, so a deployment could set
	// MESSAGE_TTL_UNDELIVERED_HOURS=120 against the 96 h blob default — or set
	// BLOB_TTL_HOURS=24, which `TestLoadKeepsPositiveBlobValues` explicitly
	// blessed — and the relay would then deliver an envelope whose attachment had
	// already been reclaimed: a live message with a permanently dead attachment,
	// 404 on redeem, surfaced to the user as "unavailable".
	//
	// The margin covers the janitor's 10-minute sweep period plus slack for the
	// upload→send gap (upload is anchored at deposit, the envelope's TTL at send).
	// **It does NOT make that gap bounded** — a frozen Android process can resume a
	// send continuation much later, which is a declared residual and needs a
	// client-side fix, not a bigger number here. What this closes is the
	// cross-setting hole, which is reachable by configuration alone.
	if minBlob := cfg.MessageTTLUndeliveredHours + blobTTLMarginHours; cfg.BlobTTLHours < minBlob {
		cfg.BlobTTLHours = minBlob
	}
	// A <=0 BLOB_MAX_BYTES would cap every attachment at zero bytes (or worse,
	// underflow downstream size math) — never trust it; fall back to the default.
	if cfg.BlobMaxBytes <= 0 {
		cfg.BlobMaxBytes = 8 * 1024 * 1024
	}
	if cfg.DatabaseURL == "" {
		return nil, fmt.Errorf("DATABASE_URL is required")
	}
	if cfg.JWTPrivateKeyPath == "" || cfg.JWTPublicKeyPath == "" {
		return nil, fmt.Errorf("JWT_PRIVATE_KEY_PATH and JWT_PUBLIC_KEY_PATH are required")
	}
	return cfg, nil
}

// splitCSV parses a comma-separated env value into a trimmed, non-empty list.
func splitCSV(v string) []string {
	if v == "" {
		return nil
	}
	parts := strings.Split(v, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		if t := strings.TrimSpace(p); t != "" {
			out = append(out, t)
		}
	}
	return out
}

func envInt(key string, fallback int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return fallback
}

func envBool(key string, fallback bool) bool {
	if v := os.Getenv(key); v != "" {
		if b, err := strconv.ParseBool(v); err == nil {
			return b
		}
	}
	return fallback
}
