package com.example.network.supabase

import android.content.Context
import com.example.BuildConfig
import com.example.data.SecurePrefsManager
import org.json.JSONObject
import timber.log.Timber
import java.util.Base64

object SupabaseConfig {
    /**
     * HexShard Supabase backend configuration.
     * 
     * Security rule: Only the public publishable/anon key is allowed on client devices.
     * Service role keys, database secrets, and bot tokens must NEVER be present here.
     */
    const val SUPABASE_PROJECT_REF = "zqyipemjvzcxjpglmegy"
    const val DEFAULT_SUPABASE_URL = "https://zqyipemjvzcxjpglmegy.supabase.co"
    const val DEFAULT_SUPABASE_ANON_KEY = "sb_publishable_NNLyZlCPko3fbD4ibBjeQw_7OV0OpcS"

    const val TELEGRAM_BOT_USERNAME = "HexShardBot"
    const val TELEGRAM_BOT_URL = "https://t.me/HexShardBot"

    fun getBaseUrl(): String {
        try {
            val buildUrl = BuildConfig.SUPABASE_URL
            if (!buildUrl.isNullOrBlank() && buildUrl.startsWith("http")) {
                return buildUrl.trim().removeSuffix("/")
            }
        } catch (_: Throwable) {
            // BuildConfig field not available
        }
        return DEFAULT_SUPABASE_URL
    }

    fun getProjectRef(): String {
        return SUPABASE_PROJECT_REF
    }

    /**
     * Validates a candidate Supabase public API key.
     * Rejects sentinels, placeholders, test dummy signatures, and wrong project references.
     * Never logs full token content.
     */
    fun sanitizeAndValidateKey(candidate: String?): String {
        if (candidate.isNullOrBlank()) return ""
        val trimmed = candidate.trim()
        if (trimmed.equals("REPLACE_ME", ignoreCase = true) ||
            trimmed.contains("placeholder", ignoreCase = true) ||
            trimmed.endsWith(".anon_key_public", ignoreCase = true) ||
            trimmed.contains("dummy", ignoreCase = true)
        ) {
            return ""
        }

        // Diagnostic verification of JWT payload without cryptographic trust
        if (trimmed.count { it == '.' } == 2) {
            try {
                val parts = trimmed.split(".")
                if (parts.size == 3) {
                    val payloadBytes = Base64.getUrlDecoder().decode(parts[1])
                    val json = JSONObject(String(payloadBytes, Charsets.UTF_8))
                    val role = json.optString("role", "")
                    if (role == "service_role") {
                        Timber.e("CRITICAL: Service role key detected on client device! Rejecting.")
                        return ""
                    }
                    val ref = json.optString("ref", "")
                    if (ref.isNotBlank() && ref != SUPABASE_PROJECT_REF) {
                        Timber.e("Supabase public key project ref mismatch: configured for '$ref', expected '$SUPABASE_PROJECT_REF'")
                        return ""
                    }
                }
            } catch (e: Exception) {
                Timber.w("Unable to parse public JWT candidate for validation: ${e.message}")
            }
        }

        return trimmed
    }

    /**
     * Dynamically resolves the Anon Key with precedence:
     * 1. BuildConfig key (injected via .env / Secrets panel)
     * 2. Validated custom override in EncryptedSharedPreferences (developer/debug override)
     * 
     * STRICT NO-FAIL-OPEN: If no valid key is configured, returns empty string.
     */
    fun getAnonKey(context: Context? = null): String {
        // 1. Try BuildConfig build-time key first
        try {
            val buildConfigKey = BuildConfig.SUPABASE_ANON_KEY
            val validatedBuildKey = sanitizeAndValidateKey(buildConfigKey)
            if (validatedBuildKey.isNotBlank()) {
                return validatedBuildKey
            }
        } catch (_: Throwable) {
            // BuildConfig field not available
        }

        // 2. Try default project publishable key
        val validatedDefault = sanitizeAndValidateKey(DEFAULT_SUPABASE_ANON_KEY)
        if (validatedDefault.isNotBlank()) {
            return validatedDefault
        }

        // 3. Fallback to developer-configured custom key in SecurePrefs
        if (context != null) {
            val userKey = SecurePrefsManager.getCustomSupabaseAnonKey(context)
            val validatedUserKey = sanitizeAndValidateKey(userKey)
            if (validatedUserKey.isNotBlank()) {
                return validatedUserKey
            }
        }

        return ""
    }

    fun isConfigured(context: Context? = null): Boolean {
        val key = getAnonKey(context)
        return key.isNotBlank()
    }

    /**
     * Diagnostic safe configuration fingerprint (never prints token or secret).
     */
    fun getConfigFingerprint(context: Context? = null): Map<String, String> {
        val key = getAnonKey(context)
        val isPresent = key.isNotBlank()
        val isJwt = key.count { it == '.' } == 2
        return mapOf(
            "project_ref" to SUPABASE_PROJECT_REF,
            "key_present" to isPresent.toString(),
            "key_kind" to if (isJwt) "legacy_jwt" else if (key.startsWith("sb_p_") || key.startsWith("sb_publishable_")) "publishable" else "unknown",
            "key_length" to key.length.toString()
        )
    }
}

/**
 * Strict canonical header builder to ensure public API keys and user access tokens
 * are never confused.
 */
object SupabaseAuthHeaders {
    fun buildPublicHeaders(anonKey: String): Map<String, String> {
        require(anonKey.isNotBlank()) { "Supabase public API key (anonKey) must not be blank" }
        return mapOf("apikey" to anonKey)
    }

    fun buildAuthenticatedHeaders(anonKey: String, userAccessToken: String): Map<String, String> {
        require(anonKey.isNotBlank()) { "Supabase public API key (anonKey) must not be blank" }
        require(userAccessToken.isNotBlank()) { "User access token must not be blank for authenticated request" }
        return mapOf(
            "apikey" to anonKey,
            "Authorization" to "Bearer $userAccessToken"
        )
    }
}
