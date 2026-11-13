package com.example.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.updates.AppUpdate
import com.example.updates.AppUpdateManager
import com.example.ui.theme.HexDarkSurfaceElevated
import com.example.ui.theme.HexShardTeal
import com.example.ui.theme.HexTextPrimary
import com.example.ui.theme.HexTextSecondary
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun UpdateCenterDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    fun install(apk: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(AppUpdateManager.unknownSourcesIntent(context))
            status = "Разрешите установку из HexShard, затем вернитесь и нажмите «Установить»."
            return
        }
        context.startActivity(AppUpdateManager.installIntent(context, apk))
    }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        modifier = Modifier.testTag("update_center_dialog"),
        containerColor = HexDarkSurfaceElevated,
        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = HexShardTeal) },
        title = { Text("Обновления и исправления", color = HexTextPrimary) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Text("Текущая версия: ${BuildConfig.VERSION_NAME}", color = HexTextSecondary)
                Spacer(Modifier.height(8.dp))
                Text("Обновления загружаются только из официального GitHub-релиза. Контрольная сумма и подпись APK проверяются до установки.", color = HexTextSecondary, style = MaterialTheme.typography.bodySmall)
                update?.let {
                    Spacer(Modifier.height(18.dp))
                    Text("HexShard ${it.versionName}", color = HexTextPrimary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(it.notes.ifBlank { "Исправления ошибок и улучшение стабильности." }, color = HexTextSecondary)
                }
                status?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = HexTextSecondary)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !checking && !downloading,
                colors = ButtonDefaults.buttonColors(containerColor = HexShardTeal),
                modifier = Modifier.testTag("update_center_action"),
                onClick = {
                    downloadedApk?.let { install(it); return@Button }
                    val available = update
                    if (available != null) {
                        scope.launch {
                            downloading = true
                            status = "Загрузка и проверка APK…"
                            AppUpdateManager.downloadAndVerify(context, available)
                                .onSuccess { downloadedApk = it; status = "APK проверен. Android покажет подтверждение установки."; install(it) }
                                .onFailure { status = "Ошибка: ${it.message ?: "не удалось загрузить обновление"}" }
                            downloading = false
                        }
                    } else {
                        scope.launch {
                            checking = true
                            status = "Проверка официального релиза…"
                            AppUpdateManager.checkForUpdate()
                                .onSuccess { update = it; status = if (it == null) "Установлена последняя версия." else null }
                                .onFailure { status = "Ошибка: ${it.message ?: "не удалось проверить обновление"}" }
                            checking = false
                        }
                    }
                }
            ) {
                Text(when {
                    downloading -> "Загрузка…"
                    checking -> "Проверка…"
                    downloadedApk != null -> "Установить"
                    update != null -> "Скачать и установить"
                    else -> "Проверить"
                })
            }
        },
        dismissButton = { TextButton(enabled = !downloading, onClick = onDismiss) { Text("Закрыть") } }
    )
}
