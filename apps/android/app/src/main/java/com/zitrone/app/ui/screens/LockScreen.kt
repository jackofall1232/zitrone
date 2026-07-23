// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zitrone.app.ui.components.LemonSliceLogo
import com.zitrone.app.ui.theme.BackgroundPrimary
import com.zitrone.app.ui.theme.ErrorRed
import com.zitrone.app.ui.theme.Lemon
import com.zitrone.app.ui.theme.TextOnLemon
import com.zitrone.app.ui.theme.TextSecondary

/**
 * Vault unlock gate (PR-D2c). The PASSPHRASE field is always present — it is the
 * posture-independent factor and the biometric fallback. The biometric affordance
 * appears ONLY when [onBiometricUnlock] is non-null (a wrap is enabled and the platform
 * can authenticate BIOMETRIC_STRONG right now). The error line stays and shows the router's
 * uniform failure / re-enroll note; no field ever names a slot.
 */
@Composable
fun LockScreen(
    onUnlockWithPassphrase: (String) -> Unit,
    onBiometricUnlock: (() -> Unit)?,
    errorMessage: String?,
    unlocking: Boolean,
    modifier: Modifier = Modifier,
) {
    var passphrase by remember { mutableStateOf("") }

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
            text = "Locked",
            style = MaterialTheme.typography.headlineLarge,
            color = Lemon,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            text = "Your keys stay sealed until you unlock.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Passphrase") },
            singleLine = true,
            enabled = !unlocking,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { if (!unlocking && passphrase.isNotEmpty()) onUnlockWithPassphrase(passphrase) },
            enabled = !unlocking && passphrase.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Lemon,
                contentColor = TextOnLemon,
            ),
            modifier = Modifier.padding(top = 16.dp),
        ) {
            if (unlocking) {
                CircularProgressIndicator(
                    color = TextOnLemon,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Text("Unlock")
            }
        }
        if (onBiometricUnlock != null) {
            OutlinedButton(
                onClick = { if (!unlocking) onBiometricUnlock() },
                enabled = !unlocking,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text("Use biometrics", color = Lemon)
            }
        }
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = ErrorRed,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
