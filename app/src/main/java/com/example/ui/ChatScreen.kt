package com.example.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.ConversationType
import com.example.data.database.ChatEntity
import com.example.data.database.MessageEntity
import com.example.data.database.getConversationType
import com.example.data.database.isAiAssistant
import com.example.data.database.isSavedMessages
import kotlinx.coroutines.launch

// Custom Theme colors matching user specs
val SpotifyGreen = Color(0xFF1DB954)
val SpotifyGreenHover = Color(0xFF1ED760)

// Dark Theme Colors
val DarkBg = Color(0xFF121212)
val DarkBgSurface = Color(0xFF181818)
val DarkBgActive = Color(0xFF282828)
val DarkBorder = Color(0xFF323232)
val DarkTxtMain = Color(0xFFFFFFFF)
val DarkTxtSec = Color(0xFFB3B3B3)
val DarkBubbleMe = Color(0xFF1DB954)
val DarkBubbleOther = Color(0xFF242424)

// Light Theme Colors
val LightBg = Color(0xFFFFFFFF)
val LightBgSurface = Color(0xFFF5F5F5)
val LightBgActive = Color(0xFFEBEBEB)
val LightBorder = Color(0xFFE4E4E4)
val LightTxtMain = Color(0xFF191414)
val LightTxtSec = Color(0xFF737373)
val LightBubbleMe = Color(0xFF1DB954)
val LightBubbleOther = Color(0xFFECECEC)

val DangerColor = Color(0xFFE53935)

@Composable
fun HexShardApp(viewModel: ChatViewModel, onNavigateToSettings: () -> Unit = {}) {
    val isDark by viewModel.isDarkTheme.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.onAccountChanged()
    }
    
    // Choose active palette
    val bg = if (isDark) DarkBg else LightBg
    val txtMain = if (isDark) DarkTxtMain else LightTxtMain

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = bg
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().navigationBarsPadding().statusBarsPadding()) {
            val isWide = maxWidth > 640.dp
            
            val chats by viewModel.chats.collectAsStateWithLifecycle()
            val selectedId by viewModel.selectedChatId.collectAsStateWithLifecycle()
            val messages by viewModel.selectedChatMessages.collectAsStateWithLifecycle()
            val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
            val isSending by viewModel.isSending.collectAsStateWithLifecycle()
            val typingChatId by viewModel.typingChatId.collectAsStateWithLifecycle()
            val selectedAiModel by viewModel.selectedAiModel.collectAsStateWithLifecycle()
            val aiErrorState by viewModel.aiErrorState.collectAsStateWithLifecycle()

            if (isWide) {
                // Expanded / Tablet Canonical Layout (List-Detail in row)
                Row(modifier = Modifier.fillMaxSize()) {
                    SidebarPanel(
                        modifier = Modifier.width(360.dp).fillMaxHeight(),
                        chats = chats,
                        selectedId = selectedId,
                        searchQuery = searchQuery,
                        typingChatId = typingChatId,
                        isDark = isDark,
                        onChatSelected = { viewModel.selectChat(it) },
                        onQueryChanged = { viewModel.updateSearchQuery(it) },
                        onAddChatClicked = { name, initials -> viewModel.createNewChat(name, initials) },
                        onToggleTheme = { viewModel.toggleTheme() },
                        onNavigateToSettings = onNavigateToSettings,
                        onTogglePin = { viewModel.toggleChatPin(it) },
                        onDeleteChat = { viewModel.deleteChat(it) }
                    )
                    
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f)
                            .background(if (isDark) Color(0xFF0C0C0C) else Color(0xFFFAFAFA))
                    ) {
                        if (selectedId != null) {
                            val activeChat = chats.find { it.id == selectedId }
                            if (activeChat != null) {
                                ChatArea(
                                    modifier = Modifier.fillMaxSize(),
                                    chat = activeChat,
                                    messages = messages,
                                    isSending = isSending,
                                    isTyping = typingChatId == selectedId,
                                    isDark = isDark,
                                    showBackButton = false,
                                    onBackClicked = { viewModel.deselectChat() },
                                    onMessageSent = { viewModel.sendMessage(it) },
                                    onAttachmentSent = { viewModel.sendMessage("", isAttachment = true) },
                                    onSendVoiceMessage = { file, dur -> viewModel.sendVoiceMessage(file, dur) },
                                    onSendMediaMessage = { uri, type -> viewModel.sendMediaMessage(context, uri, type) },
                                    onDownloadVoice = { url -> viewModel.downloadVoiceMessage(url) },
                                    onDownloadMedia = { url -> viewModel.downloadMediaMessage(url) },
                                    onRetryMessage = { msgId, chatId, text -> viewModel.retryMessage(msgId, chatId, text) },
                                    selectedAiModel = selectedAiModel,
                                    onSwitchAiModel = { model -> viewModel.switchToAiChat(model) },
                                    aiError = aiErrorState,
                                    onDismissAiError = { viewModel.dismissAiError() }
                                )
                            }
                        } else {
                            EmptyStatePlaceholder(isDark = isDark)
                        }
                    }
                }
            } else {
                // Compact Screen Layout (Mobile Single-view Navigation)
                AnimatedContent(
                    targetState = selectedId,
                    transitionSpec = {
                        if (targetState != null) {
                            // Slide inside details
                            slideInHorizontally { width -> width } + fadeIn() togetherWith
                                    slideOutHorizontally { width -> -width } + fadeOut()
                        } else {
                            // Slide back, screen switches left-to-right
                            slideInHorizontally { width -> -width } + fadeIn() togetherWith
                                    slideOutHorizontally { width -> width } + fadeOut()
                        }
                    },
                    label = "mobile_screen_transition"
                ) { currentId ->
                    if (currentId == null) {
                        SidebarPanel(
                            modifier = Modifier.fillMaxSize(),
                            chats = chats,
                            selectedId = null,
                            searchQuery = searchQuery,
                            typingChatId = typingChatId,
                            isDark = isDark,
                            onChatSelected = { viewModel.selectChat(it) },
                            onQueryChanged = { viewModel.updateSearchQuery(it) },
                            onAddChatClicked = { name, initials -> viewModel.createNewChat(name, initials) },
                            onToggleTheme = { viewModel.toggleTheme() },
                            onNavigateToSettings = onNavigateToSettings,
                            onTogglePin = { viewModel.toggleChatPin(it) },
                            onDeleteChat = { viewModel.deleteChat(it) }
                        )
                    } else {
                        val activeChat = chats.find { it.id == currentId }
                        if (activeChat != null) {
                            ChatArea(
                                modifier = Modifier.fillMaxSize(),
                                chat = activeChat,
                                messages = messages,
                                isSending = isSending,
                                isTyping = typingChatId == currentId,
                                isDark = isDark,
                                showBackButton = true,
                                onBackClicked = { viewModel.deselectChat() },
                                onMessageSent = { viewModel.sendMessage(it) },
                                onAttachmentSent = { viewModel.sendMessage("", isAttachment = true) },
                                onSendVoiceMessage = { file, dur -> viewModel.sendVoiceMessage(file, dur) },
                                onSendMediaMessage = { uri, type -> viewModel.sendMediaMessage(context, uri, type) },
                                onDownloadVoice = { url -> viewModel.downloadVoiceMessage(url) },
                                onDownloadMedia = { url -> viewModel.downloadMediaMessage(url) },
                                onRetryMessage = { msgId, chatId, text -> viewModel.retryMessage(msgId, chatId, text) },
                                selectedAiModel = selectedAiModel,
                                onSwitchAiModel = { model -> viewModel.switchToAiChat(model) },
                                aiError = aiErrorState,
                                onDismissAiError = { viewModel.dismissAiError() }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SidebarPanel(
    modifier: Modifier = Modifier,
    chats: List<ChatEntity>,
    selectedId: Int?,
    searchQuery: String,
    typingChatId: Int?,
    isDark: Boolean,
    onChatSelected: (Int) -> Unit,
    onQueryChanged: (String) -> Unit,
    onAddChatClicked: (String, String) -> Unit,
    onToggleTheme: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onTogglePin: (Int) -> Unit = {},
    onDeleteChat: (Int) -> Unit = {}
) {
    val bg = if (isDark) DarkBg else LightBg
    val border = if (isDark) DarkBorder else LightBorder
    val txtMain = if (isDark) DarkTxtMain else LightTxtMain
    val txtSec = if (isDark) DarkTxtSec else LightTxtSec

    val strings = LocalStrings.current
    var showNewChatDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .background(bg)
            .drawBehind {
                // Draw sleek dividing line on right of sidebar
                drawLine(
                    color = border,
                    start = Offset(size.width, 0f),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx()
                )
            }
    ) {
        // SB-Head
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                HexagonLogo(size = 26.dp)
                Text(
                    text = "HexShard",
                    color = txtMain,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = 0.5.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Settings IconButton
                IconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            if (isDark) Color(0xFF1E1E1E) else Color(0xFFEBEBEB),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = txtSec,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Theme ToggleIconButton
                IconButton(
                    onClick = onToggleTheme,
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            if (isDark) Color(0xFF1E1E1E) else Color(0xFFEBEBEB),
                            shape = CircleShape
                        )
                        .testTag("theme_toggle_btn")
                ) {
                    Icon(
                        imageVector = if (isDark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                        contentDescription = "Toggle Theme",
                        tint = if (isDark) Color(0xFFFFD54F) else SpotifyGreen,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Add button [+]
                IconButton(
                    onClick = { showNewChatDialog = true },
                    modifier = Modifier
                        .size(36.dp)
                        .background(SpotifyGreen, shape = CircleShape)
                        .testTag("add_chat_btn")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "New Chat",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Search: Pill-shape
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            var isFocused by remember { mutableStateOf(false) }
            val borderTint = if (isFocused) SpotifyGreen else border

            OutlinedTextField(
                value = searchQuery,
                onValueChange = onQueryChanged,
                placeholder = {
                    Text(
                        strings.searchMessages,
                        fontSize = 14.sp,
                        color = txtSec
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = txtSec,
                        modifier = Modifier.size(18.dp)
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = if (isDark) Color(0xFF1E1E1E) else Color(0xFFF9F9F9),
                    unfocusedContainerColor = if (isDark) Color(0xFF151515) else Color(0xFFF0F0F0),
                    focusedBorderColor = SpotifyGreen,
                    unfocusedBorderColor = border,
                    focusedTextColor = txtMain,
                    unfocusedTextColor = txtMain
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("search_input")
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Chat list
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("chat_list")
        ) {
            if (chats.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 80.dp, start = 16.dp, end = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        AnimatedCrystalLogo(size = 140.dp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = strings.noConversationsYet,
                            color = txtSec,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                val sortedChats = chats.sortedByDescending { it.isPinned }
                items(sortedChats, key = { it.id }) { chat ->
                    val isSelected = chat.id == selectedId
                    val isTyping = typingChatId == chat.id
                    
                    ChatListItem(
                        chat = chat,
                        isSelected = isSelected,
                        isTyping = isTyping,
                        isDark = isDark,
                        onClick = { onChatSelected(chat.id) },
                        onPin = { onTogglePin(chat.id) },
                        onDelete = { onDeleteChat(chat.id) }
                    )
                }
            }
        }
    }

    if (showNewChatDialog) {
        NewChatDialog(
            isDark = isDark,
            onDismiss = { showNewChatDialog = false },
            onConfirm = { name, initials ->
                onAddChatClicked(name, initials)
                showNewChatDialog = false
            }
        )
    }
}

@Composable
fun ChatListItem(
    chat: ChatEntity,
    isSelected: Boolean,
    isTyping: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit = {}
) {
    val strings = LocalStrings.current
    var expanded by remember { mutableStateOf(false) }

    val activeBg = if (isDark) DarkBgActive else LightBgActive
    val bg = if (isSelected) activeBg else Color.Transparent
    val txtMain = if (isDark) DarkTxtMain else LightTxtMain
    val txtSec = if (isDark) DarkTxtSec else LightTxtSec

    val isSelfChat = chat.isSavedMessages
    val isAiChat = chat.isAiAssistant

    val displayName = if (isSelfChat) strings.savedMessages else chat.name
    val displayAva = if (isSelfChat) (if (strings == RussianStrings) "ИЗ" else "SM") else if (isAiChat) "HA" else chat.ava
    val displayPreview = if (isSelfChat && (chat.preview == "Добро пожаловать в Избранное!" || chat.preview == "Welcome to Saved Messages!")) {
        strings.welcomeSavedMessages
    } else {
        chat.preview
    }

    val itemAvatarBrush = when {
        isAiChat -> Brush.linearGradient(listOf(SpotifyGreen, Color(0xFF00897B)))
        else -> Brush.linearGradient(listOf(SpotifyGreen, Color(0xFF0D5E29)))
    }
    
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onClick() },
                        onLongPress = { expanded = true }
                    )
                }
                .background(bg)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .drawBehind {
                    if (isSelected) {
                        drawRect(
                            color = SpotifyGreen,
                            topLeft = Offset(0f, 0f),
                            size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)
                        )
                    }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(44.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = itemAvatarBrush,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = displayAva.uppercase(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                if (chat.status == "online") {
                    OnlinePulseDot(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 1.dp, end = 1.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayName,
                            color = txtMain,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isAiChat) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = SpotifyGreen.copy(alpha = 0.18f)
                            ) {
                                Text(
                                    text = "AI",
                                    color = SpotifyGreen,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (chat.isPinned) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.PushPin, contentDescription = "Pinned", tint = txtSec, modifier = Modifier.size(12.dp).graphicsLayer(rotationZ = 45f))
                        }
                    }
                    Text(
                        text = chat.time,
                        color = txtSec,
                        fontSize = 10.sp
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                
                if (isTyping) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = strings.typing,
                            color = SpotifyGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        JumpingTypingIndicator(color = SpotifyGreen)
                    }
                } else {
                    Text(
                        text = displayPreview,
                        color = txtSec,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(if (isDark) Color(0xFF1E1E1E) else Color.White)
        ) {
            DropdownMenuItem(
                text = { Text(if (chat.isPinned) strings.unpinChat else strings.pinChat) },
                onClick = {
                    expanded = false
                    onPin()
                },
                leadingIcon = { Icon(Icons.Default.PushPin, null) }
            )
            if (!isSelfChat && !isAiChat) {
                DropdownMenuItem(
                    text = { Text(strings.deleteChat, color = Color.Red) },
                    onClick = {
                        expanded = false
                        onDelete()
                    },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color.Red) }
                )
            }
        }
    }
}

@Composable
fun ChatArea(
    modifier: Modifier = Modifier,
    chat: ChatEntity,
    messages: List<MessageEntity>,
    isSending: Boolean,
    isTyping: Boolean,
    isDark: Boolean,
    showBackButton: Boolean,
    onBackClicked: () -> Unit,
    onMessageSent: (String) -> Unit,
    onAttachmentSent: () -> Unit,
    onSendVoiceMessage: (java.io.File, Int) -> Unit,
    onSendMediaMessage: (android.net.Uri, String) -> Unit = { _, _ -> },
    onDownloadVoice: suspend (String) -> ByteArray? = { null },
    onDownloadMedia: suspend (String) -> ByteArray? = { null },
    onRetryMessage: (Int, Int, String) -> Unit = { _, _, _ -> },
    selectedAiModel: com.example.network.AiModelOption = com.example.network.AiModelOption.DEFAULT,
    onSwitchAiModel: (com.example.network.AiModelOption) -> Unit = {},
    aiError: AiUiError? = null,
    onDismissAiError: () -> Unit = {}
) {
    val bg = if (isDark) Color(0xFF0F0F0F) else Color(0xFFF9F9F9)
    val headBg = if (isDark) DarkBgSurface else LightBgSurface
    val border = if (isDark) DarkBorder else LightBorder
    val txtMain = if (isDark) DarkTxtMain else LightTxtMain
    val txtSec = if (isDark) DarkTxtSec else LightTxtSec

    val strings = LocalStrings.current
    val isRussian = strings == RussianStrings
    val context = androidx.compose.ui.platform.LocalContext.current
    val isSelfChat = chat.isSavedMessages
    val isAiChat = chat.isAiAssistant
    val isHexagonActive = isAiChat && selectedAiModel == com.example.network.AiModelOption.HEXAGON_BETA
    val canCallRecipient = canInitiateCall(chat)
    val displayName = if (isSelfChat) strings.savedMessages else chat.name
    val displayAva = if (isSelfChat) (if (isRussian) "ИЗ" else "SM") else if (isAiChat) (if (isHexagonActive) "HX" else "VX") else chat.ava
    val statusText = if (isTyping) {
        strings.typing
    } else if (isSelfChat) {
        strings.savedMessagesDesc
    } else if (isAiChat) {
        if (isHexagonActive) {
            strings.hexagonDesc
        } else {
            strings.ventaxisDesc
        }
    } else if (chat.status == "online") {
        strings.online
    } else {
        strings.offline
    }

    val topAvatarBrush = when {
        isHexagonActive -> Brush.linearGradient(listOf(Color(0xFF7C4DFF), Color(0xFF512DA8)))
        isAiChat -> Brush.linearGradient(listOf(SpotifyGreen, Color(0xFF00897B)))
        else -> Brush.linearGradient(listOf(SpotifyGreen, Color(0xFF0D5E29)))
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Scroll to bottom whenever messages or typing status changes
    val itemCount = if (isTyping) messages.size else messages.size - 1
    LaunchedEffect(messages.size, isTyping) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(if(itemCount > 0) itemCount else 0)
        }
    }

    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredMessages = (if (searchQuery.isBlank()) {
        messages
    } else {
        messages.filter { it.text.contains(searchQuery, ignoreCase = true) }
    }).filter { it.text.isNotBlank() || it.isAttachment || it.audioUrl != null }

    Column(
        modifier = modifier
            .background(bg)
            .fillMaxSize()
    ) {
        // Chat Header
        AnimatedContent(
            targetState = isSearching,
            transitionSpec = {
                (slideInVertically { -it } + fadeIn()).togetherWith(slideOutVertically { -it } + fadeOut())
            },
            label = "search_header"
        ) { searching ->
            if (searching) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(headBg)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { isSearching = false; searchQuery = "" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close Search", tint = txtMain)
                    }
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        placeholder = { Text(strings.searchMessages, color = txtSec) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = txtMain,
                            unfocusedTextColor = txtMain,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        )
                    )
                    Text(
                        text = strings.resultsCount.replace("%d", "${filteredMessages.size}"),
                        color = txtSec,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(headBg)
                        .drawBehind {
                            drawLine(
                                color = border,
                                start = Offset(0f, size.height),
                                end = Offset(size.width, size.height),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showBackButton) {
                        IconButton(
                            onClick = onBackClicked,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(36.dp)
                                .testTag("back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = txtMain
                            )
                        }
                    }

                    // Compact Avatar
                    Box(modifier = Modifier.size(38.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = topAvatarBrush,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = displayAva.uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
        if (chat.status == "online" && !isSelfChat) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(10.dp)
                    .background(bg, shape = CircleShape)
                    .padding(1.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SpotifyGreen, shape = CircleShape)
                )
            }
        }
    }

    Spacer(modifier = Modifier.width(10.dp))

    // User Info
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = displayName,
            color = txtMain,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val statusLabel = when {
            isTyping -> strings.typing
            isHexagonActive -> strings.hexagonDesc
            isAiChat -> strings.ventaxisDesc
            else -> statusText
        }
        Text(
            text = statusLabel,
            color = if (isTyping) SpotifyGreen else txtSec,
            fontSize = 12.sp,
            fontWeight = if (isTyping) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    // Quick action items icons
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = { isSearching = true }) {
            Icon(Icons.Default.Search, contentDescription = "Search", tint = txtSec, modifier = Modifier.size(20.dp))
        }
    }
        }
            }
        }

        // AI Model Switcher Bar for AI chats (Ventaxis AI & Hexagon AI)
        if (isAiChat) {
            Surface(
                color = if (isDark) Color(0xFF14171E) else Color(0xFFEEF1F6),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isVentaxisActive = !isHexagonActive

                    // Option 1: Ventaxis AI
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isVentaxisActive) SpotifyGreen else if (isDark) Color(0xFF1E222A) else Color(0xFFE2E7EE),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSwitchAiModel(com.example.network.AiModelOption.VENTAXIS) }
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp)
                        ) {
                            Text(
                                text = "Ventaxis AI",
                                color = if (isVentaxisActive) Color.Black else txtMain,
                                fontSize = 13.sp,
                                fontWeight = if (isVentaxisActive) FontWeight.Bold else FontWeight.Medium
                            )
                            Spacer(Modifier.width(5.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isVentaxisActive) Color.Black.copy(alpha = 0.16f) else SpotifyGreen.copy(alpha = 0.16f)
                            ) {
                                Text(
                                    text = com.example.network.AiModelOption.VENTAXIS.badge,
                                    color = if (isVentaxisActive) Color.Black else SpotifyGreen,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    // Option 2: Hexagon AI (Beta)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isHexagonActive) Color(0xFF6750A4) else if (isDark) Color(0xFF1E222A) else Color(0xFFE2E7EE),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSwitchAiModel(com.example.network.AiModelOption.HEXAGON_BETA) }
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp)
                        ) {
                            Text(
                                text = "Hexagon AI",
                                color = if (isHexagonActive) Color.White else txtMain,
                                fontSize = 13.sp,
                                fontWeight = if (isHexagonActive) FontWeight.Bold else FontWeight.Medium
                            )
                            Spacer(Modifier.width(5.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isHexagonActive) Color.White.copy(alpha = 0.25f) else Color(0xFF6750A4).copy(alpha = 0.25f)
                            ) {
                                Text(
                                    text = com.example.network.AiModelOption.HEXAGON_BETA.badge,
                                    color = if (isHexagonActive) Color.White else (if (isDark) Color(0xFFD0BCFF) else Color(0xFF6750A4)),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Messages Feed
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (filteredMessages.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AnimatedCrystalLogo(size = 150.dp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (searchQuery.isNotBlank()) strings.noMessagesFound.replace("%s", searchQuery) else strings.sayHiTo.replace("%s", chat.name),
                        color = txtSec,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("messages_scroll_container")
                ) {
                    val grouped = filteredMessages.groupBy { 
                    val cal = java.util.Calendar.getInstance()
                    val today = cal.get(java.util.Calendar.DAY_OF_YEAR)
                    cal.timeInMillis = it.timestamp
                    val msgDay = cal.get(java.util.Calendar.DAY_OF_YEAR)
                    when {
                        today == msgDay -> strings.today
                        today - msgDay == 1 -> strings.yesterday
                        else -> java.text.SimpleDateFormat("MMMM d", java.util.Locale.getDefault()).format(java.util.Date(it.timestamp))
                    }
                }
                
                grouped.forEach { (dateStr, msgs) ->
                    item {
                        var isVisible by remember { mutableStateOf(false) }
                        LaunchedEffect(dateStr) { isVisible = true }
                        
                        Column(modifier = Modifier.fillMaxWidth()) {
                            AnimatedVisibility(
                                visible = isVisible,
                                enter = fadeIn(tween(500)),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                color = txtSec.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(50)
                                            )
                                            .padding(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = dateStr,
                                            color = txtSec,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                    itemsIndexed(msgs, key = { _, msg -> msg.id }) { index, msg ->
                        val prevMsg = msgs.getOrNull(index - 1)
                        val isSameSenderAsPrev = prevMsg != null && prevMsg.sender == msg.sender && prevMsg.isMe == msg.isMe
                        MessageBubble(
                            message = msg,
                            isDark = isDark,
                            searchQuery = searchQuery,
                            isGroupedWithPrevious = isSameSenderAsPrev,
                            senderAva = chat.ava,
                            onDownloadVoice = onDownloadVoice,
                            onDownloadMedia = onDownloadMedia,
                            onRetry = { onRetryMessage(msg.id, msg.chatId, msg.text) }
                        )
                    }
                }
                
                if (isTyping) {
                    item {
                        TypingBubble(isDark = isDark, name = chat.name)
                    }
                }
            }
            }
        }

        // AI Error Banner
        if (isAiChat && aiError != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFB00020).copy(alpha = 0.15f),
                border = BorderStroke(1.dp, Color(0xFFB00020).copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "AI Error",
                            tint = Color(0xFFCF6679),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = aiError.getLocalizedMessage(strings),
                            color = if (isDark) Color(0xFFF2B8B5) else Color(0xFFB00020),
                            fontSize = 13.sp,
                            lineHeight = 16.sp
                        )
                    }
                    IconButton(
                        onClick = onDismissAiError,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Dismiss error",
                            tint = if (isDark) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // Bottom Input bar
        InputBarLayout(
            isSending = isSending,
            isDark = isDark,
            chatId = chat.id,
            onSend = onMessageSent,
            onAttachment = onAttachmentSent,
            onSendVoice = onSendVoiceMessage,
            onSendMedia = onSendMediaMessage
        )
    }
}

@Composable
fun MessageBubble(
    message: MessageEntity,
    isDark: Boolean,
    searchQuery: String = "",
    isGroupedWithPrevious: Boolean = false,
    senderAva: String = "",
    onDownloadVoice: suspend (String) -> ByteArray? = { null },
    onDownloadMedia: suspend (String) -> ByteArray? = { null },
    onRetry: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val strings = LocalStrings.current

    val bubbleColor = if (message.isMe) {
        if (isDark) DarkBubbleMe else LightBubbleMe
    } else {
        if (isDark) DarkBubbleOther else LightBubbleOther
    }
    
    val txtColor = if (message.isMe) {
        Color.White
    } else {
        if (isDark) Color.White else Color(0xFF191414)
    }
    
    val bubbleShape = if (message.isMe) {
        RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = 16.dp,
            bottomEnd = 3.dp
        )
    } else {
        RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = 3.dp,
            bottomEnd = 16.dp
        )
    }

    val bubbleAlignment = if (message.isMe) Alignment.CenterEnd else Alignment.CenterStart

    var scaleState by remember { mutableStateOf(0.9f) }
    LaunchedEffect(Unit) {
        scaleState = 1.0f
    }
    val animatedScale by animateFloatAsState(
        targetValue = scaleState,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "bubble_spring"
    )

    val topPadding = if (isGroupedWithPrevious) 2.dp else 10.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding)
            .graphicsLayer(scaleX = animatedScale, scaleY = animatedScale),
        contentAlignment = bubbleAlignment
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            if (!message.isMe) {
                if (!isGroupedWithPrevious) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(
                                color = SpotifyGreen,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = senderAva.ifBlank { message.sender.take(2).uppercase() },
                            color = Color.Black,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                } else {
                    Spacer(modifier = Modifier.width(34.dp))
                }
            }

            Column(
                modifier = Modifier.widthIn(max = 280.dp),
                horizontalAlignment = if (message.isMe) Alignment.End else Alignment.Start
            ) {
                Box {
                    Box(
                        modifier = Modifier
                            .shadow(1.dp, shape = bubbleShape)
                            .background(bubbleColor, shape = bubbleShape)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onLongPress = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        expanded = true
                                    }
                                )
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                    Column {
                        if (message.type == "voice" && message.audioUrl != null) {
                            VoiceMessagePayload(
                                audioUrl = message.audioUrl,
                                duration = message.duration ?: 0,
                                isDark = isDark,
                                txtColor = txtColor,
                                onDownloadVoice = onDownloadVoice
                            )
                        } else if ((message.type == "image" || message.type == "video") && message.audioUrl != null) {
                            MediaPayload(
                                type = message.type,
                                url = message.audioUrl,
                                isDark = isDark,
                                txtColor = txtColor,
                                onDownloadMedia = onDownloadMedia
                            )
                        } else {
                            if (message.isAttachment) {
                                AttachmentPayload(isDark = isDark)
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            if (message.text.isNotBlank() && (!message.isAttachment || message.text != "📎 Sent an attachment")) {
                                val rawText = message.text
                                val displayText = if (message.idempotencyKey.startsWith("init_saved_msg_")) {
                                    strings.welcomeSavedMessages
                                } else {
                                    rawText
                                }
                                if (searchQuery.isNotBlank() && displayText.contains(searchQuery, ignoreCase = true)) {
                                    val startIndex = displayText.indexOf(searchQuery, ignoreCase = true)
                                    val endIndex = startIndex + searchQuery.length
                                    val annotatedString = buildAnnotatedString {
                                        append(displayText.substring(0, startIndex))
                                        withStyle(style = SpanStyle(background = Color.Yellow, color = Color.Black)) {
                                            append(displayText.substring(startIndex, endIndex))
                                        }
                                        append(displayText.substring(endIndex))
                                    }
                                    Text(
                                        text = annotatedString,
                                        color = txtColor,
                                        fontSize = 14.sp,
                                        lineHeight = 19.sp
                                    )
                                } else {
                                    Text(
                                        text = displayText,
                                        color = txtColor,
                                        fontSize = 14.sp,
                                        lineHeight = 19.sp
                                    )
                                }
                            }
                        }
                    }
                }
                
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(if (isDark) Color(0xFF1E1E1E) else Color.White)
                ) {
                    DropdownMenuItem(text = { Text(strings.reply) }, onClick = { expanded = false }, leadingIcon = { Icon(Icons.Default.Reply, null) })
                    DropdownMenuItem(text = { Text(strings.copy) }, onClick = { expanded = false }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) })
                    DropdownMenuItem(text = { Text(strings.forward) }, onClick = { expanded = false }, leadingIcon = { Icon(Icons.Default.ArrowForward, null) })
                    if (message.isMe) {
                        DropdownMenuItem(text = { Text(strings.edit) }, onClick = { expanded = false }, leadingIcon = { Icon(Icons.Default.Edit, null) })
                        DropdownMenuItem(text = { Text(strings.delete, color = Color.Red) }, onClick = { expanded = false }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color.Red) })
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (message.isMe) {
                    when (message.status) {
                        "sending" -> {
                            val infiniteTransition = rememberInfiniteTransition(label = "sending_pulse")
                            val alpha by infiniteTransition.animateFloat(
                                initialValue = 0.3f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(800, easing = LinearEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "sending_alpha"
                            )
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = "Sending",
                                tint = (if (isDark) DarkTxtSec else LightTxtSec).copy(alpha = alpha),
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        "sent" -> {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Sent",
                                tint = if (isDark) DarkTxtSec else LightTxtSec,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        "delivered" -> {
                            Icon(
                                imageVector = Icons.Default.DoneAll,
                                contentDescription = "Delivered",
                                tint = if (isDark) DarkTxtSec else LightTxtSec,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        "read" -> {
                            Icon(
                                imageVector = Icons.Default.DoneAll,
                                contentDescription = "Read",
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        "error" -> {
                            Text(
                                "${strings.failedToSend} - ${strings.retry}",
                                color = Color.Red,
                                fontSize = 10.sp,
                                modifier = Modifier.clickable { onRetry() }
                            )
                        }
                    }
                    if (message.status != "error") {
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                }
                if (message.status != "error") {
                    Text(
                        text = message.time,
                        color = if (isDark) DarkTxtSec else LightTxtSec,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
        }
    }
}

@Composable
fun AttachmentPayload(isDark: Boolean) {
    // Elegant attachment banner frame
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isDark) Color(0xFF1E1E1E) else Color(0xFFF0F0F0))
            .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.InsertDriveFile,
                contentDescription = "Attachment document",
                tint = SpotifyGreen,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "HexShard_mockups.pdf",
                    color = if (isDark) Color.White else Color.Black,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "4.2 MB • PDF Document",
                    color = if (isDark) DarkTxtSec else LightTxtSec,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
fun InputBarLayout(
    isSending: Boolean,
    isDark: Boolean,
    chatId: Int,
    onSend: (String) -> Unit,
    onAttachment: () -> Unit,
    onSendVoice: (java.io.File, Int) -> Unit,
    onSendMedia: (android.net.Uri, String) -> Unit = { _, _ -> }
) {
    var text by remember { mutableStateOf("") }
    val strings = LocalStrings.current
    val txtMain = if (isDark) DarkTxtMain else LightTxtMain
    val txtSec = if (isDark) DarkTxtSec else LightTxtSec
    val border = if (isDark) DarkBorder else LightBorder
    val bg = if (isDark) DarkBgSurface else LightBgSurface

    val kbController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val voiceHelper = remember { VoiceMessageHelper(context) }
    var isRecording by remember { mutableStateOf(false) }
    var recordDuration by remember { mutableIntStateOf(0) }

    val mediaPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val mimeType = context.contentResolver.getType(uri)
            val type = if (mimeType?.startsWith("video") == true) "video" else "image"
            onSendMedia(uri, type)
        }
    }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            isRecording = true
            recordDuration = 0
            voiceHelper.startRecording()
        } else {
            android.widget.Toast.makeText(context, strings.micPermissionRequired, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (recordDuration < 120) {
                kotlinx.coroutines.delay(1000)
                recordDuration++
            }
            // Auto stop at 120 seconds
            isRecording = false
            val file = voiceHelper.stopRecording()
            if (file != null) {
                onSendVoice(file, recordDuration)
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = border,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            },
        color = bg
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Clip / Paperclip button
            IconButton(
                onClick = {
                    mediaPickerLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo
                        )
                    )
                },
                modifier = Modifier
                    .size(38.dp)
                    .background(
                        if (isDark) Color(0xFF1F1F1F) else Color(0xFFEDEDED),
                        shape = CircleShape
                    )
                    .testTag("attachment_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = "Attach file",
                    tint = if (isDark) DarkTxtSec else LightTxtSec,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Text pill-shape typing area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (isDark) Color(0xFF151515) else Color(0xFFF1F1F1))
                    .border(
                        width = 1.dp,
                        color = if (text.isNotBlank() || isRecording) SpotifyGreen.copy(alpha = 0.5f) else border,
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (isRecording) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val infiniteTransition = rememberInfiniteTransition(label = "recording_pulse")
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 0.2f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(600, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "recording_alpha"
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color.Red.copy(alpha = alpha), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${strings.recording} ${recordDuration / 60}:${(recordDuration % 60).toString().padStart(2, '0')}",
                            color = Color.Red,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = strings.slideToCancel,
                            color = txtSec,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    if (text.isEmpty()) {
                        Text(
                            text = strings.messagePlaceholder,
                            color = if (isDark) DarkTxtSec else LightTxtSec,
                            fontSize = 14.sp
                        )
                    }

                    BasicTextField(
                        value = text,
                        onValueChange = { if (it.length <= 500) text = it },
                        textStyle = TextStyle(
                            color = txtMain,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.SansSerif
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (text.isNotBlank() && !isSending) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSend(text)
                                    text = ""
                                }
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 100.dp)
                            .testTag("message_input_box")
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Send button or Microphone
            val isEnabled = text.isNotBlank() && !isSending
            if (text.isNotBlank()) {
                val animScale by animateFloatAsState(
                    targetValue = if (isEnabled) 1.0f else 0.85f,
                    label = "btn_scale"
                )

                IconButton(
                    onClick = {
                        if (isEnabled) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSend(text)
                            text = ""
                            kbController?.hide()
                            focusManager.clearFocus()
                        }
                    },
                    enabled = isEnabled,
                    modifier = Modifier
                        .size(38.dp)
                        .graphicsLayer(scaleX = animScale, scaleY = animScale)
                        .background(
                            if (isEnabled) SpotifyGreen else if (isDark) Color(0xFF242424) else Color(0xFFE0E0E0),
                            shape = CircleShape
                        )
                        .testTag("send_message_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send message",
                        tint = if (isEnabled) Color.White else if (isDark) Color(0xFF555555) else Color(0xFFAAAAAA),
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(if (isRecording) Color.Red else SpotifyGreen, CircleShape)
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                        isRecording = true
                                        recordDuration = 0
                                        voiceHelper.startRecording()
                                    } else {
                                        permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                onDrag = { change: PointerInputChange, dragAmount: Offset ->
                                    if (isRecording && change.position.x < -100f) {
                                        isRecording = false
                                        voiceHelper.cancelRecording()
                                    }
                                },
                                onDragEnd = {
                                    if (isRecording) {
                                        isRecording = false
                                        val file = voiceHelper.stopRecording()
                                        if (file != null) {
                                            onSendVoice(file, recordDuration)
                                        }
                                    }
                                },
                                onDragCancel = {
                                    if (isRecording) {
                                        isRecording = false
                                        voiceHelper.cancelRecording()
                                    }
                                }
                            )
                        }
                        .testTag("voice_message_btn"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Record Voice Message",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyStatePlaceholder(isDark: Boolean) {
    val strings = LocalStrings.current
    val txtMain = if (isDark) DarkTxtMain else LightTxtMain
    val txtSec = if (isDark) DarkTxtSec else LightTxtSec

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Display beautiful custom generated hero banner
        Image(
            painter = painterResource(id = R.drawable.img_chat_empty_hero_1782160410030),
            contentDescription = "HexShard logo banner background",
            modifier = Modifier
                .widthIn(max = 300.dp)
                .height(168.dp)
                .clip(RoundedCornerShape(16.dp))
                .shadow(4.dp, RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Crop
        )

        Spacer(modifier = Modifier.height(28.dp))

        HexagonLogo(size = 48.dp)

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = strings.welcomeHexShard,
            color = txtMain,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = strings.welcomeHexShardDesc,
            color = txtSec,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 340.dp),
            lineHeight = 20.sp
        )
    }
}

@Composable
fun NewChatDialog(
    isDark: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, initials: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var initials by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }
    val strings = LocalStrings.current

    val bg = if (isDark) DarkBgSurface else LightBg
    val txtMain = if (isDark) DarkTxtMain else LightTxtMain

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                strings.newChat,
                color = txtMain,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (it.isNotBlank() && initials.isBlank()) {
                            initials = it.split(" ").mapNotNull { word -> word.firstOrNull()?.toString() }.joinToString("").take(2).uppercase()
                        }
                    },
                    label = { Text(strings.recipientName) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SpotifyGreen,
                        focusedLabelColor = SpotifyGreen
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("dialog_name_input")
                )

                OutlinedTextField(
                    value = initials,
                    onValueChange = { if (it.length <= 2) initials = it },
                    label = { Text(strings.initialsLabel) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SpotifyGreen,
                        focusedLabelColor = SpotifyGreen
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("dialog_initials_input")
                )

                if (errorMsg.isNotBlank()) {
                    Text(
                        text = errorMsg,
                        color = DangerColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank() || initials.isBlank()) {
                        errorMsg = strings.initialsMandatory
                    } else {
                        onConfirm(name, initials)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
                modifier = Modifier.testTag("dialog_confirm_btn")
            ) {
                Text(strings.create, color = Color.White)
            }
        },
        dismissButton = {
            TextButton(
                onClick = dismiss@{ onDismiss() },
                colors = ButtonDefaults.textButtonColors(contentColor = if (isDark) DarkTxtSec else LightTxtSec),
                modifier = Modifier.testTag("dialog_dismiss_btn")
            ) {
                Text(strings.cancel)
            }
        },
        containerColor = bg
    )
}

@Composable
fun OnlinePulseDot(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scale"
    )
    val opacity by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "opacity"
    )

    Box(modifier = modifier.size(12.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .background(SpotifyGreen.copy(alpha = opacity), shape = CircleShape)
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(SpotifyGreen, shape = CircleShape)
                .border(2.dp, Color.Black, CircleShape)
        )
    }
}

@Composable
fun HexagonLogo(size: Dp = 24.dp) {
    Image(
        painter = painterResource(id = R.drawable.ic_launcher_dark),
        contentDescription = "HexShard Logo",
        modifier = Modifier.size(size),
        contentScale = ContentScale.Fit
    )
}

@Composable
fun JumpingTypingIndicator(color: Color, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "jumping")

    @Composable
    fun Dot(delayMillis: Int) {
        val translationY by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = -5f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 600
                    0f at 0 with FastOutSlowInEasing
                    -5f at 150 with FastOutSlowInEasing
                    0f at 300 with FastOutSlowInEasing
                    0f at 600
                },
                repeatMode = RepeatMode.Restart,
                initialStartOffset = StartOffset(delayMillis)
            ),
            label = "translationY"
        )
        Box(
            modifier = Modifier
                .padding(horizontal = 1.dp)
                .size(4.dp)
                .graphicsLayer(translationY = translationY)
                .background(color, CircleShape)
        )
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Dot(0)
        Dot(150)
        Dot(300)
    }
}

@Composable
fun TypingBubble(isDark: Boolean, name: String) {
    val bubbleColor = if (isDark) DarkBubbleOther else LightBubbleOther
    
    val bubbleShape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = 3.dp,
        bottomEnd = 16.dp
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column {
            Text(
                "$name is typing...",
                color = if (isDark) DarkTxtSec else LightTxtSec,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
            )
            Box(
                modifier = Modifier
                    .shadow(1.dp, shape = bubbleShape)
                    .background(bubbleColor, shape = bubbleShape)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                JumpingTypingIndicator(color = if (isDark) DarkTxtSec else LightTxtSec)
            }
        }
    }
}

@Composable
fun VoiceMessagePayload(
    audioUrl: String, 
    duration: Int, 
    isDark: Boolean, 
    txtColor: Color,
    onDownloadVoice: suspend (String) -> ByteArray? = { null }
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val playerHelper = remember { AudioPlayerHelper(context) }
    val isPlaying by playerHelper.isPlaying.collectAsStateWithLifecycle()
    val currentPosition by playerHelper.currentPosition.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    
    var isLoading by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            playerHelper.stopAudio()
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            playerHelper.updateProgress()
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(min = 160.dp).padding(8.dp)
    ) {
        if (isLoading) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(48.dp).padding(8.dp),
                color = txtColor,
                strokeWidth = 2.dp
            )
        } else {
            IconButton(
                onClick = {
                    if (isPlaying) {
                        playerHelper.pauseAudio()
                    } else {
                        isLoading = true
                        coroutineScope.launch {
                            val bytes = onDownloadVoice(audioUrl)
                            isLoading = false
                            if (bytes != null) {
                                playerHelper.playAudio(bytes) {
                                    // Complete
                                }
                            } else {
                                android.widget.Toast.makeText(context, "Failed to load audio", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = txtColor,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(verticalAlignment = Alignment.Bottom) {
            // Simple Waveform visualization
            Row(
                modifier = Modifier.weight(1f).height(32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val barsCount = 20
                val progressPercent = if (duration > 0) currentPosition / (duration * 1000f) else 0f
                
                for (i in 0 until barsCount) {
                    val barProgress = i.toFloat() / barsCount
                    val barColor = if (barProgress <= progressPercent) txtColor else txtColor.copy(alpha = 0.3f)
                    val heightPercent = 0.3f + 0.7f * Math.abs(Math.sin((i + 1) * 1.5))
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight(heightPercent.toFloat())
                            .background(barColor, CircleShape)
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Timer
            val displayTime = if (isPlaying) (currentPosition / 1000) else duration
            Text(
                text = "${displayTime / 60}:${(displayTime % 60).toString().padStart(2, '0')}",
                color = txtColor,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun MediaPayload(
    type: String,
    url: String,
    isDark: Boolean,
    txtColor: Color,
    onDownloadMedia: suspend (String) -> ByteArray? = { null }
) {
    var mediaFile by remember { mutableStateOf<java.io.File?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(url) {
        isLoading = true
        val bytes = onDownloadMedia(url)
        if (bytes != null) {
            val ext = if (type == "image") ".jpg" else ".mp4"
            val tempFile = java.io.File.createTempFile("decrypted_", ext, context.cacheDir)
            tempFile.writeBytes(bytes)
            mediaFile = tempFile
        }
        isLoading = false
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            androidx.compose.material3.CircularProgressIndicator(color = txtColor)
        } else if (mediaFile != null) {
            if (type == "image") {
                coil.compose.AsyncImage(
                    model = mediaFile,
                    contentDescription = "Image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else {
                // Video placeholder (Thumbnail with play button)
                coil.compose.AsyncImage(
                    model = coil.request.ImageRequest.Builder(context)
                        .data(mediaFile)
                        .decoderFactory(coil.decode.VideoFrameDecoder.Factory())
                        .build(),
                    contentDescription = "Video",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .clickable {
                            try {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.provider",
                                    mediaFile!!
                                )
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "video/*")
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                timber.log.Timber.e(e, "Failed to open video")
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play Video",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        } else {
            Icon(
                imageVector = Icons.Default.BrokenImage,
                contentDescription = "Error loading media",
                tint = txtColor
            )
        }
    }
}

/**
 * Central restriction check determining if calls are permissible for a given conversation.
 * Strictly blocks audio & video calls to:
 * 1. Saved Messages / Self
 * 2. HexShard AI / AI Assistant
 * 3. System announcements
 * 4. Empty / Invalid recipients
 */
fun canInitiateCall(chat: ChatEntity): Boolean {
    val type = chat.getConversationType()
    if (type == ConversationType.SAVED_MESSAGES ||
        type == ConversationType.AI_ASSISTANT ||
        type == ConversationType.SYSTEM
    ) {
        return false
    }
    return chat.recipientId.isNotBlank() && (type == ConversationType.DIRECT || type == ConversationType.GROUP)
}

