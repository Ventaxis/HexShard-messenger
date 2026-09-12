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
                val pId = it.personaId ?: AiModelOption.VENTAXIS.personaId
                pId == aiModel.personaId
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

    suspend fun downloadVoiceMessage(url: String): ByteArray? {
        val cid = _selectedChatId.value ?: return null
        val chat = chats.value.find { it.id == cid } ?: return null
        return repository.downloadVoiceMessage(url, chat.recipientId.ifEmpty { chat.name })
    }

    suspend fun downloadMediaMessage(url: String): ByteArray? {
        val cid = _selectedChatId.value ?: return null
        val chat = chats.value.find { it.id == cid } ?: return null
        return repository.downloadMediaMessage(url, chat.recipientId.ifEmpty { chat.name })
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
                val finalRecipientId = if (isAiChat) "ai_assistant" else recipientId

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
        // If not in an AI chat, select the single HexShard AI chat
        val currentChat = chats.value.find { it.id == _selectedChatId.value }
        if (currentChat == null || !currentChat.isAiAssistant) {
            val aiChat = chats.value.find { it.isAiAssistant }
            if (aiChat != null) {
                _selectedChatId.value = aiChat.id
            }
        }
    }

    private fun handleAiReply(chatId: Int, userMsg: String, originalMsgId: Int) {
        viewModelScope.launch {
            delay(200)
            repository.updateMessageStatus(originalMsgId, "read")
            _typingChatId.value = chatId

            val modelOption = _selectedAiModel.value

            // Retrieve conversation history strictly for this specific persona
            val pastMessages = repository.getMessagesForChat(chatId).first()
                .filter { msg ->
                    !msg.isAttachment && msg.text.isNotBlank() && msg.id != originalMsgId &&
                    (msg.personaId ?: AiModelOption.VENTAXIS.personaId) == modelOption.personaId
                }
                .takeLast(20)

            val history = pastMessages.map { msg ->
                Pair(if (msg.isMe) "user" else "model", msg.text)
            }

            val result = geminiService.requestAssistant(
                prompt = userMsg,
                history = history,
                modelOption = modelOption
            )

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
                        recipientId = "ai_assistant",
                        idempotencyKey = generateIdempotencyKey(),
                        personaId = modelOption.personaId
                    )
                }
                is AiResult.RateLimited -> {
                    _aiErrorState.value = AiUiError.RateLimited(result.retryAfterSeconds)
                }
                is AiResult.Unauthorized -> {
                    _aiErrorState.value = AiUiError.Unauthorized
                }
                is AiResult.FunctionNotFound -> {
                    _aiErrorState.value = AiUiError.FunctionNotFound
                }
                is AiResult.DeploymentUnavailable -> {
                    _aiErrorState.value = AiUiError.DeploymentUnavailable
                }
                is AiResult.ModelUnavailable -> {
                    _aiErrorState.value = AiUiError.ModelUnavailable
                }
                is AiResult.ConfigurationError -> {
                    _aiErrorState.value = AiUiError.ConfigurationError
                }
                is AiResult.InvalidRequest -> {
                    _aiErrorState.value = AiUiError.InvalidRequest
                }
                is AiResult.NetworkError -> {
                    _aiErrorState.value = AiUiError.NetworkError
                }
                is AiResult.UpstreamError -> {
                    _aiErrorState.value = AiUiError.UpstreamError(result.message)
                }
                is AiResult.Loading -> {
                    // Loading state
                }
                is AiResult.Error -> {
                    _aiErrorState.value = AiUiError.Custom(if (result.message.isNotBlank()) result.message else "AI Error (${result.code})")
                }
            }

            _typingChatId.value = null
        }
    }

    fun createNewChat(name: String, initials: String) {
        viewModelScope.launch {
            val trimmed = name.trim()
            val currentUserId = repository.getCurrentUserId()
            
            val isHexagon = trimmed.equals("Hexagon", ignoreCase = true) || trimmed.equals("Hexagon AI", ignoreCase = true)
            val isVentaxis = trimmed.equals("Ventaxis", ignoreCase = true) || trimmed.equals("Ventaxis AI", ignoreCase = true)
            val isAi = isHexagon || isVentaxis || trimmed.equals("AI", ignoreCase = true) || trimmed.equals("AI Assistant", ignoreCase = true)
            
            if (isAi) {
                val existingAiChat = chats.value.find { it.isAiAssistant }
                if (existingAiChat != null) {
                    if (isHexagon) {
                        _selectedAiModel.value = AiModelOption.HEXAGON_BETA
                    } else {
                        _selectedAiModel.value = AiModelOption.VENTAXIS
                    }
                    _selectedChatId.value = existingAiChat.id
                    return@launch
                }
            }

            val resolved = repository.resolveUser(trimmed)
            val finalRecipientId = resolved?.first ?: trimmed
            val finalName = resolved?.second ?: trimmed
            val finalAva = if (initials.isNotBlank()) initials.uppercase().take(2) else finalName.take(2).uppercase()
            val conversationId = repository.computeConversationId(currentUserId, finalRecipientId)

            val existing = chats.value.find { 
                it.recipientId == finalRecipientId || it.name.equals(finalName, ignoreCase = true)
            }
            if (existing != null) {
                _selectedChatId.value = existing.id
                return@launch
            }

            val newChat = ChatEntity(
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

    private fun getFormattedTime(): String {
        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
        return sdf.format(Date())
    }
}
