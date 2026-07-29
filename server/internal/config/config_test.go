// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package config

import "testing"

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
		{"zero", "0", "0", 168, 8 * 1024 * 1024},
		{"negative", "-5", "-1", 168, 8 * 1024 * 1024},
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

// A valid positive override must pass through untouched — the clamp only guards
// against misconfiguration, it never overrides an operator's real value.
func TestLoadKeepsPositiveBlobValues(t *testing.T) {
	setRequiredEnv(t)
	t.Setenv("BLOB_TTL_HOURS", "24")
	t.Setenv("BLOB_MAX_BYTES", "1234567")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if cfg.BlobTTLHours != 24 {
		t.Errorf("BlobTTLHours = %d, want 24", cfg.BlobTTLHours)
	}
	if cfg.BlobMaxBytes != 1234567 {
		t.Errorf("BlobMaxBytes = %d, want 1234567", cfg.BlobMaxBytes)
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
