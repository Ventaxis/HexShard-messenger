package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.database.ChatEntity
import com.example.data.database.ChatRepository
import com.example.data.database.MessageEntity
import com.example.data.database.isAiAssistant
import com.example.data.database.isSavedMessages
import com.example.network.AiModelOption
import com.example.network.AiResult
import com.example.network.GeminiService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

sealed class AiUiError {
    data class RateLimited(val retryAfterSeconds: Long) : AiUiError()
    object Unauthorized : AiUiError()
    object FunctionNotFound : AiUiError()
    object DeploymentUnavailable : AiUiError()
    object ModelUnavailable : AiUiError()
    object ConfigurationError : AiUiError()
    object InvalidRequest : AiUiError()
    object NetworkError : AiUiError()
    data class UpstreamError(val message: String) : AiUiError()
    data class Custom(val message: String) : AiUiError()

    fun getLocalizedMessage(strings: AppStrings): String {
        return when (this) {
            is RateLimited -> strings.aiRateLimited.replace("%d", "$retryAfterSeconds")
            is Unauthorized -> strings.aiUnauthorized
            is FunctionNotFound -> strings.aiFunctionNotFound
            is DeploymentUnavailable -> strings.aiDeploymentUnavailable
            is ModelUnavailable -> strings.aiModelUnavailable
            is ConfigurationError -> strings.aiConfigurationError
            is InvalidRequest -> strings.aiInvalidRequest
            is NetworkError -> strings.aiNetworkError
            is UpstreamError -> message.ifBlank { strings.aiUnavailable }
            is Custom -> message
        }
    }
}

class ChatViewModel @Inject constructor(
    private val repository: ChatRepository,
    private val geminiService: GeminiService
) : ViewModel() {

    private val _selectedChatId = MutableStateFlow<Int?>(null)
    val selectedChatId: StateFlow<Int?> = _selectedChatId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _pendingProfileConfirmation = MutableStateFlow<com.example.util.ProfileQrData?>(null)
    val pendingProfileConfirmation: StateFlow<com.example.util.ProfileQrData?> = _pendingProfileConfirmation.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _typingChatId = MutableStateFlow<Int?>(null)
    val typingChatId: StateFlow<Int?> = _typingChatId.asStateFlow()

    private val _isDarkTheme = MutableStateFlow(true)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    private val _selectedAiModel = MutableStateFlow(AiModelOption.DEFAULT)
    val selectedAiModel: StateFlow<AiModelOption> = _selectedAiModel.asStateFlow()

    private val _aiErrorState = MutableStateFlow<AiUiError?>(null)
    val aiErrorState: StateFlow<AiUiError?> = _aiErrorState.asStateFlow()

    fun dismissAiError() {
        _aiErrorState.value = null
    }

    // SECURITY: Thread-safe last send time using AtomicLong
    private val lastSendTimeNano = AtomicLong(0L)
    private val MIN_SEND_INTERVAL_MS = 300L

    init {
        viewModelScope.launch {
            repository.populateInitialDataIfNeeded()
        }
    }

    fun onAccountChanged() {
        repository.refreshAccount()
        viewModelScope.launch {
            repository.populateInitialDataIfNeeded()
        }
    }

    fun ensureDefaultChats() {
        viewModelScope.launch {
            repository.populateInitialDataIfNeeded()
        }
    }

    val chats: StateFlow<List<ChatEntity>> = combine(
        repository.allChats,
        _searchQuery
    ) { chatList, query ->
        if (query.isBlank()) {
            chatList
        } else {
            chatList.filter {
                it.name.contains(query, ignoreCase = true) ||
                it.preview.contains(query, ignoreCase = true)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val selectedChatMessages: StateFlow<List<MessageEntity>> = combine(
        _selectedChatId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else repository.getMessagesForChat(id)
        },
        _selectedChatId,
        _selectedAiModel,
        repository.allChats
    ) { messages, chatId, aiModel, allChatsList ->
        if (chatId == null) return@combine emptyList()
        val chat = allChatsList.find { it.id == chatId }
        if (chat != null && chat.isAiAssistant) {
            // Filter strictly by personaId enum identifier, completely isolated
            val validMessages = messages.filter { it.text.isNotBlank() || it.isAttachment || it.audioUrl != null }
            validMessages.filter {
                resolvePersonaId(it) == aiModel.personaId
            }
        } else {
            messages
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun selectChat(id: Int) {
        _selectedChatId.value = id
        viewModelScope.launch {
            repository.markAllAsRead(id)
        }
    }

    fun deselectChat() {
        _selectedChatId.value = null
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleTheme() {
        _isDarkTheme.value = !_isDarkTheme.value
    }

    fun toggleChatPin(chatId: Int) {
        viewModelScope.launch {
            repository.toggleChatPin(chatId)
        }
    }

    fun deleteChat(chatId: Int) {
        viewModelScope.launch {
            if (_selectedChatId.value == chatId) {
                _selectedChatId.value = null
            }
            repository.deleteChat(chatId)
        }
    }

    suspend fun downloadVoiceMessage(url: String, idempotencyKey: String = ""): ByteArray? {
        val cid = _selectedChatId.value ?: return null
        val chat = chats.value.find { it.id == cid } ?: return null
        return repository.downloadVoiceMessage(url, chat.recipientId.ifEmpty { chat.name }, idempotencyKey)
    }

    suspend fun downloadMediaMessage(url: String, idempotencyKey: String = ""): ByteArray? {
        val cid = _selectedChatId.value ?: return null
        val chat = chats.value.find { it.id == cid } ?: return null
        return repository.downloadMediaMessage(url, chat.recipientId.ifEmpty { chat.name }, idempotencyKey)
    }

    fun sendMediaMessage(context: android.content.Context, uri: android.net.Uri, type: String) {
        val cid = _selectedChatId.value ?: return
        
        // SECURITY: Check throttle atomically
        if (!shouldAllowSend()) {
            timber.log.Timber.w("Media send throttled")
            return
        }

        if (!_isSending.compareAndSet(expect = false, update = true)) {
            timber.log.Timber.w("Already sending media")
            return
        }

        viewModelScope.launch {
            try {
                val chat = chats.value.find { it.id == cid } ?: return@launch
                val timeStr = getFormattedTime()
                val idempotencyKey = generateIdempotencyKey()

                val audioUrl = repository.uploadMediaMessage(context, uri, cid, type, idempotencyKey, chat.recipientId.ifEmpty { chat.name })
                
                if (audioUrl == null) {
                    _isSending.value = false
                    return@launch
                }

                val isSelfChat = chat.isSavedMessages
                val isAiChat = chat.isAiAssistant

                val msgId = repository.sendMessage(
                    chatId = cid,
                    sender = "me",
                    text = if (type == "image") "📷 Photo" else "🎥 Video",
                    isMe = true,
                    isAttachment = false,
                    timeStr = timeStr,
                    status = if (isSelfChat || isAiChat) "delivered" else "sending",
                    type = type,
                    audioUrl = audioUrl,
                    duration = null,
                    recipientId = chat.recipientId.ifEmpty { chat.name },
                    idempotencyKey = idempotencyKey
                )
                
                _isSending.value = false
                if (msgId != -1L) {
                    if (isSelfChat || isAiChat) {
                        repository.updateMessageStatus(msgId.toInt(), "delivered")
                    }
                }
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to send media message")
                _isSending.value = false
            }
        }
    }

    fun sendVoiceMessage(file: java.io.File, duration: Int) {
        val cid = _selectedChatId.value ?: return
        
        if (!shouldAllowSend()) {
            timber.log.Timber.w("Voice send throttled")
            return
        }

        if (!_isSending.compareAndSet(expect = false, update = true)) {
            timber.log.Timber.w("Already sending voice")
            return
        }

        viewModelScope.launch {
            try {
                val chat = chats.value.find { it.id == cid } ?: return@launch
                val timeStr = getFormattedTime()
                val idempotencyKey = generateIdempotencyKey()

                val audioUrl = repository.uploadVoiceMessage(file, cid, idempotencyKey, chat.recipientId.ifEmpty { chat.name })
                file.delete()
                
                if (audioUrl == null) {
                    _isSending.value = false
                    return@launch
                }

                val isSelfChat = chat.isSavedMessages
                val isAiChat = chat.isAiAssistant

                val msgId = repository.sendMessage(
                    chatId = cid,
                    sender = "me",
                    text = "🎤 Voice message",
                    isMe = true,
                    isAttachment = false,
                    timeStr = timeStr,
                    status = if (isSelfChat || isAiChat) "delivered" else "sending",
                    type = "voice",
                    audioUrl = audioUrl,
                    duration = duration,
                    recipientId = chat.recipientId.ifEmpty { chat.name },
                    idempotencyKey = idempotencyKey
                )
                
                _isSending.value = false
                if (msgId != -1L) {
                    if (isSelfChat || isAiChat) {
                        repository.updateMessageStatus(msgId.toInt(), "delivered")
                    }
                }
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to send voice message")
                file.delete()
                _isSending.value = false
            }
        }
    }

    fun sendLocationMessage(context: android.content.Context) {
        val cid = _selectedChatId.value ?: return
        if (!shouldAllowSend()) return
        if (!_isSending.compareAndSet(expect = false, update = true)) return

        viewModelScope.launch {
            try {
                val chat = chats.value.find { it.id == cid } ?: run {
                    _isSending.value = false
                    return@launch
                }
                val locationData = com.example.util.LocationHelper.getCurrentLocation(context)
                if (locationData == null) {
                    _isSending.value = false
                    return@launch
                }
                val timeStr = getFormattedTime()
                val idempotencyKey = generateIdempotencyKey()
                val isSelfChat = chat.isSavedMessages
                val isAiChat = chat.isAiAssistant

                val msgId = repository.sendMessage(
                    chatId = cid,
                    sender = "me",
                    text = locationData.toJson(),
                    isMe = true,
                    isAttachment = false,
                    timeStr = timeStr,
                    status = if (isSelfChat || isAiChat) "delivered" else "sending",
                    type = "location",
                    audioUrl = null,
                    duration = null,
                    recipientId = chat.recipientId.ifEmpty { chat.name },
                    idempotencyKey = idempotencyKey
                )
                _isSending.value = false
                if (msgId != -1L && (isSelfChat || isAiChat)) {
                    repository.updateMessageStatus(msgId.toInt(), "delivered")
                }
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to send location message")
                _isSending.value = false
            }
        }
    }

    fun sendMessage(text: String, isAttachment: Boolean = false) {
        val cid = _selectedChatId.value ?: return
        val trimmed = text.trim()
        
        if (!isAttachment && !com.example.utils.InputValidator.isValidMessage(text)) {
            return
        }

        // SECURITY: Check throttle BEFORE anything else
        if (!shouldAllowSend()) {
            timber.log.Timber.w("Message send throttled")
            return
        }

        // SECURITY: Atomic check-then-set
        if (!_isSending.compareAndSet(expect = false, update = true)) {
            timber.log.Timber.w("Already sending message (concurrent attempt)")
            return
        }

        viewModelScope.launch {
            try {
                val chat = chats.value.find { it.id == cid } ?: run {
                    _isSending.value = false
                    return@launch
                }
                
                val timeStr = getFormattedTime()
                val recipientId = chat.recipientId.ifEmpty { chat.name }
                val idempotencyKey = generateIdempotencyKey(cid, recipientId, trimmed)
                val isSelfChat = chat.isSavedMessages
                val isAiChat = chat.isAiAssistant
                val activeAi = _selectedAiModel.value
                val finalRecipientId = if (isAiChat) {
                    if (chat.recipientId.isNotBlank()) chat.recipientId
                    else if (activeAi.persona == com.example.data.AiPersona.HEXAGON) "ai_hexagon"
                    else "ai_ventaxis"
                } else recipientId

                val msgId = repository.sendMessage(
                    chatId = cid,
                    sender = "me",
                    text = trimmed.ifBlank { "📎 Sent an attachment" },
                    isMe = true,
                    isAttachment = isAttachment,
                    timeStr = timeStr,
                    status = if (isSelfChat || isAiChat) "delivered" else "sending",
                    recipientId = finalRecipientId,
                    idempotencyKey = idempotencyKey,
                    personaId = if (isAiChat) activeAi.personaId else null
                )
                
                _isSending.value = false
                if (msgId != -1L) {
                    if (isSelfChat) {
                        repository.updateMessageStatus(msgId.toInt(), "delivered")
                    } else if (isAiChat) {
                        repository.updateMessageStatus(msgId.toInt(), "delivered")
                        handleAiReply(cid, trimmed, msgId.toInt())
                    }
                }

            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to send message")
                _isSending.value = false
            }
        }
    }

    // SECURITY: Throttle send operations
    private fun shouldAllowSend(): Boolean {
        val now = System.nanoTime()
        val last = lastSendTimeNano.get()
        val elapsed = (now - last) / 1_000_000 // Convert to milliseconds
        
        if (elapsed < MIN_SEND_INTERVAL_MS) {
            return false
        }
        
        // Update only if we haven't been updated since
        lastSendTimeNano.compareAndSet(last, now)
        return true
    }

    // SECURITY: Generate cryptographic idempotency key (32 random bytes, URL-safe Base64 without padding)
    private fun generateIdempotencyKey(
        chatId: Int = 0,
        recipientId: String = "me",
        content: String = ""
    ): String {
        val randomBytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(randomBytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
    }

    fun logout(context: android.content.Context, onComplete: () -> Unit) {
        viewModelScope.launch {
            repository.logout(context)
            _selectedChatId.value = null
            onAccountChanged()
            onComplete()
        }
    }

    fun deleteAccount(context: android.content.Context, onComplete: () -> Unit) {
        viewModelScope.launch {
            repository.deleteAccount(context)
            _selectedChatId.value = null
            onAccountChanged()
            onComplete()
        }
    }
    
    fun retryMessage(msgId: Int, chatId: Int, text: String) {
        viewModelScope.launch {
            val chat = chats.value.find { it.id == chatId } ?: return@launch
            val isSelfChat = chat.isSavedMessages
            val isAiChat = chat.isAiAssistant
            if (isSelfChat) {
                repository.updateMessageStatus(msgId, "delivered")
            } else if (isAiChat) {
                repository.updateMessageStatus(msgId, "delivered")
                handleAiReply(chatId, text, msgId)
            } else {
                repository.retryMessage(msgId, chatId, text)
            }
        }
    }

    fun selectAiModel(model: AiModelOption) {
        _selectedAiModel.value = model
    }

    fun switchToAiChat(model: AiModelOption) {
        _selectedAiModel.value = model
    }

    private fun handleAiReply(chatId: Int, userMsg: String, originalMsgId: Int) {
        viewModelScope.launch {
            delay(150)
            repository.updateMessageStatus(originalMsgId, "read")
            _typingChatId.value = chatId

            val modelOption = _selectedAiModel.value
            val activePersonaId = modelOption.personaId

            try {
                // Retrieve conversation history strictly for this active persona
                val pastMessages = repository.getMessagesForChat(chatId).first()
                    .filter { msg ->
                        !msg.isAttachment && msg.text.isNotBlank() && msg.id != originalMsgId &&
                        resolvePersonaId(msg) == activePersonaId
                    }
                    .takeLast(10)

                val history = pastMessages.map { msg ->
                    Pair(if (msg.isMe) "user" else "model", msg.text)
                }

                // Guard with a strict 9s timeout so the UI never hangs or shows typing indefinitely
                val result = kotlinx.coroutines.withTimeoutOrNull(9000L) {
                    geminiService.requestAssistant(
                        prompt = userMsg,
                        history = history,
                        modelOption = modelOption
                    )
                } ?: geminiService.generateFallbackResult(userMsg, history, modelOption)

                when (result) {
                    is AiResult.Success -> {
                        _aiErrorState.value = null
                        val replyTime = getFormattedTime()
                        repository.sendMessage(
                            chatId = chatId,
                            sender = modelOption.displayName,
                            text = result.reply,
                            isMe = false,
                            isAttachment = false,
                            timeStr = replyTime,
                            status = "delivered",
                            recipientId = "ai_hexagon",
                            idempotencyKey = generateIdempotencyKey(),
                            personaId = activePersonaId
                        )
                    }
                    is AiResult.RateLimited -> {
                        _aiErrorState.value = AiUiError.RateLimited(result.retryAfterSeconds)
                    }
                    else -> {
                        // Any other status: fallback with graceful in-character response
                        _aiErrorState.value = null
                        val replyTime = getFormattedTime()
                        val fallback = geminiService.generateFallbackReply(userMsg, history, modelOption)
                        repository.sendMessage(
                            chatId = chatId,
                            sender = modelOption.displayName,
                            text = fallback,
                            isMe = false,
                            isAttachment = false,
                            timeStr = replyTime,
                            status = "delivered",
                            recipientId = "ai_hexagon",
                            idempotencyKey = generateIdempotencyKey(),
                            personaId = activePersonaId
                        )
                    }
                }
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Error during handleAiReply")
                val replyTime = getFormattedTime()
                val fallback = geminiService.generateFallbackReply(userMsg, emptyList(), modelOption)
                repository.sendMessage(
                    chatId = chatId,
                    sender = modelOption.displayName,
                    text = fallback,
                    isMe = false,
                    isAttachment = false,
                    timeStr = replyTime,
                    status = "delivered",
                    recipientId = "ai_hexagon",
                    idempotencyKey = generateIdempotencyKey(),
                    personaId = activePersonaId
                )
            } finally {
                _typingChatId.value = null
            }
        }
    }

    fun createNewChat(name: String, initials: String) {
        viewModelScope.launch {
            val trimmed = name.trim()
            val currentUserId = repository.getCurrentUserId()
            
            val isHexagon = trimmed.equals("Hexagon", ignoreCase = true) || trimmed.equals("Hexagon AI", ignoreCase = true)
            val isVentaxis = trimmed.equals("Ventaxis", ignoreCase = true) || trimmed.equals("Ventaxis AI", ignoreCase = true)
            val isHexShard = trimmed.equals("HexShard", ignoreCase = true) || trimmed.equals("HexShard AI", ignoreCase = true)
            val isAi = isHexagon || isVentaxis || isHexShard || trimmed.equals("AI", ignoreCase = true) || trimmed.equals("AI Assistant", ignoreCase = true)
            
            if (isAi) {
                if (isVentaxis) {
                    _selectedAiModel.value = AiModelOption.VENTAXIS
                } else {
                    _selectedAiModel.value = AiModelOption.HEXAGON
                }
                val aiChat = chats.value.find { it.isAiAssistant } ?: repository.getChatByRecipientId("ai_hexagon")
                if (aiChat != null) {
                    _selectedChatId.value = aiChat.id
                }
                return@launch
            }

            val resolved = repository.resolveUser(trimmed)
            val finalRecipientId = resolved?.first ?: trimmed
            val finalName = resolved?.second ?: trimmed
            val finalAva = if (initials.isNotBlank()) initials.uppercase().take(2) else finalName.take(2).uppercase()
            val conversationId = repository.computeConversationId(currentUserId, finalRecipientId)

            val existing = repository.getChatByConversationId(conversationId)
                ?: repository.getChatByRecipientId(finalRecipientId)
                ?: chats.value.find { 
                    it.recipientId == finalRecipientId || it.name.equals(finalName, ignoreCase = true) || (it.conversationId.isNotBlank() && it.conversationId == conversationId)
                }
            if (existing != null) {
                _selectedChatId.value = existing.id
                return@launch
            }

            val newChat = ChatEntity(
                accountId = currentUserId,
                name = finalName,
                ava = finalAva,
                status = "online",
                preview = "E2E encryption channel opened",
                time = getFormattedTime(),
                recipientId = finalRecipientId,
                conversationId = conversationId,
                conversationType = com.example.data.ConversationType.DIRECT.name
            )
            val newChatIdInt = repository.insertChat(newChat).toInt()
            _selectedChatId.value = newChatIdInt
        }
    }

    fun importContactFromQr(profile: com.example.util.ProfileQrData, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val currentUserId = repository.getCurrentUserId()
                if (profile.userId.isNotBlank() && profile.publicKey.isNotBlank()) {
                    repository.registerVerifiedPeerKey(profile.userId, profile.publicKey)
                }
                val finalName = profile.displayName.ifBlank { profile.hexNumber.ifBlank { profile.userId } }
                val finalAva = finalName.take(2).uppercase()
                val conversationId = repository.computeConversationId(currentUserId, profile.userId)

                val existing = repository.getChatByConversationId(conversationId)
                    ?: repository.getChatByRecipientId(profile.userId)
                    ?: chats.value.find { 
                        it.recipientId == profile.userId || it.name.equals(finalName, ignoreCase = true) || (it.conversationId.isNotBlank() && it.conversationId == conversationId)
                    }
                if (existing != null) {
                    _selectedChatId.value = existing.id
                    onComplete(true)
                    return@launch
                }

                val newChat = ChatEntity(
                    accountId = currentUserId,
                    name = finalName,
                    ava = finalAva,
                    status = "online",
                    preview = "Verified via QR profile exchange",
                    time = getFormattedTime(),
                    recipientId = profile.userId,
                    conversationId = conversationId,
                    conversationType = com.example.data.ConversationType.DIRECT.name
                )
                val newChatIdInt = repository.insertChat(newChat).toInt()
                _selectedChatId.value = newChatIdInt
                onComplete(true)
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to import contact from QR")
                onComplete(false)
            }
        }
    }

    fun resolveAndImportQrToken(token: String, onComplete: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val profile = repository.resolveProfileShareToken(token)
                if (profile == null) {
                    onComplete(false, "Token expired or invalid")
                    return@launch
                }
                if (profile.publicKey.isNotBlank()) {
                    repository.registerVerifiedPeerKey(profile.userId, profile.publicKey)
                }
                val currentUserId = repository.getCurrentUserId()
                val finalName = profile.username.ifBlank { profile.hexNumber.ifBlank { profile.userId } }
                val finalAva = finalName.take(2).uppercase()
                val conversationId = repository.computeConversationId(currentUserId, profile.userId)

                val existing = repository.getChatByConversationId(conversationId)
                    ?: repository.getChatByRecipientId(profile.userId)
                    ?: chats.value.find { 
                        it.recipientId == profile.userId || it.name.equals(finalName, ignoreCase = true) || (it.conversationId.isNotBlank() && it.conversationId == conversationId)
                    }
                if (existing != null) {
                    _selectedChatId.value = existing.id
                    onComplete(true, null)
                    return@launch
                }

                val newChat = ChatEntity(
                    accountId = currentUserId,
                    name = finalName,
                    ava = finalAva,
                    status = "online",
                    preview = "Verified via secure QR token exchange",
                    time = getFormattedTime(),
                    recipientId = profile.userId,
                    conversationId = conversationId,
                    conversationType = com.example.data.ConversationType.DIRECT.name
                )
                val newChatIdInt = repository.insertChat(newChat).toInt()
                _selectedChatId.value = newChatIdInt
                onComplete(true, null)
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to resolve QR token")
                onComplete(false, e.localizedMessage)
            }
        }
    }

    fun handleIncomingDeepLink(token: String) {
        viewModelScope.launch {
            try {
                val profile = repository.resolveProfileShareToken(token)
                if (profile != null) {
                    _pendingProfileConfirmation.value = profile
                }
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to handle incoming deep link token")
            }
        }
    }

    fun confirmAddContact(profile: com.example.util.ProfileQrData, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            try {
                if (profile.publicKey.isNotBlank()) {
                    repository.registerVerifiedPeerKey(profile.userId, profile.publicKey)
                }
                val currentUserId = repository.getCurrentUserId()
                val finalName = profile.username.ifBlank { profile.hexNumber.ifBlank { profile.userId } }
                val finalAva = finalName.take(2).uppercase()
                val conversationId = repository.computeConversationId(currentUserId, profile.userId)

                val existing = repository.getChatByConversationId(conversationId)
                    ?: repository.getChatByRecipientId(profile.userId)
                    ?: chats.value.find { 
                        it.recipientId == profile.userId || it.name.equals(finalName, ignoreCase = true) || (it.conversationId.isNotBlank() && it.conversationId == conversationId)
                    }
                if (existing != null) {
                    _selectedChatId.value = existing.id
                } else {
                    val newChat = ChatEntity(
                        accountId = currentUserId,
                        name = finalName,
                        ava = finalAva,
                        status = "online",
                        preview = "Verified via secure QR token exchange",
                        time = getFormattedTime(),
                        recipientId = profile.userId,
                        conversationId = conversationId,
                        conversationType = com.example.data.ConversationType.DIRECT.name
                    )
                    val newChatIdInt = repository.insertChat(newChat).toInt()
                    _selectedChatId.value = newChatIdInt
                }
                _pendingProfileConfirmation.value = null
                onComplete(true)
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to confirm add contact")
                _pendingProfileConfirmation.value = null
                onComplete(false)
            }
        }
    }

    fun dismissPendingProfileConfirmation() {
        _pendingProfileConfirmation.value = null
    }

    private fun resolvePersonaId(msg: MessageEntity): String {
        msg.personaId?.let { return it.lowercase() }
        val sender = msg.sender
        if (sender.contains("Ventaxis", ignoreCase = true)) return "ventaxis"
        if (sender.contains("Hexagon", ignoreCase = true) || sender.contains("HexShard", ignoreCase = true)) return "hexagon"
        val text = msg.text
        if (text.contains("Ventaxis", ignoreCase = true)) return "ventaxis"
        if (text.contains("Hexagon", ignoreCase = true) || text.contains("HexShard", ignoreCase = true)) return "hexagon"
        return "hexagon"
    }

    private fun getFormattedTime(): String {
        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
        return sdf.format(Date())
    }
}
