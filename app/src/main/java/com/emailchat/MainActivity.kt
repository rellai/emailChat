package com.emailchat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import com.emailchat.data.ChatDatabase
import com.emailchat.data.PreferencesKeys
import com.emailchat.data.dataStore
import com.emailchat.service.EmailSyncService
import com.emailchat.ui.chat.ChatScreen
import com.emailchat.ui.chatlist.ChatListScreen
import com.emailchat.ui.setup.SetupScreen
import com.emailchat.ui.theme.EmailChatTheme
import com.emailchat.viewmodel.ChatViewModel
import com.emailchat.viewmodel.SetupViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val database by lazy { ChatDatabase.getInstance(this) }
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Запрос разрешения на уведомления (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Запуск службы синхронизации (IMAP IDLE)
        EmailSyncService.start(this)

        setContent {
            EmailChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    EmailChatApp()
                }
            }
        }
    }

    @Composable
    private fun EmailChatApp() {
        val navController = rememberNavController()
        // ✅ Используем явный applicationContext
        val appContext = this@MainActivity.applicationContext

        var isSetup by remember { mutableStateOf(false) }

        // Проверяем, настроен ли аккаунт при старте
        LaunchedEffect(Unit) {
            isSetup = appContext.dataStore.data.first()[PreferencesKeys.EMAIL] != null
        }

        // Factory для SetupViewModel
        val setupViewModel: SetupViewModel = viewModel(
            factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SetupViewModel(appContext) as T
                }
            }
        )

        // Factory для ChatViewModel
        val chatViewModel: ChatViewModel = viewModel(
            factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ChatViewModel(appContext, database) as T
                }
            }
        )

        NavHost(
            navController = navController,
            startDestination = if (isSetup) Screen.ChatList.route else Screen.Setup.route
        ) {
            // Экран настройки аккаунта
            composable(Screen.Setup.route) {
                SetupScreen(
                    vm = setupViewModel,
                    onDone = {
                        isSetup = true
                        navController.navigate(Screen.ChatList.route) {
                            popUpTo(Screen.Setup.route) { inclusive = true }
                        }
                    }
                )
            }

            // Список чатов
            composable(Screen.ChatList.route) {
                val conversations by chatViewModel.conversations.collectAsState(initial = emptyList())

                ChatListScreen(
                    list = conversations,
                    onSel = { email ->
                        chatViewModel.selectConversation(email)
                        navController.navigate(Screen.Chat.createRoute(email))
                    },
                    onSync = {
                        // Принудительная синхронизация (опционально, IDLE работает автоматически)
                        // Можно добавить вызов в ViewModel если нужно
                    },
                    onNewChat = { /* Обработано внутри ChatListScreen диалогом */ },
                    onSettings = {
                        // ✅ Сброс настроек и возврат к экрану входа
                        activityScope.launch {
                            appContext.dataStore.edit { prefs ->
                                prefs.clear() // Удаляем все ключи аккаунта
                            }
                        }
                        isSetup = false
                        navController.navigate(Screen.Setup.route) {
                            popUpTo(Screen.ChatList.route) { inclusive = true }
                        }
                    }
                )
            }

            // Экран чата
            composable(Screen.Chat.route) { backStackEntry ->
                val email = backStackEntry.arguments?.getString("email") ?: return@composable
                val messages by chatViewModel.getMessages(email).collectAsState(initial = emptyList())

                ChatScreen(
                    id = email,
                    msgs = messages,
                    vm = chatViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Очищаем скоуп, чтобы избежать утечек
        activityScope.cancel()
        // Не останавливаем EmailSyncService здесь — он должен жить в фоне
    }
}

// ═══════════════════════════════════════════════════════════
// 🧭 Навигация: определение экранов
// ═══════════════════════════════════════════════════════════

sealed class Screen(val route: String) {
    object Setup : Screen("setup")
    object ChatList : Screen("chat_list")
    object Chat : Screen("chat/{email}") {
        fun createRoute(email: String) = "chat/$email"
    }
}