package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.SpotifyGreen
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.ui.AppNavigator
import com.example.ui.ChatViewModel
import com.example.ui.LocalAppLanguage
import com.example.ui.LocalStrings
import com.example.ui.LocalizationManager
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by lazy {
        val appComponent = (application as HexShardApplication).appComponent
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return appComponent.chatViewModel() as T
            }
        })[ChatViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)
        try {
            // Initialize Localization (defaults to English, persists user language choice)
            LocalizationManager.init(this)

            // Enable edge to edge drawing (safe statusBars and navigationBars)
            enableEdgeToEdge()

            setContent {
                val currentLang by LocalizationManager.currentLanguage.collectAsState()
                val strings = LocalizationManager.getStrings(currentLang)

                CompositionLocalProvider(
                    LocalAppLanguage provides currentLang,
                    LocalStrings provides strings
                ) {
                    MyApplicationTheme {
                        Surface(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            AppNavigator(viewModel = viewModel)

                            val currentPendingProfile by viewModel.pendingProfileConfirmation.collectAsState()
                            currentPendingProfile?.let { p ->
                                AlertDialog(
                                    onDismissRequest = { viewModel.dismissPendingProfileConfirmation() },
                                    title = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = SpotifyGreen)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(strings.scanQrTitle, fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                text = strings.serverVerifiedProfile.uppercase(),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = SpotifyGreen
                                            )
                                            Text(text = p.displayName.ifBlank { "User" }, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                            Text(text = "ID: ${p.userId}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                            if (p.hexNumber.isNotBlank()) {
                                                Text(text = "Number: ${p.hexNumber}", fontSize = 12.sp, color = SpotifyGreen)
                                            }
                                            Text(
                                                text = "E2E Key: ${p.publicKey.take(16)}...",
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    },
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                viewModel.confirmAddContact(p) { success ->
                                                    if (success) {
                                                        Toast.makeText(this@MainActivity, "Contact added!", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        ) {
                                            Text(strings.addContact)
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { viewModel.dismissPendingProfileConfirmation() }) {
                                            Text(strings.cancel)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Fatal exception in MainActivity initialization")
            setContent {
                MyApplicationTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.background
                    ) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            androidx.compose.foundation.layout.Column(
                                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)
                            ) {
                                androidx.compose.material3.Text(
                                    text = "Something went wrong",
                                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                )
                                androidx.compose.material3.Text(
                                    text = "An unexpected error occurred. Please restart the application.",
                                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                androidx.compose.material3.Button(
                                    onClick = {
                                        val intent = packageManager.getLaunchIntentForPackage(packageName)
                                        finishAffinity()
                                        if (intent != null) startActivity(intent)
                                    }
                                ) {
                                    androidx.compose.material3.Text("Restart App")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: android.content.Intent?) {
        val dataUri = intent?.data ?: return
        val rawUri = dataUri.toString()
        val token = com.example.util.QrCodeManager.parseProfileToken(rawUri)
        if (token != null) {
            viewModel.handleIncomingDeepLink(token)
        }
    }
}
