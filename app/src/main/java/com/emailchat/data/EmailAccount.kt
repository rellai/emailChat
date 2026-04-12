package com.emailchat.data

data class EmailAccount(
    val email: String, val password: String, val displayName: String,
    val imapHost: String, val imapPort: Int = 993, val imapUseSSL: Boolean = true,
    val smtpHost: String, val smtpPort: Int = 465, val smtpUseSSL: Boolean = true
) {
    companion object {
        fun fromEmail(email: String, password: String, displayName: String): EmailAccount {
            val d = email.substringAfter("@")
            return EmailAccount(
                email,
                password,
                displayName,
                detectHost("imap", d),
                smtpHost = detectHost("smtp", d)
            )
        }

        private fun detectHost(type: String, domain: String) = when (domain.lowercase()) {
            "gmail.com" -> if (type == "imap") "imap.gmail.com" else "smtp.gmail.com"
            "yandex.ru", "yandex.com", "ya.ru" -> if (type == "imap") "imap.yandex.ru" else "smtp.yandex.ru"
            "mail.ru" -> if (type == "imap") "imap.mail.ru" else "smtp.mail.ru"
            else -> "$type.$domain"
        }
    }
}
