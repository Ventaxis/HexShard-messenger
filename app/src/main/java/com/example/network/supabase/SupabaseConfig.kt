package com.example.network.supabase

import android.content.Context
import com.example.BuildConfig
import com.example.data.SecurePrefsManager

object SupabaseConfig {
    /**
     * HexShard Supabase backend configuration.
     * 
     * Security rule: Only the public publishable/anon key is allowed on client devices.
     * Service role keys, database secrets, and bot tokens must NEVER be present here.
     */
    const val SUPABASE_PROJECT_REF = "zqyipemjvzcxjpglmegy"
    const val DEFAULT_SUPABASE_URL = "https://zqyipemjvzcxjpglmegy.supabase.co"

    const val TELEGRAM_BOT_USERNAME = "HexShardBot"
    const val TELEGRAM_BOT_URL = "https://t.me/HexShardBot"

    fun getBaseUrl(): String {
        return DEFAULT_SUPABASE_URL
    }

    fun getProjectRef(): String {
        return SUPABASE_PROJECT_REF
    }

    /**
     * Dynamically resolves the Anon Key with precedence:
     * 1. Secure user-configured override in EncryptedSharedPreferences
     * 2. Build-time BuildConfig (injected via .env / Secrets panel)
     * 
     * STRICT NO-FAIL-OPEN: If no valid key is configured, returns empty string.
     * Never uses dummy or fake JWT tokens that create unauthenticated or deceptive network traffic.
     */
    fun getAnonKey(context: Context? = null): String {
        if (context != null) {
            val userKey = SecurePrefsManager.getCustomSupabaseAnonKey(context)
            if (!userKey.isNullOrBlank() && userKey != "placeholder") {
                return userKey
            }
        }

        try {
            val buildConfigKey = BuildConfig.SUPABASE_ANON_KEY
            if (!buildConfigKey.isNullOrBlank() && buildConfigKey != "placeholder") {
                return buildConfigKey
            }
        } catch (_: Throwable) {
            // BuildConfig field not available in testing or standard mode
        }

        return ""
    }

    fun isConfigured(context: Context? = null): Boolean {
        return getAnonKey(context).isNotBlank()
    }
}
