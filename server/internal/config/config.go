// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package config

import (
	"encoding/base64"
	"fmt"
	"os"
	"strconv"
	"strings"

	"github.com/zitrone/server/internal/regpow"
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
	TorEnabled                 bool
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
	// BlobTTLHours is the unfetched-blob fallback TTL. Successful redemption
	// deletes the blob immediately (fetch-and-burn); this only bounds the max
	// lifetime of ciphertext that is never redeemed. Default 1 week (168h).
	BlobTTLHours    int
	RelayPrivateKey string   // base64 Curve25519 private key; enables /relay/forward when set
	RelayPublicKey  string   // base64 Curve25519 public key advertised in the relay registry
	RelayPeers      []string // allowlist of next-hop forward URLs; forwarding fails closed otherwise

	// 0.9.4-beta registration proof-of-work (see internal/regpow). Enforcing
	// this rejects every client that doesn't send a proof, including the
	// shipped 0.9.3 build — a breaking change. MUST stay false in production
	// until the 0.9.4 client has replaced 0.9.3 in the field. NOT deployed
	// this session regardless of this flag's value; written for review only.
	RegistrationPoWEnabled bool
	// RegistrationChallengeSecret is the raw HMAC key regpow signs challenge
	// tokens with. Required (and validated at startup) only when
	// RegistrationPoWEnabled is true — fails closed rather than issuing
	// challenges under an empty key.
	RegistrationChallengeSecret    []byte
	RegistrationChallengeMaxAgeSec int
	// Hashcash pre-stage difficulty (leading zero bits, fed to pow.Verify).
	// DROP_POW_DIFFICULTY's shipped default (20, ~1M hashes) is a real
	// calibration point for what this codebase's clients tolerate — the spec
	// brief says start measurement from there, not from zero.
	RegistrationHashcashDifficulty int
	// TODO(pow-calibration, unmeasured): the three fields below need
	// Revvl-6x-in-battery-saver client cost measurement and relay-side
	// verification-cost-at-volume measurement before this ships. Defaulted
	// from regpow's own TODO'd constants — see that package doc, do not treat
	// either set of defaults as decided.
	RegistrationArgon2DifficultyBits int
	RegistrationArgon2TimeCost       uint32
	RegistrationArgon2MemoryKiB      uint32
}

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
		BlobTTLHours:    envInt("BLOB_TTL_HOURS", 168),
		RelayPrivateKey: os.Getenv("RELAY_PRIVATE_KEY"),
		RelayPublicKey:  os.Getenv("RELAY_PUBLIC_KEY"),
		RelayPeers:      splitCSV(os.Getenv("RELAY_PEERS")),

		RegistrationPoWEnabled:           envBool("REGISTRATION_POW_ENABLED", false),
		RegistrationChallengeMaxAgeSec:   envInt("REGISTRATION_CHALLENGE_MAX_AGE_SEC", 300),
		RegistrationHashcashDifficulty:   envInt("REGISTRATION_HASHCASH_DIFFICULTY", 20),
		RegistrationArgon2DifficultyBits: envInt("REGISTRATION_ARGON2_DIFFICULTY_BITS", 8),
		RegistrationArgon2TimeCost:       uint32(envInt("REGISTRATION_ARGON2_TIME_COST", regpow.Argon2TimeCostDefault)),
		RegistrationArgon2MemoryKiB:      uint32(envInt("REGISTRATION_ARGON2_MEMORY_KIB", regpow.Argon2MemoryKiBDefault)),
	}
	if secret := os.Getenv("REGISTRATION_CHALLENGE_SECRET"); secret != "" {
		decoded, err := base64.StdEncoding.DecodeString(secret)
		if err != nil {
			return nil, fmt.Errorf("REGISTRATION_CHALLENGE_SECRET: invalid base64: %w", err)
		}
		cfg.RegistrationChallengeSecret = decoded
	}
	// Fail closed: enforcement without a real HMAC key would either crash on
	// first use or (worse, if guarded loosely elsewhere) sign every challenge
	// under an empty key. Never silently downgrade to enforcement-off here —
	// that would mask a misconfiguration as a policy decision.
	if cfg.RegistrationPoWEnabled && len(cfg.RegistrationChallengeSecret) < 32 {
		return nil, fmt.Errorf("REGISTRATION_CHALLENGE_SECRET must be a base64 key of at least 32 bytes when REGISTRATION_POW_ENABLED is true")
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
		cfg.BlobTTLHours = 168
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
