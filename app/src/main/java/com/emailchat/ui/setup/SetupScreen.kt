package com.emailchat.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.emailchat.viewmodel.SetupState
import com.emailchat.viewmodel.SetupViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    vm: SetupViewModel,
    onDone: () -> Unit,
    onCancel: () -> Unit // ✅ Добавлен колбэк отмены
) {
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    
    val savedAccount by vm.savedAccount.collectAsState(initial = null)

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }

    var imapHost by remember { mutableStateOf("") }
    var imapPort by remember { mutableStateOf("993") }
    var imapSSL by remember { mutableStateOf(true) }

    var smtpHost by remember { mutableStateOf("") }
    var smtpPort by remember { mutableStateOf("465") }
    var smtpSSL by remember { mutableStateOf(true) }

    var isImapManual by remember { mutableStateOf(false) }
    var isSmtpManual by remember { mutableStateOf(false) }

    LaunchedEffect(savedAccount) {
        savedAccount?.let { acc ->
            email = acc.email
            password = acc.password
            displayName = acc.displayName
            imapHost = acc.imapHost
            imapPort = acc.imapPort.toString()
            imapSSL = acc.imapUseSSL
            smtpHost = acc.smtpHost
            smtpPort = acc.smtpPort.toString()
            smtpSSL = acc.smtpUseSSL
            if (acc.email.isNotBlank()) {
                isImapManual = true
                isSmtpManual = true
            }
        }
    }

    if (state is SetupState.Success) {
        LaunchedEffect(Unit) { onDone() }
    }

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { Text("Настройка почты") },
                navigationIcon = {
                    // ✅ Кнопка отмены (назад) в тулбаре
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Отмена")
                    }
                }
            ) 
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Основные настройки", style = MaterialTheme.typography.titleMedium)
            
            OutlinedTextField(
                value = email,
                onValueChange = { newEmail ->
                    email = newEmail
                    if (newEmail.contains("@")) {
                        val domain = newEmail.substringAfter("@").lowercase()
                        if (domain.isNotBlank()) {
                            if (!isImapManual) {
                                imapHost = when (domain) {
                                    "gmail.com" -> "imap.gmail.com"
                                    "yandex.ru" -> "imap.yandex.ru"
                                    "mail.ru", "list.ru", "bk.ru", "inbox.ru" -> "imap.mail.ru"
                                    "outlook.com", "hotmail.com" -> "outlook.office365.com"
                                    else -> "imap.$domain"
                                }
                            }
                            if (!isSmtpManual) {
                                smtpHost = when (domain) {
                                    "gmail.com" -> "smtp.gmail.com"
                                    "yandex.ru" -> "smtp.yandex.ru"
                                    "mail.ru", "list.ru", "bk.ru", "inbox.ru" -> "smtp.mail.ru"
                                    "outlook.com", "hotmail.com" -> "smtp.office365.com"
                                    else -> "smtp.$domain"
                                }
                            }
                        }
                    }
                },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Пароль приложения") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                helperText = { Text("Используйте пароль приложения, а не основной пароль.") }
            )

            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Ваше имя (для отображения)") },
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Сервер входящей почты (IMAP)", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = imapHost,
                onValueChange = { imapHost = it; isImapManual = true },
                label = { Text("IMAP Хост") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = imapPort,
                    onValueChange = { imapPort = it },
                    label = { Text("Порт") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text("SSL")
                Checkbox(checked = imapSSL, onCheckedChange = { imapSSL = it })
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Сервер исходящей почты (SMTP)", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = smtpHost,
                onValueChange = { smtpHost = it; isSmtpManual = true },
                label = { Text("SMTP Хост") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = smtpPort,
                    onValueChange = { smtpPort = it },
                    label = { Text("Порт") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text("SSL")
                Checkbox(checked = smtpSSL, onCheckedChange = { smtpSSL = it })
            }

            if (state is SetupState.Error) {
                Text(
                    text = (state as SetupState.Error).msg,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ✅ Кнопка текстовой отмены
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    enabled = state !is SetupState.Loading
                ) {
                    Text("Отмена")
                }

                Button(
                    onClick = {
                        scope.launch {
                            val ok = vm.testConnection(
                                email, password, displayName,
                                imapHost, imapPort.toIntOrNull() ?: 993, imapSSL,
                                smtpHost, smtpPort.toIntOrNull() ?: 465, smtpSSL
                            )
                            if (ok) {
                                vm.saveAccount(
                                    email, password, displayName,
                                    imapHost, imapPort.toIntOrNull() ?: 993, imapSSL,
                                    smtpHost, smtpPort.toIntOrNull() ?: 465, smtpSSL
                                )
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = state !is SetupState.Loading
                ) {
                    if (state is SetupState.Loading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Text("Сохранить")
                    }
                }
            }
        }
    }
}

@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    helperText: @Composable (() -> Unit)? = null
) {
    Column(modifier = modifier) {
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            singleLine = true
        )
        if (helperText != null) {
            Box(modifier = Modifier.padding(start = 16.dp, top = 4.dp)) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                    ProvideTextStyle(MaterialTheme.typography.bodySmall) {
                        helperText()
                    }
                }
            }
        }
    }
}
