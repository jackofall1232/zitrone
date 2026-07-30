// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package config

import (
	"fmt"
	"testing"
)

// setRequiredEnv sets the env vars Load() insists on so the tests can focus on
// the blob-store clamps.
func setRequiredEnv(t *testing.T) {
	t.Helper()
	t.Setenv("DATABASE_URL", "postgres://localhost/test")
	t.Setenv("JWT_PRIVATE_KEY_PATH", "/tmp/jwt.key")
	t.Setenv("JWT_PUBLIC_KEY_PATH", "/tmp/jwt.pub")
}

// A <=0 BLOB_TTL_HOURS would store already-expired rows so every recipient fetch
// 404s; a <=0 BLOB_MAX_BYTES would cap attachments at zero. Both must clamp to
// their secure defaults rather than be trusted.
func TestLoadClampsNonPositiveBlobValues(t *testing.T) {
	cases := []struct {
		name        string
		ttl         string
		maxBytes    string
		wantTTL     int
		wantMaxByte int
	}{
		{"zero", "0", "0", 96, 8 * 1024 * 1024},
		{"negative", "-5", "-1", 96, 8 * 1024 * 1024},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			setRequiredEnv(t)
			t.Setenv("BLOB_TTL_HOURS", tc.ttl)
			t.Setenv("BLOB_MAX_BYTES", tc.maxBytes)

			cfg, err := Load()
			if err != nil {
				t.Fatalf("Load() error = %v", err)
			}
			if cfg.BlobTTLHours != tc.wantTTL {
				t.Errorf("BlobTTLHours = %d, want %d", cfg.BlobTTLHours, tc.wantTTL)
			}
			if cfg.BlobMaxBytes != tc.wantMaxByte {
				t.Errorf("BlobMaxBytes = %d, want %d", cfg.BlobMaxBytes, tc.wantMaxByte)
			}
		})
	}
}

// A positive override ABOVE THE FLOOR passes through untouched — the clamps guard
// against misconfiguration, they never override an operator's real value.
//
// THIS TEST USED TO REQUIRE BLOB_TTL_HOURS=24 TO SURVIVE, which made the suite
// RATIFY a config that silently loses attachments (0.10.2 review round 1, both
// lenses; one called that ratification worse than the missing enforcement). 24 h
// is below the envelope TTL, so an envelope would be delivered after its blob had
// been reclaimed. The value here is now above the floor, and the clamp itself is
// covered by TestLoadEnforcesBlobTTLExceedsEnvelopeTTL.
func TestLoadKeepsPositiveBlobValues(t *testing.T) {
	setRequiredEnv(t)
	t.Setenv("BLOB_TTL_HOURS", "200")
	t.Setenv("BLOB_MAX_BYTES", "1234567")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if cfg.BlobTTLHours != 200 {
		t.Errorf("BlobTTLHours = %d, want 200 (above the floor, must not be clamped)", cfg.BlobTTLHours)
	}
	if cfg.BlobMaxBytes != 1234567 {
		t.Errorf("BlobMaxBytes = %d, want 1234567", cfg.BlobMaxBytes)
	}
}

// The blob TTL must exceed the undelivered-envelope TTL by the janitor+slack
// margin, ENFORCED rather than described (0.10.2 review round 1, both lenses).
// Otherwise a deployment delivers an envelope whose attachment is already gone:
// RedeemBlob 404s and the user sees "unavailable" on a message that arrived.
func TestLoadEnforcesBlobTTLExceedsEnvelopeTTL(t *testing.T) {
	for name, tc := range map[string]struct{ msgTTL, blobTTL, wantBlob string }{
		"blob below the floor is raised":        {"72", "24", "96"},
		"a longer envelope TTL raises the blob": {"120", "96", "144"},
		"equal TTLs are still raised":           {"72", "72", "96"},
	} {
		t.Run(name, func(t *testing.T) {
			setRequiredEnv(t)
			t.Setenv("MESSAGE_TTL_UNDELIVERED_HOURS", tc.msgTTL)
			t.Setenv("BLOB_TTL_HOURS", tc.blobTTL)

			cfg, err := Load()
			if err != nil {
				t.Fatalf("Load() error = %v", err)
			}
			want := 0
			if _, err := fmt.Sscanf(tc.wantBlob, "%d", &want); err != nil {
				t.Fatal(err)
			}
			if cfg.BlobTTLHours != want {
				t.Errorf("BlobTTLHours = %d, want %d", cfg.BlobTTLHours, want)
			}
			if cfg.BlobTTLHours <= cfg.MessageTTLUndeliveredHours {
				t.Errorf("blob TTL %d must exceed envelope TTL %d",
					cfg.BlobTTLHours, cfg.MessageTTLUndeliveredHours)
			}
		})
	}
}

// A <=0 MESSAGE_TTL_UNDELIVERED_HOURS silently dropped EVERY offline message once
// item 3 added the delivery cutoff (`created_at >= now() - ttl`): at 0 nothing
// matches and the janitor then deletes it, with the sender having seen success.
// Clamped, not documented (0.10.2 review round 1, P1).
func TestLoadClampsNonPositiveMessageTTL(t *testing.T) {
	for _, raw := range []string{"0", "-5"} {
		t.Run(raw, func(t *testing.T) {
			setRequiredEnv(t)
			t.Setenv("MESSAGE_TTL_UNDELIVERED_HOURS", raw)

			cfg, err := Load()
			if err != nil {
				t.Fatalf("Load() error = %v", err)
			}
			if cfg.MessageTTLUndeliveredHours != 72 {
				t.Errorf("MessageTTLUndeliveredHours = %d, want 72", cfg.MessageTTLUndeliveredHours)
			}
		})
	}
}

// CX23 item (b): the per-account send budget must default to 200, not 100.
// Since 0.10.0-beta a covered send is two frames on one authenticated socket
// (real envelope + decoy), so the budget is charged twice per real message;
// at 100 an account exhausted at ~50 real sends and cover traffic caused real
// sends to fail. The default is pinned here so it cannot revert silently.
func TestLoadSendRateDefaultsAboveCoverTrafficDoubling(t *testing.T) {
	setRequiredEnv(t)

	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.SendRatePerMinute != 200 {
		t.Fatalf("SendRatePerMinute = %d, want 200 — a covered send costs two permits", cfg.SendRatePerMinute)
	}
}

// It stays operator-tunable without a rebuild.
func TestLoadSendRateHonoursEnv(t *testing.T) {
	setRequiredEnv(t)
	t.Setenv("SEND_RATE_PER_MINUTE", "350")

	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.SendRatePerMinute != 350 {
		t.Fatalf("SendRatePerMinute = %d, want 350", cfg.SendRatePerMinute)
	}
}
