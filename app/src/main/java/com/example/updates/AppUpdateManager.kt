package com.example.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppUpdate(
    val versionName: String,
    val versionCode: Long,
    val notes: String,
    val downloadUrl: String,
    val checksumUrl: String,
    val expectedSize: Long
)

/** Official GitHub APK updates; Android remains the final signature authority. */
object AppUpdateManager {
    private const val RELEASES_API = "https://api.github.com/repos/Ventaxis/HexShard-messenger/releases/latest"
    private const val RELEASE_HOST = "github.com"
    private val allowedReleaseHosts = setOf(RELEASE_HOST, "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    private const val MAX_APK_BYTES = 200L * 1024 * 1024
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun checkForUpdate(): Result<AppUpdate?> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(RELEASES_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "HexShard-Android")
                .build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "GitHub вернул HTTP ${response.code}" }
                findUpdate(response.body?.string().orEmpty(), BuildConfig.VERSION_CODE.toLong())
            }
        }
    }

    /** Parses the public release boundary without executing downloaded content. */
    fun findUpdate(releaseJson: String, currentVersionCode: Long): AppUpdate? {
        val release = JSONObject(releaseJson)
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val tag = release.getString("tag_name")
        require(tag.matches(Regex("^v\\d+\\.\\d+\\.\\d+$"))) { "Некорректная версия релиза" }
        val metadataAsset = release.getJSONArray("assets").let { assets ->
            (0 until assets.length()).map(assets::getJSONObject).firstOrNull {
                it.optString("name") == "update.json"
            }
        } ?: return null
        val metadataUrl = metadataAsset.getString("browser_download_url")
        requireOfficialUrl(metadataUrl)
        val metadata = JSONObject(fetchText(metadataUrl))
        require(metadata.getString("package") == BuildConfig.APPLICATION_ID) { "Релиз предназначен другому приложению" }
        require(metadata.getString("tag") == tag) { "Метаданные не соответствуют релизу" }
        val versionCode = metadata.getLong("versionCode")
        if (versionCode <= currentVersionCode) return null
        val downloadUrl = metadata.getString("apkUrl")
        val checksumUrl = metadata.getString("sha256Url")
        requireOfficialUrl(downloadUrl)
        requireOfficialUrl(checksumUrl)
        val size = metadata.getLong("size")
        require(size in 1..MAX_APK_BYTES) { "Некорректный размер APK" }
        return AppUpdate(tag.removePrefix("v"), versionCode, release.optString("body"), downloadUrl, checksumUrl, size)
    }

    suspend fun downloadAndVerify(context: Context, update: AppUpdate): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            requireOfficialUrl(update.downloadUrl)
            requireOfficialUrl(update.checksumUrl)
            val expectedSha = fetchText(update.checksumUrl).trim().substringBefore(' ').lowercase()
            require(expectedSha.matches(Regex("^[0-9a-f]{64}$"))) { "Некорректная контрольная сумма" }
            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            val file = File(directory, "hexshard-${update.versionCode}.apk")
            val temporary = File(directory, "download.tmp")
            temporary.delete()
            try {
                client.newCall(Request.Builder().url(update.downloadUrl).build()).execute().use { response ->
                    check(response.isSuccessful) { "Загрузка APK: HTTP ${response.code}" }
                    response.body?.byteStream()?.use { input ->
                        temporary.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total == total.coerceAtMost(update.expectedSize)) { "APK превышает заявленный размер" }
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: error("Пустой ответ при загрузке APK")
                }
                check(temporary.length() == update.expectedSize) { "APK загружен не полностью" }
                check(sha256(temporary) == expectedSha) { "Контрольная сумма APK не совпала" }
                verifyArchive(context, temporary, update.versionCode)
                if (file.exists()) file.delete()
                check(temporary.renameTo(file)) { "Не удалось сохранить APK" }
                file
            } catch (error: Throwable) {
                temporary.delete()
                throw error
            }
        }
    }

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun unknownSourcesIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}")
    )

    private fun verifyArchive(context: Context, apk: File, expectedVersion: Long) {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES.toLong()
        val archive = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(flags))
        } else {
            @Suppress("DEPRECATION") context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags.toInt())
        } ?: error("Android не смог прочитать APK")
        require(archive.packageName == context.packageName) { "APK принадлежит другому приложению" }
        require(archive.longVersionCode == expectedVersion) { "Версия APK не совпадает с метаданными" }
        val installed = packageInfo(context)
        val installedSigners = installed.signers()
        val archiveSigners = archive.signers()
        require(installedSigners.isNotEmpty() && archiveSigners.isNotEmpty()) { "APK не подписан" }
        require(installedSigners == archiveSigners) { "Подпись APK отличается от установленного HexShard" }
    }

    private fun packageInfo(context: Context): PackageInfo {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES.toLong()
        return if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags))
        } else {
            @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, flags.toInt())
        }
    }

    private fun PackageInfo.signers(): Set<String> = signingInfo?.let { info ->
        val signatures = if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        signatures?.map { sha256(it.toByteArray()) }?.toSet()
    }.orEmpty()

    private fun fetchText(url: String): String {
        requireOfficialUrl(url)
        return client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "Метаданные релиза: HTTP ${response.code}" }
            response.body?.string() ?: error("Пустые метаданные релиза")
        }
    }

    private fun requireOfficialUrl(url: String) {
        val uri = android.net.Uri.parse(url)
        require(uri.scheme == "https" && uri.host in allowedReleaseHosts &&
            (uri.host != RELEASE_HOST || uri.path?.startsWith("/Ventaxis/HexShard-messenger/releases/download/") == true)) {
            "Источник обновления не является официальным HTTPS-релизом"
        }
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
