package com.example.calls

import android.content.Context
import com.example.data.SecurePrefsManager
import com.example.network.supabase.SupabaseConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One decoded `call_signal` payload received from the remote peer. */
data class CallSignal(
    val kind: String,
    val fromUid: String,
    val callId: String,
    val sdp: String? = null,
    val candidateSdp: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int = -1
)

/**
 * Phoenix-Channel signaling client for 1:1 audio calls.
 *
 * [SupabaseRealtimeManager] is hard-wired to the `realtime:public:messages`
 * topic, so this manager opens its OWN WebSocket to the same Realtime
 * endpoint (identical URL / apikey header pattern) and joins deterministic
 * pair topics ([CallSignaling.topicFor]). Signaling runs purely through
 * Realtime broadcast — no backing table or RLS policy is required.
 */
object CallSignalingManager {

    /** A contact we keep a signaling topic open for, so incoming calls ring. */
    data class CallWatchPeer(val uid: String, val name: String)

    private const val JOIN_TIMEOUT_MS = 10_000L
    private const val HEARTBEAT_INTERVAL_MS = 25_000L
    private const val MAX_RECONNECT_BACKOFF_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _signals = MutableSharedFlow<CallSignal>(extraBufferCapacity = 64)
    val signals: SharedFlow<CallSignal> = _signals.asSharedFlow()

    private val _joinedTopics = MutableStateFlow<Set<String>>(emptySet())
    val joinedTopics: StateFlow<Set<String>> = _joinedTopics.asStateFlow()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var appContext: Context? = null
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var myUid: String = ""
    @Volatile private var desiredTopics: Set<String> = emptySet()
    @Volatile private var connectionGeneration = 0

    private val refCounter = AtomicInteger(1)
    private val pendingJoins = ConcurrentHashMap<String, String>() // join ref -> topic
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    /**
     * Keeps one joined signaling topic per known peer so an incoming `offer`
     * broadcast reaches us even when the app sits on any screen.
     */
    fun setWatchPeers(me: String, peers: List<CallWatchPeer>) {
        if (me.isBlank()) return
        myUid = me
        desiredTopics = peers.mapNotNull { peer ->
            val uid = peer.uid.trim()
            if (uid.isBlank() || uid == me ||
                uid == "self" || uid.startsWith("self_") ||
                uid == "ai_assistant" || uid.startsWith("ai_")
            ) {
                null
            } else {
                CallSignaling.topicFor(me, uid)
            }
        }.toSet()

        val ws = webSocket
        if (ws == null) {
            ensureConnection()
        } else {
            joinMissingTopics()
        }
    }

    /** Opens the signaling WebSocket if it is not alive yet. */
    fun ensureConnection() {
        synchronized(this) {
            if (webSocket != null) return
        }
        val context = appContext ?: return
        val uid = SecurePrefsManager.getUserId(context)
        val token = SecurePrefsManager.getSupabaseAccessToken(context)
        val anonKey = SupabaseConfig.getAnonKey(context)
        if (uid.isBlank() || token.isBlank() || anonKey.isBlank()) {
            Timber.d("Call signaling not started: missing uid/token/anon key")
            return
        }

        myUid = uid
        connectionGeneration++
        val generation = connectionGeneration

        val baseUrl = SupabaseConfig.getBaseUrl()
        val wsUrl = baseUrl.replaceFirst("http", "ws") + "/realtime/v1/websocket?apikey=$anonKey&vsn=1.0.0"
        val request = Request.Builder()
            .url(wsUrl)
            .header("apikey", anonKey)
            .build()

        Timber.i("Opening call signaling WebSocket")
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (connectionGeneration != generation) {
                    ws.close(1000, "Stale connection")
                    return
                }
                Timber.i("Call signaling WebSocket opened")
                reconnectAttempts = 0
                startHeartbeat(ws, generation)
                joinMissingTopics()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (connectionGeneration == generation) {
                    handleIncomingMessage(text)
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Timber.d("Call signaling WebSocket closed: $code")
                scheduleReconnect(generation)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Timber.w("Call signaling WebSocket failure: ${t.message}")
                scheduleReconnect(generation)
            }
        })
    }

    /**
     * Pushes a fresh access token into every joined call topic (supabase-js
     * parity with `access_token` channel messages) — called before dialing
     * so long-lived sockets never dial with an expired token.
     */
    fun refreshAccessToken() {
        val context = appContext ?: return
        val token = SecurePrefsManager.getSupabaseAccessToken(context)
        if (token.isBlank()) return
        val ws = webSocket ?: return
        for (topic in _joinedTopics.value) {
            try {
                val ref = refCounter.incrementAndGet().toString()
                val msg = JSONObject().apply {
                    put("topic", topic)
                    put("event", "access_token")
                    put("payload", JSONObject().put("access_token", token))
                    put("ref", ref)
                }
                ws.send(msg.toString())
            } catch (e: Exception) {
                Timber.w("Failed to refresh call signaling access token: ${e.message}")
            }
        }
    }

    /** Sends one call signal as a broadcast on the pair topic of [peerUid]. */
    fun sendSignal(peerUid: String, payload: Map<String, Any?>) {
        scope.launch {
            if (myUid.isBlank()) {
                val context = appContext ?: return@launch
                myUid = SecurePrefsManager.getUserId(context)
                if (myUid.isBlank()) return@launch
            }
            val topic = CallSignaling.topicFor(myUid, peerUid)
            if (!awaitTopicJoined(topic, JOIN_TIMEOUT_MS)) {
                Timber.w("Call signal dropped: topic not joined (kind=${payload["kind"]})")
                return@launch
            }
            val ws = webSocket ?: return@launch
            try {
                val ref = refCounter.incrementAndGet().toString()
                val frame = JSONObject(CallSignaling.broadcastEnvelope(topic, payload))
                if (ws.send(frame.toString())) {
                    Timber.i("Call signal sent (kind=${payload["kind"]})")
                } else {
                    Timber.w("Call signal enqueue failed (kind=${payload["kind"]})")
                }
            } catch (e: Exception) {
                Timber.w("Call signal send error (kind=${payload["kind"]}): ${e.message}")
            }
        }
    }

    /** Suspends until [topic] reports a successful phx_join or times out. */
    suspend fun awaitTopicJoined(topic: String, timeoutMs: Long = JOIN_TIMEOUT_MS): Boolean =
        withTimeoutOrNull(timeoutMs) {
            _joinedTopics.first { topic in it }
            true
        } ?: false

    private fun joinMissingTopics() {
        val context = appContext ?: return
        val token = SecurePrefsManager.getSupabaseAccessToken(context)
        val ws = webSocket ?: return
        if (token.isBlank()) return

        for (topic in desiredTopics) {
            if (topic in _joinedTopics.value || pendingJoins.containsValue(topic)) continue
            try {
                val ref = refCounter.incrementAndGet().toString()
                pendingJoins[ref] = topic
                val frame = JSONObject(CallSignaling.joinEnvelope(topic, ref, token))
                ws.send(frame.toString())

                // Expire stale join refs so retried joins are not blocked forever
                scope.launch {
                    delay(JOIN_TIMEOUT_MS)
                    if (pendingJoins.remove(ref) != null) {
                        Timber.w("Call topic join timed out")
                    }
                }
            } catch (e: Exception) {
                Timber.w("Failed to send call topic join: ${e.message}")
            }
        }
    }

    private fun startHeartbeat(ws: WebSocket, expectedGen: Int) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && connectionGeneration == expectedGen && webSocket === ws) {
                delay(HEARTBEAT_INTERVAL_MS)
                try {
                    val heartbeat = JSONObject().apply {
                        put("topic", "phoenix")
                        put("event", "heartbeat")
                        put("payload", JSONObject())
                        put("ref", refCounter.incrementAndGet().toString())
                    }
                    ws.send(heartbeat.toString())
                } catch (e: Exception) {
                    Timber.w("Call signaling heartbeat failed: ${e.message}")
                }
            }
        }
    }

    private fun scheduleReconnect(generation: Int) {
        if (connectionGeneration != generation) return
        heartbeatJob?.cancel()
        pendingJoins.clear()
        _joinedTopics.value = emptySet()
        webSocket = null

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val backoffMs = (1000L * (1 shl reconnectAttempts.coerceAtMost(5))).coerceAtMost(MAX_RECONNECT_BACKOFF_MS)
            reconnectAttempts++
            Timber.d("Call signaling reconnect in ${backoffMs}ms (attempt #$reconnectAttempts)")
            delay(backoffMs)
            if (connectionGeneration == generation && webSocket == null) {
                ensureConnection()
            }
        }
    }

    private fun markJoined(topic: String) {
        if (topic !in _joinedTopics.value) {
            _joinedTopics.value = _joinedTopics.value + topic
            Timber.i("Call signaling topic joined")
        }
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("event")) {
                "phx_reply" -> {
                    val ref = json.optString("ref")
                    val status = json.optJSONObject("payload")?.optString("status")
                    val topic = pendingJoins.remove(ref)
                    if (topic != null && status == "ok") {
                        markJoined(topic)
                    }
                }
                "phx_error", "phx_close" -> {
                    Timber.w("Call signaling channel error/close event received")
                    scheduleReconnect(connectionGeneration)
                }
                "broadcast" -> parseBroadcast(json)
            }
        } catch (e: Exception) {
            Timber.w("Error parsing call signaling event: ${e.message}")
        }
    }

    /**
     * Tolerant broadcast parser. Depending on the Realtime server version the
     * broadcast body arrives either symmetric to what we send
     * (`payload = {type, event, payload}`) or wrapped by the server
     * (`payload = {data: {event, payload}}`) — both shapes are decoded here.
     */
    private fun parseBroadcast(json: JSONObject) {
        // SEC-001: drop frames for topics we never joined (topic confusion).
        if (!CallSignaling.broadcastFromJoinedTopic(
                json.optString("topic"),
                _joinedTopics.value
            )
        ) return
        val payload = json.optJSONObject("payload") ?: return
        val data = payload.optJSONObject("data")
        val layer = listOfNotNull(payload, data)
            .firstOrNull { it.optString("event") == CallSignaling.SIGNAL_EVENT } ?: return
        val body = layer.optJSONObject("payload") ?: return

        val fromUid = body.optString("fromUid")
        // Self-echo protection (broadcast.self is false, but never trust the wire)
        if (fromUid.isBlank() || fromUid == myUid) return

        val kind = body.optString("kind")
        val signal = CallSignal(
            kind = kind,
            fromUid = fromUid,
            callId = body.optString("callId"),
            sdp = body.optString("sdp").takeIf { it.isNotBlank() && (kind == CallSignaling.KIND_OFFER || kind == CallSignaling.KIND_ANSWER) },
            candidateSdp = body.optString("candidate").takeIf { it.isNotBlank() },
            sdpMid = body.optString("sdpMid").takeIf { it.isNotBlank() },
            sdpMLineIndex = body.optInt("sdpMLineIndex", -1)
        )
        Timber.i("Call signal received (kind=$kind)")
        if (!_signals.tryEmit(signal)) {
            Timber.w("Call signal dropped: buffer overflow (kind=$kind)")
        }
    }
}
