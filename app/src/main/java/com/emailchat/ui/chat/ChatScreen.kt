package com.emailchat.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.emailchat.R
import com.emailchat.data.Attachment
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
    val atts by vm.pendingAtt.collectAsState<List<Uri>>()
    val isDark = isSystemInDarkTheme()

    // Цветовая палитра TG
    val panelColor = if (isDark) Color(0xFF17212B) else Color.White
    val inputBgColor = if (isDark) Color(0xFF242F3D) else Color(0xFFF1F1F1)

    val pickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) vm.addAtt(uris)
    }

    val reversedMsgs = remember(msgs) { msgs.sortedByDescending { it.timestamp } }

    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) {
            ls.animateScrollToItem(0)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = { Text(id.substringBefore("@")) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = panelColor.copy(alpha = 0.95f),
                    titleContentColor = if (isDark) Color.White else Color.Black,
                    navigationIconContentColor = if (isDark) Color.White else Color.Black
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ✅ Фоновое изображение
            Image(
                painter = painterResource(
                    id = if (isDark) R.drawable.background_hd_dark else R.drawable.background_hd
                ),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            Column(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = ls,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp), // Уменьшен отступ
                    reverseLayout = true 
                ) {
                    itemsIndexed(reversedMsgs, key = { _, m -> m.id }) { index, msg ->
                        val isLastInGroup = if (index > 0) {
                            reversedMsgs[index - 1].isOutgoing != msg.isOutgoing
                        } else true

                        MessageBubble(
                            m = msg, 
                            vm = vm, 
                            showTail = isLastInGroup,
                            isDark = isDark
                        )
                    }
                }

                Surface(
                    color = panelColor,
                    tonalElevation = 4.dp,
                    shadowElevation = 16.dp
                ) {
                    Column {
                        if (atts.isNotEmpty()) {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(atts) { _, uri ->
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
                                                null, 
                                                tint = Color.Red, 
                                                modifier = Modifier.background(Color.White, CircleShape)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { pickLauncher.launch("*/*") }) {
                                Icon(Icons.Default.AttachFile, null, tint = Color.Gray)
                            }
                            OutlinedTextField(
                                value = txt,
                                onValueChange = { txt = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Сообщение", color = Color.Gray) },
                                maxLines = 5,
                                shape = RoundedCornerShape(24.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedContainerColor = inputBgColor,
                                    focusedContainerColor = inputBgColor,
                                    unfocusedTextColor = if (isDark) Color.White else Color.Black,
                                    focusedTextColor = if (isDark) Color.White else Color.Black
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val canSend = txt.isNotBlank() || atts.isNotEmpty()
                            FloatingActionButton(
                                onClick = { if (canSend) { vm.send(id, txt, atts); txt = "" } },
                                shape = CircleShape,
                                containerColor = if (canSend) Color(0xFF517DA2) else Color(0xFFE1E1E1).copy(alpha = if (isDark) 0.2f else 1f),
                                contentColor = Color.White,
                                modifier = Modifier.size(48.dp),
                                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, null)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MessageBubble(m: Message, vm: ChatViewModel, showTail: Boolean, isDark: Boolean) {
    val dbAttachments by vm.getAttachmentsForMessage(m.id).collectAsState(initial = emptyList())
    
    val outColor = if (isDark) Color(0xFF3E6147) else Color(0xFFEFFDDE)
    val inColor = if (isDark) Color(0xFF242F3D) else Color.White
    val textColor = if (isDark) Color.White else Color.Black
    val timeColor = if (m.isOutgoing) {
        if (isDark) Color(0xFF80B679) else Color(0xFF5BA053)
    } else {
        Color.Gray
    }

    val bubbleShape = if (m.isOutgoing) {
        OutgoingBubbleShape(cornerRadius = 32f, showTail = showTail)
    } else {
        IncomingBubbleShape(cornerRadius = 32f, showTail = showTail)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(
            start = if (m.isOutgoing) 64.dp else 0.dp,
            end = if (m.isOutgoing) 0.dp else 64.dp,
            bottom = if (showTail) 4.dp else 1.dp // Уменьшен отступ
        ),
        horizontalArrangement = if (m.isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (m.isOutgoing) outColor else inColor,
            shape = bubbleShape,
            shadowElevation = 3.dp
        ) {
            Column(modifier = Modifier.padding(vertical = 5.dp, horizontal = 10.dp)) { // Уменьшен внутренний отступ
                if (m.text.isNotBlank()) {
                    Text(
                        text = m.text, 
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor
                    )
                }
                
                if (dbAttachments.isNotEmpty()) {
                    Column(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        dbAttachments.forEach { att ->
                            Box(modifier = Modifier.width(240.dp).height(180.dp).clip(RoundedCornerShape(16.dp))) {
                                if (att.isImage) {
                                    val imageFile = File(att.localPath)
                                    AsyncImage(
                                        model = if (imageFile.exists()) imageFile else Uri.parse(att.localPath),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize().background(if (isDark) Color(0xFF17212B) else Color(0xFFE9E9E9)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(att.fileName, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                                    }
                                }
                            }
                        }
                    }
                }
                
                Row(
                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = timeColor
                    )
                }
            }
        }
    }
}
