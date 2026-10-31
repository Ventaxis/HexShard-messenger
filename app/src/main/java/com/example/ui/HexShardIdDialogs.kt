package com.example.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.SecurePrefsManager
import com.example.network.supabase.SessionManager
import com.example.network.supabase.SupabaseAuthService
import com.example.network.supabase.VirtualNumberConfirmationResult
import com.example.network.supabase.VirtualNumberReservationResult
import com.example.network.supabase.VirtualNumberService
import com.example.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Notice dialog displayed when the current authenticated account has no active +999 HexShard ID.
 * Follows server-authoritative check and non-intrusive action buttons.
 */
@Composable
fun HexShardIdMissingDialog(
    initialNumber: String = "",
    onContinue: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val strings = LocalStrings.current
    var inputNumber by remember {
        val digits = initialNumber.filter { it.isDigit() }.let {
            if (it.length == 11 && it.startsWith("999")) it.substring(3) else it.take(8)
        }
        mutableStateOf(digits.ifBlank { com.example.util.VirtualNumberGenerator.generateCandidate8Digits() })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("missing_hexshard_id_dialog"),
        shape = RoundedCornerShape(24.dp),
        containerColor = HexDarkSurfaceElevated,
        icon = {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(HexShardTealContainer)
                    .border(1.dp, HexShardTeal.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = HexShardTealLight,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        title = {
            Text(
                text = strings.noHexShardIdTitle,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = HexTextPrimary
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("missing_hexshard_id_title")
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = strings.noHexShardIdDesc,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = HexTextSecondary,
                        lineHeight = 20.sp
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 4.dp).testTag("missing_hexshard_id_desc")
                )
                Spacer(modifier = Modifier.height(14.dp))
                
                // Direct custom number input card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(HexDarkSurfaceInput)
                        .border(1.dp, HexShardTeal.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (strings == RussianStrings) "ВВЕДИТЕ ЖЕЛАЕМЫЙ НОМЕР (+999)" else "CHOOSE YOUR +999 NUMBER",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = HexShardTealLight,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(HexDarkBg)
                            .border(1.dp, HexDarkBorder, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "+999",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = HexShardTealLight,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        OutlinedTextField(
                            value = inputNumber,
                            onValueChange = { newVal ->
                                val digits = newVal.filter { it.isDigit() }
                                if (digits.length <= 8) {
                                    inputNumber = digits
                                }
                            },
                            placeholder = {
                                Text("XXXXXXXX", color = HexTextTertiary, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = HexTextPrimary,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        if (inputNumber.isNotEmpty()) {
                            IconButton(onClick = { inputNumber = "" }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = HexTextSecondary, modifier = Modifier.size(14.dp))
                            }
                        }
                        IconButton(
                            onClick = {
                                inputNumber = com.example.util.VirtualNumberGenerator.generateCandidate8Digits()
                            },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Random", tint = HexShardTealLight, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    val preview = com.example.util.VirtualNumberGenerator.format8Digits(inputNumber)
                    Text(
                        text = if (inputNumber.length == 8) preview else if (strings == RussianStrings) "Требуется ровно 8 цифр" else "8 digits required",
                        fontSize = 11.sp,
                        color = if (inputNumber.length == 8) HexShardTealLight else HexTextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onContinue(inputNumber) },
                enabled = inputNumber.length == 8,
                modifier = Modifier.testTag("missing_hexshard_id_continue_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HexShardTeal,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(strings.continueAction, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("missing_hexshard_id_later_button"),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = HexTextSecondary
                )
            ) {
                Text(strings.laterAction)
            }
        }
    )
}

/**
 * Interactive dialog allowing the user to enter/choose, reserve and confirm an active +999 HexShard ID.
 * Directly integrates with server RPC and authoritative user_metadata storage.
 */
@Composable
fun HexShardIdClaimDialog(
    initialPreferred: String = "",
    onDismiss: () -> Unit,
    onSuccess: (formattedNumber: String) -> Unit
) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()

    val currentUserId = remember { SecurePrefsManager.getUserId(context) }
    val currentToken = remember { SecurePrefsManager.getSupabaseAccessToken(context) }

    var userNumberInput by remember {
        val initialDigits = initialPreferred.filter { it.isDigit() }.let {
            if (it.length == 11 && it.startsWith("999")) it.substring(3) else it.take(8)
        }
        mutableStateOf(
            initialDigits.ifBlank {
                SecurePrefsManager.getRawPrivateVirtualNumber(context, currentUserId).ifBlank {
                    com.example.util.VirtualNumberGenerator.generateCandidate8Digits(currentUserId)
                }
            }
        )
    }

    var reservedFormatted by remember {
        mutableStateOf(com.example.util.VirtualNumberGenerator.format8Digits(userNumberInput))
    }
    var isConfirming by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isActivatedSuccess by remember { mutableStateOf(false) }

    fun confirmCandidate() {
        val targetNumber = userNumberInput.filter { it.isDigit() }
        if (targetNumber.length != 8) {
            errorMessage = if (strings == RussianStrings) "Номер должен содержать ровно 8 цифр" else "Number must be exactly 8 digits"
            return
        }
        isConfirming = true
        errorMessage = null
        scope.launch {
            val token = SecurePrefsManager.getSupabaseAccessToken(context).ifBlank { currentToken }
            when (val res = VirtualNumberService.confirmVirtualNumberDetailed(currentUserId, token, targetNumber, context)) {
                is VirtualNumberConfirmationResult.Success -> {
                    isConfirming = false
                    isActivatedSuccess = true
                    reservedFormatted = res.formatted
                    SessionManager.updateVirtualNumber(context, targetNumber)
                    SecurePrefsManager.setPrivateVirtualNumber(context, targetNumber, currentUserId)
                    Toast.makeText(context, strings.virtualNumberActivatedSuccess, Toast.LENGTH_SHORT).show()
                    onSuccess(res.formatted)
                }
                is VirtualNumberConfirmationResult.Error -> {
                    isConfirming = false
                    errorMessage = res.message
                }
            }
        }
    }

    Dialog(
        onDismissRequest = {
            if (!isConfirming) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .testTag("hexshard_id_claim_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = HexDarkSurfaceElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, HexDarkBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header with close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(HexShardTealContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = HexShardTealLight,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = strings.claimHexShardId,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = HexTextPrimary
                            )
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        enabled = !isConfirming,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = HexTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (isActivatedSuccess) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = HexShardTealLight,
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = strings.virtualNumberActivatedSuccess,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = HexTextPrimary
                            ),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = reservedFormatted,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = HexShardTealLight,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = HexShardTeal,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (strings == RussianStrings) "Готово" else "Done", fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    // Interactive Custom & Generated Number Card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(HexDarkSurfaceInput)
                            .border(1.dp, HexShardTeal.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                            .padding(vertical = 16.dp, horizontal = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "HEXSHARD ID (+999)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = HexShardTealLight,
                                letterSpacing = 1.2.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            // Editable Input Row with fixed +999 prefix
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(HexDarkBg)
                                    .border(1.dp, HexDarkBorder, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "+999",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = HexShardTealLight,
                                    fontFamily = FontFamily.Monospace
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                OutlinedTextField(
                                    value = userNumberInput,
                                    onValueChange = { newVal ->
                                        val digits = newVal.filter { it.isDigit() }
                                        if (digits.length <= 8) {
                                            userNumberInput = digits
                                        }
                                    },
                                    placeholder = {
                                        Text(
                                            "XXXXXXXX",
                                            color = HexTextTertiary,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 17.sp
                                        )
                                    },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done
                                    ),
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = HexTextPrimary,
                                        fontFamily = FontFamily.Monospace,
                                        letterSpacing = 1.2.sp
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent,
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("custom_hexshard_input")
                                )

                                if (userNumberInput.isNotEmpty()) {
                                    IconButton(
                                        onClick = { userNumberInput = "" },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Clear",
                                            tint = HexTextSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                }

                                IconButton(
                                    onClick = {
                                        userNumberInput = com.example.util.VirtualNumberGenerator.generateCandidate8Digits()
                                    },
                                    enabled = !isConfirming,
                                    modifier = Modifier.size(28.dp).testTag("randomize_hexshard_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = strings.reserveNewNumber,
                                        tint = HexShardTealLight,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            val previewFormatted = com.example.util.VirtualNumberGenerator.format8Digits(userNumberInput)
                            Text(
                                text = if (userNumberInput.length == 8) {
                                    previewFormatted
                                } else {
                                    val needed = 8 - userNumberInput.length
                                    if (strings == RussianStrings) "Введите ещё $needed цифр или нажмите ⟳" else "Enter $needed more digits or tap ⟳"
                                },
                                fontSize = 12.sp,
                                color = if (userNumberInput.length == 8) HexShardTealLight else HexTextSecondary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    errorMessage?.let { msg ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = msg,
                            color = HexDanger,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().testTag("claim_error_text")
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                userNumberInput = com.example.util.VirtualNumberGenerator.generateCandidate8Digits()
                                errorMessage = null
                            },
                            enabled = !isConfirming,
                            modifier = Modifier.weight(1f).testTag("reserve_another_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = HexTextPrimary
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, HexDarkBorder)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (strings == RussianStrings) "Случайный" else "Random",
                                fontSize = 12.sp
                            )
                        }

                        Button(
                            onClick = { confirmCandidate() },
                            enabled = !isConfirming && userNumberInput.length == 8,
                            modifier = Modifier.weight(1.3f).testTag("confirm_hexshard_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = HexShardTeal,
                                contentColor = Color.White
                            )
                        ) {
                            if (isConfirming) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(strings.confirmAndActivate, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
