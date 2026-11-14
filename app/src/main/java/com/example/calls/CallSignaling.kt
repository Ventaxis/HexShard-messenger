package com.example.calls

/**
 * Pure, Android-free helpers for the 1:1 WebRTC call signaling protocol.
 *
 * Both peers share one deterministic Supabase Realtime channel topic derived
 * from their UIDs, so either side can compute the channel name without a
 * lookup: "realtime:call_<lowerUid>_<higherUid>".
 *
 * Every function here is pure (deterministic, no Context, no network, no
 * Android framework types) so it can be unit-tested with plain JUnit —
 * see app/src/test/java/com/example/CallSignalingTest.kt.
 */
object CallSignaling {

    /** Broadcast event name both sides listen for inside the channel. */
    const val SIGNAL_EVENT = "call_signal"

    /** Kinds of signaling payloads exchanged between the two peers. */
    const val KIND_OFFER = "offer"
    const val KIND_ANSWER = "answer"
    const val KIND_ICE = "ice"
    const val KIND_BYE = "bye"

    /**
     * Deterministic Realtime topic for a call between [uidA] and [uidB].
     * Order-independent: both peers always compute the same topic.
     */
    fun topicFor(uidA: String, uidB: String): String {
        val (lower, higher) = sortedPair(uidA, uidB)
        return "realtime:call_${lower}_${higher}"
    }

    /** Lexicographically sorted, trimmed UID pair (order-independent). */
    fun sortedPair(uidA: String, uidB: String): List<String> =
        listOf(uidA.trim(), uidB.trim()).sorted()

    /** Base envelope every call signal carries. */
    fun signalPayload(kind: String, fromUid: String, callId: String): Map<String, Any?> = linkedMapOf(
        "kind" to kind,
        "fromUid" to fromUid,
        "callId" to callId
    )

    /** SDP offer payload sent by the caller right after joining the topic. */
    fun offerPayload(fromUid: String, callId: String, sdp: String): Map<String, Any?> =
        signalPayload(KIND_OFFER, fromUid, callId) + ("sdp" to sdp)

    /** SDP answer payload sent by the callee after accepting the call. */
    fun answerPayload(fromUid: String, callId: String, sdp: String): Map<String, Any?> =
        signalPayload(KIND_ANSWER, fromUid, callId) + ("sdp" to sdp)

    /** ICE candidate payload with its media line coordinates. */
    fun icePayload(
        fromUid: String,
        callId: String,
        candidateSdp: String,
        sdpMid: String?,
        sdpMLineIndex: Int
    ): Map<String, Any?> = signalPayload(KIND_ICE, fromUid, callId) + linkedMapOf(
        "candidate" to candidateSdp,
        "sdpMid" to (sdpMid ?: ""),
        "sdpMLineIndex" to sdpMLineIndex
    )

    /** Hang-up / decline / cancel payload. */
    fun byePayload(fromUid: String, callId: String, reason: String? = null): Map<String, Any?> {
        val base = signalPayload(KIND_BYE, fromUid, callId)
        return if (reason.isNullOrBlank()) base else base + ("reason" to reason)
    }

    /**
     * Phoenix channel frame used to broadcast a call signal on [topic]
     * (mirrors what supabase-js `channel.send({type:"broadcast",...})` emits).
     */
    fun broadcastEnvelope(topic: String, signal: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        "topic" to topic,
        "event" to "broadcast",
        "payload" to linkedMapOf(
            "type" to "broadcast",
            "event" to SIGNAL_EVENT,
            "payload" to signal
        )
    )

    // --- SEC-001 signaling gates (pure, unit-tested) ---

    /** Offers ring only for accepted DIRECT contacts — no full-screen spoofing. */
    fun offerAllowed(knownContacts: Set<String>, fromUid: String): Boolean =
        fromUid.isNotBlank() && knownContacts.contains(fromUid)

    /** A hangup is honored only when it names the current call. */
    fun byeMatches(expectedCallId: String, signalCallId: String): Boolean =
        expectedCallId.isNotBlank() && signalCallId.isNotBlank() && signalCallId == expectedCallId

    /** Broadcast frames outside our joined topics are dropped (topic confusion). */
    fun broadcastFromJoinedTopic(topic: String, joinedTopics: Set<String>): Boolean =
        topic.isNotBlank() && topic in joinedTopics

    /** Unpredictable call id — a timestamp would let third parties forge hangs. */
    fun newCallId(): String = java.util.UUID.randomUUID().toString()

    /** Phoenix channel join frame for the call topic (broadcast-only config). */
    fun joinEnvelope(topic: String, ref: String, accessToken: String): Map<String, Any?> = linkedMapOf(
        "topic" to topic,
        "event" to "phx_join",
        "join_ref" to ref,
        "ref" to ref,
        "payload" to linkedMapOf(
            "config" to linkedMapOf(
                "broadcast" to linkedMapOf("self" to false),
                "presence" to linkedMapOf("key" to "")
            ),
            "access_token" to accessToken
        )
    )
}
