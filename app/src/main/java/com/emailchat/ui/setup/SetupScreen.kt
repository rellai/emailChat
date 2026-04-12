package com.emailchat.ui.setup

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.emailchat.data.EmailAccount
import com.emailchat.data.PreferencesKeys
import com.emailchat.data.dataStore
import com.emailchat.viewmodel.SetupState
import com.emailchat.viewmodel.SetupViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    vm: SetupViewModel,
    onDone: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }

    // Серверные настройки (с автозаполнением)
    var imapHost by remember { mutableStateOf("") }
    var imapPort by remember { mutableStateOf("993") }
    var imapSSL by remember { mutableStateOf(true) }
    var smtpHost by remember { mutableStateOf("") }
    var smtpPort by remember { mutableStateOf("465") }
    var smtpSSL by remember { mutableStateOf(true) }

    var showAdvanced by remember { mutableStateOf(false) }
    val state by vm.state.collectAsState<SetupState>()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Автозаполнение при вводе email
    LaunchedEffect(email) {
        if (email.contains("@")) {
            val domain = email.substringAfter("@")
            imapHost = detectImapHost(domain)
            smtpHost = detectSmtpHost(domain)
        }
    }

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { Text("Настройка аккаунта") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Default.Close, contentDescription = "Отмена")
                    }
                }
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text("Email") }, modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            OutlinedTextField(
                value = displayName, onValueChange = { displayName = it },
                label = { Text("Ваше имя (отправитель)") }, modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Пароль приложения") }, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), singleLine = true
            )

            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) "▲ Скрыть настройки сервера" else "▼ Настройки сервера (вручную)")
            }

            if (showAdvanced) {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("IMAP (входящие)", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = imapHost, onValueChange = { imapHost = it },
                            label = { Text("Сервер") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = imapPort, onValueChange = { imapPort = it },
                                label = { Text("Порт") }, modifier = Modifier.weight(1f), singleLine = true
                            )
                            CheckboxRow(label = "SSL", checked = imapSSL, onCheckedChange = { imapSSL = it })
                        }

                        Spacer(Modifier.height(8.dp))
                        Text("SMTP (исходящие)", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = smtpHost, onValueChange = { smtpHost = it },
                            label = { Text("Сервер") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = smtpPort, onValueChange = { smtpPort = it },
                                label = { Text("Порт") }, modifier = Modifier.weight(1f), singleLine = true
                            )
                            CheckboxRow(label = "SSL", checked = smtpSSL, onCheckedChange = { smtpSSL = it })
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        scope.launch {
                            val success = vm.testConnection(
                                email, password, displayName,
                                imapHost, imapPort.toIntOrNull() ?: 993, imapSSL,
                                smtpHost, smtpPort.toIntOrNull() ?: 465, smtpSSL
                            )
                            if (success) {
                                Toast.makeText(context, "✅ Подключение успешно!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "❌ Ошибка подключения", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) { Text("Проверить") }

                Button(
                    onClick = {
                        scope.launch {
                            val saved = vm.saveAccount(
                                email, password, displayName,
                                imapHost, imapPort.toIntOrNull() ?: 993, imapSSL,
                                smtpHost, smtpPort.toIntOrNull() ?: 465, smtpSSL
                            )
                            if (saved) {
                                Toast.makeText(context, "✅ Аккаунт сохранён", Toast.LENGTH_SHORT).show()
                                onDone()
                            } else {
                                Toast.makeText(context, "❌ Не удалось сохранить", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = email.contains("@") && password.isNotBlank() && imapHost.isNotBlank() && smtpHost.isNotBlank()
                ) { Text("Войти") }

                Button(
                    onClick = { onDone() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) { Text("Отменить") }
            }

            if (state is SetupState.Loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            if (state is SetupState.Error) {
                Text(
                    text = "⚠️ ${(state as SetupState.Error).msg}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }

            Text(
                text = "💡 Для Gmail/Yandex/Mail.ru используйте «Пароль приложения», а не основной пароль.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun CheckboxRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// Автоопределение серверов (можно расширять)
fun detectImapHost(domain: String) = when (domain.lowercase()) {
    "gmail.com" -> "imap.gmail.com"
    "yandex.ru", "yandex.com", "ya.ru", "yandex.by", "yandex.kz", "yandex.ua" -> "imap.yandex.ru"
    "mail.ru", "bk.ru", "inbox.ru", "list.ru" -> "imap.mail.ru"
    "outlook.com", "hotmail.com", "live.com", "msn.com" -> "outlook.office365.com"
    "yahoo.com" -> "imap.mail.yahoo.com"
    else -> "imap.$domain"
}

fun detectSmtpHost(domain: String) = when (domain.lowercase()) {
    "gmail.com" -> "smtp.gmail.com"
    "yandex.ru", "yandex.com", "ya.ru", "yandex.by", "yandex.kz", "yandex.ua" -> "smtp.yandex.ru"
    "mail.ru", "bk.ru", "inbox.ru", "list.ru" -> "smtp.mail.ru"
    "outlook.com", "hotmail.com", "live.com", "msn.com" -> "smtp.office365.com"
    "yahoo.com" -> "smtp.mail.yahoo.com"
    else -> "smtp.$domain"
}