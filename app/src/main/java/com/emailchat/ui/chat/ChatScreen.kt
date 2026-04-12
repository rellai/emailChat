package com.emailchat.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.emailchat.data.Attachment
import com.emailchat.data.ChatDao
import com.emailchat.data.Message
import com.emailchat.viewmodel.ChatViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    id: String,
    msgs: List<Message>,
    vm: ChatViewModel,
    onBack: () -> Unit
) {
    var txt by remember { mutableStateOf("") }
    val ls = rememberLazyListState()
    // ✅ Явно указываем тип, чтобы компилятор не ругался
    val atts by vm.pendingAtt.collectAsState<List<Uri>>()
    val ctx = LocalContext.current

    val pickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) vm.addAtt(uris)
    }

    // Автопрокрутка вниз при новых сообщениях
    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) ls.animateScrollToItem(msgs.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = { Text(id.substringBefore("@")) }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Список сообщений
            LazyColumn(
                state = ls,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(msgs, key = { it.id }) { msg ->
                    MessageBubble(m = msg, vm = vm)
                }
            }

            // Превью прикреплённых файлов
            if (atts.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(atts, key = { it.toString() }) { uri ->
                        Box(modifier = Modifier.size(60.dp)) {
                            AsyncImage(
                                model = uri,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.small),
                                contentScale = ContentScale.Crop
                            )
                            IconButton(
                                onClick = { vm.rmAtt(uri) },
                                modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Удалить",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // Поле ввода + кнопка отправки
            val canSend = txt.isNotBlank() || atts.isNotEmpty()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { pickLauncher.launch("*/*") }) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Прикрепить")
                }
                OutlinedTextField(
                    value = txt,
                    onValueChange = { txt = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Сообщение...") },
                    maxLines = 4,
                    shape = MaterialTheme.shapes.large
                )
                Spacer(modifier = Modifier.width(8.dp))
                // ✅ В M3 FAB нет параметра enabled, управляем цветом и логикой внутри onClick
                FloatingActionButton(
                    onClick = {
                        if (canSend) {
                            vm.send(id, txt, atts)
                            txt = ""
                        }
                    },
                    containerColor = if (canSend) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (canSend) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                }
            }
        }
    }
}

@Composable
fun MessageBubble(m: Message, vm: ChatViewModel) {
    // Получаем вложения из БД через Flow
    val dbAttachments by vm.getAttachmentsForMessage(m.id).collectAsState(initial = emptyList())
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (m.isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (m.isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (m.text.isNotBlank()) {
                    Text(text = m.text, style = MaterialTheme.typography.bodyMedium)
                }
                
                // Отображение вложений
                if (dbAttachments.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(dbAttachments) { att ->
                            Box(modifier = Modifier.size(80.dp)) {
                                if (att.isImage) {
                                    val imageFile = File(att.localPath)
                                    val imageModel = if (imageFile.exists()) {
                                        imageFile as Any
                                    } else {
                                        att.localPath
                                    }
                                    
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(imageModel)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = att.fileName,
                                        modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.small),
                                        contentScale = ContentScale.Crop
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Image,
                                        contentDescription = "Image",
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .size(20.dp)
                                            .background(MaterialTheme.colorScheme.surface, CircleShape),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    // Не изображения - показываем иконку файла
                                    Icon(
                                        imageVector = Icons.Default.AttachFile,
                                        contentDescription = att.fileName,
                                        modifier = Modifier.fillMaxSize(),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}