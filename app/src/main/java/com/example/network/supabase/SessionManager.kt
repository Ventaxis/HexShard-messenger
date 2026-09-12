package com.example.network.supabase

import android.content.Context
import com.example.data.AccountType
import com.example.data.SecurePrefsManager
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
            val vNum = SecurePrefsManager.getPrivateVirtualNumber(context)
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
            if (resp.isSuccessful) {
                val vNum = SecurePrefsManager.getPrivateVirtualNumber(context)
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
            } else if (resp.code == 401 && refreshToken.isNotBlank()) {
                // Token expired - attempt single-flight token refresh
                return@withContext refreshSession(context, refreshToken)
            } else {
                Timber.w("Supabase token validation rejected with status ${resp.code}")
                clearSession(context)
                return@withContext AuthState.UNAUTHENTICATED
            }
        } catch (e: Exception) {
            Timber.w(e, "Network exception during Supabase session validation. Using AUTHENTICATED_OFFLINE_CACHED.")
            val vNum = SecurePrefsManager.getPrivateVirtualNumber(context)
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
                val vNum = SecurePrefsManager.getPrivateVirtualNumber(context)
                val isTg = SecurePrefsManager.isTelegramVerified(context)
                val session = SessionData(
                    userId = SecurePrefsManager.getUserId(context),
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
                val body = resp.body?.string() ?: ""
                if (resp.isSuccessful && body.isNotEmpty()) {
                    val json = JSONObject(body)
                    val newAccess = json.optString("access_token")
                    val newRefresh = json.optString("refresh_token")
                    val userObj = json.optJSONObject("user")
                    val uid = userObj?.optString("id") ?: SecurePrefsManager.getUserId(context)

                    if (newAccess.isNotBlank()) {
                        SecurePrefsManager.saveSupabaseTokens(context, newAccess, newRefresh)
                        val vNum = SecurePrefsManager.getPrivateVirtualNumber(context)
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
                } else if (resp.code == 400 || resp.code == 401) {
                    Timber.w("Refresh token rejected by server (status ${resp.code}); clearing session")
                    clearSession(context)
                    _authState.value = AuthState.UNAUTHENTICATED
                    return@withLock AuthState.UNAUTHENTICATED
                }
            } catch (e: Exception) {
                Timber.e(e, "Token refresh network exception")
                // On transient network failure, mark offline cached
                _authState.value = AuthState.AUTHENTICATED_OFFLINE_CACHED
                return@withLock AuthState.AUTHENTICATED_OFFLINE_CACHED
            }

            clearSession(context)
            _authState.value = AuthState.UNAUTHENTICATED
            AuthState.UNAUTHENTICATED
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
        SecurePrefsManager.saveProfile(
            context = context,
            userId = userId,
            username = username,
            phone = "",
            accountType = AccountType.PHONE // Registered server-backed account
        )
        SecurePrefsManager.saveSupabaseTokens(context, accessToken, refreshToken)
        SecurePrefsManager.setPrivateVirtualNumber(context, virtualNumber)
        SecurePrefsManager.setTelegramVerified(context, isTelegramVerified)

        val session = SessionData(
            userId = userId,
            username = username,
            accessToken = accessToken,
            refreshToken = refreshToken,
            isTelegramVerified = isTelegramVerified,
            virtualNumber = virtualNumber
        )
        _currentSession.value = session
        _authState.value = AuthState.AUTHENTICATED
    }

    fun updateTelegramVerified(context: Context, verified: Boolean) {
        SecurePrefsManager.setTelegramVerified(context, verified)
        _currentSession.value = _currentSession.value?.copy(isTelegramVerified = verified)
    }

    fun updateVirtualNumber(context: Context, virtualNumber: String) {
        SecurePrefsManager.setPrivateVirtualNumber(context, virtualNumber)
        _currentSession.value = _currentSession.value?.copy(virtualNumber = virtualNumber)
    }

    fun clearSession(context: Context) {
        SecurePrefsManager.clear(context)
        _currentSession.value = null
        _authState.value = AuthState.UNAUTHENTICATED
    }
}
