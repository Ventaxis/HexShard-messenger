package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
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
}
