package com.emailchat.viewmodel

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emailchat.data.EmailAccount
import com.emailchat.data.PreferencesKeys
import com.emailchat.data.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

// ═══════════════════════════════════════════════════════════
// 📊 СОСТОЯНИЕ (STATE)
// ═══════════════════════════════════════════════════════════

sealed interface SetupState {
    object Idle : SetupState
    object Loading : SetupState
    object Success : SetupState
    data class Error(val msg: String) : SetupState
}

class SetupViewModel(private val ctx: Context) : ViewModel() {

    private val _state = MutableStateFlow<SetupState>(SetupState.Idle)
    val state: StateFlow<SetupState> = _state.asStateFlow()

    // ═══════════════════════════════════════════════════════════
    // 🔍 ПРОВЕРКА ПОДКЛЮЧЕНИЯ (SMTP + IMAP)
    // ═══════════════════════════════════════════════════════════

    /**
     * Тестирует подключение к почтовым серверам
     * @return true если оба подключения успешны
     */
    suspend fun testConnection(
        email: String,
        password: String,
        displayName: String,
        imapHost: String,
        imapPort: Int,
        imapSSL: Boolean,
        smtpHost: String,
        smtpPort: Int,
        smtpSSL: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d("SetupVM", "🔍 Тест подключения для $email")

            // 1. Тест SMTP (исходящие)
            if (!testSmtp(smtpHost, smtpPort, smtpSSL, email, password)) {
                Log.e("SetupVM", "❌ SMTP тест не пройден")
                return@withContext false
            }
            Log.d("SetupVM", "✅ SMTP OK")

            // 2. Тест IMAP (входящие)
            if (!testImap(imapHost, imapPort, imapSSL, email, password)) {
                Log.e("SetupVM", "❌ IMAP тест не пройден")
                return@withContext false
            }
            Log.d("SetupVM", "✅ IMAP OK")

            true
        } catch (e: javax.mail.AuthenticationFailedException) {
            Log.e("SetupVM", "🔐 Auth failed: ${e.message}")
            _state.value = SetupState.Error("Неверный пароль. Используйте «Пароль приложения», а не основной.")
            false
        } catch (e: java.net.UnknownHostException) {
            Log.e("SetupVM", "🌐 Host not found: ${e.message}")
            _state.value = SetupState.Error("Сервер не найден. Проверьте название хоста.")
            false
        } catch (e: Exception) {
            Log.e("SetupVM", "💥 Connection error: ${e.message}", e)
            _state.value = SetupState.Error("Ошибка подключения: ${e.message ?: "Неизвестная ошибка"}")
            false
        }
    }

    // Тест SMTP подключения
    private fun testSmtp(
        host: String, port: Int, useSSL: Boolean,
        email: String, password: String
    ): Boolean {
        val props = Properties().apply {
            put("mail.smtp.host", host)
            put("mail.smtp.port", port.toString())
            put("mail.smtp.ssl.enable", useSSL.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "10000")
        }

        val auth = object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(email, password)
        }

        val session = Session.getInstance(props, auth)
        // session.setDebug(true) // Раскомментируйте для детальных логов

        var transport: Transport? = null
        return try {
            transport = session.getTransport("smtp")
            transport.connect(host, email, password)
            transport.isConnected
        } finally {
            transport?.close()
        }
    }

    // Тест IMAP подключения
    private fun testImap(
        host: String, port: Int, useSSL: Boolean,
        email: String, password: String
    ): Boolean {
        val props = Properties().apply {
            put("mail.imap.host", host)
            put("mail.imap.port", port.toString())
            put("mail.imap.ssl.enable", useSSL.toString())
            put("mail.imap.auth", "true")
            put("mail.imap.starttls.enable", "true")
            put("mail.imap.connectiontimeout", "10000")
            put("mail.imap.timeout", "10000")
        }

        val auth = object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(email, password)
        }

        val session = Session.getInstance(props, auth)
        var store: javax.mail.Store? = null
        return try {
            store = session.getStore("imap")
            store.connect(host, email, password)
            store.isConnected
        } finally {
            store?.close()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 💾 СОХРАНЕНИЕ АККАУНТА В DATASTORE
    // ═══════════════════════════════════════════════════════════

    /**
     * Сохраняет настройки аккаунта в локальное хранилище
     * @return true если сохранение успешно
     */
    suspend fun saveAccount(
        email: String,
        password: String,
        displayName: String,
        imapHost: String,
        imapPort: Int,
        imapSSL: Boolean,
        smtpHost: String,
        smtpPort: Int,
        smtpSSL: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            ctx.dataStore.edit { prefs ->
                prefs[PreferencesKeys.EMAIL] = email
                prefs[PreferencesKeys.PASSWORD] = password
                prefs[PreferencesKeys.DISPLAY_NAME] = displayName

                prefs[PreferencesKeys.IMAP_HOST] = imapHost
                prefs[PreferencesKeys.IMAP_PORT] = imapPort
                prefs[PreferencesKeys.IMAP_USE_SSL] = imapSSL

                prefs[PreferencesKeys.SMTP_HOST] = smtpHost
                prefs[PreferencesKeys.SMTP_PORT] = smtpPort
                prefs[PreferencesKeys.SMTP_USE_SSL] = smtpSSL
            }
            Log.d("SetupVM", "💾 Аккаунт сохранён: $email")
            _state.value = SetupState.Success
            
            // Принудительная проверка сохранения
            val prefs = ctx.dataStore.data.first()
            val savedEmail = prefs[PreferencesKeys.EMAIL]
            val savedPassword = prefs[PreferencesKeys.PASSWORD]
            
            if (savedEmail == email && savedPassword == password) {
                Log.d("SetupVM", "✅ Проверка: настройки действительно сохранены")
                true
            } else {
                Log.e("SetupVM", "❌ Проверка: настройки не сохранились")
                _state.value = SetupState.Error("Не удалось сохранить настройки")
                false
            }
        } catch (e: Exception) {
            Log.e("SetupVM", "❌ Ошибка сохранения: ${e.message}", e)
            _state.value = SetupState.Error("Не удалось сохранить: ${e.message}")
            false
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 🔄 ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ═══════════════════════════════════════════════════════════

    fun resetState() {
        _state.value = SetupState.Idle
    }

    fun isLoading(): Boolean = _state.value is SetupState.Loading

    fun getError(): String? = when (val s = _state.value) {
        is SetupState.Error -> s.msg
        else -> null
    }
}