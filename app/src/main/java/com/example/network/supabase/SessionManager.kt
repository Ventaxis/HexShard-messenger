package com.example.network.supabase

import android.content.Context
import com.example.data.AccountType
import com.example.data.SecurePrefsManager
import com.example.util.VirtualNumberGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AuthState {
    UNKNOWN,
    CHECKING,
    AUTHENTICATED,
    AUTHENTICATED_OFFLINE_CACHED,
    TOKEN_EXPIRED,
    UNAUTHENTICATED,
    REFRESHING
}

data class SessionData(
    val userId: String,
    val username: String,
    val accessToken: String,
    val refreshToken: String,
    val isTelegramVerified: Boolean,
    val virtualNumber: String
)

object SessionManager {

    private val _authState = MutableStateFlow(AuthState.UNKNOWN)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _currentSession = MutableStateFlow<SessionData?>(null)
    val currentSession: StateFlow<SessionData?> = _currentSession.asStateFlow()

    private val refreshMutex = Mutex()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

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
            Timber.w("Supabase anon key not configured; falling back to offline cached session")
            val vNum = SecurePrefsManager.getPrivateVirtualNumber(context, userId)
            val isTg = SecurePrefsManager.isTelegramVerified(context)
            val session = SessionData(
                userId = userId,
                username = username,
                accessToken = accessToken,
                refreshToken = refreshToken,
                isTelegramVerified = isTg,
                virtualNumber = vNum
            )
            _currentSession.value = session
            _authState.value = AuthState.AUTHENTICATED_OFFLINE_CACHED
            return@withContext AuthState.AUTHENTICATED_OFFLINE_CACHED
        }

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
                    val vNum = SecurePrefsManager.getPrivateVirtualNumber(context, userId)
                    val isTg = SecurePrefsManager.isTelegramVerified(context)
                    val session = SessionData(
                        userId = userId,
                        username = username,
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        isTelegramVerified = isTg,
                        virtualNumber = vNum
                    )
                    _currentSession.value = session
                    _authState.value = AuthState.AUTHENTICATED
                    return@withContext AuthState.AUTHENTICATED
                }
                code == 401 -> {
                    // Token expired - attempt single-flight token refresh
                    if (refreshToken.isNotBlank()) {
                        return@withContext refreshSession(context, refreshToken)
                    } else {
                        clearSessionSoft(context)
                        return@withContext AuthState.UNAUTHENTICATED
                    }
                }
                code == 408 || code == 429 || code in 500..599 -> {
                    // VPN / transient rate-limit / server 5xx: NEVER wipe session or tokens!
                    Timber.w("Supabase returned status $code ($body). Preserving session as AUTHENTICATED_OFFLINE_CACHED.")
                    val vNum = SecurePrefsManager.getPrivateVirtualNumber(context, userId)
                    val isTg = SecurePrefsManager.isTelegramVerified(context)
                    val session = SessionData(
                        userId = userId,
                        username = username,
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        isTelegramVerified = isTg,
                        virtualNumber = vNum
                    )
                    _currentSession.value = session
                    _authState.value = AuthState.AUTHENTICATED_OFFLINE_CACHED
                    return@withContext AuthState.AUTHENTICATED_OFFLINE_CACHED
                }
                code == 403 -> {
                    // 403 Forbidden: check if refresh token can recover
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
            val session = SessionData(
                userId = userId,
                username = username,
                accessToken = accessToken,
                refreshToken = refreshToken,
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
            // Check if another coroutine already refreshed the token
            val currentStoredAccess = SecurePrefsManager.getSupabaseAccessToken(context)
            val currentStoredRefresh = SecurePrefsManager.getSupabaseRefreshToken(context)
            if (currentStoredRefresh.isNotBlank() && currentStoredRefresh != refreshToken && currentStoredAccess.isNotBlank()) {
                val uid = SecurePrefsManager.getUserId(context)
                val vNum = SecurePrefsManager.getPrivateVirtualNumber(context, uid)
                val isTg = SecurePrefsManager.isTelegramVerified(context)
                val session = SessionData(
                    userId = uid,
                    username = SecurePrefsManager.getUsername(context),
                    accessToken = currentStoredAccess,
                    refreshToken = currentStoredRefresh,
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
                _authState.value = AuthState.AUTHENTICATED_OFFLINE_CACHED
                return@withLock AuthState.AUTHENTICATED_OFFLINE_CACHED
            }

            val payload = JSONObject().apply {
                put("refresh_token", refreshToken)
            }

            val req = Request.Builder()
                .url("$baseUrl/auth/v1/token?grant_type=refresh_token")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            try {
                val resp = httpClient.newCall(req).execute()
                val code = resp.code
                val body = resp.body?.string() ?: ""

                if (resp.isSuccessful && body.isNotEmpty()) {
                    val json = JSONObject(body)
                    val newAccess = json.optString("access_token")
                    val newRefresh = json.optString("refresh_token")
                    val userObj = json.optJSONObject("user")
                    val uid = userObj?.optString("id") ?: SecurePrefsManager.getUserId(context)

                    if (newAccess.isNotBlank()) {
                        SecurePrefsManager.saveSupabaseTokens(context, newAccess, newRefresh)
                        val vNum = SecurePrefsManager.getPrivateVirtualNumber(context, uid)
                        val isTg = SecurePrefsManager.isTelegramVerified(context)
                        val session = SessionData(
                            userId = uid,
                            username = SecurePrefsManager.getUsername(context),
                            accessToken = newAccess,
                            refreshToken = newRefresh,
                            isTelegramVerified = isTg,
                            virtualNumber = vNum
                        )
                        _currentSession.value = session
                        _authState.value = AuthState.AUTHENTICATED
                        return@withLock AuthState.AUTHENTICATED
                    }
                } else if (code == 400 || code == 401) {
                    Timber.w("Refresh token rejected by server (status $code); clearing tokens only")
                    clearSessionSoft(context)
                    _authState.value = AuthState.UNAUTHENTICATED
                    return@withLock AuthState.UNAUTHENTICATED
                } else {
                    Timber.w("Token refresh non-terminal error ($code). Keeping cached session.")
                    _authState.value = AuthState.AUTHENTICATED_OFFLINE_CACHED
                    return@withLock AuthState.AUTHENTICATED_OFFLINE_CACHED
                }
            } catch (e: Exception) {
                Timber.e(e, "Token refresh network exception")
                // On transient network failure, mark offline cached
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

        SecurePrefsManager.saveProfile(
            context = context,
            userId = userId,
            username = username,
            phone = "",
            accountType = AccountType.PHONE // Registered server-backed account
        )
        SecurePrefsManager.saveSupabaseTokens(context, accessToken, refreshToken)

        // Account-scoped virtual number handling:
        // 1. If server provides a valid virtualNumber, save it for this userId.
        // 2. If server provides blank (e.g. transient error during login), check if this exact account
        //    already holds a valid local cached number. If so, preserve it instead of wiping.
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

        val session = SessionData(
            userId = userId,
            username = username,
            accessToken = accessToken,
            refreshToken = refreshToken,
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
        _currentSession.value = null
        _authState.value = AuthState.UNAUTHENTICATED
    }

    fun clearSession(context: Context) {
        clearSessionHard(context)
    }
}
