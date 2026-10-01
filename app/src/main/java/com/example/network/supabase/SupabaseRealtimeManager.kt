package com.example.network.supabase

import android.content.Context
import com.example.data.SecurePrefsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class RealtimeConnectionState {
    DISCONNECTED,
    CONNECTING,
    JOINING,
    CONNECTED,
    RECONNECTING,
    FAILED
}

/**
 * Robust Supabase Realtime Client using Phoenix Channels WebSocket protocol.
 * Subscribes to PostgreSQL changes on the `messages` table with an explicit state machine.
 */
class SupabaseRealtimeManager(
    private val context: Context,
    private val onMessageRecordReceived: (JSONObject) -> Unit,
    private val onConnectionStateChanged: (RealtimeConnectionState) -> Unit = {},
    private val onChannelJoined: () -> Unit = {}
) {
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    private val _connectionState = MutableStateFlow(RealtimeConnectionState.DISCONNECTED)
    val connectionState: StateFlow<RealtimeConnectionState> = _connectionState.asStateFlow()

    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private val refCounter = AtomicInteger(1)
    private var activeJoinRef: String? = null
    private var reconnectAttempts = 0

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private fun setState(state: RealtimeConnectionState) {
        if (_connectionState.value != state) {
            _connectionState.value = state
            Timber.d("Supabase Realtime state changed to: $state")
            onConnectionStateChanged(state)
        }
    }

    fun connect() {
        val currentUserId = SecurePrefsManager.getUserId(context)
        if (currentUserId.isBlank()) {
            setState(RealtimeConnectionState.DISCONNECTED)
            return
        }

        val anonKey = SupabaseConfig.getAnonKey(context)
        if (anonKey.isBlank()) {
            setState(RealtimeConnectionState.FAILED)
            return
        }

        val currentState = _connectionState.value
        if (currentState == RealtimeConnectionState.CONNECTING || 
            currentState == RealtimeConnectionState.JOINING || 
            currentState == RealtimeConnectionState.CONNECTED) {
            return
        }

        setState(RealtimeConnectionState.CONNECTING)

        val baseUrl = SupabaseConfig.getBaseUrl()
        val wsUrl = baseUrl.replaceFirst("http", "ws") + "/realtime/v1/websocket?apikey=$anonKey&vsn=1.0.0"

        val request = Request.Builder()
            .url(wsUrl)
            .header("apikey", anonKey)
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Timber.i("Supabase Realtime WebSocket opened, transitioning to JOINING")
                setState(RealtimeConnectionState.JOINING)
                reconnectAttempts = 0

                startHeartbeat(ws)
                joinMessagesChannel(ws, currentUserId, anonKey)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleIncomingEvent(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Timber.d("Supabase Realtime WebSocket closing: $code / $reason")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Timber.d("Supabase Realtime WebSocket closed: $code / $reason")
                handleDisconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Timber.w("Supabase Realtime WebSocket failure: ${t.message}")
                handleDisconnect()
            }
        })
    }

    private fun startHeartbeat(ws: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = clientScope.launch {
            while (isActive && (_connectionState.value == RealtimeConnectionState.JOINING || _connectionState.value == RealtimeConnectionState.CONNECTED)) {
                delay(25_000)
                try {
                    val ref = refCounter.incrementAndGet().toString()
                    val heartbeat = JSONObject().apply {
                        put("topic", "phoenix")
                        put("event", "heartbeat")
                        put("payload", JSONObject())
                        put("ref", ref)
                    }
                    ws.send(heartbeat.toString())
                } catch (e: Exception) {
                    Timber.w("Failed to send Realtime heartbeat: ${e.message}")
                }
            }
        }
    }

    private fun joinMessagesChannel(ws: WebSocket, currentUserId: String, anonKey: String) {
        try {
            val joinRef = refCounter.incrementAndGet().toString()
            activeJoinRef = joinRef
            val token = SecurePrefsManager.getSupabaseAccessToken(context).takeIf { it.isNotBlank() } ?: anonKey

            val payload = JSONObject().apply {
                val config = JSONObject().apply {
                    put("broadcast", JSONObject().put("self", false))
                    put("presence", JSONObject().put("key", ""))
                    val pgChanges = JSONArray().apply {
                        put(
                            JSONObject().apply {
                                put("event", "*")
                                put("schema", "public")
                                put("table", "messages")
                                put("filter", "recipient_id=eq.$currentUserId")
                            }
                        )
                        put(
                            JSONObject().apply {
                                put("event", "*")
                                put("schema", "public")
                                put("table", "messages")
                                put("filter", "sender_id=eq.$currentUserId")
                            }
                        )
                    }
                    put("postgres_changes", pgChanges)
                }
                put("config", config)
                put("access_token", token)
            }

            val joinMsg = JSONObject().apply {
                put("topic", "realtime:public:messages")
                put("event", "phx_join")
                put("payload", payload)
                put("ref", joinRef)
                put("join_ref", joinRef)
            }

            ws.send(joinMsg.toString())
            Timber.i("Sent Realtime channel join request (ref=$joinRef) for user messages")
        } catch (e: Exception) {
            Timber.e(e, "Error sending phx_join to Supabase Realtime")
            setState(RealtimeConnectionState.FAILED)
            handleDisconnect()
        }
    }

    private fun handleIncomingEvent(text: String) {
        try {
            val json = JSONObject(text)
            val event = json.optString("event")
            val ref = json.optString("ref")

            when (event) {
                "phx_reply" -> {
                    val payload = json.optJSONObject("payload")
                    val status = payload?.optString("status")
                    if (ref == activeJoinRef || json.optString("topic") == "realtime:public:messages") {
                        if (status == "ok") {
                            Timber.i("Realtime channel successfully joined! Transitioning to CONNECTED")
                            setState(RealtimeConnectionState.CONNECTED)
                            onChannelJoined()
                        } else if (status == "error") {
                            Timber.w("Realtime channel join rejected: ${payload?.opt("response")}")
                            setState(RealtimeConnectionState.FAILED)
                            handleDisconnect()
                        }
                    }
                }
                "phx_error", "phx_close" -> {
                    Timber.w("Realtime channel error/close event received: $event")
                    handleDisconnect()
                }
                "postgres_changes" -> {
                    val payload = json.optJSONObject("payload") ?: return
                    val data = payload.optJSONObject("data")
                    val record = data?.optJSONObject("record") ?: payload.optJSONObject("record")
                    if (record != null) {
                        onMessageRecordReceived(record)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w("Error parsing Realtime event: ${e.message}")
        }
    }

    private fun handleDisconnect() {
        val wasConnected = _connectionState.value == RealtimeConnectionState.CONNECTED
        heartbeatJob?.cancel()
        setState(RealtimeConnectionState.RECONNECTING)

        // Exponential backoff reconnection up to 30s
        reconnectJob?.cancel()
        reconnectJob = clientScope.launch {
            val backoffMs = (1000L * (1 shl reconnectAttempts.coerceAtMost(5))).coerceAtMost(30_000L)
            reconnectAttempts++
            Timber.d("Scheduling Realtime reconnect in ${backoffMs}ms (attempt #$reconnectAttempts)")
            delay(backoffMs)
            connect()
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        setState(RealtimeConnectionState.DISCONNECTED)
        try {
            webSocket?.close(1000, "Client disconnect")
        } catch (_: Exception) {}
        webSocket = null
        activeJoinRef = null
    }

    fun isRealtimeConnected(): Boolean = _connectionState.value == RealtimeConnectionState.CONNECTED
}
