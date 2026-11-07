package com.example.data.repository

import android.content.Context
import com.example.data.SecurePrefsManager
import com.example.data.database.ChatDao
import com.example.data.database.ChatEntity
import com.example.data.database.MessageEntity
import com.example.data.database.isSavedMessages
import com.example.network.supabase.RealtimeConnectionState
import com.example.network.supabase.SupabaseConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

class SyncManager(
    private val chatDao: ChatDao,
    private val cryptoRepository: CryptoRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val context: Context? = null
) {
    private val syncJob = SupervisorJob()
    private val syncScope = CoroutineScope(Dispatchers.IO + syncJob)
    private var syncJobInstance: Job? = null
    private val syncMutex = Mutex()
    private val flushMutex = Mutex()

    @Volatile
    private var realtimeStarted = false

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    // Server-derived ids are interpolated into PostgREST filter URLs; anything outside
    // this shape must never reach the query string (filter-injection guard).
    private val SAFE_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,128}$")

    private var realtimeManager: com.example.network.supabase.SupabaseRealtimeManager? = null
    private val realtimeQueue = kotlinx.coroutines.channels.Channel<JSONObject>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    fun startRealtimeSync() {
        val currentUserId = context?.let { SecurePrefsManager.getUserId(it) } ?: ""
        if (currentUserId.isBlank()) return

        // Re-entry guard: ChatRepository calls this from init and public API paths;
        // a second call without stopRealtimeSync would pile up workers and collectors.
        if (realtimeStarted) {
            triggerCatchUpSync()
            return
        }
        realtimeStarted = true

        // 1. Recover stale sending outbox entries on startup
        syncScope.launch {
            try {
                val recovered = chatDao.recoverStaleSendingOutbox(currentUserId)
                if (recovered > 0) {
                    Timber.i("Recovered $recovered stale outbox entries to pending on startup")
                }
            } catch (e: Exception) {
                Timber.w("Failed to recover stale outbox: ${e.message}")
            }
            // 2. Perform initial catch-up sync once upon startup
            performCatchUpSync(currentUserId)
        }

        // Sequential message worker processing realtime messages in strict arrival order
        syncScope.launch {
            for (record in realtimeQueue) {
                try {
                    processSingleMessageRecord(record, currentUserId)
                } catch (e: Throwable) {
                    Timber.w("Error processing queued realtime message: ${e.message}")
                }
            }
        }

        // 3. Connect Supabase Realtime WebSocket with explicit state machine
        if (context != null) {
            realtimeManager?.disconnect()
            realtimeManager = com.example.network.supabase.SupabaseRealtimeManager(
                context = context,
                onMessageRecordReceived = { record ->
                    realtimeQueue.trySend(record)
                },
                onConnectionStateChanged = { state ->
                    Timber.d("Realtime state update in SyncManager: $state")
                },
                onChannelJoined = {
                    Timber.i("Supabase Realtime channel joined, triggering cursor-based catch-up sync")
                    syncScope.launch { performCatchUpSync(currentUserId) }
                }
            ).also { it.connect() }
        }

        // 4. Background maintenance job for durable outbox retry with exponential backoff (no tight polling)
        syncJobInstance?.cancel()
        syncJobInstance = syncScope.launch {
            while (isActive) {
                delay(60_000) // 1-minute maintenance interval strictly for offline outbox retry
                try {
                    flushOutbox(currentUserId)
                } catch (e: Throwable) {
                    Timber.d("Outbox retry check error: ${e.message}")
                }
            }
        }

        // 5. Propagate session token updates and auth state changes to Realtime gateway
        syncScope.launch {
            com.example.network.supabase.SessionManager.currentSession.collect { session ->
                if (session == null || session.accessToken.isBlank()) {
                    realtimeManager?.disconnect()
                } else {
                    realtimeManager?.updateAccessToken(session.accessToken)
                }
            }
        }
    }

    /**
     * Triggered on app startup, WebSocket reconnect, push notification wakeup, or network restore.
     */
    fun triggerCatchUpSync() {
        val currentUserId = context?.let { SecurePrefsManager.getUserId(it) } ?: ""
        if (currentUserId.isBlank()) return
        syncScope.launch {
            performCatchUpSync(currentUserId)
        }
    }

    private suspend fun performCatchUpSync(currentUserId: String) {
        try {
            // 1. Authoritative cloud-first sync of conversations from Supabase
            conversationRepository.syncConversationsFromServer(currentUserId)
            // 2. Fetch messages by cursor
            syncIncomingMessages(currentUserId)
            syncSentStatus(currentUserId)
            flushOutbox(currentUserId)
        } catch (e: Throwable) {
            Timber.d("Catch-up sync error: ${e.message}")
        }
    }

    fun triggerOutboxFlush() {
        val currentUserId = context?.let { SecurePrefsManager.getUserId(it) } ?: ""
        if (currentUserId.isBlank()) return
        syncScope.launch {
            try {
                flushOutbox(currentUserId)
            } catch (e: Exception) {
                Timber.w("Manual outbox flush error: ${e.message}")
            }
        }
    }

    fun stopRealtimeSync() {
        realtimeStarted = false
        realtimeManager?.disconnect()
        realtimeManager = null
        syncJobInstance?.cancel()
        syncJobInstance = null
        syncJob.cancelChildren()
    }

    private suspend fun syncIncomingMessages(currentUserId: String) = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)
        val token = SecurePrefsManager.getSupabaseAccessToken(ctx).takeIf { it.isNotBlank() }
            ?: return@withContext
        if (anonKey.isBlank()) return@withContext

        // 1. Authoritative per-conversation sequence sync (prevents cross-conversation cursor starvation)
        val userChats = try {
            chatDao.getAllChatsListForAccount(currentUserId)
        } catch (_: Exception) {
            emptyList()
        }

        for (chat in userChats) {
            val convId = chat.conversationId.ifBlank {
                if (chat.isSavedMessages) "self_$currentUserId"
                else conversationRepository.computeConversationId(currentUserId, chat.recipientId)
            }
            if (convId.isBlank()) continue
            if (!SAFE_ID_REGEX.matches(convId)) {
                Timber.w("Skipping chat with non-conforming conversationId")
                continue
            }

            var maxSeq = chatDao.getMaxSequenceForConversation(convId, currentUserId) ?: 0L

            // Page cap mirrors the global-stream loop below: a malformed full page (missing/zero
            // sequence values) would otherwise repeat the same URL forever and hang the sync pass.
            var convPageCount = 0
            while (convPageCount < 100) {
                convPageCount++
                val pageStartSeq = maxSeq
                val url = "$baseUrl/rest/v1/messages?conversation_id=eq.$convId&sequence=gt.$maxSeq&order=sequence.asc&limit=50"
                val req = Request.Builder()
                    .url(url)
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()

                val resp = try { httpClient.newCall(req).execute() } catch (_: Exception) { null } ?: break
                val body = resp.body?.string().orEmpty()
                val ok = resp.isSuccessful
                resp.close()

                if (!ok || body.isBlank() || body == "[]") break

                val arr = try { JSONArray(body) } catch (_: Exception) { JSONArray() }
                if (arr.length() == 0) break

                var highestInBatch = maxSeq
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val s = obj.optLong("sequence", 0L)
                    processSingleMessageRecord(obj, currentUserId)
                    if (s > highestInBatch) highestInBatch = s
                }

                if (highestInBatch > maxSeq) {
                    maxSeq = highestInBatch
                }

                // Full page that did not advance the cursor: the same URL would be fetched
                // again and again. Stop instead of spinning on malformed server rows.
                if (arr.length() == 50 && maxSeq == pageStartSeq) {
                    Timber.w("Per-conversation catch-up stalled for $convId (full page, cursor not advancing); stopping")
                    break
                }

                if (arr.length() < 50) break
            }
        }

        // 2. Global stream catch-up using global_sequence for new conversations or missed events
        var globalCursor = SecurePrefsManager.getSyncCursor(ctx, currentUserId)
        val filter = "or=(recipient_id.eq.$currentUserId,sender_id.eq.$currentUserId)"
        var pageCount = 0

        while (pageCount < 100) {
            pageCount++
            val globalUrl = if (globalCursor > 0L) {
                "$baseUrl/rest/v1/messages?$filter&global_sequence=gt.$globalCursor&order=global_sequence.asc&limit=50"
            } else {
                "$baseUrl/rest/v1/messages?$filter&order=global_sequence.asc&limit=50"
            }

            val req = Request.Builder()
                .url(globalUrl)
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            val resp = try { httpClient.newCall(req).execute() } catch (_: Exception) { null } ?: break
            val body = resp.body?.string().orEmpty()
            val ok = resp.isSuccessful
            resp.close()

            if (!ok || body.isBlank() || body == "[]") break

            val arr = try { JSONArray(body) } catch (_: Exception) { JSONArray() }
            if (arr.length() == 0) break

            var maxGlob = globalCursor
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val gSeq = obj.optLong("global_sequence", 0L)
                val processed = processSingleMessageRecord(obj, currentUserId)
                // Advance the cursor ONLY past successfully processed records: a transient
                // MISSING_KEY must be retried by the next sync instead of being skipped forever.
                if (processed && gSeq > maxGlob) maxGlob = gSeq
            }

            if (maxGlob > globalCursor) {
                globalCursor = maxGlob
                SecurePrefsManager.setSyncCursor(ctx, currentUserId, maxGlob)
            } else {
                break
            }

            if (arr.length() < 50) break
        }
    }

    /**
     * Returns false when the record was NOT persisted (e.g. MISSING_KEY transient) so
     * the caller does not advance the sync cursor past it — otherwise the first
     * message from a new peer is lost until their second message arrives.
     */
    private suspend fun processSingleMessageRecord(obj: JSONObject, currentUserId: String): Boolean = withContext(Dispatchers.IO) {
        val ctx = context
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = ctx?.let { SupabaseConfig.getAnonKey(it) } ?: ""
        val token = ctx?.let { SecurePrefsManager.getSupabaseAccessToken(it) }?.takeIf { it.isNotBlank() } ?: ""

        val docId = obj.optString("id")
        val sender = obj.optString("sender_id")
        val recipient = obj.optString("recipient_id")
        val sequence = obj.optLong("sequence", 0L)
        if (sender.isBlank()) return@withContext true

        // P0-03: Process tombstones FIRST before checking payload, as server sets payload='' on delete
        val isDeleted = obj.optBoolean("is_deleted", false)
        if (isDeleted) {
            if (docId.isNotBlank()) {
                chatDao.deleteMessageByServerId(docId, currentUserId)
            }
            val clientMsgId = obj.optString("client_message_id", obj.optString("idempotency_key", ""))
            if (clientMsgId.isNotBlank()) {
                chatDao.deleteMessageByIdempotencyKey(clientMsgId, currentUserId)
            }
            return@withContext true
        }

        val payloadString = obj.optString("payload")
        if (payloadString.isBlank()) return@withContext true

        val type = obj.optString("type", "text")
        val timeStr = obj.optString("time_str", "")
        val idempotencyKey = obj.optString("client_message_id", obj.optString("idempotency_key", docId)).ifBlank { docId }
        val personaId = obj.optString("persona_id").takeIf { it.isNotBlank() && it != "null" }
        val editedAt = obj.optString("edited_at").takeIf { it.isNotBlank() && it != "null" }
        val serverStatus = obj.optString("status").takeIf { it.isNotBlank() } ?: "sent"

        val isSelfMessage = (sender == currentUserId && (recipient == currentUserId || recipient == "self" || recipient.isBlank()))
        val isOutboundFromAnotherDevice = (sender == currentUserId && recipient != currentUserId && recipient.isNotBlank())
        val isAiConversation = recipient.startsWith("ai_") || recipient == "ai_assistant" || personaId != null || obj.optString("conversation_id").startsWith("ai_")

        val peerId = when {
            isSelfMessage -> "self"
            isAiConversation -> "ai_assistant"
            isOutboundFromAnotherDevice -> recipient
            else -> sender
        }

        val conversationId = obj.optString("conversation_id").ifBlank {
            when {
                isSelfMessage -> "self_$currentUserId"
                isAiConversation -> "ai_$currentUserId"
                else -> conversationRepository.computeConversationId(currentUserId, peerId)
            }
        }

        // P0-04: Existing message detection: update server metadata and check if edited
        val existingMsg = (if (docId.isNotBlank()) chatDao.getMessageByServerId(docId, currentUserId) else null)
            ?: (if (idempotencyKey.isNotBlank()) chatDao.getMessageByIdempotencyKey(idempotencyKey, currentUserId) else null)

        if (existingMsg != null) {
            if ((existingMsg.serverMessageId.isBlank() && docId.isNotBlank()) ||
                (existingMsg.serverSequence == 0L && sequence > 0L) ||
                (existingMsg.status != "read" && serverStatus == "read")
            ) {
                chatDao.updateMessageServerInfoByIdempotencyKey(
                    idempotencyKey = idempotencyKey,
                    serverId = docId,
                    sequence = sequence,
                    status = serverStatus,
                    accountId = currentUserId
                )
            }
            if (editedAt == null) {
                // Not edited, already present in local database
                return@withContext true
            }
        }

        var isMe = sender == currentUserId
        var resolvedSender = sender
        var resolvedText = payloadString
        var resolvedAudioUrl: String? = null
        var resolvedDuration: Int? = null
        var resolvedType = type
        var signatureValid = true
        var isDecryptionError = false

        if (isAiConversation) {
            if (recipient.startsWith("ai_") || recipient == "ai_assistant") {
                isMe = true
                resolvedSender = "me"
            } else {
                isMe = false
                resolvedSender = if (personaId?.lowercase() == "ventaxis") "Ventaxis AI" else "Hexagon AI"
            }
        } else {
            // E2EE message decryption
            // P0-06: For outbound message from another device of same account,
            // the ECDH shared secret was derived with peerId (recipient), not self.
            val effectiveCryptoPeer = when {
                isSelfMessage -> currentUserId
                isOutboundFromAnotherDevice -> recipient
                else -> sender
            }

            val signatureStr = obj.optString("signature").takeIf { it.isNotBlank() }
            val decryptedResult = cryptoRepository.decryptInboundMessage(
                senderId = effectiveCryptoPeer,
                recipientId = currentUserId,
                conversationId = conversationId,
                idempotencyKey = idempotencyKey,
                base64Payload = payloadString,
                base64Signature = signatureStr,
                // The canonical envelope/AAD was built with the identities as recorded
                // server-side at send time; for this account's own outbound messages
                // (sender == me) the crypto peer and the envelope pair are reversed.
                envelopeSenderId = if (isOutboundFromAnotherDevice) currentUserId else sender,
                envelopeRecipientId = if (isOutboundFromAnotherDevice) recipient else currentUserId
            )

            isDecryptionError = decryptedResult.trustStatus == DecryptionTrustStatus.CORRUPT_PAYLOAD ||
                                decryptedResult.trustStatus == DecryptionTrustStatus.DECRYPTION_FAILED
            resolvedText = decryptedResult.plainText
            signatureValid = decryptedResult.isSignatureValid

            // MISSING_KEY is transient (peer key not cached, network blip): do NOT persist
            // a stub row — the next sync pass will re-fetch the key and decrypt properly.
            if (decryptedResult.trustStatus == DecryptionTrustStatus.MISSING_KEY) {
                return@withContext false
            }

            // TOFU: the served key differs from the pinned one. Verification above used
            // the pinned key; warn the user once per peer so a server-side substitution
            // never passes silently.
            if (decryptedResult.senderKeyChanged && ctx != null) {
                val alertKey = "tofu_alert_${effectiveCryptoPeer}_$currentUserId"
                val prefs = SecurePrefsManager.getPrefs(ctx)
                if (!prefs.getBoolean(alertKey, false)) {
                    prefs.edit().putBoolean(alertKey, true).apply()
                    Timber.w("SECURITY: identity key of peer $effectiveCryptoPeer changed vs pinned key!")
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            android.widget.Toast.makeText(
                                ctx.applicationContext,
                                "⚠️ Security: $effectiveCryptoPeer's identity key changed. Verify this contact!",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to show key-change alert")
                        }
                    }
                }
            }

            if (!isDecryptionError && decryptedResult.plainText.startsWith("{") && decryptedResult.plainText.endsWith("}")) {
                try {
                    val json = JSONObject(decryptedResult.plainText)
                    if (json.has("audioUrl") || json.has("type")) {
                        resolvedText = json.optString("text", resolvedText)
                        resolvedAudioUrl = json.optString("audioUrl").takeIf { it.isNotBlank() }
                        if (json.has("duration")) resolvedDuration = json.optInt("duration")
                        resolvedType = json.optString("type", type)
                    }
                } catch (_: Exception) {}
            }
        }

        val messageStatus = when {
            isDecryptionError || !signatureValid -> "integrity_error"
            isMe -> "sent"
            else -> "delivered"
        }

        // Synchronize local database insert under syncMutex
        syncMutex.withLock {
            val existing = (if (docId.isNotBlank()) chatDao.getMessageByServerId(docId, currentUserId) else null)
                ?: (if (idempotencyKey.isNotBlank()) chatDao.getMessageByIdempotencyKey(idempotencyKey, currentUserId) else null)

            if (existing != null) {
                if (editedAt != null) {
                    // Only overwrite a good local text with a GOOD decryption: a failed
                    // pass (peer key rotated, cache cold) must never replace readable
                    // content with a decryption-error stub.
                    if (!isDecryptionError) {
                        chatDao.updateMessageText(existing.id, resolvedText, System.currentTimeMillis(), currentUserId)
                        chatDao.updateMessageStatus(existing.id, messageStatus, currentUserId)
                        conversationRepository.updateChatPreview(existing.chatId, resolvedText, timeStr, System.currentTimeMillis())
                    }
                }
                return@withContext true
            }

            var chat = when {
                isSelfMessage -> chatDao.getSavedMessagesChat(currentUserId)
                isAiConversation -> chatDao.getAiAssistantChat(currentUserId)
                else -> chatDao.getChatByConversationId(conversationId, currentUserId) ?: chatDao.getChatByRecipientId(peerId, currentUserId)
            }

            if (chat == null) {
                val newChat = when {
                    isSelfMessage -> ChatEntity(
                        accountId = currentUserId,
                        name = "Saved Messages",
                        ava = "🔖",
                        status = "",
                        preview = if (isDecryptionError) "⚠️ Corrupted message" else resolvedText,
                        time = timeStr,
                        recipientId = "self",
                        conversationId = "self_$currentUserId",
                        conversationType = com.example.data.ConversationType.SAVED_MESSAGES.name
                    )
                    isAiConversation -> ChatEntity(
                        accountId = currentUserId,
                        name = "HexShard AI",
                        ava = "HX",
                        status = "online",
                        preview = resolvedText,
                        time = timeStr,
                        recipientId = "ai_assistant",
                        conversationId = "ai_$currentUserId",
                        conversationType = com.example.data.ConversationType.AI_ASSISTANT.name
                    )
                    else -> ChatEntity(
                        accountId = currentUserId,
                        name = peerId,
                        ava = peerId.take(2).uppercase(),
                        status = "offline",
                        preview = if (isDecryptionError) "⚠️ Corrupted message" else resolvedText,
                        time = timeStr,
                        recipientId = peerId,
                        conversationId = conversationId,
                        conversationType = com.example.data.ConversationType.DIRECT.name
                    )
                }
                val newChatId = chatDao.insertChat(newChat).toInt()
                chat = chatDao.getChatById(newChatId, currentUserId)
            }
            val resolvedChatId = chat?.id ?: 1

            val msg = MessageEntity(
                accountId = currentUserId,
                chatId = resolvedChatId,
                sender = if (isMe) "me" else resolvedSender,
                text = resolvedText,
                time = timeStr,
                timestamp = System.currentTimeMillis(),
                isMe = isMe,
                isAttachment = resolvedType != "text",
                type = resolvedType,
                audioUrl = resolvedAudioUrl,
                duration = resolvedDuration,
                status = messageStatus,
                idempotencyKey = idempotencyKey,
                conversationId = conversationId,
                serverMessageId = docId,
                signatureValid = signatureValid,
                encryptionVersion = 2,
                personaId = personaId,
                serverSequence = sequence
            )
            val rowId = chatDao.insertMessageIfNotExists(msg)
            if (rowId > 0) {
                val previewText = when (resolvedType) {
                    "voice" -> "🎤 Voice message"
                    "image" -> "📷 Photo"
                    "location" -> "📍 Location"
                    else -> if (isDecryptionError) "⚠️ Corrupted message" else resolvedText
                }
                chatDao.updateChatPreview(resolvedChatId, previewText, timeStr, System.currentTimeMillis(), currentUserId)
                if (token.isNotBlank() && !isMe && !isDecryptionError && signatureValid && !isAiConversation) {
                    markDeliveredOnRemote(docId, baseUrl, anonKey, token)
                }
                true
            } else {
                // Deduplicated insert (row already present): still a successful process
                true
            }
        }
        true
    }

    private suspend fun markDeliveredOnRemote(
        docId: String,
        baseUrl: String,
        anonKey: String,
        token: String
    ) = withContext(Dispatchers.IO) {
        if (!SAFE_ID_REGEX.matches(docId)) {
            Timber.w("Skipping delivery receipt for non-conforming message id")
            return@withContext
        }
        try {
            val body = JSONObject().apply {
                put("status", "delivered")
            }.toString().toRequestBody(JSON_MEDIA)

            val ackReq = Request.Builder()
                .url("$baseUrl/rest/v1/messages?id=eq.$docId")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .patch(body)
                .build()

            httpClient.newCall(ackReq).execute().close()
        } catch (e: Exception) {
            Timber.d("Failed to send delivery receipt: ${e.message}")
        }
    }

    private suspend fun syncSentStatus(currentUserId: String) = withContext(Dispatchers.IO) {
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        val token = context?.let { SecurePrefsManager.getSupabaseAccessToken(it) }?.takeIf { it.isNotBlank() }
            ?: return@withContext

        val url = "$baseUrl/rest/v1/messages?sender_id=eq.$currentUserId&select=id,status&order=id.desc&limit=30"
        val req = Request.Builder()
            .url(url)
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: "[]"
            if (!resp.isSuccessful) return@withContext

            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val docId = obj.optString("id")
                val status = obj.optString("status")
                if (docId.isNotBlank() && status.isNotBlank()) {
                    chatDao.updateMessageStatusByServerId(docId, status, currentUserId)
                }
            }
        } catch (e: Exception) {
            Timber.d("Status sync failed: ${e.message}")
        }
    }

    private suspend fun resolveWaitingKeyOutboxEntries(currentUserId: String) = withContext(Dispatchers.IO) {
        val waiting = try {
            chatDao.getWaitingKeyOutboxEntries(currentUserId)
        } catch (_: Exception) {
            emptyList()
        }
        if (waiting.isEmpty()) return@withContext

        for (entry in waiting) {
            try {
                val pubKey = cryptoRepository.userRepository.getOrFetchUserPublicKey(entry.recipientId)
                if (pubKey != null) {
                    val localMsg = chatDao.getMessageByIdempotencyKey(entry.idempotencyKey, currentUserId)
                    if (localMsg != null && localMsg.text.isNotBlank()) {
                        val enc = cryptoRepository.encryptOutboundMessage(
                            currentUserId = currentUserId,
                            recipientId = entry.recipientId,
                            conversationId = entry.conversationId,
                            idempotencyKey = entry.idempotencyKey,
                            plainText = localMsg.text,
                            isSelfOrAi = false
                        )
                        chatDao.updateOutboxPayload(entry.id, enc.base64Payload, enc.base64Signature, currentUserId)
                        chatDao.updateMessageStatus(localMsg.id, "sending", currentUserId)
                        Timber.i("Resolved peer key for waiting outbox entry ${entry.idempotencyKey}")
                    }
                }
            } catch (e: Exception) {
                Timber.d("Waiting peer key resolution check: ${e.message}")
            }
        }
    }

    private suspend fun flushOutbox(currentUserId: String) = withContext(Dispatchers.IO) {
        // Single-flight: maintenance loop, catch-up sync and manual triggers can all
        // reach this concurrently; two flushers would send the same entries twice.
        flushMutex.withLock {
            flushOutboxLocked(currentUserId)
        }
    }

    private suspend fun flushOutboxLocked(currentUserId: String) = withContext(Dispatchers.IO) {
        // 1. Resolve any outbox entries waiting for recipient's public key
        resolveWaitingKeyOutboxEntries(currentUserId)

        // 2. Fetch pending entries ready for delivery (excluding unencrypted WAITING_FOR_PEER_KEY)
        val pendingEntries = try {
            chatDao.getReadyPendingOutboxEntries(currentUserId)
        } catch (e: Exception) {
            emptyList()
        }
        if (pendingEntries.isEmpty()) return@withContext

        val now = System.currentTimeMillis()
        for (entry in pendingEntries) {
            if (entry.status == "failed" || entry.status == "WAITING_FOR_PEER_KEY") continue

            if (entry.attempts >= 5) {
                chatDao.updateOutboxAttempt(entry.id, "failed", now, "Max retry attempts reached", currentUserId)
                chatDao.updateMessageStatusByIdempotencyKey(entry.idempotencyKey, "error", currentUserId)
                continue
            }

            // Exponential backoff: 2s, 4s, 8s, 16s, 32s
            val backoffMs = (1000L * (1 shl (entry.attempts.coerceAtMost(5))))
            if (entry.lastAttemptAt > 0 && now - entry.lastAttemptAt < backoffMs) {
                continue
            }

            chatDao.updateOutboxAttempt(entry.id, "sending", now, "", currentUserId)

            val success = try {
                when (entry.type) {
                    "ai_transcript" -> {
                        val json = JSONObject(entry.payload)
                        messageRepository.sendAiTranscript(
                            conversationId = entry.conversationId,
                            userClientId = json.optString("userClientId"),
                            userText = json.optString("userText"),
                            replyClientId = json.optString("replyClientId"),
                            replyText = json.optString("replyText"),
                            personaId = json.optString("personaId", "hexagon"),
                            timeStr = entry.timeStr
                        )
                    }
                    "create_conversation" -> {
                        val json = JSONObject(entry.payload)
                        val pType = json.optString("p_type", "DIRECT")
                        val pRecipientId = json.optString("p_recipient_id", entry.recipientId)
                        val pTitle = json.optString("p_title", "")
                        val convId = conversationRepository.createOrGetConversationRemote(pType, pRecipientId, pTitle)
                        if (convId != null) {
                            val localChat = chatDao.getChatById(entry.chatId, currentUserId)
                            if (localChat != null && localChat.conversationId != convId) {
                                if (localChat.conversationId.isNotBlank()) {
                                    // Rebind BOTH the chat and its messages, or per-conversation
                                    // sync/read-receipts keep using the stale deterministic id.
                                    chatDao.updateChatConversationId(localChat.conversationId, convId, currentUserId)
                                    chatDao.updateMessagesConversationId(localChat.conversationId, convId, currentUserId)
                                } else {
                                    chatDao.updateChat(localChat.copy(conversationId = convId))
                                }
                            }
                            true
                        } else {
                            false
                        }
                    }
                    else -> {
                        val sendResult = messageRepository.sendRemoteMessage(
                            idempotencyKey = entry.idempotencyKey,
                            conversationId = entry.conversationId,
                            senderId = entry.senderId,
                            recipientId = entry.recipientId,
                            payloadBase64 = entry.payload,
                            signatureBase64 = entry.signature,
                            type = entry.type,
                            timeStr = entry.timeStr
                        )
                        sendResult is RemoteMessageSendResult.Success
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Outbox delivery failed for key ${entry.idempotencyKey}")
                false
            }

            if (success) {
                chatDao.deleteOutboxByIdempotencyKey(entry.idempotencyKey, currentUserId)
                chatDao.updateMessageStatusByIdempotencyKey(entry.idempotencyKey, "sent", currentUserId)
            } else {
                val newAttempts = entry.attempts + 1
                val newStatus = if (newAttempts >= 5) "failed" else "pending"
                chatDao.updateOutboxAttempt(entry.id, newStatus, System.currentTimeMillis(), "Delivery attempt $newAttempts failed", currentUserId)
                // Roll the message status back out of "sending" so it doesn't hang there
                // between retries; "failed" marks the terminal state.
                if (newStatus == "failed") {
                    chatDao.updateMessageStatusByIdempotencyKey(entry.idempotencyKey, "error", currentUserId)
                } else {
                    chatDao.updateMessageStatusByIdempotencyKey(entry.idempotencyKey, "pending", currentUserId)
                }
            }
        }
    }
}
