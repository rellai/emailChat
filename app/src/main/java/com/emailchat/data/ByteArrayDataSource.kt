package com.emailchat.data

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.activation.DataSource

/**
 * Простая реализация DataSource для отправки байтов в письме.
 * Работает на Android без внешних зависимостей.
 */
class ByteArrayDataSource(
    private val data: ByteArray,
    private val mimeType: String
) : DataSource {

    override fun getInputStream(): InputStream = ByteArrayInputStream(data)
    override fun getOutputStream(): OutputStream =
        throw IOException("OutputStream not supported")
    override fun getContentType(): String = mimeType
    override fun getName(): String = "ByteArrayDataSource"
}