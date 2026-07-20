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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zitrone.app.ui.components.LemonSliceLogo
import com.zitrone.app.ui.theme.BackgroundPrimary
import com.zitrone.app.ui.theme.Lemon
import com.zitrone.app.ui.theme.LemonGlow30
import com.zitrone.app.ui.theme.TextOnLemon
import com.zitrone.app.ui.theme.TextPrimary
import com.zitrone.app.ui.theme.TextSecondary

/**
 * Shown when this phone opens a lemon-drop link (`https://zitrone.app/d/…`).
 *
 * This is a MARKETING moment, not an error — a scan that isn't for us is turned
 * into "you're part of the network, keep spreading it." So it is styled warm and
 * lemon-bright: the signature slice glowing on the dark ground, NO error red, NO
 * warning iconography. The copy is deliberately honest — it does not claim a
 * decryption was attempted (V1 attempts none), does not say the drop was meant
 * for a specific person we know, and does not call this scan "anonymous". It
 * states only the plain, true fact: a drop can be opened solely by the device it
 * was sealed for. See MainActivity for WHY an Android scan is never that device
 * in V1.
 */
@Composable
fun LemonDropAdvocacyScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundPrimary)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Slice on a soft lemon halo — the same warm glow the send button wears,
        // signalling "part of the network" rather than "something went wrong".
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(LemonGlow30, Color.Transparent),
                        ),
                        shape = CircleShape,
                    ),
            )
            LemonSliceLogo(size = 96.dp)
        }

        Text(
            text = "Sealed for someone else's device",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 40.dp),
        )
        Text(
            text = "Only the device a lemon drop was made for can open it. " +
                "The relay can't read it — and neither can this phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "You're holding a piece of the network. Pass it on.",
            style = MaterialTheme.typography.bodyLarge,
            color = Lemon,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )

        Button(
            onClick = onDismiss,
            colors = ButtonDefaults.buttonColors(
                containerColor = Lemon,
                contentColor = TextOnLemon,
            ),
            modifier = Modifier.padding(top = 40.dp),
        ) {
            Text("Got it")
        }
    }
}
