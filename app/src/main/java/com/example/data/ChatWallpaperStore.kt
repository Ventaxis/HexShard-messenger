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

    fun path(context: Context, accountId: String, conversationId: String): String? =
        File(directory(context, accountId), conversationId).takeIf { it.isFile }?.absolutePath

    fun set(context: Context, accountId: String, conversationId: String, uri: Uri): Result<String> = runCatching {
        require(accountId.isNotBlank() && conversationId.matches(Regex("^[A-Za-z0-9_-]{1,128}$")))
        val destination = File(directory(context, accountId), conversationId)
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        context.contentResolver.openInputStream(uri)?.use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Cannot open selected image")
        require(temporary.length() in 1..10 * 1024 * 1024) { "Image must be at most 10 MB" }
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
        destination.absolutePath
    }

    fun clear(context: Context, accountId: String, conversationId: String) {
        path(context, accountId, conversationId)?.let { File(it).delete() }
    }
}
