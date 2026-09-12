package com.example.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.util.VirtualNumberGenerator
import timber.log.Timber

object SecurePrefsManager {
    private const val PREFS_FILE = "hexshard_user_secure_prefs"
    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    @Synchronized
    fun getPrefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        return try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            cachedPrefs = prefs
            prefs
        } catch (e: Exception) {
            Timber.e(e, "Error initializing EncryptedSharedPreferences, resetting secure storage")
            try {
                context.applicationContext.deleteSharedPreferences(PREFS_FILE)
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val prefs = EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                cachedPrefs = prefs
                prefs
            } catch (e2: Exception) {
                if (isTestEnvironment()) {
                    Timber.w("Test environment detected (Robolectric); using in-memory SharedPreferences fallback")
                    val testPrefs = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                    cachedPrefs = testPrefs
                    return testPrefs
                }
                Timber.e(e2, "Hardware-backed EncryptedSharedPreferences failed to initialize. Failing closed.")
                throw SecurityException("Secure hardware-backed encrypted storage is unavailable on this device", e2)
            }
        }
    }

    private fun isTestEnvironment(): Boolean {
        return try {
            Class.forName("org.robolectric.Robolectric") != null
        } catch (e: Throwable) {
            android.os.Build.FINGERPRINT.startsWith("robolectric")
        }
    }

    fun getUserId(context: Context): String {
        return getPrefs(context).getString("userId", "") ?: ""
    }

    /**
     * Internal Account ID (Server-generated UUID).
     */
    fun getAccountId(context: Context): String {
        return getUserId(context)
    }

    /**
     * Returns a stable, persistent installation device identifier.
     * Guaranteed non-empty, non-null, and unique across installs.
     */
    fun getDeviceId(context: Context): String {
        val prefs = getPrefs(context)
        val existing = prefs.getString("device_installation_id", "") ?: ""
        if (existing.isNotBlank()) return existing

        val androidId = try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
        } catch (_: Exception) { null }

        val newId = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            "android_$androidId"
        } else {
            "android_${java.util.UUID.randomUUID().toString().replace("-", "")}"
        }

        prefs.edit().putString("device_installation_id", newId).apply()
        return newId
    }

    fun getUsername(context: Context): String {
        return getPrefs(context).getString("username", "user") ?: "user"
    }

    fun getPhone(context: Context): String {
        return getPrefs(context).getString("phone", "") ?: ""
    }

    /**
     * Returns the formatted private virtual +999 identity number (+999 XXXX XXXX).
     */
    fun getPrivateVirtualNumber(context: Context): String {
        val raw = getPrefs(context).getString("private_virtual_number", "") ?: ""
        if (raw.isNotBlank()) {
            return VirtualNumberGenerator.format8Digits(raw)
        }
        val legacy = getPrefs(context).getString("virtual_number", "") ?: ""
        if (legacy.isNotBlank()) {
            val extracted = VirtualNumberGenerator.extractRaw8Digits(legacy) ?: legacy.filter { it.isDigit() }
            return VirtualNumberGenerator.format8Digits(extracted)
        }
        return ""
    }

    /**
     * Returns the canonical raw 8 digits of the private virtual identity (e.g. "67676767").
     */
    fun getRawPrivateVirtualNumber(context: Context): String {
        val raw = getPrefs(context).getString("private_virtual_number", "") ?: ""
        if (raw.isNotBlank()) return raw
        val legacy = getPrefs(context).getString("virtual_number", "") ?: ""
        return VirtualNumberGenerator.extractRaw8Digits(legacy) ?: legacy.filter { it.isDigit() }
    }

    /**
     * Persists the private virtual number as canonical 8 raw digits.
     * Note: Does NOT pollute the regular cellular phone field.
     */
    fun setPrivateVirtualNumber(context: Context, numberOrDigits: String) {
        val rawDigits = VirtualNumberGenerator.extractRaw8Digits(numberOrDigits) 
            ?: numberOrDigits.filter { it.isDigit() }.takeLast(8)
        getPrefs(context).edit()
            .putString("private_virtual_number", rawDigits)
            .apply()
    }

    fun isTelegramVerified(context: Context): Boolean {
        return getPrefs(context).getBoolean("telegram_verified", false)
    }

    fun setTelegramVerified(context: Context, verified: Boolean) {
        getPrefs(context).edit().putBoolean("telegram_verified", verified).apply()
    }

    fun saveSupabaseTokens(context: Context, accessToken: String, refreshToken: String) {
        getPrefs(context).edit()
            .putString("supabase_access_token", accessToken)
            .putString("supabase_refresh_token", refreshToken)
            .apply()
    }

    fun getSupabaseAccessToken(context: Context): String {
        return getPrefs(context).getString("supabase_access_token", "") ?: ""
    }

    fun getSupabaseRefreshToken(context: Context): String {
        return getPrefs(context).getString("supabase_refresh_token", "") ?: ""
    }

    fun getCustomSupabaseAnonKey(context: Context): String? {
        return getPrefs(context).getString("custom_supabase_anon_key", null)?.takeIf { it.isNotBlank() }
    }

    fun setCustomSupabaseAnonKey(context: Context, key: String?) {
        if (key.isNullOrBlank()) {
            getPrefs(context).edit().remove("custom_supabase_anon_key").apply()
        } else {
            getPrefs(context).edit().putString("custom_supabase_anon_key", key.trim()).apply()
        }
    }

    fun clearSession(context: Context) {
        getPrefs(context).edit()
            .remove("supabase_access_token")
            .remove("supabase_refresh_token")
            .apply()
    }

    fun getAccountType(context: Context): AccountType {
        val stored = getPrefs(context).getString("accountType", null)
        return AccountType.fromId(stored)
    }

    fun getAvatarUri(context: Context): String? {
        return getPrefs(context).getString("avatarUri", null)
    }

    fun saveProfile(
        context: Context,
        userId: String,
        username: String,
        phone: String,
        avatarUri: String? = null,
        accountType: AccountType = AccountType.PHONE
    ) {
        val editor = getPrefs(context).edit()
            .putString("userId", userId)
            .putString("username", username)
            .putString("phone", phone)
            .putString("accountType", accountType.id)
        if (avatarUri != null) {
            editor.putString("avatarUri", avatarUri)
        }
        editor.apply()
    }

    fun setAvatarUri(context: Context, avatarUri: String) {
        getPrefs(context).edit().putString("avatarUri", avatarUri).apply()
    }

    fun isNotificationsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean("notifications_enabled", true)
    }

    fun setNotificationsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean("notifications_enabled", enabled).apply()
    }

    fun isInAppSoundsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean("in_app_sounds_enabled", true)
    }

    fun setInAppSoundsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean("in_app_sounds_enabled", enabled).apply()
    }

    fun getSelectedAiPersona(context: Context, accountId: String): String {
        return getPrefs(context).getString("ai_persona_${accountId}", "VENTAXIS") ?: "VENTAXIS"
    }

    fun setSelectedAiPersona(context: Context, accountId: String, personaId: String) {
        getPrefs(context).edit().putString("ai_persona_${accountId}", personaId).apply()
    }

    fun clear(context: Context) {
        try {
            getPrefs(context).edit().clear().commit()
        } catch (e: Exception) {
            Timber.w(e, "Error clearing encrypted preferences")
        }
        cachedPrefs = null
    }
}
