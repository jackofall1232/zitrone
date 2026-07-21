// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.ui.screens

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.zitrone.app.data.Conversation
import com.zitrone.app.data.parseQrDropLink
import com.zitrone.app.ui.components.ConversationList
import com.zitrone.app.ui.components.LemonSliceLogo
import com.zitrone.app.ui.components.SecureCaptureActivity
import com.zitrone.app.ui.components.fingerprintWatermark
import com.zitrone.app.ui.theme.BackgroundElevated
import com.zitrone.app.ui.theme.BackgroundPrimary
import com.zitrone.app.ui.theme.BorderColor
import com.zitrone.app.ui.theme.BurnOrange
import com.zitrone.app.ui.theme.Lemon
import com.zitrone.app.ui.theme.PillShape
import com.zitrone.app.ui.theme.TextMuted
import com.zitrone.app.ui.theme.TextOnLemon
import com.zitrone.app.ui.theme.TextPrimary
import com.zitrone.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * Chat list (design_system.screens.chat_list): wordmark header with lemon-drop
 * scan + settings actions, lemon-bordered pill search, conversation list, and
 * the lemon FAB with its glow shadow. Root warning (security/RootDetection)
 * appears as a dismissible banner — warn, never block.
 *
 * The header QR control opens an in-app ZXing scanner (same stack as add-contact)
 * scoped to lemon-drop sticker URLs only. A valid scan hands the qr_id to
 * [onOpenLemonDrop] — the same entry as App Links / VIEW intents — so resolve
 * logic is never duplicated. Non-matching codes surface a snackbar (not silent).
 */
@Composable
fun ChatListScreen(
    conversations: List<Conversation>,
    rootWarningVisible: Boolean,
    onDismissRootWarning: () -> Unit,
    onOpenConversation: (Conversation) -> Unit,
    onDeleteContact: (Conversation) -> Unit,
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
    /**
     * A successfully scanned lemon-drop qr_id (verbatim path segment). Caller
     * routes into the existing AppContainer open/resolve path.
     */
    onOpenLemonDrop: (qrId: String) -> Unit,
    modifier: Modifier = Modifier,
    /** This device's own identity fingerprint for the security-paper watermark. */
    identityFingerprint: String? = null,
) {
    var query by remember { mutableStateOf("") }
    val filtered = if (query.isBlank()) {
        conversations
    } else {
        conversations.filter { it.displayName.contains(query, ignoreCase = true) }
    }

    val context = LocalContext.current
    val hasCamera = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()

    // ZXing owns the camera + runtime permission flow (same as AddContactScreen).
    // Results are untrusted text: only [parseQrDropLink] may promote them.
    val lemonDropScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@rememberLauncherForActivityResult
        val qrId = parseQrDropLink(raw)
        if (qrId != null) {
            onOpenLemonDrop(qrId)
        } else {
            snackbarScope.launch {
                snackbarHostState.showSnackbar(
                    message = "That isn’t a Zitrone lemon-drop code.",
                )
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundPrimary)
            // Security paper behind the conversation list.
            .fingerprintWatermark(identityFingerprint),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header — wordmark left, scan + settings right.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "ZITRONE",
                    style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 0.18.em),
                    color = Lemon,
                    modifier = Modifier.weight(1f),
                )
                if (hasCamera) {
                    IconButton(
                        onClick = {
                            lemonDropScanLauncher.launch(
                                ScanOptions()
                                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    .setBeepEnabled(false)
                                    .setOrientationLocked(false)
                                    .setPrompt("Point at a lemon-drop sticker")
                                    .setCaptureActivity(SecureCaptureActivity::class.java),
                            )
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.QrCodeScanner,
                            contentDescription = "Scan lemon drop",
                            tint = Lemon,
                        )
                    }
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Settings",
                        tint = Lemon,
                    )
                }
            }

            if (rootWarningVisible) {
                RootWarningBanner(onDismiss = onDismissRootWarning)
            }

            // Lemon-bordered pill search bar.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .heightIn(min = 40.dp)
                    .background(BackgroundElevated, PillShape)
                    .border(1.dp, if (query.isBlank()) BorderColor else Lemon, PillShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
                Box(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Search",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                        cursorBrush = SolidColor(Lemon),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (filtered.isEmpty()) {
                EmptyChatListState(modifier = Modifier.weight(1f))
            } else {
                ConversationList(
                    conversations = filtered,
                    onOpenConversation = onOpenConversation,
                    onDeleteContact = onDeleteContact,
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 8.dp),
                )
            }
        }

        // Compose FAB — lemon circle, lemon-glow shadow, bottom right.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
                .shadow(elevation = 12.dp, shape = CircleShape, ambientColor = Lemon, spotColor = Lemon)
                .size(56.dp)
                .background(Lemon, CircleShape)
                .clickable(onClick = onNewChat),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "New encrypted chat",
                tint = TextOnLemon,
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp),
        )
    }
}

@Composable
private fun EmptyChatListState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LemonSliceLogo(size = 72.dp, tint = TextMuted)
        Text(
            text = "Nothing here. That's the point.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "Tap the lemon to start an encrypted chat.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun RootWarningBanner(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(BackgroundElevated, MaterialTheme.shapes.medium)
            .border(1.dp, BurnOrange, MaterialTheme.shapes.medium)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "This device looks rooted",
                style = MaterialTheme.typography.titleSmall,
                color = BurnOrange,
            )
            Text(
                text = "Root access can expose decrypted messages in memory. " +
                    "Zitrone still works — but the device itself is the weak link.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Dismiss warning",
                tint = TextSecondary,
            )
        }
    }
}
