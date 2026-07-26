// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zitrone.app.ui.theme.ErrorRed
import com.zitrone.app.ui.theme.Lemon
import com.zitrone.app.ui.theme.TextPrimary
import com.zitrone.app.ui.theme.TextSecondary

/**
 * PUCKER BURN PASSWORD SETUP (0.9.3 Unit S) — set or silently replace the duress credential.
 *
 * **The warning is the feature, not decoration.** A user who misunderstands this dialog can destroy
 * their own vault permanently, or believe they have protection they do not have. The four points
 * below are required by spec §5 and each closes a specific misunderstanding:
 *
 *  1. **It cannot be recovered or checked.** There is no "is it set?" readback anywhere in the app —
 *     by design, because that readback would itself be the discoverable artifact that proves a duress
 *     credential exists. The consequence for the user is that forgetting it is unrecoverable and they
 *     cannot verify it later, so they must be told before they commit.
 *  2. **Anyone who learns it can erase everything, forever.** It is not a second password to the same
 *     data; it is a destruction trigger. The copy says "everything Zitrone holds on this device"
 *     rather than "this vault" (review round 1, both reviewers): the burn is a device-local fresh
 *     install covering every slot in the shared image, prefs, keystore and caches — a user reading
 *     "this vault" could reasonably think only the session they are in is at risk. It deliberately
 *     does NOT count vaults or hint how many exist, which would break plausible deniability.
 *  3. **A burn CONSUMES the credential.** After a burn the device is a fresh install with no burn
 *     password at all, so it must be set again. Users otherwise assume protection persists.
 *  4. **Setting it again silently replaces it.** There is no confirmation that an old one existed,
 *     because the app genuinely cannot tell.
 *
 * **Actively acknowledged**, not merely displayed: the confirm button stays disabled until the box is
 * ticked. A dialog that can be dismissed with a reflexive tap has not obtained understanding, and
 * this is the one irreversible control in the app.
 *
 * The entry that opens this dialog is PERMANENT and identical whether or not a credential is set
 * (invariant P1) — a row that appeared or changed once armed would leak the very fact it protects.
 */
@Composable
fun BurnSetupDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    busy: Boolean,
    error: String?,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(false) }

    val mismatch = confirm.isNotEmpty() && password != confirm
    // Deliberately permissive on strength: a duress credential the user cannot recall under
    // pressure is worse than a short one, and there is no lockout to brute-force past. The only
    // hard requirements are non-empty and typed twice identically.
    val ready = password.isNotEmpty() && password == confirm && acknowledged && !busy

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Pucker Burn password", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    "Entering this password at the lock screen erases everything Zitrone holds on " +
                        "this device and returns the app to a fresh install. There is no " +
                        "confirmation step and no undo.",
                    color = TextPrimary,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(12.dp))
                WarningPoint("It can never be recovered or checked. The app cannot tell you whether one is set.")
                WarningPoint("Anyone who learns it can erase everything Zitrone holds here, forever.")
                WarningPoint("Using it consumes it — after a burn you must set a new one.")
                WarningPoint("Setting one again silently replaces the old one.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Burn password") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("Type it again") },
                    singleLine = true,
                    enabled = !busy,
                    isError = mismatch,
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (mismatch) {
                    Spacer(Modifier.height(4.dp))
                    Text("These don't match.", color = ErrorRed, fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = acknowledged,
                        onCheckedChange = { acknowledged = it },
                        enabled = !busy,
                        colors = CheckboxDefaults.colors(checkedColor = Lemon),
                    )
                    Text(
                        "I understand this cannot be recovered and will erase everything Zitrone " +
                            "holds on this device.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = ErrorRed, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = ready) {
                if (busy) {
                    CircularProgressIndicator(Modifier.height(16.dp), color = Lemon)
                } else {
                    Text("Set burn password", color = if (ready) ErrorRed else TextSecondary)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel", color = TextSecondary) }
        },
    )
}

@Composable
private fun WarningPoint(text: String) {
    Row(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.Start) {
        Text("•  ", color = ErrorRed, fontSize = 13.sp)
        Text(text, color = TextSecondary, fontSize = 13.sp)
    }
}
