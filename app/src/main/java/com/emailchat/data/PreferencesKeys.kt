package com.emailchat.data

import androidx.datastore.preferences.core.*

object PreferencesKeys {
    val EMAIL = stringPreferencesKey("email")
    val PASSWORD = stringPreferencesKey("password")
    val DISPLAY_NAME = stringPreferencesKey("display_name")

    val IMAP_HOST = stringPreferencesKey("imap_host")
    val IMAP_PORT = intPreferencesKey("imap_port")
    val IMAP_USE_SSL = booleanPreferencesKey("imap_use_ssl")

    val SMTP_HOST = stringPreferencesKey("smtp_host")
    val SMTP_PORT = intPreferencesKey("smtp_port")
    val SMTP_USE_SSL = booleanPreferencesKey("smtp_use_ssl")
}