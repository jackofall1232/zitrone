// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zitrone.app.data.LemonDropVeil
import com.zitrone.app.ui.components.LemonSliceLogo
import com.zitrone.app.ui.components.fingerprintWatermark
import com.zitrone.app.ui.theme.BackgroundPrimary
import com.zitrone.app.ui.theme.Lemon
import com.zitrone.app.ui.theme.LemonGlow30
import com.zitrone.app.ui.theme.TextOnLemon
import com.zitrone.app.ui.theme.TextPrimary
import com.zitrone.app.ui.theme.TextSecondary

/**
 * Shown when a scanned lemon drop decrypted for THIS device but the biometric
 * gate has not passed yet. Renders NO message content — that is the veil's
 * standing security invariant (see [LemonDropVeil]): this screen may sit in
 * front of the lock only because it holds nothing secret. "Not now" leaves the
 * drop unburned on the relay — a later re-scan opens it again.
 */
@Composable
fun LemonDropUnlockScreen(
    onUnlock: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** This device's own identity fingerprint for the security-paper watermark. */
    identityFingerprint: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundPrimary)
            .fingerprintWatermark(identityFingerprint)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Halo()
        Text(
            text = "This one is for you",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 40.dp),
        )
        Text(
            text = "A lemon drop sealed for exactly this device. " +
                "Unlock to open it — reading it destroys the relay's only copy.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Button(
            onClick = onUnlock,
            colors = ButtonDefaults.buttonColors(
                containerColor = Lemon,
                contentColor = TextOnLemon,
            ),
            modifier = Modifier.padding(top = 40.dp),
        ) {
            Text("Unlock to open")
        }
        OutlinedButton(onClick = onDismiss, modifier = Modifier.padding(top = 12.dp)) {
            Text("Not now")
        }
    }
}

/**
 * The delivered lemon drop: one-shot display, post-unlock only. Reaching this
 * screen already fired the delivery side effects — the relay's copy is being
 * burned — so what is on screen is the message's last copy. One-way by
 * design: there is deliberately no reply affordance and no conversation is
 * created; the copy says so instead of pretending otherwise.
 */
@Composable
fun LemonDropDeliveredScreen(
    veil: LemonDropVeil.Delivered,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** This device's own identity fingerprint for the security-paper watermark. */
    identityFingerprint: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundPrimary)
            // The reveal shows see-once plaintext — the highest-value surface for
            // the deterrent, so the paper is applied here above all.
            .fingerprintWatermark(identityFingerprint)
            .padding(horizontal = 32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Halo()
        Text(
            text = "A lemon drop, opened",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 32.dp),
        )
        Text(
            // The label is either a trusted contact's display name (pinned key
            // matched) or the sender key's fingerprint (relay-confirmed only).
            text = if (veil.senderVerified) {
                "From ${veil.senderLabel}"
            } else {
                "From an unverified sender · key ${veil.senderLabel}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (veil.senderVerified) TextSecondary else Lemon,
            fontFamily = if (veil.senderVerified) null else FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Surface(
            color = Color.Transparent,
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, LemonGlow30),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
        ) {
            Text(
                text = veil.text,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                modifier = Modifier.padding(20.dp),
            )
        }
        Text(
            // Honest about the best-effort burn: if the network was reachable
            // the relay's copy is being shredded now, but an offline open can't
            // reach it — the drop's TTL is the guaranteed backstop either way.
            // Never assert a destruction that may not have happened.
            text = "You've opened your one-time copy. Zitrone is asking the relay to " +
                "shred its copy now; if that didn't get through, the drop still " +
                "clears itself at its expiry. Either way it won't reappear here. " +
                "Lemon drops are one-way — to talk with this person, add them as a " +
                "contact the ordinary way.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp),
        )
        Button(
            onClick = onDismiss,
            colors = ButtonDefaults.buttonColors(
                containerColor = Lemon,
                contentColor = TextOnLemon,
            ),
            modifier = Modifier.padding(top = 32.dp, bottom = 40.dp),
        ) {
            Text("Done")
        }
    }
}

/** The advocacy screen's lemon-slice halo, shared by both veil variants. */
@Composable
private fun Halo() {
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(180.dp)
                .background(
                    brush = Brush.radialGradient(colors = listOf(LemonGlow30, Color.Transparent)),
                    shape = CircleShape,
                ),
        )
        LemonSliceLogo(size = 96.dp)
    }
}
