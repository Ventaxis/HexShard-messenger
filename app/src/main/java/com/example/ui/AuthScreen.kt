package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.SecurePrefsManager
import com.example.network.supabase.ChallengeVerifyResult
import com.example.network.supabase.SignInResult
import com.example.network.supabase.SignUpResult
import com.example.network.supabase.SupabaseAuthService
import com.example.network.supabase.TelegramChallenge
import com.example.util.VirtualNumberGenerator
import kotlinx.coroutines.launch

@Composable
fun AnimatedCrystalLogo(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 140.dp) {
    val infiniteTransition = rememberInfiniteTransition(label = "emblem_anim")
    
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val entranceScale = remember { Animatable(0.2f) }
    LaunchedEffect(Unit) {
        entranceScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                stiffness = 500f,
                dampingRatio = 0.65f
            )
        )
    }

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = entranceScale.value * pulse
                scaleY = entranceScale.value * pulse
            },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_dark),
            contentDescription = "HexShard Logo",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    leadingIcon: @Composable (() -> Unit)? = null
) {
    val gradient = Brush.horizontalGradient(
        colors = listOf(Color(0xFF1DB954), Color(0xFF179443))
    )
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        enabled = enabled && !isLoading,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent
        ),
        contentPadding = PaddingValues()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (enabled) gradient else Brush.linearGradient(listOf(Color(0xFF232332), Color(0xFF232332))),
                    RoundedCornerShape(14.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (leadingIcon != null) {
                        leadingIcon()
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (enabled) Color.White else Color(0xFF7A7A8E),
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(onAuthComplete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val strings = LocalStrings.current
    val isRussian = LocalizationManager.currentLanguage.value == AppLanguage.RUSSIAN

    // Auth steps:
    // 1 = Form (Sign Up or Sign In tab)
    // 2 = Private Virtual +999 Number Selection / Generation
    // 3 = Account Ready & Telegram Verification (Optional)
    var currentStep by remember { mutableIntStateOf(1) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Sign Up, 1 = Sign In

    // Form inputs
    var usernameInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var passwordConfirmInput by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    // Step 2: Private +999 Virtual Number inputs
    var raw8DigitsInput by remember { mutableStateOf("") }

    // Step 3: Account ID and Telegram Challenge state
    var assignedAccountId by remember { mutableStateOf("") }
    var assignedUsername by remember { mutableStateOf("") }
    var confirmedVirtualNumber by remember { mutableStateOf("") }
    var activeChallenge by remember { mutableStateOf<TelegramChallenge?>(null) }
    var telegramCodeInput by remember { mutableStateOf("") }
    var telegramVerified by remember { mutableStateOf(false) }

    // General state
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Auto-detect existing saved user session
    LaunchedEffect(Unit) {
        val uid = SecurePrefsManager.getUserId(context)
        val uname = SecurePrefsManager.getUsername(context)
        if (uid.isNotBlank() && uname.isNotBlank() && uname != "user") {
            onAuthComplete()
        }
    }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color(0xFF1DB954),
        unfocusedBorderColor = Color(0xFF252538),
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedLabelColor = Color(0xFF1DB954),
        unfocusedLabelColor = Color(0xFF8B8B9E),
        cursorColor = Color(0xFF1DB954),
        focusedContainerColor = Color(0xFF12121C),
        unfocusedContainerColor = Color(0xFF0F0F18)
    )

    // Action: Step 1 Sign Up -> create account via Supabase
    fun performSignUp() {
        val cleanUsername = usernameInput.trim().removePrefix("@")
        val uError = SupabaseAuthService.validateUsername(cleanUsername)
        if (uError != null) {
            errorMessage = if (isRussian) strings.usernameTooShort else uError
            return
        }
        val pError = SupabaseAuthService.validatePassword(passwordInput)
        if (pError != null) {
            errorMessage = if (isRussian) "Пароль должен быть не менее 8 символов" else "Password must be at least 8 characters"
            return
        }
        if (passwordInput != passwordConfirmInput) {
            errorMessage = strings.passwordMismatch
            return
        }

        isLoading = true
        errorMessage = null

        scope.launch {
            when (val result = SupabaseAuthService.signUp(cleanUsername, passwordInput, context)) {
                is SignUpResult.Success -> {
                    assignedAccountId = result.accountId
                    assignedUsername = result.username
                    activeChallenge = SupabaseAuthService.createTelegramChallenge(result.accountId, context)
                    isLoading = false
                    // Proceed to Step 2: Secure your account (Telegram Verification)
                    currentStep = 2
                }
                is SignUpResult.Error -> {
                    isLoading = false
                    errorMessage = if (result.isUsernameTaken) {
                        strings.usernameAlreadyTaken
                    } else {
                        result.message
                    }
                }
            }
        }
    }

    fun requestCandidateNumber(preferred: String? = null) {
        isLoading = true
        errorMessage = null
        scope.launch {
            when (val res = SupabaseAuthService.reserveVirtualNumber(assignedAccountId, context, preferred)) {
                is com.example.network.supabase.VirtualNumberReservationResult.Success -> {
                    raw8DigitsInput = res.raw8Digits
                    isLoading = false
                }
                is com.example.network.supabase.VirtualNumberReservationResult.Error -> {
                    isLoading = false
                    errorMessage = res.message
                }
            }
        }
    }

    // Automatically reserve or generate candidate virtual number upon entering step 3
    LaunchedEffect(currentStep) {
        if (currentStep == 3 && raw8DigitsInput.isBlank()) {
            requestCandidateNumber()
        }
    }

    // Action: Step 3 -> Confirm private virtual number & advance to Step 4 (Account Ready)
    fun confirmPrivateNumber() {
        val cleanDigits = raw8DigitsInput.filter { it.isDigit() }
        if (cleanDigits.length != 8) {
            errorMessage = if (isRussian) "Номер должен состоять ровно из 8 цифр" else "Number must be exactly 8 digits"
            return
        }

        isLoading = true
        errorMessage = null

        scope.launch {
            val token = com.example.data.SecurePrefsManager.getSupabaseAccessToken(context)
            when (val confResult = com.example.network.supabase.VirtualNumberService.confirmVirtualNumberDetailed(assignedAccountId, token, cleanDigits, context)) {
                is com.example.network.supabase.VirtualNumberConfirmationResult.Success -> {
                    confirmedVirtualNumber = confResult.formatted
                    isLoading = false
                    currentStep = 4
                }
                is com.example.network.supabase.VirtualNumberConfirmationResult.Error -> {
                    isLoading = false
                    errorMessage = confResult.message
                }
            }
        }
    }

    // Action: Step 3 -> Skip private virtual number
    fun skipPrivateNumber() {
        confirmedVirtualNumber = ""
        errorMessage = null
        currentStep = 4
    }

    // Action: Step 2 -> Verify Telegram challenge code
    fun verifyTelegramChallengeCode() {
        val challengeToken = activeChallenge?.challengeId ?: assignedAccountId
        if (challengeToken.isBlank()) {
            errorMessage = if (isRussian) "Сессия верификации недоступна" else "Challenge session unavailable"
            return
        }
        val cleanCode = telegramCodeInput.trim().filter { it.isDigit() }
        if (cleanCode.length != 6) {
            errorMessage = if (isRussian) "Введите 6-значный код" else "Please enter a 6-digit code"
            return
        }

        isLoading = true
        errorMessage = null

        scope.launch {
            when (val result = SupabaseAuthService.verifyTelegramChallenge(challengeToken, cleanCode, context)) {
                is ChallengeVerifyResult.Success -> {
                    telegramVerified = true
                    isLoading = false
                    Toast.makeText(
                        context,
                        if (isRussian) "Telegram успешно привязан!" else "Telegram successfully linked!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is ChallengeVerifyResult.Error -> {
                    isLoading = false
                    errorMessage = if (isRussian) {
                        if (result.message.contains("expired", ignoreCase = true)) strings.codeExpired else strings.invalidCode
                    } else {
                        result.message
                    }
                }
            }
        }
    }

    // Action: Execute Sign In
    fun performSignIn() {
        val cleanUsername = usernameInput.trim().removePrefix("@")
        if (cleanUsername.isBlank() || passwordInput.isBlank()) {
            errorMessage = strings.invalidCredentials
            return
        }

        isLoading = true
        errorMessage = null

        scope.launch {
            when (val result = SupabaseAuthService.signIn(cleanUsername, passwordInput, context)) {
                is SignInResult.Success -> {
                    isLoading = false
                    onAuthComplete()
                }
                is SignInResult.Error -> {
                    isLoading = false
                    errorMessage = strings.invalidCredentials
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF08080C)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(vertical = 20.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Animated HexShard Logo
            AnimatedCrystalLogo(size = 130.dp)

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "HexShard",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 2.sp
            )
            Text(
                text = strings.authSubtitle,
                fontSize = 13.sp,
                color = Color(0xFF8B8B9E),
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (currentStep > 1) {
                Row(
                    modifier = Modifier.padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in 1..4) {
                        Box(
                            modifier = Modifier
                                .width(if (i == currentStep) 28.dp else 16.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    when {
                                        i == currentStep -> Color(0xFF1DB954)
                                        i < currentStep -> Color(0xFF1B4D2E)
                                        else -> Color(0xFF252538)
                                    }
                                )
                        )
                    }
                }
            }

            // Step Content Transitions
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInHorizontally { it } + fadeIn()).togetherWith(slideOutHorizontally { -it } + fadeOut())
                    } else {
                        (slideInHorizontally { -it } + fadeIn()).togetherWith(slideOutHorizontally { it } + fadeOut())
                    }.using(SizeTransform(clip = false))
                },
                label = "auth_steps"
            ) { step ->
                when (step) {
                    1 -> {
                        // STEP 1: Registration or Sign In Tab
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Segmented Tab Pill
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .background(Color(0xFF14141E), RoundedCornerShape(14.dp))
                                    .border(1.dp, Color(0xFF252538), RoundedCornerShape(14.dp))
                                    .padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (selectedTab == 0) Color(0xFF1DB954) else Color.Transparent)
                                        .clickable {
                                            selectedTab = 0
                                            errorMessage = null
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = strings.signUpTab,
                                        fontSize = 14.sp,
                                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selectedTab == 0) Color.White else Color(0xFF8B8B9E)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (selectedTab == 1) Color(0xFF1DB954) else Color.Transparent)
                                        .clickable {
                                            selectedTab = 1
                                            errorMessage = null
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = strings.signInTab,
                                        fontSize = 14.sp,
                                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selectedTab == 1) Color.White else Color(0xFF8B8B9E)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(22.dp))

                            // Username input
                            OutlinedTextField(
                                value = usernameInput,
                                onValueChange = { if (it.length <= 30) usernameInput = it },
                                label = { Text(strings.chooseUsername) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Person,
                                        contentDescription = null,
                                        tint = Color(0xFF8B8B9E),
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                placeholder = { Text("username", color = Color(0xFF555566)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Ascii,
                                    imeAction = ImeAction.Next
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = textFieldColors
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            // Password input
                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text(strings.passwordLabel) },
                                placeholder = { Text(strings.passwordPlaceholder, color = Color(0xFF555566)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = Color(0xFF8B8B9E),
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                trailingIcon = {
                                    IconButton(onClick = { showPassword = !showPassword }) {
                                        Icon(
                                            imageVector = if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = null,
                                            tint = Color(0xFF8B8B9E)
                                        )
                                    }
                                },
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = if (selectedTab == 0) ImeAction.Next else ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { if (selectedTab == 1) performSignIn() }
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = textFieldColors
                            )

                            // Confirm Password (Sign Up only)
                            if (selectedTab == 0) {
                                Spacer(modifier = Modifier.height(14.dp))
                                OutlinedTextField(
                                    value = passwordConfirmInput,
                                    onValueChange = { passwordConfirmInput = it },
                                    label = { Text(strings.passwordConfirmLabel) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Lock,
                                            contentDescription = null,
                                            tint = Color(0xFF8B8B9E),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = { performSignUp() }
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = textFieldColors
                                )
                            }

                            // Error text
                            if (errorMessage != null) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = errorMessage!!,
                                    color = Color(0xFFFF5252),
                                    fontSize = 13.sp,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            // Action button
                            GradientButton(
                                text = if (selectedTab == 0) strings.signUpTab else strings.signInTab,
                                onClick = {
                                    if (selectedTab == 0) performSignUp() else performSignIn()
                                },
                                enabled = usernameInput.isNotBlank() && passwordInput.isNotBlank(),
                                isLoading = isLoading
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            // Privacy badge footer
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF101018), RoundedCornerShape(12.dp))
                                    .border(1.dp, Color(0xFF1F1F2C), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = Color(0xFF1DB954),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = strings.guestNotice,
                                    color = Color(0xFF8B8B9E),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }

                    2 -> {
                        // STEP 2: Secure your account (Telegram Account Binding)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF101C2B))
                                    .border(1.dp, Color(0xFF2990D6), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Send,
                                    contentDescription = null,
                                    tint = Color(0xFF2990D6),
                                    modifier = Modifier.size(26.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = strings.telegramBindingTitle,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = strings.telegramBindingDesc,
                                fontSize = 13.sp,
                                color = Color(0xFF8B8B9E),
                                lineHeight = 18.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(18.dp))

                            // 1. Account ID Display & Copy Card
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1522)),
                                border = BorderStroke(1.dp, Color(0xFF2990D6).copy(alpha = 0.5f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = strings.accountIdLabel.uppercase(),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF2990D6),
                                            letterSpacing = 1.sp
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color(0xFF1B2838)
                                        ) {
                                            Text(
                                                text = if (isRussian) "УНИКАЛЬНЫЙ ID" else "UNIQUE ID",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF64B5F6),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF080C14), RoundedCornerShape(8.dp))
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = assignedAccountId.ifBlank { "account-id-pending" },
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White,
                                            modifier = Modifier.weight(1f)
                                        )

                                        IconButton(
                                            onClick = {
                                                try {
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    val clip = ClipData.newPlainText("HexShard ID", assignedAccountId)
                                                    clipboard.setPrimaryClip(clip)
                                                    Toast.makeText(context, strings.accountIdCopied, Toast.LENGTH_SHORT).show()
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, assignedAccountId, Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = strings.copyAccountId,
                                                tint = Color(0xFF2990D6),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // 2. Telegram Verification Card
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF101520)),
                                border = BorderStroke(1.dp, if (telegramVerified) Color(0xFF1DB954) else Color(0xFF222232))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(CircleShape)
                                                .background(if (telegramVerified) Color(0xFF1B382B) else Color(0xFF1A2A38)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (telegramVerified) Icons.Default.Check else Icons.Default.Send,
                                                contentDescription = null,
                                                tint = if (telegramVerified) Color(0xFF1DB954) else Color(0xFF2990D6),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (telegramVerified) strings.telegramConnected else "@HexShardBot",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp,
                                                color = if (telegramVerified) Color(0xFF1DB954) else Color.White
                                            )
                                            Text(
                                                text = if (telegramVerified) {
                                                    if (isRussian) "Аккаунт успешно подтвержден" else "Account successfully verified"
                                                } else {
                                                    if (isRussian) "Отправьте ID боту и введите полученный код" else "Send ID to bot and enter received code"
                                                },
                                                fontSize = 12.sp,
                                                color = Color(0xFF8B8B9E)
                                            )
                                        }
                                    }

                                    if (!telegramVerified) {
                                        Spacer(modifier = Modifier.height(14.dp))

                                        // Deep link to @HexShardBot with assigned account ID
                                        OutlinedButton(
                                            onClick = {
                                                try {
                                                    val url = activeChallenge?.deepLink ?: "https://t.me/HexShardBot?start=$assignedAccountId"
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                    context.startActivity(intent)
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "t.me/HexShardBot", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(46.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, Color(0xFF2990D6)),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF2990D6))
                                        ) {
                                            Icon(
                                                Icons.Default.Send,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = strings.telegramBotButton,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // 6-digit code input + verify button
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedTextField(
                                                value = telegramCodeInput,
                                                onValueChange = {
                                                    val digits = it.filter { ch -> ch.isDigit() }
                                                    if (digits.length <= 6) telegramCodeInput = digits
                                                },
                                                placeholder = { Text("482913", color = Color(0xFF555566), fontSize = 14.sp, fontFamily = FontFamily.Monospace) },
                                                singleLine = true,
                                                keyboardOptions = KeyboardOptions(
                                                    keyboardType = KeyboardType.Number,
                                                    imeAction = ImeAction.Done
                                                ),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(50.dp),
                                                shape = RoundedCornerShape(10.dp),
                                                colors = textFieldColors
                                            )

                                            Spacer(modifier = Modifier.width(8.dp))

                                            Button(
                                                onClick = { verifyTelegramChallengeCode() },
                                                enabled = telegramCodeInput.length == 6 && !isLoading,
                                                modifier = Modifier.height(50.dp),
                                                shape = RoundedCornerShape(10.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2990D6))
                                            ) {
                                                Text(strings.verifyCode, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }

                            if (errorMessage != null) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = errorMessage!!,
                                    color = Color(0xFFFF5252),
                                    fontSize = 13.sp,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            GradientButton(
                                text = if (isRussian) "Продолжить" else "Continue",
                                onClick = {
                                    errorMessage = null
                                    currentStep = 3
                                }
                            )

                            if (!telegramVerified) {
                                Spacer(modifier = Modifier.height(8.dp))

                                TextButton(
                                    onClick = {
                                        errorMessage = null
                                        currentStep = 3
                                    }
                                ) {
                                    Text(
                                        text = strings.skipTelegramForNow,
                                        color = Color(0xFF8B8B9E),
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }

                    3 -> {
                        // STEP 3: Your Private Identity (Choose +999 Virtual Number)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = strings.privateIdentityTitle,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = strings.privateIdentityDesc,
                                fontSize = 13.sp,
                                color = Color(0xFF8B8B9E),
                                lineHeight = 18.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(22.dp))

                            // Private Number Customization Card
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF13131D)),
                                border = BorderStroke(1.dp, Color(0xFF252538))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(18.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = strings.virtualNumberLabel.uppercase(),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF1DB954),
                                        letterSpacing = 1.2.sp
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Field with fixed +999 prefix and 8 editable digits + Generate button
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF0A0A10), RoundedCornerShape(12.dp))
                                            .border(1.dp, Color(0xFF222232), RoundedCornerShape(12.dp))
                                            .padding(horizontal = 14.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "+999",
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF1DB954),
                                            fontFamily = FontFamily.Monospace
                                        )

                                        Spacer(modifier = Modifier.width(10.dp))

                                        OutlinedTextField(
                                            value = raw8DigitsInput,
                                            onValueChange = { newVal ->
                                                val digitsOnly = newVal.filter { it.isDigit() }
                                                if (digitsOnly.length <= 8) {
                                                    raw8DigitsInput = digitsOnly
                                                }
                                            },
                                            singleLine = true,
                                            placeholder = { Text("XXXXXXXX", color = Color(0xFF444455)) },
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Number,
                                                imeAction = ImeAction.Done
                                            ),
                                            textStyle = androidx.compose.ui.text.TextStyle(
                                                fontSize = 19.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                fontFamily = FontFamily.Monospace,
                                                letterSpacing = 1.5.sp
                                            ),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = Color.Transparent,
                                                unfocusedBorderColor = Color.Transparent,
                                                focusedContainerColor = Color.Transparent,
                                                unfocusedContainerColor = Color.Transparent
                                            ),
                                            modifier = Modifier.weight(1f)
                                        )

                                        // Refresh / Generate button
                                        IconButton(
                                            onClick = {
                                                requestCandidateNumber()
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = strings.generateNumber,
                                                tint = Color(0xFF1DB954),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    // Formatted preview
                                    val formattedPreview = VirtualNumberGenerator.format8Digits(raw8DigitsInput)
                                    Text(
                                        text = formattedPreview,
                                        fontSize = 14.sp,
                                        color = Color(0xFF8B8B9E),
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Explanatory warning note (Rule: +999 is internal only)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF1B1B26), RoundedCornerShape(12.dp))
                                    .border(1.dp, Color(0xFF2E2E40), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = Color(0xFFFFB74D),
                                    modifier = Modifier
                                        .size(18.dp)
                                        .padding(top = 1.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = strings.virtualNumberWarning,
                                    color = Color(0xFFB0B0C2),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                            }

                            if (errorMessage != null) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = errorMessage!!,
                                    color = Color(0xFFFF5252),
                                    fontSize = 13.sp,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            GradientButton(
                                text = strings.useThisNumber,
                                onClick = { confirmPrivateNumber() },
                                enabled = raw8DigitsInput.filter { it.isDigit() }.length == 8,
                                isLoading = isLoading
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            TextButton(
                                onClick = { skipPrivateNumber() }
                            ) {
                                Text(
                                    text = strings.skipPrivateNumber,
                                    color = Color(0xFF8B8B9E),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    4 -> {
                        // STEP 4: Account Ready
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1B382B))
                                    .border(1.5.dp, Color(0xFF1DB954), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color(0xFF1DB954),
                                    modifier = Modifier.size(34.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = strings.accountReadyTitle,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = strings.accountReadyDesc,
                                fontSize = 13.sp,
                                color = Color(0xFF8B8B9E),
                                lineHeight = 18.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(22.dp))

                            // Identity Overview Card (Rule 4 & 44: Account ID is private and NOT shown here)
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF14141E)),
                                border = BorderStroke(1.dp, Color(0xFF252538))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(18.dp)
                                ) {
                                    // Username
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(strings.username, fontSize = 13.sp, color = Color(0xFF8B8B9E))
                                        Text(
                                            text = "@${assignedUsername.ifBlank { usernameInput.trim().removePrefix("@") }}",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }

                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 12.dp),
                                        color = Color(0xFF222232)
                                    )

                                    // Private Virtual Number (Optional)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(strings.privateNumberLabel, fontSize = 13.sp, color = Color(0xFF8B8B9E))
                                        Text(
                                            text = if (confirmedVirtualNumber.isNotBlank()) confirmedVirtualNumber else strings.notAssigned,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = if (confirmedVirtualNumber.isNotBlank()) FontFamily.Monospace else FontFamily.Default,
                                            color = if (confirmedVirtualNumber.isNotBlank()) Color(0xFF1DB954) else Color(0xFF8B8B9E)
                                        )
                                    }

                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 12.dp),
                                        color = Color(0xFF222232)
                                    )

                                    // Security Status
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (isRussian) "Безопасность" else "Security",
                                            fontSize = 13.sp,
                                            color = Color(0xFF8B8B9E)
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (telegramVerified) Color(0xFF1B382B) else Color(0xFF222232)
                                        ) {
                                            Text(
                                                text = if (telegramVerified) strings.telegramConnected else strings.telegramNotConnected,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (telegramVerified) Color(0xFF1DB954) else Color(0xFF8B8B9E),
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(28.dp))

                            // Enter HexShard button
                            GradientButton(
                                text = strings.enterHexShard,
                                onClick = { onAuthComplete() }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
