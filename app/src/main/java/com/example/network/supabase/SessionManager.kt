package com.example.network.supabase

import android.content.Context
import com.example.data.AccountType
import com.example.data.SecurePrefsManager
import com.example.util.VirtualNumberGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class AuthState {
    UNKNOWN,
    CHECKING,
    AUTHENTICATED,
    AUTHENTICATED_OFFLINE_CACHED,
    TOKEN_EXPIRED,
    UNAUTHENTICATED,
    REFRESHING,
    CONFIGURATION_ERROR
}

data class SessionRecord(
    val userId: String,
    val username: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long = 0L,
    val generation: Long = 0L,
    val isTelegramVerified: Boolean = false,
    val virtualNumber: String = ""
)

typealias SessionData = SessionRecord

object SessionManager {

    private val _authState = MutableStateFlow(AuthState.UNKNOWN)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _currentSession = MutableStateFlow<SessionRecord?>(null)
    val currentSession: StateFlow<SessionRecord?> = _currentSession.asStateFlow()

    private val sessionGeneration = AtomicLong(1L)
    private val refreshMutex = Mutex()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    fun getCurrentGeneration(): Long = sessionGeneration.get()

    fun isGenerationActive(generation: Long): Boolean = sessionGeneration.get() == generation

    fun nextGeneration(): Long = sessionGeneration.incrementAndGet()

    suspend fun checkSession(context: Context): AuthState = withContext(Dispatchers.IO) {
        _authState.value = AuthState.CHECKING

        val accessToken = SecurePrefsManager.getSupabaseAccessToken(context)
        val refreshToken = SecurePrefsManager.getSupabaseRefreshToken(context)
        val userId = SecurePrefsManager.getUserId(context)
        val username = SecurePrefsManager.getUsername(context)

        if (accessToken.isBlank() || userId.isBlank()) {
            _currentSession.value = null
            _authState.value = AuthState.UNAUTHENTICATED
            return@withContext AuthState.UNAUTHENTICATED
        }

        // Validate token against Supabase Auth GoTrue endpoint
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        if (anonKey.isBlank()) {
            Timber.w("Supabase anon key not configured; reporting CONFIGURATION_ERROR")
            val vNum = SecurePrefsManager.getPrivateVirtualNumber(context, userId)
            val isTg = SecurePrefsManager.isTelegramVerified(context)
            val session = SessionRecord(
                userId = userId,
                username = username,
                accessToken = accessToken,
                refreshToken = refreshToken,
                generation = sessionGeneration.get(),
                isTelegramVerified = isTg,
                virtualNumber = vNum
            )
            _currentSession.value = session
            _authState.value = AuthState.CONFIGURATION_ERROR
            return@withContext AuthState.CONFIGURATION_ERROR
        }

        val currentGen = sessionGeneration.get()

        try {
            val req = Request.Builder()
                .url("$baseUrl/auth/v1/user")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            val resp = httpClient.newCall(req).execute()
            val code = resp.code
            val body = resp.body?.string() ?: ""

            when {
                resp.isSuccessful -> {
                    if (!isGenerationActive(currentGen)) {
                        Timber.w("Stale checkSession response discarded due to generation mismatch")
                        return@withContext _authState.value
                    }

                    // Verify server user identity
                    val serverUid = try {
                        JSONObject(body).optString("id")
                    } catch (_: Exception) { "" }

                    if (serverUid.isNotBlank() && serverUid != userId) {
                        Timber.e("Server user ID mismatch ($serverUid != $userId). Invalidate session.")
                        clearSessionSoft(context)
                        _authState.value = AuthState.UNAUTHENTICATED
                        return@withContext AuthState.UNAUTHENTICATED
                    }

                    val vNum = SecurePrefsManager.getPrivateVirtualNumber(context, userId)
                    val isTg = SecurePrefsManager.isTelegramVerified(context)
                    val session = SessionRecord(
                        userId = userId,
                        username = username,
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        generation = currentGen,
                        isTelegramVerified = isTg,
                        virtualNumber = vNum
                    )
                    _currentSession.value = session
                    _authState.value = AuthState.AUTHENTICATED
                    return@withContext AuthState.AUTHENTICATED
                }
                code == 401 -> {
                    val errLower = body.lowercase()
                    if (errLower.contains("invalid api key") || errLower.contains("invalid apikey")) {
                        Timber.w("Supabase public API key rejected by server (401: $body). Preserving user session as CONFIGURATION_ERROR.")
                        val vNum = SecurePrefsManager.getPrivateVirtualNumber(context, userId)
                        val isTg = SecurePrefsManager.isTelegramVerified(context)
                        val session = SessionRecord(
                            userId = userId,
                            username = username,
                            accessToken = accessToken,
                            refreshToken = refreshToken,
                            generation = currentGen,
                            isTelegramVerified = isTg,
                            virtualNumber = vNum
                        )
                        _currentSession.value = session
                        _authState.value = AuthState.CONFIGURATION_ERROR
                        return@withContext AuthState.CONFIGURATION_ERROR
                    }
                    // Token expired - attempt single-flight token refresh
                    if (refreshToken.isNotBlank()) {
                        return@withContext refreshSession(context, refreshToken)
                    } else {
                        clearSessionSoft(context)
                        _authState.value = AuthState.UNAUTHENTICATED
                        return@withContext AuthState.UNAUTHENTICATED
                    }
                }
                code == 408 || code == 429 || code in 500..599 -> {
                    // VPN / transient rate-limit / server 5xx: NEVER wipe session or tokens!
                    Timber.w("Supabase returned status $code ($body). Preserving session as AUTHENTICATED_OFFLINE_CACHED.")
                    val vNum = SecurePrefsManager.getPrivateVirtualNumber(context, userId)
                    val isTg = SecurePrefsManager.isTelegramVerified(context)
                    val session = SessionRecord(
                        userId = userId,
                        username = username,
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        generation = currentGen,
                        isTelegramVerified = isTg,
                        virtualNumber = vNum
                    )
                    _currentSession.value = session
                    _authState.value = AuthState.AUTHENTICATED_OFFLINE_CACHED
                    return@withContext AuthState.AUTHENTICATED_OFFLINE_CACHED
                }
                code == 403 -> {
                    if (refreshToken.isNotBlank()) {
                        val refState = refreshSession(context, refreshToken)
                        if (refState == AuthState.AUTHENTICATED || refState == AuthState.AUTHENTICATED_OFFLINE_CACHED) {
                            return@withContext refState
                        }
                    }
                    clearSessionSoft(context)
                    return@withContext AuthState.UNAUTHENTICATED
                }
                else -> {
                    Timber.w("Supabase token validation rejected with status $code: $body")
                    if (refreshToken.isNotBlank()) {
                        val refState = refreshSession(context, refreshToken)
                        if (refState == AuthState.AUTHENTICATED || refState == AuthState.AUTHENTICATED_OFFLINE_CACHED) {
                            return@withContext refState
                        }
                    }
                    clearSessionSoft(context)
                    return@withContext AuthState.UNAUTHENTICATED
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Network exception during Supabase session validation. Using AUTHENTICATED_OFFLINE_CACHED.")
            val vNum = SecurePrefsManager.getPrivateVirtualNumber(context, userId)
            val isTg = SecurePrefsManager.isTelegramVerified(context)
            val session = SessionRecord(
                userId = userId,
                username = username,
                accessToken = accessToken,
                refreshToken = refreshToken,
                generation = currentGen,
                isTelegramVerified = isTg,
                virtualNumber = vNum
            )
            _currentSession.value = session
            _authState.value = AuthState.AUTHENTICATED_OFFLINE_CACHED
            return@withContext AuthState.AUTHENTICATED_OFFLINE_CACHED
        }
    }

    suspend fun refreshSession(context: Context, refreshToken: String): AuthState = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val requestGen = sessionGeneration.get()

            // Check if another coroutine already refreshed the token
            val currentStoredAccess = SecurePrefsManager.getSupabaseAccessToken(context)
            val currentStoredRefresh = SecurePrefsManager.getSupabaseRefreshToken(context)
            if (currentStoredRefresh.isNotBlank() && currentStoredRefresh != refreshToken && currentStoredAccess.isNotBlank()) {
                val uid = SecurePrefsManager.getUserId(context)
                val vNum = SecurePrefsManager.getPrivateVirtualNumber(context, uid)
                val isTg = SecurePrefsManager.isTelegramVerified(context)
                val session = SessionRecord(
                    userId = uid,
                    username = SecurePrefsManager.getUsername(context),
                    accessToken = currentStoredAccess,
                    refreshToken = currentStoredRefresh,
                    generation = requestGen,
                    isTelegramVerified = isTg,
                    virtualNumber = vNum
                )
                _currentSession.value = session
                _authState.value = AuthState.AUTHENTICATED
                return@withLock AuthState.AUTHENTICATED
            }

            _authState.value = AuthState.REFRESHING
            val baseUrl = SupabaseConfig.getBaseUrl()
            val anonKey = SupabaseConfig.getAnonKey(context)
            if (anonKey.isBlank()) {
                Timber.w("Cannot refresh token without Supabase anon key")
                _authState.value = AuthState.CONFIGURATION_ERROR
                return@withLock AuthState.CONFIGURATION_ERROR
            }

            val payload = JSONObject().apply {
                put("refresh_token", refreshToken)
            }

            // CRITICAL: Refresh request uses ONLY apikey header. NEVER Authorization: Bearer $anonKey!
            val req = Request.Builder()
                .url("$baseUrl/auth/v1/token?grant_type=refresh_token")
                .header("apikey", anonKey)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            try {
                val resp = httpClient.newCall(req).execute()
                val code = resp.code
                val body = resp.body?.string() ?: ""

                // Verify session generation is still active (no account switch occurred during network call)
                if (!isGenerationActive(requestGen)) {
                    Timber.w("Refresh response discarded: session generation changed ($requestGen != ${sessionGeneration.get()})")
                    return@withLock _authState.value
                }

                if (resp.isSuccessful && body.isNotEmpty()) {
                    val json = JSONObject(body)
                    val newAccess = json.optString("access_token")
                    val rawNewRefresh = json.optString("refresh_token")
                    // If server didn't issue a new refresh token, preserve current valid refresh token!
                    val finalRefresh = if (rawNewRefresh.isNotBlank()) rawNewRefresh else refreshToken
                    val userObj = json.optJSONObject("user")
                    val uid = userObj?.optString("id") ?: SecurePrefsManager.getUserId(context)

                    if (newAccess.isNotBlank()) {
                        SecurePrefsManager.saveSupabaseTokens(context, newAccess, finalRefresh)
                        val vNum = SecurePrefsManager.getPrivateVirtualNumber(context, uid)
                        val isTg = SecurePrefsManager.isTelegramVerified(context)
                        val session = SessionRecord(
                            userId = uid,
                            username = SecurePrefsManager.getUsername(context),
                            accessToken = newAccess,
                            refreshToken = finalRefresh,
                            generation = requestGen,
                            isTelegramVerified = isTg,
                            virtualNumber = vNum
                        )
                        _currentSession.value = session
                        _authState.value = AuthState.AUTHENTICATED
                        return@withLock AuthState.AUTHENTICATED
                    }
                } else if (code == 400 || code == 401) {
                    val errLower = body.lowercase()
                    if (errLower.contains("invalid api key") || errLower.contains("invalid apikey")) {
                        Timber.w("Supabase anon key rejected by server during refresh ($code: $body); preserving session as CONFIGURATION_ERROR")
                        _authState.value = AuthState.CONFIGURATION_ERROR
                        return@withLock AuthState.CONFIGURATION_ERROR
                    } else if (errLower.contains("invalid_grant") || errLower.contains("refresh_token_not_found") || errLower.contains("invalid refresh token")) {
                        Timber.w("Refresh token rejected by server ($code: $body); invalidating session")
                        clearSessionSoft(context)
                        _authState.value = AuthState.UNAUTHENTICATED
                        return@withLock AuthState.UNAUTHENTICATED
                    } else {
                        Timber.w("Token refresh returned $code: $body. Preserving cached session.")
                        _authState.value = AuthState.AUTHENTICATED_OFFLINE_CACHED
                        return@withLock AuthState.AUTHENTICATED_OFFLINE_CACHED
                    }
                } else {
                    Timber.w("Token refresh non-terminal status ($code). Keeping cached session.")
                    _authState.value = AuthState.AUTHENTICATED_OFFLINE_CACHED
                    return@withLock AuthState.AUTHENTICATED_OFFLINE_CACHED
                }
            } catch (e: Exception) {
                Timber.e(e, "Token refresh network exception")
                _authState.value = AuthState.AUTHENTICATED_OFFLINE_CACHED
                return@withLock AuthState.AUTHENTICATED_OFFLINE_CACHED
            }

            _authState.value = AuthState.AUTHENTICATED_OFFLINE_CACHED
            AuthState.AUTHENTICATED_OFFLINE_CACHED
        }
    }

    fun onLoginSuccess(
        context: Context,
        userId: String,
        username: String,
        accessToken: String,
        refreshToken: String,
        virtualNumber: String,
        isTelegramVerified: Boolean
    ) {
        if (accessToken.isBlank()) {
            Timber.w("onLoginSuccess called with empty access_token; ignoring")
            return
        }

        val newGen = nextGeneration()

        SecurePrefsManager.saveProfile(
            context = context,
            userId = userId,
            username = username,
            phone = "",
            accountType = AccountType.PHONE
        )
        SecurePrefsManager.saveSupabaseTokens(context, accessToken, refreshToken)

        val finalVirtualNumber = if (virtualNumber.isNotBlank()) {
            SecurePrefsManager.setPrivateVirtualNumber(context, virtualNumber, userId)
            VirtualNumberGenerator.format8Digits(virtualNumber)
        } else {
            val existingForAccount = SecurePrefsManager.getPrivateVirtualNumber(context, userId)
            if (existingForAccount.isNotBlank()) {
                existingForAccount
            } else {
                ""
            }
        }

        SecurePrefsManager.setTelegramVerified(context, isTelegramVerified)

        val session = SessionRecord(
            userId = userId,
            username = username,
            accessToken = accessToken,
            refreshToken = refreshToken,
            generation = newGen,
            isTelegramVerified = isTelegramVerified,
            virtualNumber = finalVirtualNumber
        )
        _currentSession.value = session
        _authState.value = AuthState.AUTHENTICATED
    }

    fun updateTelegramVerified(context: Context, verified: Boolean) {
        SecurePrefsManager.setTelegramVerified(context, verified)
        _currentSession.value = _currentSession.value?.copy(isTelegramVerified = verified)
    }

    fun updateVirtualNumber(context: Context, virtualNumber: String) {
        val uid = _currentSession.value?.userId ?: SecurePrefsManager.getUserId(context)
        SecurePrefsManager.setPrivateVirtualNumber(context, virtualNumber, uid)
        val formatted = if (virtualNumber.isNotBlank()) VirtualNumberGenerator.format8Digits(virtualNumber) else ""
        _currentSession.value = _currentSession.value?.copy(virtualNumber = formatted)
    }

    fun clearSessionSoft(context: Context) {
        nextGeneration()
        SecurePrefsManager.clearTokensOnly(context)
        _currentSession.value = null
        _authState.value = AuthState.UNAUTHENTICATED
    }

    fun clearSessionHard(context: Context) {
        val uid = _currentSession.value?.userId ?: SecurePrefsManager.getUserId(context)
        SecurePrefsManager.clearPrivateVirtualNumber(context, uid)
        SecurePrefsManager.setTelegramVerified(context, false)
        clearSessionSoft(context)
        SecurePrefsManager.saveProfile(context, "", "", "")
    }

    fun clearEverything(context: Context) {
        val uid = _currentSession.value?.userId ?: SecurePrefsManager.getUserId(context)
        SecurePrefsManager.clearPrivateVirtualNumber(context, uid)
        SecurePrefsManager.clear(context)
        nextGeneration()
        _currentSession.value = null
        _authState.value = AuthState.UNAUTHENTICATED
    }

    fun clearSession(context: Context) {
        clearSessionHard(context)
    }
}
