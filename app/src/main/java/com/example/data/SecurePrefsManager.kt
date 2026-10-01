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
            if (isTestEnvironment()) {
                Timber.w("Test environment detected (Robolectric); using standard SharedPreferences fallback")
                val testPrefs = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                cachedPrefs = testPrefs
                return testPrefs
            }
            Timber.e(e, "Hardware-backed EncryptedSharedPreferences failed to initialize. Failing closed.")
            throw SecurityException("Secure hardware-backed encrypted storage is unavailable on this device", e)
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

    fun getBio(context: Context): String {
        return getPrefs(context).getString("profile_bio", "") ?: ""
    }

    fun setBio(context: Context, bio: String) {
        getPrefs(context).edit().putString("profile_bio", bio).apply()
    }

    fun getDateOfBirth(context: Context): String {
        return getPrefs(context).getString("profile_dob", "") ?: ""
    }

    fun setDateOfBirth(context: Context, dob: String) {
        getPrefs(context).edit().putString("profile_dob", dob).apply()
    }

    /**
     * Returns the formatted private virtual +999 identity number (+999 XXXX XXXX).
     * Account-isolated: strictly loads the number bound to [accountId] (or the active session userId if omitted).
     */
    fun getPrivateVirtualNumber(context: Context, accountId: String? = null): String {
        val targetId = accountId?.trim()?.ifBlank { null } ?: getUserId(context).trim().ifBlank { null }
        val prefs = getPrefs(context)
        if (targetId != null) {
            val accountRaw = prefs.getString("private_virtual_number_$targetId", "") ?: ""
            if (accountRaw.isNotBlank()) {
                val digits = VirtualNumberGenerator.extractRaw8Digits(accountRaw) ?: accountRaw.filter { it.isDigit() }
                if (digits.length == 8) return VirtualNumberGenerator.format8Digits(digits)
            }
            // Strict account isolation: if an accountId is resolved, NEVER fall back to another account's number
            return ""
        }
        val raw = prefs.getString("private_virtual_number", "") ?: ""
        if (raw.isNotBlank()) {
            val digits = VirtualNumberGenerator.extractRaw8Digits(raw) ?: raw.filter { it.isDigit() }
            if (digits.length == 8) return VirtualNumberGenerator.format8Digits(digits)
        }
        return ""
    }

    /**
     * Returns the canonical raw 8 digits of the private virtual identity (e.g. "67676767").
     */
    fun getRawPrivateVirtualNumber(context: Context, accountId: String? = null): String {
        val targetId = accountId?.trim()?.ifBlank { null } ?: getUserId(context).trim().ifBlank { null }
        val prefs = getPrefs(context)
        if (targetId != null) {
            val accountRaw = prefs.getString("private_virtual_number_$targetId", "") ?: ""
            if (accountRaw.isNotBlank()) {
                val digits = VirtualNumberGenerator.extractRaw8Digits(accountRaw) ?: accountRaw.filter { it.isDigit() }
                if (digits.length == 8) return digits
            }
            // Strict account isolation: if an accountId is resolved, NEVER fall back to another account's number
            return ""
        }
        val raw = prefs.getString("private_virtual_number", "") ?: ""
        if (raw.isNotBlank()) {
            val digits = VirtualNumberGenerator.extractRaw8Digits(raw) ?: raw.filter { it.isDigit() }
            if (digits.length == 8) return digits
        }
        return ""
    }

    /**
     * Persists the private virtual number as canonical 8 raw digits.
     * Note: Does NOT pollute the regular cellular phone field.
     * Prevents empty-string wipe regressions: If [numberOrDigits] is blank,
     * existing valid cached numbers for this account are preserved.
     * Use [clearPrivateVirtualNumber] to explicitly clear.
     */
    fun setPrivateVirtualNumber(context: Context, numberOrDigits: String, accountId: String? = null) {
        val targetId = accountId?.trim()?.ifBlank { null } ?: getUserId(context).trim().ifBlank { null }
        val rawDigits = VirtualNumberGenerator.extractRaw8Digits(numberOrDigits)
            ?: numberOrDigits.filter { it.isDigit() }.let { if (it.length == 8) it else null }

        if (rawDigits != null && rawDigits.length == 8) {
            val editor = getPrefs(context).edit()
            editor.putString("private_virtual_number", rawDigits)
            if (targetId != null) {
                editor.putString("private_virtual_number_$targetId", rawDigits)
            }
            editor.apply()
        } else if (numberOrDigits.isBlank()) {
            // DO NOT wipe existing valid number on empty string, preventing login/signup race regression.
            Timber.d("setPrivateVirtualNumber called with blank value; preserving existing record for account: $targetId")
        }
    }

    /**
     * Explicitly clears the virtual number for an account and active session.
     */
    fun clearPrivateVirtualNumber(context: Context, accountId: String? = null) {
        val targetId = accountId?.trim()?.ifBlank { null } ?: getUserId(context).trim().ifBlank { null }
        val editor = getPrefs(context).edit()
        editor.remove("private_virtual_number")
        editor.remove("virtual_number")
        if (targetId != null) {
            editor.remove("private_virtual_number_$targetId")
        }
        editor.apply()
    }

    fun isTelegramVerified(context: Context): Boolean {
        return getPrefs(context).getBoolean("telegram_verified", false)
    }

    fun setTelegramVerified(context: Context, verified: Boolean) {
        getPrefs(context).edit().putBoolean("telegram_verified", verified).apply()
    }

    fun saveSupabaseTokens(context: Context, accessToken: String, refreshToken: String) {
        val cleanAccess = accessToken.trim()
        val cleanRefresh = refreshToken.trim()
        if (cleanAccess.isBlank() && cleanRefresh.isBlank()) {
            Timber.w("saveSupabaseTokens called with blank tokens; ignoring")
            return
        }
        getPrefs(context).edit()
            .putString("supabase_access_token", cleanAccess)
            .putString("supabase_refresh_token", cleanRefresh)
            .commit()
    }

    fun clearTokensOnly(context: Context) {
        getPrefs(context).edit()
            .remove("supabase_access_token")
            .remove("supabase_refresh_token")
            .commit()
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
        clearTokensOnly(context)
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
        editor.commit()
    }

    fun setAvatarUri(context: Context, avatarUri: String) {
        getPrefs(context).edit().putString("avatarUri", avatarUri).apply()
    }

    fun getProfileBackgroundPath(context: Context, accountId: String): String? {
        val cleanId = accountId.trim()
        if (cleanId.isBlank()) return null
        return getPrefs(context).getString("profile_bg_path_${cleanId}", null)
    }

    fun getProfileBackgroundType(context: Context, accountId: String): String? {
        val cleanId = accountId.trim()
        if (cleanId.isBlank()) return null
        return getPrefs(context).getString("profile_bg_type_${cleanId}", null)
    }

    fun setProfileBackground(context: Context, accountId: String, path: String?, type: String?) {
        val cleanId = accountId.trim()
        if (cleanId.isBlank()) return
        val editor = getPrefs(context).edit()
        if (path != null) {
            editor.putString("profile_bg_path_${cleanId}", path)
        } else {
            editor.remove("profile_bg_path_${cleanId}")
        }
        if (type != null) {
            editor.putString("profile_bg_type_${cleanId}", type)
        } else {
            editor.remove("profile_bg_type_${cleanId}")
        }
        editor.apply()
    }

    fun clearProfileBackground(context: Context, accountId: String) {
        setProfileBackground(context, accountId, null, null)
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
        return getPrefs(context).getString("ai_persona_${accountId}", "ventaxis") ?: "ventaxis"
    }

    fun setSelectedAiPersona(context: Context, accountId: String, personaId: String) {
        getPrefs(context).edit().putString("ai_persona_${accountId}", personaId).apply()
    }

    fun getSyncCursor(context: Context, accountId: String): Long {
        return getPrefs(context).getLong("sync_cursor_${accountId}", 0L)
    }

    fun setSyncCursor(context: Context, accountId: String, cursor: Long) {
        getPrefs(context).edit().putLong("sync_cursor_${accountId}", cursor).apply()
    }

    fun isHexShardPromptDismissed(context: Context, accountId: String): Boolean {
        return getPrefs(context).getBoolean("hexshard_prompt_dismissed_${accountId}", false)
    }

    fun setHexShardPromptDismissed(context: Context, accountId: String, dismissed: Boolean) {
        getPrefs(context).edit().putBoolean("hexshard_prompt_dismissed_${accountId}", dismissed).apply()
    }

    fun clear(context: Context) {
        try {
            val customKey = getCustomSupabaseAnonKey(context)
            val editor = getPrefs(context).edit().clear()
            if (!customKey.isNullOrBlank()) {
                editor.putString("custom_supabase_anon_key", customKey)
            }
            editor.commit()
        } catch (e: Exception) {
            Timber.w(e, "Error clearing encrypted preferences")
        }
        cachedPrefs = null
    }
}
