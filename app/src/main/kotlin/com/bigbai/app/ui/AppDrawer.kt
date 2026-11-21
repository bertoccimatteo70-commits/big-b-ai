package com.bigbai.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bigbai.app.R
import com.bigbai.core.model.Conversation

/**
 * Opens the main drawer from any screen. Screens only need the menu button in
 * their top bar; the drawer itself is hosted once, above the navigation graph,
 * so every destination shares the same navigation model.
 */
val LocalAppDrawer = staticCompositionLocalOf<() -> Unit> { {} }

private data class DrawerEntry(
    val label: String,
    val icon: ImageVector,
    val route: String,
)

private val entries = listOf(
    DrawerEntry("Cronologia", Icons.Outlined.History, Routes.HISTORY),
    DrawerEntry("Ricerca conversazioni", Icons.Outlined.Search, Routes.SEARCH),
    DrawerEntry("ITAGPT Code", Icons.Outlined.Code, Routes.CODE),
    DrawerEntry("Libreria modelli AI", Icons.Outlined.MenuBook, Routes.models(0)),
    DrawerEntry("Download modelli", Icons.Outlined.CloudDownload, Routes.models(1)),
    DrawerEntry("Modelli installati", Icons.Outlined.CheckCircle, Routes.models(2)),
    DrawerEntry("Diagnostica", Icons.Outlined.Speed, Routes.DIAGNOSTICS),
    DrawerEntry("Plugin e integrazioni", Icons.Outlined.Extension, Routes.PLUGINS),
    DrawerEntry("Impostazioni", Icons.Outlined.Settings, Routes.SETTINGS),
    DrawerEntry("Informazioni sull'app", Icons.Outlined.Info, Routes.ABOUT),
)

/**
 * Main navigation drawer, modelled on the simplicity of ChatGPT: one obvious
 * "new chat" action, a short list of destinations, then the most recent
 * conversations. It replaces the previous history-only drawer, which hid
 * models, code and settings behind overflow menus.
 */
@Composable
fun AppDrawer(
    currentBaseRoute: String,
    currentConversationId: Long,
    conversations: List<Conversation>,
    onNewChat: () -> Unit,
    onOpenConversation: (Long) -> Unit,
    onNavigate: (String) -> Unit,
    codeQuotaRemaining: Int? = null,
    codeQuotaLimit: Int = 5,
) {
    ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
        Column(modifier = Modifier.fillMaxHeight()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 12.dp, top = 20.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_itagpt_mark),
                        contentDescription = "ITAGPT",
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("ITAGPT", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Assistente locale",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            FilledTonalButton(
                onClick = onNewChat,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Nuova chat")
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(entries, key = { it.label }) { entry ->
                    val selected = currentBaseRoute == entry.route
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 1.dp)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { onNavigate(entry.route) }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            entry.icon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                entry.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            if (entry.route == Routes.CODE && codeQuotaRemaining != null) {
                                Text(
                                    "$codeQuotaRemaining di $codeQuotaLimit richieste oggi",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (conversations.isNotEmpty()) {
                    item {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                        Text(
                            "CONVERSAZIONI RECENTI",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 18.dp, top = 4.dp, bottom = 4.dp),
                        )
                    }
                    items(conversations.take(6), key = { "recent-${it.id}" }) { conversation ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 1.dp)
                                .background(
                                    if (conversation.id == currentConversationId) {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                                    },
                                    RoundedCornerShape(10.dp),
                                )
                                .clickable { onOpenConversation(conversation.id) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.History,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                conversation.title,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "ITAGPT ${com.bigbai.app.BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Elaborazione sul dispositivo",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
