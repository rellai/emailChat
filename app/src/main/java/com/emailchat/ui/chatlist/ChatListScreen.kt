package com.emailchat.ui.chatlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emailchat.data.Conversation
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    list: List<Conversation>,
    onSel: (String) -> Unit,
    onSync: () -> Unit,
    onNewChat: () -> Unit,      // ✅ Добавлено: создать новый чат
    onSettings: () -> Unit      // ✅ Добавлено: открыть настройки
) {
    var showNewChatDialog by remember { mutableStateOf(false) }
    var newEmail by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Чаты") },
                actions = {
                    IconButton(onClick = onSync) {
                        Icon(Icons.Default.Refresh, "Синхронизировать")
                    }
                    // ✅ Меню настроек
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, "Настройки аккаунта")
                    }
                }
            )
        },
        // ✅ Плавающая кнопка «+»
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewChatDialog = true }) {
                Icon(Icons.Default.Add, "Новый чат")
            }
        }
    ) { padding ->
        if (list.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Email,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Нет чатов", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Нажмите + или введите email контакта,\nчтобы начать общение",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(onClick = { showNewChatDialog = true }) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Начать новый чат")
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(list, key = { it.id }) { conv ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSel(conv.id) }
                            .padding(8.dp, 4.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(conv.contactName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    conv.lastMessage,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(conv.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                            if (conv.unreadCount > 0) {
                                Badge { Text(conv.unreadCount.toString()) }
                            }
                        }
                    }
                }
            }
        }
    }

    // ✅ Диалог создания нового чата
    if (showNewChatDialog) {
        AlertDialog(
            onDismissRequest = { showNewChatDialog = false },
            title = { Text("Новый чат") },
            text = {
                Column {
                    Text("Введите email собеседника:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newEmail,
                        onValueChange = { newEmail = it },
                        placeholder = { Text("user@example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newEmail.contains("@")) {
                            onSel(newEmail.trim())
                            showNewChatDialog = false
                            newEmail = ""
                        }
                    },
                    enabled = newEmail.contains("@")
                ) { Text("Создать") }
            },
            dismissButton = {
                TextButton(onClick = { showNewChatDialog = false }) { Text("Отмена") }
            }
        )
    }
}