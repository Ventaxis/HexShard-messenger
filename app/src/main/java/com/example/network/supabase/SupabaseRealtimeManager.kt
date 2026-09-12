package com.example.network.supabase

import android.content.Context
import com.example.data.SecurePrefsManager
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real Supabase Realtime Client using Phoenix Channels WebSocket protocol.
 * Subscribes to PostgreSQL changes (INSERT, UPDATE, DELETE) on the `messages` table.
 * Replaces polling as the primary synchronization mechanism.
 */
class SupabaseRealtimeManager(
    private val context: Context,
    private val onMessageRecordReceived: (JSONObject) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit = {}
) {
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private val refCounter = AtomicInteger(1)
    private var reconnectAttempts = 0

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // Keep-alive for WebSockets
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    fun connect() {
        val currentUserId = SecurePrefsManager.getUserId(context)
        if (currentUserId.isBlank()) return

        val anonKey = SupabaseConfig.getAnonKey(context)
        if (anonKey.isBlank()) return

        if (isConnected.get() || isConnecting.get()) return
        isConnecting.set(true)

        val baseUrl = SupabaseConfig.getBaseUrl()
        val wsUrl = baseUrl.replaceFirst("http", "ws") + "/realtime/v1/websocket?apikey=$anonKey&vsn=1.0.0"

        val request = Request.Builder()
            .url(wsUrl)
            .header("apikey", anonKey)
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Timber.i("Supabase Realtime WebSocket connected")
                isConnected.set(true)
                isConnecting.set(false)
                reconnectAttempts = 0
                onConnectionStateChanged(true)

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
            while (isActive && isConnected.get()) {
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
            val ref = refCounter.incrementAndGet().toString()
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
                put("ref", ref)
            }

            ws.send(joinMsg.toString())
            Timber.i("Sent Realtime channel join request for user messages")
        } catch (e: Exception) {
            Timber.e(e, "Error sending phx_join to Supabase Realtime")
        }
    }

    private fun handleIncomingEvent(text: String) {
        try {
            val json = JSONObject(text)
            val event = json.optString("event")

            if (event == "postgres_changes") {
                val payload = json.optJSONObject("payload") ?: return
                val data = payload.optJSONObject("data")
                val record = data?.optJSONObject("record") ?: payload.optJSONObject("record")
                if (record != null) {
                    onMessageRecordReceived(record)
                }
            }
        } catch (e: Exception) {
            Timber.w("Error parsing Realtime event: ${e.message}")
        }
    }

    private fun handleDisconnect() {
        isConnected.set(false)
        isConnecting.set(false)
        heartbeatJob?.cancel()
        onConnectionStateChanged(false)

        // Schedule reconnection with exponential backoff (max 30s)
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
        isConnected.set(false)
        isConnecting.set(false)
        try {
            webSocket?.close(1000, "Client disconnect")
        } catch (_: Exception) {}
        webSocket = null
    }

    fun isRealtimeConnected(): Boolean = isConnected.get()
}
