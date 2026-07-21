// Zitrone — Copyright (C) 2026 Zitrone contributors
// Licensed under the GNU Affero General Public License v3.0 or later.
// See the LICENSE file in the repository root for full license text.
// SPDX-License-Identifier: AGPL-3.0-only

package com.zitrone.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zitrone.app.data.Conversation
import com.zitrone.app.ui.theme.ErrorRed
import com.zitrone.app.ui.theme.Lemon
import com.zitrone.app.ui.theme.LemonZest
import com.zitrone.app.ui.theme.MonoFamily
import com.zitrone.app.ui.theme.PillShape
import com.zitrone.app.ui.theme.TextMuted
import com.zitrone.app.ui.theme.TextOnLemon
import com.zitrone.app.ui.theme.TextPrimary
import com.zitrone.app.ui.theme.TextSecondary
import com.zitrone.app.ui.theme.TypeScale
import com.zitrone.app.ui.theme.VerifiedGreen
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ListTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/**
 * Conversation list (design_system.components.conversation_list_item).
 * Privacy rule baked in: the preview line is ALWAYS the literal text
 * "Encrypted message" — message content never appears outside an open chat.
 *
 * Long-press opens the irreversible "Delete contact" confirmation: full
 * cryptographic session teardown (X3DH/Double Ratchet + identity record).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationList(
    conversations: List<Conversation>,
    onOpenConversation: (Conversation) -> Unit,
    onDeleteContact: (Conversation) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }

    pendingDelete?.let { target ->
        DeleteContactConfirmDialog(
            displayName = target.displayName,
            onConfirm = {
                pendingDelete = null
                onDeleteContact(target)
            },
            onDismiss = { pendingDelete = null },
        )
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(conversations, key = { it.id }) { conversation ->
            ConversationListItem(
                conversation = conversation,
                onClick = { onOpenConversation(conversation) },
                onLongClick = { pendingDelete = conversation },
            )
        }
    }
}

/**
 * Explicit confirmation before irreversible contact crypto teardown. Past
 * messages from this contact become permanently undecryptable once session
 * keys are destroyed; history rows themselves are not removed (decoupled).
 */
@Composable
fun DeleteContactConfirmDialog(
    displayName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Delete contact?",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
            )
        },
        text = {
            Text(
                text = "This permanently destroys the encryption session with " +
                    "“$displayName”, including their identity key and all ratchet " +
                    "keys. You will not be able to decrypt past messages from them. " +
                    "Re-adding them later starts a completely fresh key exchange.\n\n" +
                    "This cannot be undone.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = "Delete contact",
                    color = ErrorRed,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Cancel",
                    color = Lemon,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        containerColor = com.zitrone.app.ui.theme.BackgroundElevated,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationListItem(
    conversation: Conversation,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LemonAvatar(
            name = conversation.displayName,
            verified = conversation.verified,
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = conversation.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
            )
            // ALWAYS "Encrypted message" — never a content preview.
            Text(
                text = "Encrypted message",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                maxLines = 1,
            )
        }

        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (conversation.lastActivityMs > 0L) {
                Text(
                    text = ListTimeFormatter.format(Instant.ofEpochMilli(conversation.lastActivityMs)),
                    fontFamily = MonoFamily,
                    fontSize = TypeScale.Xs,
                    color = TextMuted,
                )
            }
            if (conversation.unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .background(Lemon, PillShape)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = conversation.unreadCount.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = TextOnLemon,
                    )
                }
            }
        }
    }
}

/** 44dp circular avatar on a lemon gradient; verified ring in green. */
@Composable
fun LemonAvatar(
    name: String,
    verified: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .then(
                if (verified) {
                    Modifier.border(2.dp, VerifiedGreen, CircleShape)
                } else {
                    Modifier
                },
            )
            .padding(if (verified) 3.dp else 0.dp)
            .background(
                brush = Brush.linearGradient(listOf(Lemon, LemonZest)),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = TextOnLemon,
        )
    }
}
