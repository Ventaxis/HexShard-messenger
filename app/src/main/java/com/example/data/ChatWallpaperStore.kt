package com.example.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/** Private, per-account and per-conversation wallpapers; no public Storage upload. */
object ChatWallpaperStore {
    private fun directory(context: Context, accountId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "chat-wallpapers/$digest").apply { mkdirs() }
    }

    fun path(context: Context, accountId: String, conversationId: String): String? {
        if (accountId.isBlank() || !conversationId.matches(Regex("^[A-Za-z0-9_-]{1,128}$"))) return null
        return File(directory(context, accountId), conversationId).takeIf { it.isFile }?.absolutePath
    }

    fun set(context: Context, accountId: String, conversationId: String, uri: Uri): Result<String> = runCatching {
        require(accountId.isNotBlank() && conversationId.matches(Regex("^[A-Za-z0-9_-]{1,128}$"))) {
            "Не удалось определить чат. Откройте его повторно."
        }
        val destination = File(directory(context, accountId), conversationId)
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.delete()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 10 * 1024 * 1024) { "Изображение должно быть не больше 10 МБ" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Не удалось открыть выбранное изображение")
            require(temporary.length() > 0) { "Выбранный файл пуст" }
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(temporary.absolutePath, options)
            require(options.outWidth > 0 && options.outHeight > 0) { "Выберите поддерживаемое изображение" }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
            destination.absolutePath
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    fun clear(context: Context, accountId: String, conversationId: String) {
        path(context, accountId, conversationId)?.let { File(it).delete() }
    }
}
