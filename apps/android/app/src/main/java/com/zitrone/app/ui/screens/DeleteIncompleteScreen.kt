// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zitrone.app.ui.components.LemonSliceLogo
import com.zitrone.app.ui.theme.BackgroundPrimary
import com.zitrone.app.ui.theme.ErrorRed
import com.zitrone.app.ui.theme.Lemon
import com.zitrone.app.ui.theme.TextOnLemon
import com.zitrone.app.ui.theme.TextSecondary

/**
 * Terminal "finish deleting your account" screen (PR-D2c). Shown when the SERVER account is
 * already deleted but the local vault destroy could not verify its unlink (a surviving file /
 * the boot-time destroy-pending marker). The ONLY exit is a confirmed destroy — the caller's
 * [onRetry] routes to Onboarding once it verifies. Deliberately NO unlock affordance: a
 * half-deleted vault must never be offered a passphrase gate (it either cannot open, or opens
 * empty and would silently re-register).
 */
@Composable
fun DeleteIncompleteScreen(
    retrying: Boolean,
    showError: Boolean,
    onRetry: () -> Unit,
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
        LemonSliceLogo(size = 96.dp)
        Text(
            text = "Finishing deletion",
            style = MaterialTheme.typography.headlineLarge,
            color = Lemon,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            text = "Your account was deleted from the server, but this device " +
                "couldn't finish removing your local data yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(
            onClick = { if (!retrying) onRetry() },
            enabled = !retrying,
            colors = ButtonDefaults.buttonColors(
                containerColor = Lemon,
                contentColor = TextOnLemon,
            ),
        ) {
            if (retrying) {
                CircularProgressIndicator(
                    color = TextOnLemon,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Text("Finish deleting")
            }
        }
        if (showError) {
            Text(
                text = "Still couldn't remove the local data. Check storage and try again.",
                style = MaterialTheme.typography.bodySmall,
                color = ErrorRed,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
