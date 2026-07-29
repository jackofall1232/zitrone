// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// Package api hosts the REST endpoints: registration, sessions, prekeys, and
// account deletion. Requests are never body-logged; errors carry codes only.
package api

import (
	"crypto/ed25519"
	"encoding/base64"
	"strings"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"

	"github.com/zitrone/server/internal/auth"
	"github.com/zitrone/server/internal/config"
	"github.com/zitrone/server/internal/db"
	"github.com/zitrone/server/internal/ratelimit"
	"github.com/zitrone/server/internal/regpow"
	"github.com/zitrone/server/internal/relay"
)

type Handlers struct {
	store          *db.Store
	issuer         *auth.Issuer
	cfg            *config.Config
	registerLimit  *ratelimit.Limiter
	challengeLimit *ratelimit.Limiter
	prekeyLimit    *ratelimit.Limiter
	dropLimit      *ratelimit.Limiter
	blobLimit      *ratelimit.Limiter
	qrDropLimit    *ratelimit.Limiter
	// relayKey is non-nil only when this deployment is configured as a relay node.
	relayKey  *relay.KeyPair
	forwarder Forwarder
	// relayPeers is the allowlist of next-hop forward URLs this relay may forward
	// to. Empty means forwarding is refused (fail closed) — an SSRF guard.
	relayPeers map[string]bool
	// clientKey derives every IP-derived rate-limit key. See clientkey.go for why
	// it is not simply c.IP() and why trusting X-Forwarded-For naively would be
	// worse than the bug it fixes.
	clientKey *clientKeyer
}

// ClientKeyingEnabled reports whether trusted-proxy client keying is active, so
// startup can say which mode the limiters are running in.
func (h *Handlers) ClientKeyingEnabled() bool { return h.clientKey != nil && h.clientKey.enabled() }

func New(store *db.Store, issuer *auth.Issuer, cfg *config.Config) *Handlers {
	return &Handlers{
		store:  store,
		issuer: issuer,
		cfg:    cfg,
		// INTERIM: still a single global bucket (keyed on Caddy's socket address,
		// see Caddyfile — X-Forwarded-For is appended not overwritten, so trusting
		// it as-is would let clients spoof buckets). Widened from 5/hour, which was
		// a wall at ~2 users worldwide, while the real per-client fix (0.9.4
		// registration proof-of-work, this branch) is built. Not a fix.
		registerLimit: ratelimit.New(300, time.Hour, cfg.RateLimitEnabled),
		// Challenge issuance is regpow's second DoS layer (settled decision 3):
		// stage-1 verification is cheap, but nothing stops a client requesting
		// unlimited challenges, so issuance itself is capped independently. Same
		// shared-bucket caveat as registerLimit above — a separate brake, not a fix.
		challengeLimit: ratelimit.New(300, time.Hour, cfg.RateLimitEnabled),
		prekeyLimit:    ratelimit.New(50, time.Minute, cfg.RateLimitEnabled),
		// Dead drops are unauthenticated — proof-of-work is the main cost, but a
		// per-IP cap blunts abuse from a single source too.
		dropLimit: ratelimit.New(60, time.Minute, cfg.RateLimitEnabled),
		// Blob upload/redeem are per-IP capped like drops. Uploads are also
		// JWT-gated, but a per-IP cap still blunts a single source spraying large
		// ciphertexts; redemption is unauthenticated, so the cap is its only brake.
		blobLimit: ratelimit.New(60, time.Minute, cfg.RateLimitEnabled),
		// QR dead drops (lemon drops) are unauthenticated like plain drops:
		// proof-of-work gates deposit, but fetch and burn carry no PoW, so a per-IP
		// cap is their only brake against a single source probing or scraping.
		// KNOWN LIMIT (shared with dropLimit/blobLimit, not new here): behind the
		// Tor/I2P sidecars every anonymous client presents the sidecar's address,
		// so "per-IP" degrades to one global bucket for all overlay users of these
		// endpoints. Admission control that prices anonymous overlay traffic
		// per-request (PoW on fetch/burn, or similar) is a tracked follow-up.
		qrDropLimit: ratelimit.New(60, time.Minute, cfg.RateLimitEnabled),
		relayKey:    loadRelayKey(cfg),
		forwarder:   DefaultForwarder(),
		relayPeers:  relayPeerSet(cfg.RelayPeers),
		clientKey:   newClientKeyer(cfg.TrustedProxyIPs),
	}
}

func relayPeerSet(peers []string) map[string]bool {
	set := make(map[string]bool, len(peers))
	for _, p := range peers {
		set[p] = true
	}
	return set
}

// RelayEnabled reports whether this deployment serves /relay/forward.
func (h *Handlers) RelayEnabled() bool { return h.relayKey != nil }

// loadRelayKey decodes the relay Curve25519 keypair from config, or returns nil
// if this deployment is not acting as a relay node.
func loadRelayKey(cfg *config.Config) *relay.KeyPair {
	if cfg.RelayPrivateKey == "" || cfg.RelayPublicKey == "" {
		return nil
	}
	priv, err1 := base64.StdEncoding.DecodeString(cfg.RelayPrivateKey)
	pub, err2 := base64.StdEncoding.DecodeString(cfg.RelayPublicKey)
	if err1 != nil || err2 != nil || len(priv) != 32 || len(pub) != 32 {
		return nil
	}
	kp := &relay.KeyPair{}
	copy(kp.Private[:], priv)
	copy(kp.Public[:], pub)
	return kp
}

func errJSON(c *fiber.Ctx, status int, code string) error {
	return c.Status(status).JSON(fiber.Map{"error": code})
}

// djbType is libsignal's Curve25519 key-type tag (Curve.DJB_TYPE on Android/
// iOS clients). Clients sign a signed prekey's full libsignal serialize()
// form (this tag byte + the 32-byte public key) with their identity key, NOT
// the raw 32-byte wire form stored/transmitted here — a receiving peer's
// SessionBuilder reconstructs and verifies against that same serialized form
// (see apps/android SignalProtocolManager.kt, Run 13). So the server must
// reconstruct it the same way before verifying, or every valid signature
// will be rejected as invalid (see .l00prite/ledger.md Run 12/14).
const djbType = 0x05

// signedPrekeyMessage reconstructs the exact byte string a mobile client
// signed for a signed prekey, from the raw 32-byte wire form.
func signedPrekeyMessage(rawPublicKey []byte) []byte {
	return append([]byte{djbType}, rawPublicKey...)
}

// verifySignedPrekey accepts either of the two (scheme, message-framing)
// pairs actually in use across shipped clients — these are coupled per
// platform, not four independent combinations to try:
//   - Web/desktop (packages/crypto/src/keys.ts): a genuine Ed25519 keypair,
//     signing the raw 32-byte prekey public key directly.
//   - Android/iOS (libsignal-client): a Curve25519 identity key, XEdDSA-
//     signing the 33-byte libsignal serialize() form.
//
// See .l00prite/ledger.md Run 14: the server originally verified only the
// first (which is why web/desktop registration worked), then briefly only
// the second (which would have fixed mobile but broken the web/desktop path
// that was already working live) — this accepts both so neither platform's
// client needs to change. Both branches independently enforce their own
// key/signature length and validity checks, so this doesn't accept anything
// beyond "valid under exactly one of the two real schemes."
func verifySignedPrekey(identityKey, rawPublicKey, signature []byte) bool {
	if ed25519.Verify(identityKey, rawPublicKey, signature) {
		return true
	}
	return auth.VerifyXEdDSA(identityKey, signedPrekeyMessage(rawPublicKey), signature)
}

// ── registration ─────────────────────────────────────────────────────────────

type prekeyJSON struct {
	ID        int32  `json:"id"`
	PublicKey string `json:"public_key"`
	Signature string `json:"signature,omitempty"`
}

type registerRequest struct {
	IdentityKey    string       `json:"identity_key"`
	SignedPrekey   prekeyJSON   `json:"signed_prekey"`
	OneTimePrekeys []prekeyJSON `json:"one_time_prekeys"`
	// PoWProof is populated only when Config.RegistrationPoWEnabled is true.
	// Absent/zero-value on every 0.9.3-and-earlier client — Register ignores
	// this field entirely while the flag is off, so old clients are unaffected.
	PoWProof *powProofJSON `json:"pow_proof,omitempty"`
}

type powProofJSON struct {
	ChallengeToken string `json:"challenge_token"`
	HashcashNonce  string `json:"hashcash_nonce"` // base64, pow.NonceBytes bytes
	ArgonNonce     string `json:"argon_nonce"`    // base64, regpow.ArgonNonceBytes bytes
}

// RegistrationChallenge issues a stateless, HMAC-signed proof-of-work
// challenge for 0.9.4-beta registration. Served unconditionally — issuing a
// challenge is harmless even when RegistrationPoWEnabled is false, since
// Register never requires one until the flag is on — but rate-limited
// (challengeLimit) as regpow's second DoS layer.
//
// NOT WIRED TO ENFORCE: see Config.RegistrationPoWEnabled doc. This handler
// exists so it can be reviewed and load-tested ahead of the flag flip, not
// because 0.9.4 is shipping this session.
func (h *Handlers) RegistrationChallenge(c *fiber.Ctx) error {
	if !h.challengeLimit.Allow(h.clientKey.key(c)) {
		return errJSON(c, fiber.StatusTooManyRequests, "rate_limited")
	}
	if len(h.cfg.RegistrationChallengeSecret) == 0 {
		// Misconfiguration (flag on, secret unset) is caught at startup in
		// config.Load — reaching here with an empty secret would mean that
		// guard was bypassed, so fail closed rather than sign under no key.
		return errJSON(c, fiber.StatusServiceUnavailable, "pow_unavailable")
	}
	token, err := regpow.IssueChallenge(h.cfg.RegistrationChallengeSecret)
	if err != nil {
		return errJSON(c, fiber.StatusInternalServerError, "challenge_failed")
	}
	return c.JSON(fiber.Map{"challenge_token": token})
}

// verifyRegistrationProof reports whether req carries a valid PoW proof for
// identityKey, using the configured regpow.Params. Only called when
// h.cfg.RegistrationPoWEnabled is true.
func (h *Handlers) verifyRegistrationProof(req registerRequest, identityKey []byte) bool {
	if req.PoWProof == nil {
		return false
	}
	hashcashNonce, err1 := base64.StdEncoding.DecodeString(req.PoWProof.HashcashNonce)
	argonNonce, err2 := base64.StdEncoding.DecodeString(req.PoWProof.ArgonNonce)
	if err1 != nil || err2 != nil {
		return false
	}
	params := regpow.Params{
		Secret:               h.cfg.RegistrationChallengeSecret,
		ChallengeMaxAge:      time.Duration(h.cfg.RegistrationChallengeMaxAgeSec) * time.Second,
		HashcashDifficulty:   h.cfg.RegistrationHashcashDifficulty,
		Argon2TimeCost:       h.cfg.RegistrationArgon2TimeCost,
		Argon2MemoryKiB:      h.cfg.RegistrationArgon2MemoryKiB,
		Argon2DifficultyBits: h.cfg.RegistrationArgon2DifficultyBits,
	}
	return regpow.Verify(params, identityKey, regpow.Proof{
		ChallengeToken: req.PoWProof.ChallengeToken,
		HashcashNonce:  hashcashNonce,
		ArgonNonce:     argonNonce,
	})
}

// Register accepts PUBLIC key material only — there is nothing secret in this
// request by construction. The client address is used transiently for rate
// limiting and never stored or logged.
func (h *Handlers) Register(c *fiber.Ctx) error {
	if !h.registerLimit.Allow(h.clientKey.key(c)) {
		return errJSON(c, fiber.StatusTooManyRequests, "rate_limited")
	}
	var req registerRequest
	if err := c.BodyParser(&req); err != nil {
		return errJSON(c, fiber.StatusBadRequest, "bad_request")
	}
	identityKey, err := base64.StdEncoding.DecodeString(req.IdentityKey)
	if err != nil || len(identityKey) != ed25519.PublicKeySize {
		return errJSON(c, fiber.StatusBadRequest, "bad_identity_key")
	}
	// OFF BY DEFAULT (Config.RegistrationPoWEnabled) — see that field's doc and
	// the regpow package doc. Every 0.9.3-and-earlier client omits pow_proof
	// entirely, so flipping this on in production before 0.9.4 ships would
	// reject 100% of registrations, not just abuse.
	if h.cfg.RegistrationPoWEnabled && !h.verifyRegistrationProof(req, identityKey) {
		return errJSON(c, fiber.StatusForbidden, "pow_required")
	}
	spkPub, err1 := base64.StdEncoding.DecodeString(req.SignedPrekey.PublicKey)
	spkSig, err2 := base64.StdEncoding.DecodeString(req.SignedPrekey.Signature)
	if err1 != nil || err2 != nil || len(spkPub) != 32 {
		return errJSON(c, fiber.StatusBadRequest, "bad_signed_prekey")
	}
	// The server verifies the prekey signature too — a malformed bundle should
	// never be servable to other clients. See verifySignedPrekey: identity
	// keys and signing schemes differ by platform.
	if !verifySignedPrekey(identityKey, spkPub, spkSig) {
		return errJSON(c, fiber.StatusBadRequest, "bad_prekey_signature")
	}

	accountID := uuid.New()
	ctx := c.Context()
	if err := h.store.CreateAccount(ctx, accountID, identityKey); err != nil {
		return errJSON(c, fiber.StatusInternalServerError, "store_failed")
	}
	if err := h.store.UpsertSignedPrekey(ctx, accountID, req.SignedPrekey.ID, spkPub, spkSig); err != nil {
		return errJSON(c, fiber.StatusInternalServerError, "store_failed")
	}
	otps := make(map[int32][]byte, len(req.OneTimePrekeys))
	for _, p := range req.OneTimePrekeys {
		pub, err := base64.StdEncoding.DecodeString(p.PublicKey)
		if err != nil || len(pub) != 32 {
			return errJSON(c, fiber.StatusBadRequest, "bad_one_time_prekey")
		}
		otps[p.ID] = pub
	}
	if err := h.store.InsertOneTimePrekeys(ctx, accountID, otps, h.cfg.MaxPrekeysPerUser); err != nil {
		return errJSON(c, fiber.StatusInternalServerError, "store_failed")
	}
	return c.Status(fiber.StatusCreated).JSON(fiber.Map{"account_id": accountID})
}

// ── sessions ─────────────────────────────────────────────────────────────────

type sessionRequest struct {
	AccountID string `json:"account_id"`
	Timestamp int64  `json:"timestamp"`
	Signature string `json:"signature"`
}

// CreateSession authenticates by signature over a timestamped challenge —
// possession of the identity key IS the account. See auth.VerifyLogin: the
// signing scheme (plain Ed25519 or libsignal's XEdDSA) differs by platform,
// docs/SECURITY_MODEL.md "Identity-key signing scheme differs by platform".
func (h *Handlers) CreateSession(c *fiber.Ctx) error {
	var req sessionRequest
	if err := c.BodyParser(&req); err != nil {
		return errJSON(c, fiber.StatusBadRequest, "bad_request")
	}
	accountID, err := uuid.Parse(req.AccountID)
	if err != nil {
		return errJSON(c, fiber.StatusBadRequest, "bad_account")
	}
	signature, err := base64.StdEncoding.DecodeString(req.Signature)
	if err != nil {
		return errJSON(c, fiber.StatusBadRequest, "bad_signature")
	}
	identityKey, err := h.store.GetAccountIdentityKey(c.Context(), accountID)
	if err != nil {
		// Same response as a bad signature — no account enumeration.
		return errJSON(c, fiber.StatusUnauthorized, "unauthorized")
	}
	now := time.Now()
	if err := auth.VerifyLogin(identityKey, accountID, time.Unix(req.Timestamp, 0), signature, now); err != nil {
		return errJSON(c, fiber.StatusUnauthorized, "unauthorized")
	}
	return h.issueTokens(c, accountID, now)
}

type refreshRequest struct {
	RefreshToken string `json:"refresh_token"`
}

// RefreshSession rotates the refresh token on every use: the presented token
// is consumed atomically and a fresh pair is issued.
func (h *Handlers) RefreshSession(c *fiber.Ctx) error {
	var req refreshRequest
	if err := c.BodyParser(&req); err != nil || req.RefreshToken == "" {
		return errJSON(c, fiber.StatusBadRequest, "bad_request")
	}
	accountID, err := h.store.ConsumeRefreshToken(c.Context(), auth.HashRefreshToken(req.RefreshToken))
	if err != nil {
		return errJSON(c, fiber.StatusUnauthorized, "unauthorized")
	}
	return h.issueTokens(c, accountID, time.Now())
}

func (h *Handlers) issueTokens(c *fiber.Ctx, accountID uuid.UUID, now time.Time) error {
	access, err := h.issuer.IssueAccessToken(accountID, now)
	if err != nil {
		return errJSON(c, fiber.StatusInternalServerError, "token_failed")
	}
	refresh, refreshHash, err := auth.NewRefreshToken()
	if err != nil {
		return errJSON(c, fiber.StatusInternalServerError, "token_failed")
	}
	if err := h.store.InsertRefreshToken(c.Context(), refreshHash, accountID, now.Add(auth.RefreshTokenTTL)); err != nil {
		return errJSON(c, fiber.StatusInternalServerError, "store_failed")
	}
	return c.JSON(fiber.Map{
		"access_token":  access,
		"refresh_token": refresh,
		"expires_in":    int(auth.AccessTokenTTL.Seconds()),
	})
}

// DeleteSession invalidates all refresh tokens for the account (logout).
func (h *Handlers) DeleteSession(c *fiber.Ctx) error {
	accountID := AccountID(c)
	if err := h.store.DeleteAccountRefreshTokens(c.Context(), accountID); err != nil {
		return errJSON(c, fiber.StatusInternalServerError, "store_failed")
	}
	return c.SendStatus(fiber.StatusNoContent)
}

// ── prekeys ──────────────────────────────────────────────────────────────────

// GetPrekeyBundle serves a peer's bundle for X3DH, consuming one one-time
// prekey atomically (single-use by design).
func (h *Handlers) GetPrekeyBundle(c *fiber.Ctx) error {
	if !h.prekeyLimit.Allow(AccountID(c).String()) {
		return errJSON(c, fiber.StatusTooManyRequests, "rate_limited")
	}
	userID, err := uuid.Parse(c.Params("id"))
	if err != nil {
		return errJSON(c, fiber.StatusBadRequest, "bad_user")
	}
	ctx := c.Context()
	identityKey, err := h.store.GetAccountIdentityKey(ctx, userID)
	if err != nil {
		return errJSON(c, fiber.StatusNotFound, "not_found")
	}
	spk, err := h.store.GetLatestSignedPrekey(ctx, userID)
	if err != nil {
		return errJSON(c, fiber.StatusNotFound, "not_found")
	}
	bundle := fiber.Map{
		"user_id":      userID,
		"identity_key": base64.StdEncoding.EncodeToString(identityKey),
		"signed_prekey": fiber.Map{
			"id":         spk.ID,
			"public_key": base64.StdEncoding.EncodeToString(spk.PublicKey),
			"signature":  base64.StdEncoding.EncodeToString(spk.Signature),
		},
		"one_time_prekey": nil,
	}
	if otp, err := h.store.ConsumeOneTimePrekey(ctx, userID); err == nil {
		bundle["one_time_prekey"] = fiber.Map{
			"id":         otp.ID,
			"public_key": base64.StdEncoding.EncodeToString(otp.PublicKey),
		}
	}
	return c.JSON(bundle)
}

type prekeyUploadRequest struct {
	SignedPrekey   *prekeyJSON  `json:"signed_prekey,omitempty"`
	OneTimePrekeys []prekeyJSON `json:"one_time_prekeys"`
}

// UploadPrekeys replenishes one-time prekeys and optionally rotates the
// signed prekey (clients rotate every 7 days).
func (h *Handlers) UploadPrekeys(c *fiber.Ctx) error {
	accountID := AccountID(c)
	var req prekeyUploadRequest
	if err := c.BodyParser(&req); err != nil {
		return errJSON(c, fiber.StatusBadRequest, "bad_request")
	}
	ctx := c.Context()
	if req.SignedPrekey != nil {
		identityKey, err := h.store.GetAccountIdentityKey(ctx, accountID)
		if err != nil {
			return errJSON(c, fiber.StatusInternalServerError, "store_failed")
		}
		pub, err1 := base64.StdEncoding.DecodeString(req.SignedPrekey.PublicKey)
		sig, err2 := base64.StdEncoding.DecodeString(req.SignedPrekey.Signature)
		if err1 != nil || err2 != nil || len(pub) != 32 ||
			!verifySignedPrekey(identityKey, pub, sig) {
			return errJSON(c, fiber.StatusBadRequest, "bad_signed_prekey")
		}
		if err := h.store.UpsertSignedPrekey(ctx, accountID, req.SignedPrekey.ID, pub, sig); err != nil {
			return errJSON(c, fiber.StatusInternalServerError, "store_failed")
		}
	}
	otps := make(map[int32][]byte, len(req.OneTimePrekeys))
	for _, p := range req.OneTimePrekeys {
		pub, err := base64.StdEncoding.DecodeString(p.PublicKey)
		if err != nil || len(pub) != 32 {
			return errJSON(c, fiber.StatusBadRequest, "bad_one_time_prekey")
		}
		otps[p.ID] = pub
	}
	if err := h.store.InsertOneTimePrekeys(ctx, accountID, otps, h.cfg.MaxPrekeysPerUser); err != nil {
		return errJSON(c, fiber.StatusInternalServerError, "store_failed")
	}
	return c.SendStatus(fiber.StatusNoContent)
}

func (h *Handlers) PrekeyCount(c *fiber.Ctx) error {
	count, err := h.store.CountOneTimePrekeys(c.Context(), AccountID(c))
	if err != nil {
		return errJSON(c, fiber.StatusInternalServerError, "store_failed")
	}
	return c.JSON(fiber.Map{"count": count})
}

// ── account ──────────────────────────────────────────────────────────────────

// DeleteAccount is a full, irreversible purge of all data for the account.
func (h *Handlers) DeleteAccount(c *fiber.Ctx) error {
	if err := h.store.DeleteAccount(c.Context(), AccountID(c)); err != nil {
		return errJSON(c, fiber.StatusInternalServerError, "store_failed")
	}
	return c.SendStatus(fiber.StatusNoContent)
}

// ── auth middleware ──────────────────────────────────────────────────────────

const accountIDKey = "sub_account_id"

// RequireAuth validates the JWT on every authenticated endpoint.
func (h *Handlers) RequireAuth(c *fiber.Ctx) error {
	header := c.Get("Authorization")
	token, ok := strings.CutPrefix(header, "Bearer ")
	if !ok || token == "" {
		return errJSON(c, fiber.StatusUnauthorized, "unauthorized")
	}
	accountID, err := h.issuer.ValidateAccessToken(token)
	if err != nil {
		return errJSON(c, fiber.StatusUnauthorized, "unauthorized")
	}
	c.Locals(accountIDKey, accountID)
	return c.Next()
}

func AccountID(c *fiber.Ctx) uuid.UUID {
	return c.Locals(accountIDKey).(uuid.UUID)
}
