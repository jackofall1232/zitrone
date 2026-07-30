// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

// Package janitor purges envelopes, drops, blobs, QR drops and refresh tokens that
// outlived their TTL.
package janitor

import (
	"context"
	"log"
	"time"

	"github.com/zitrone/server/internal/db"
)

// Run purges expired undelivered envelopes every 10 minutes until ctx is done.
// The log line carries a row count only — never content or identities.
func Run(ctx context.Context, store *db.Store, ttl time.Duration) {
	ticker := time.NewTicker(10 * time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			purged, err := store.PurgeExpiredEnvelopes(ctx, time.Now().Add(-ttl))
			if err != nil {
				log.Printf("janitor: purge failed: %v", err)
			} else if purged > 0 {
				log.Printf("janitor: purged %d undelivered envelopes past TTL", purged)
			}
			// Expired refresh tokens (0.10.2 item 1). Nothing else reclaims them: they
			// are deleted on use or at account teardown, so one that expires unused
			// used to sit forever. Unbounded growth, once per session.
			tokens, err := store.PurgeExpiredRefreshTokens(ctx, time.Now())
			if err != nil {
				log.Printf("janitor: refresh-token purge failed: %v", err)
			} else if tokens > 0 {
				log.Printf("janitor: purged %d expired refresh tokens", tokens)
			}
			// Dead drops are destroyed at their TTL whether collected or not.
			drops, err := store.PurgeExpiredDrops(ctx, time.Now())
			if err != nil {
				log.Printf("janitor: drop purge failed: %v", err)
			} else if drops > 0 {
				log.Printf("janitor: purged %d expired dead drops", drops)
			}
			// Attachment blobs are destroyed at their TTL whether redeemed or not.
			blobs, err := store.PurgeExpiredBlobs(ctx, time.Now())
			if err != nil {
				log.Printf("janitor: blob purge failed: %v", err)
			} else if blobs > 0 {
				log.Printf("janitor: purged %d expired attachment blobs", blobs)
			}
			// QR dead drops (lemon drops) are crypto-shredded at their TTL whether
			// claimed or not. The shred keeps each qr_id as a permanent tombstone
			// so a dead sticker can never be re-armed (maintainer decision 1a).
			qrDrops, err := store.PurgeExpiredQrDrops(ctx, time.Now())
			if err != nil {
				log.Printf("janitor: qr-drop purge failed: %v", err)
			} else if qrDrops > 0 {
				log.Printf("janitor: shredded %d expired QR dead drops (ids tombstoned)", qrDrops)
			}
		}
	}
}
