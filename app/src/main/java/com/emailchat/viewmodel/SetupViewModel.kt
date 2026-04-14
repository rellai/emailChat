package com.emailchat.viewmodel

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import com.emailchat.data.EmailAccount
import com.emailchat.data.PreferencesKeys
import com.emailchat.data.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Session

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

    // Поток с текущими настройками аккаунта
    val savedAccount: Flow<EmailAccount?> = ctx.dataStore.data.map { prefs ->
        val email = prefs[PreferencesKeys.EMAIL] ?: return@map null
        EmailAccount(
            email = email,
            password = prefs[PreferencesKeys.PASSWORD] ?: "",
            displayName = prefs[PreferencesKeys.DISPLAY_NAME] ?: "",
            imapHost = prefs[PreferencesKeys.IMAP_HOST] ?: "",
            imapPort = prefs[PreferencesKeys.IMAP_PORT] ?: 993,
            imapUseSSL = prefs[PreferencesKeys.IMAP_USE_SSL] ?: true,
            smtpHost = prefs[PreferencesKeys.SMTP_HOST] ?: "",
            smtpPort = prefs[PreferencesKeys.SMTP_PORT] ?: 465,
            smtpUseSSL = prefs[PreferencesKeys.SMTP_USE_SSL] ?: true
        )
    }

    // ═══════════════════════════════════════════════════════════
    // 🔍 ПРОВЕРКА ПОДКЛЮЧЕНИЯ (SMTP + IMAP)
    // ═══════════════════════════════════════════════════════════

    suspend fun testConnection(
        email: String,
        password: String,
        imapHost: String,
        imapPort: Int,
        imapSSL: Boolean,
        smtpHost: String,
        smtpPort: Int,
        smtpSSL: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            _state.value = SetupState.Loading
            Log.d("SetupVM", "🔍 Тест подключения для $email")

            if (!testSmtp(smtpHost, smtpPort, smtpSSL, email, password)) {
                _state.value = SetupState.Error("Ошибка SMTP подключения")
                return@withContext false
            }

            if (!testImap(imapHost, imapPort, imapSSL, email, password)) {
                _state.value = SetupState.Error("Ошибка IMAP подключения")
                return@withContext false
            }

            true
        } catch (e: Exception) {
            Log.e("SetupVM", "💥 Connection error: ${e.message}", e)
            _state.value = SetupState.Error(e.message ?: "Ошибка подключения")
            false
        }
    }

    private fun testSmtp(host: String, port: Int, useSSL: Boolean, email: String, password: String): Boolean {
        val props = Properties().apply {
            put("mail.smtp.host", host)
            put("mail.smtp.port", port.toString())
            put("mail.smtp.ssl.enable", useSSL.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "10000")
        }
        val session = Session.getInstance(props)
        return try {
            val transport = session.getTransport("smtp")
            transport.connect(host, email, password)
            transport.close()
            true
        } catch (e: Exception) { false }
    }

    private fun testImap(host: String, port: Int, useSSL: Boolean, email: String, password: String): Boolean {
        val props = Properties().apply {
            put("mail.imap.host", host)
            put("mail.imap.port", port.toString())
            put("mail.imap.ssl.enable", useSSL.toString())
            put("mail.imap.auth", "true")
            put("mail.imap.connectiontimeout", "10000")
            put("mail.imap.timeout", "10000")
        }
        val session = Session.getInstance(props)
        return try {
            val store = session.getStore("imap")
            store.connect(host, email, password)
            store.close()
            true
        } catch (e: Exception) { false }
    }

    // ═══════════════════════════════════════════════════════════
    // 💾 СОХРАНЕНИЕ АККАУНТА В DATASTORE
    // ═══════════════════════════════════════════════════════════

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
            _state.value = SetupState.Success
            true
        } catch (e: Exception) {
            _state.value = SetupState.Error("Ошибка сохранения: ${e.message}")
            false
        }
    }

    fun resetState() { _state.value = SetupState.Idle }
}
