package com.example.calls

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import androidx.core.content.ContextCompat
import com.example.data.SecurePrefsManager
import com.example.data.ConversationType
import com.example.data.database.ChatEntity
import com.example.data.database.getConversationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import timber.log.Timber
import kotlin.coroutines.resume

/** Lifecycle of a 1:1 audio call, observed by [com.example.ui.CallOverlay]. */
sealed class CallState {
    object Idle : CallState()
    data class Calling(val peerId: String, val peerName: String) : CallState()
    data class Ringing(val peerId: String, val peerName: String, val fromSignal: Boolean) : CallState()
    data class Active(val peerId: String, val peerName: String) : CallState()
    data class Ended(val reason: String) : CallState()
}

/**
 * Owns the WebRTC peer connection for 1:1 audio calls and drives the
 * [CallState] machine. Signaling travels over Supabase Realtime broadcast
 * via [CallSignalingManager]; media travels peer-to-peer over WebRTC.
 *
 * All WebRTC work runs on the internal [scope] (Dispatchers.IO) — public
 * functions are safe to call from the UI thread.
 */
object CallManager {

    private const val STUN_URL = "stun:stun.l.google.com:19302"
    private const val MEDIA_STREAM_ID = "hexshard_call_stream"
    private const val AUDIO_TRACK_ID = "hexshard_call_audio"
    private const val JOIN_TIMEOUT_MS = 10_000L
    private const val ENDED_STATE_RESET_MS = 2_500L
    private const val RING_TIMEOUT_MS = 45_000L

    // All state mutations serialize on one thread — public API stays callable from UI.
    private val callDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "hexshard-calls")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + callDispatcher)

    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    /** Toggle buttons bound by the call overlay. */
    val isMicMuted = MutableStateFlow(false)
    val isSpeakerOn = MutableStateFlow(true)

    @Volatile private var appContext: Context? = null
    private val factoryLock = Any()
    private var factory: PeerConnectionFactory? = null

    // Peer connection and media tracks are only touched from [scope] jobs.
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var pendingRemoteOffer: SessionDescription? = null
    private val bufferedCandidates = mutableListOf<IceCandidate>()

    @Volatile private var callId: String = ""
    @Volatile private var peerUid: String = ""
    @Volatile private var peerName: String = ""
    @Volatile private var isEnding = false
    @Volatile private var watchPeers: Map<String, String> = emptyMap() // uid -> display name

    private var ringtone: Ringtone? = null
    private var endedResetJob: Job? = null
    private var ringTimeoutJob: Job? = null

    /** Call provenance for the missed-call chat entry (set in beginCall/OFFER). */
    @Volatile private var callWasIncoming = false
    @Volatile private var callWasAnswered = false

    /**
     * Fired at call end for unanswered calls: the UI layer inserts a local
     * "missed call / no answer" chat entry. Args: peerUid, incoming, answered.
     */
    var onCallEvent: ((peerUid: String, incoming: Boolean, answered: Boolean) -> Unit)? = null

    //region Lifecycle

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        CallSignalingManager.init(appContext!!)
        startSignalCollection()
        // Warm up the factory in the background so first dial is fast; a
        // failure here is non-fatal and retried lazily by ensureFactory().
        scope.launch { ensureFactory() }
    }

    /**
     * Feeds the direct contacts whose signaling topics must stay joined so
     * incoming calls ring even when the user is inside a chat.
     */
    fun updateWatchlist(chats: List<ChatEntity>) {
        val context = appContext ?: return
        val me = SecurePrefsManager.getUserId(context)
        if (me.isBlank()) return
        val peers = chats
            .filter { it.getConversationType() == ConversationType.DIRECT && it.recipientId.isNotBlank() }
            .distinctBy { it.recipientId }
            .map { CallSignalingManager.CallWatchPeer(it.recipientId, it.name) }
        watchPeers = peers.associate { it.uid to it.name }
        CallSignalingManager.setWatchPeers(me, peers)
    }

    //endregion

    /**
     * Dev self-check: join the self-pair signaling topic (RLS allows uid twice)
     * and report success — proves the private-channel path end to end.
     */
    suspend fun signalSelfCheck(context: Context): Boolean {
        val uid = SecurePrefsManager.getUserId(context)
        if (uid.isBlank()) return false
        CallSignalingManager.refreshAccessToken()
        val topic = CallSignaling.topicFor(uid, uid)
        CallSignalingManager.ensureTopic(topic)
        return CallSignalingManager.awaitTopicJoined(topic, JOIN_TIMEOUT_MS)
    }

    //region Outgoing call

    fun startOutgoingCall(peerId: String, peerName: String) {
        scope.launch {
            val current = _state.value
            if (current !is CallState.Idle && current !is CallState.Ended) {
                _state.value = CallState.Ended("busy")
                scheduleEndedReset()
                return@launch
            }

            val context = appContext
            if (context == null || !ensureFactory()) {
                _state.value = CallState.Ended("audio_init_failed")
                scheduleEndedReset()
                return@launch
            }

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                _state.value = CallState.Ended("mic_denied")
                scheduleEndedReset()
                return@launch
            }

            val myUid = SecurePrefsManager.getUserId(context)
            if (myUid.isBlank() || peerId.isBlank()) {
                _state.value = CallState.Ended("peer_unavailable")
                scheduleEndedReset()
                return@launch
            }

            beginCall(peerId, peerName)
            _state.value = CallState.Calling(peerId, peerName)

            CallSignalingManager.refreshAccessToken()
            CallSignalingManager.ensureConnection()
            val topic = CallSignaling.topicFor(myUid, peerId)
            if (!CallSignalingManager.awaitTopicJoined(topic, JOIN_TIMEOUT_MS)) {
                endCall("signaling_failed", sendBye = false)
                return@launch
            }

            val pc = createPeerConnection() ?: run {
                endCall("audio_init_failed", sendBye = false)
                return@launch
            }
            attachLocalAudio(pc)

            val offerConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            }
            val offer = createOffer(pc, offerConstraints) ?: run {
                endCall("connection_setup_failed", sendBye = false)
                return@launch
            }
            if (setLocalDescription(pc, offer) == null) {
                endCall("connection_setup_failed", sendBye = false)
                return@launch
            }

            CallSignalingManager.sendSignal(peerId, CallSignaling.offerPayload(myUid, callId, offer.description))
            Timber.i("Outgoing call started (state=Calling)")
        }
    }

    //endregion

    //region Incoming call

    fun acceptIncomingCall() {
        scope.launch {
            val current = _state.value
            if (current !is CallState.Ringing) return@launch
            stopRingtone()

            val context = appContext
            if (context == null || !ensureFactory()) {
                endCall("audio_init_failed", sendBye = false)
                return@launch
            }
            val myUid = SecurePrefsManager.getUserId(context)
            if (myUid.isBlank()) {
                endCall("peer_unavailable", sendBye = false)
                return@launch
            }

            // Remote offer already buffered while ringing; reuse it directly
            val remoteOffer = pendingRemoteOffer ?: run {
                endCall("incoming_stale", sendBye = false)
                return@launch
            }

            _state.value = CallState.Calling(current.peerId, current.peerName)
            val pc = createPeerConnection() ?: run {
                endCall("audio_init_failed", sendBye = false)
                return@launch
            }
            attachLocalAudio(pc)

            if (setRemoteDescription(pc, remoteOffer) == null) {
                endCall("connection_setup_failed", sendBye = false)
                return@launch
            }
            flushBufferedCandidates(pc)

            val answerConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            }
            val answer = createAnswer(pc, answerConstraints) ?: run {
                endCall("connection_setup_failed", sendBye = false)
                return@launch
            }
            if (setLocalDescription(pc, answer) == null) {
                endCall("connection_setup_failed", sendBye = false)
                return@launch
            }

            CallSignalingManager.sendSignal(peerUid, CallSignaling.answerPayload(myUid, callId, answer.description))
            Timber.i("Incoming call accepted (state=Calling)")
        }
    }

    fun declineIncomingCall() {
        scope.launch {
            if (_state.value !is CallState.Ringing) return@launch
            stopRingtone()
            endCall("declined", sendBye = true)
        }
    }

    //endregion

    //region Shared controls

    fun hangUp() {
        scope.launch {
            if (_state.value is CallState.Idle) return@launch
            endCall("call_ended", sendBye = true)
        }
    }

    fun toggleMic() {
        val muted = !isMicMuted.value
        isMicMuted.value = muted
        localAudioTrack?.setEnabled(!muted)
    }

    fun toggleSpeaker() {
        val on = !isSpeakerOn.value
        isSpeakerOn.value = on
        val audioManager = audioManager()
        if (audioManager != null && _state.value is CallState.Active) {
            audioManager.isSpeakerphoneOn = on
        }
    }

    //endregion

    //region Signaling intake

    private fun startSignalCollection() {
        scope.launch {
            CallSignalingManager.signals.collect { signal -> handleSignal(signal) }
        }
    }

    private suspend fun handleSignal(signal: CallSignal) {
        val myUid = appContext?.let { SecurePrefsManager.getUserId(it) } ?: ""
        when (signal.kind) {
            CallSignaling.KIND_OFFER -> {
                // Ring whenever the line is free (Idle or just Ended); a busy
                // line politely answers with a bye so the caller hangs up.
                val current = _state.value
                if (current is CallState.Calling || current is CallState.Ringing || current is CallState.Active) {
                    CallSignalingManager.sendSignal(
                        signal.fromUid,
                        CallSignaling.byePayload(myUid, signal.callId, reason = "busy")
                    )
                    return
                }
                val context = appContext ?: return
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    return // Cannot answer without mic; overlay will not be shown
                }
                if (signal.sdp.isNullOrBlank() || signal.callId.isBlank()) return
                // SEC-001: ring only for accepted DIRECT contacts — a spoofed
                // offer from a stranger is dropped before the overlay appears.
                if (!CallSignaling.offerAllowed(watchPeers.keys, signal.fromUid)) {
                    Timber.w("Dropping call offer from unknown peer")
                    return
                }

                callId = signal.callId
                peerUid = signal.fromUid
                callWasIncoming = true
                peerName = watchPeers[signal.fromUid] ?: signal.fromUid.take(8)
                isEnding = false
                endedResetJob?.cancel()
                pendingRemoteOffer = SessionDescription(SessionDescription.Type.OFFER, signal.sdp)
                synchronized(bufferedCandidates) { bufferedCandidates.clear() }

                _state.value = CallState.Ringing(peerUid, peerName, fromSignal = true)
                startRingtone()
                // CODE-004: an unanswered ring must not hold the screen forever.
                ringTimeoutJob = scope.launch {
                    delay(RING_TIMEOUT_MS)
                    if (_state.value is CallState.Ringing) {
                        endCall("missed_call", sendBye = false)
                    }
                }
            }
            CallSignaling.KIND_ANSWER -> {
                if (_state.value is CallState.Calling && signal.fromUid == peerUid && signal.callId == callId) {
                    val pc = peerConnection ?: return
                    if (signal.sdp.isNullOrBlank()) return
                    if (setRemoteDescription(pc, SessionDescription(SessionDescription.Type.ANSWER, signal.sdp)) != null) {
                        flushBufferedCandidates(pc)
                        Timber.i("Remote answer applied (state=Connecting)")
                    }
                }
            }
            CallSignaling.KIND_ICE -> {
                if (signal.fromUid != peerUid || signal.callId != callId) return
                if (signal.candidateSdp.isNullOrBlank()) return
                val candidate = IceCandidate(signal.sdpMid, signal.sdpMLineIndex, signal.candidateSdp)
                val pc = peerConnection
                if (pc != null && pc.remoteDescription != null) {
                    try {
                        pc.addIceCandidate(candidate)
                    } catch (e: Exception) {
                        Timber.w("Failed to add remote ICE candidate: ${e.message}")
                    }
                } else {
                    // Candidate arrived while still ringing / before remote SDP — buffer it
                    synchronized(bufferedCandidates) { bufferedCandidates.add(candidate) }
                }
            }
            CallSignaling.KIND_BYE -> {
                if (signal.fromUid != peerUid) return
                // SEC-001: a forged bye from a topic eavesdropper must not kill an
                // active call — require the current call id.
                if (!CallSignaling.byeMatches(callId, signal.callId)) return
                when (_state.value) {
                    is CallState.Ringing -> endCall("call_cancelled", sendBye = false)
                    is CallState.Calling, is CallState.Active -> endCall("call_ended", sendBye = false)
                    else -> Unit
                }
            }
        }
    }

    //endregion

    //region Peer connection plumbing

    /** Common reset for both dialing directions. */
    private fun beginCall(peerId: String, name: String) {
        isEnding = false
        endedResetJob?.cancel()
        ringTimeoutJob?.cancel()
        callWasIncoming = false
        callWasAnswered = false
        callId = CallSignaling.newCallId()
        peerUid = peerId
        peerName = name
        pendingRemoteOffer = null
        synchronized(bufferedCandidates) { bufferedCandidates.clear() }
        isMicMuted.value = false
    }

    private fun createPeerConnection(): PeerConnection? {
        val factory = factory ?: return null
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder(STUN_URL).createIceServer())
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        return try {
            factory.createPeerConnection(rtcConfig, PeerConnectionObserver())
        } catch (e: Exception) {
            Timber.e(e, "Failed to create peer connection")
            null
        }
    }

    /** Mic constraints: echo cancellation, noise suppression, auto gain. */
    private fun micConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
    }

    private fun attachLocalAudio(pc: PeerConnection) {
        val factory = factory ?: return
        if (localAudioTrack == null) {
            audioSource = factory.createAudioSource(micConstraints())
            localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource)?.also {
                it.setEnabled(!isMicMuted.value)
            }
        }
        localAudioTrack?.let { pc.addTrack(it, listOf(MEDIA_STREAM_ID)) }
    }

    private fun flushBufferedCandidates(pc: PeerConnection) {
        val pending = synchronized(bufferedCandidates) {
            val copy = bufferedCandidates.toList()
            bufferedCandidates.clear()
            copy
        }
        for (candidate in pending) {
            try {
                pc.addIceCandidate(candidate)
            } catch (e: Exception) {
                Timber.w("Failed to add buffered ICE candidate: ${e.message}")
            }
        }
    }

    private fun ensureFactory(): Boolean = synchronized(factoryLock) {
        if (factory != null) return true
        val context = appContext ?: return false
        return try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            // JavaAudioDeviceModule handles capture/playback; speakerphone
            // routing itself is managed via AudioManager (see audio routing).
            val audioModule = JavaAudioDeviceModule.builder(context)
                .createAudioDeviceModule()
            factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioModule)
                .createPeerConnectionFactory()
            Timber.i("PeerConnectionFactory initialized")
            true
        } catch (e: Throwable) {
            Timber.e(e, "PeerConnectionFactory initialization failed")
            false
        }
    }

    private suspend fun createOffer(pc: PeerConnection, constraints: MediaConstraints): SessionDescription? =
        suspendCancellableCoroutine { cont ->
            pc.createOffer(sdpObserver(
                onCreate = { sdp -> if (cont.isActive) cont.resume(sdp) },
                onFail = { if (cont.isActive) cont.resume(null) }
            ), constraints)
        }

    private suspend fun createAnswer(pc: PeerConnection, constraints: MediaConstraints): SessionDescription? =
        suspendCancellableCoroutine { cont ->
            pc.createAnswer(sdpObserver(
                onCreate = { sdp -> if (cont.isActive) cont.resume(sdp) },
                onFail = { if (cont.isActive) cont.resume(null) }
            ), constraints)
        }

    private suspend fun setLocalDescription(pc: PeerConnection, sdp: SessionDescription): SessionDescription? =
        suspendCancellableCoroutine { cont ->
            pc.setLocalDescription(sdpObserver(
                onSet = { if (cont.isActive) cont.resume(sdp) },
                onFail = { if (cont.isActive) cont.resume(null) }
            ), sdp)
        }

    private suspend fun setRemoteDescription(pc: PeerConnection, sdp: SessionDescription): SessionDescription? =
        suspendCancellableCoroutine { cont ->
            pc.setRemoteDescription(sdpObserver(
                onSet = { if (cont.isActive) cont.resume(sdp) },
                onFail = { if (cont.isActive) cont.resume(null) }
            ), sdp)
        }

    private inline fun sdpObserver(
        crossinline onCreate: (SessionDescription) -> Unit = {},
        crossinline onSet: () -> Unit = {},
        crossinline onFail: () -> Unit = {}
    ): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = onCreate(sdp)
        override fun onSetSuccess() = onSet()
        override fun onCreateFailure(error: String?) {
            Timber.w("SDP create failure: $error")
            onFail()
        }
        override fun onSetFailure(error: String?) {
            Timber.w("SDP set failure: $error")
            onFail()
        }
    }

    private class PeerConnectionObserver : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            Timber.d("Peer signaling state: $state")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Timber.d("Peer ICE state: $state")
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            Timber.d("Peer ICE gathering: $state")
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            // Signal our own candidate to the peer (SDP is never logged)
            val myUid = appContext?.let { SecurePrefsManager.getUserId(it) } ?: return
            CallSignalingManager.sendSignal(
                peerUid,
                CallSignaling.icePayload(myUid, callId, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
            )
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

        override fun onAddStream(stream: MediaStream?) {}

        override fun onRemoveStream(stream: MediaStream?) {}

        override fun onDataChannel(channel: org.webrtc.DataChannel?) {}

        override fun onRenegotiationNeeded() {}

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> onPeerConnected()
                PeerConnection.PeerConnectionState.FAILED -> {
                    if (!isEnding) endCall("connection_lost", sendBye = true)
                }
                PeerConnection.PeerConnectionState.CLOSED -> {
                    if (!isEnding) endCall("call_ended", sendBye = false)
                }
                else -> Unit
            }
        }
    }

    private fun onPeerConnected() {
        val current = _state.value
        if (current is CallState.Calling || current is CallState.Ringing) {
            callWasAnswered = true
            _state.value = CallState.Active(peerUid, peerName)
            enableCallAudioRouting()
            Timber.i("Call connected (state=Active)")
        }
    }

    //endregion

    //region Audio routing

    private fun audioManager(): AudioManager? =
        appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun enableCallAudioRouting() {
        val audioManager = audioManager() ?: return
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = isSpeakerOn.value
        } catch (e: Exception) {
            Timber.w("Failed to enable call audio routing: ${e.message}")
        }
    }

    private fun resetAudioRouting() {
        val audioManager = audioManager() ?: return
        try {
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Timber.w("Failed to reset audio routing: ${e.message}")
        }
    }

    private fun startRingtone() {
        try {
            val context = appContext ?: return
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
            ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                // setLooping requires API 28; older devices play the tone once
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    isLooping = true
                }
                play()
            }
        } catch (e: Exception) {
            Timber.w("Failed to start ringtone: ${e.message}")
        }
    }

    private fun stopRingtone() {
        try {
            ringtone?.stop()
        } catch (_: Exception) {
        }
        ringtone = null
    }

    //endregion

    //region Teardown

    private fun endCall(reason: String, sendBye: Boolean) {
        if (isEnding && _state.value is CallState.Ended) return
        isEnding = true

        val byeUid = peerUid
        val byeCallId = callId
        if (sendBye && byeUid.isNotBlank() && byeCallId.isNotBlank()) {
            val myUid = appContext?.let { SecurePrefsManager.getUserId(it) } ?: ""
            CallSignalingManager.sendSignal(byeUid, CallSignaling.byePayload(myUid, byeCallId))
        }

        ringTimeoutJob?.cancel()
        stopRingtone()
        resetAudioRouting()

        try {
            peerConnection?.close()
        } catch (_: Exception) {
        }
        try {
            peerConnection?.dispose()
        } catch (_: Exception) {
        }
        peerConnection = null
        try {
            localAudioTrack?.dispose()
        } catch (_: Exception) {
        }
        localAudioTrack = null
        try {
            audioSource?.dispose()
        } catch (_: Exception) {
        }
        audioSource = null
        pendingRemoteOffer = null
        synchronized(bufferedCandidates) { bufferedCandidates.clear() }
        callId = ""
        peerUid = ""
        peerName = ""
        isMicMuted.value = false

        Timber.i("Call ended (reason=$reason)")
        // Missed-call bookkeeping: an incoming ring that nobody answered or an
        // outgoing call nobody picked up leaves a local chat entry.
        val firedPeer = peerUid
        if (firedPeer.isNotBlank()) {
            val preState = _state.value
            val incoming = callWasIncoming
            val answered = callWasAnswered
            val unanswered = (preState is CallState.Ringing) ||
                (preState is CallState.Calling && !answered)
            if (unanswered) onCallEvent?.invoke(firedPeer, incoming, answered)
        }
        _state.value = CallState.Ended(reason)
        scheduleEndedReset()
    }

    /** Briefly shows the ended reason, then returns to Idle for next call. */
    private fun scheduleEndedReset() {
        endedResetJob?.cancel()
        endedResetJob = scope.launch {
            delay(ENDED_STATE_RESET_MS)
            if (_state.value is CallState.Ended) {
                _state.value = CallState.Idle
                isEnding = false
            }
        }
    }

    //endregion
}
