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
    onContinue: () -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalStrings.current

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
                // Visual identity pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(HexDarkSurfaceInput)
                        .border(1.dp, HexDarkBorderSubtle, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = HexShardTeal,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "+999 • HexShard ID",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = HexTextPrimary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onContinue,
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
 * Interactive dialog allowing the user to reserve and confirm an active +999 HexShard ID.
 * Directly integrates with server RPC [reserve_hex_number] and [confirm_hex_number].
 */
@Composable
fun HexShardIdClaimDialog(
    onDismiss: () -> Unit,
    onSuccess: (formattedNumber: String) -> Unit
) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()

    val currentUserId = remember { SecurePrefsManager.getUserId(context) }
    val currentToken = remember { SecurePrefsManager.getSupabaseAccessToken(context) }

    var isLoading by remember { mutableStateOf(true) }
    var isConfirming by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var reservedRawNumber by remember { mutableStateOf("") }
    var reservedFormatted by remember { mutableStateOf("") }
    var isActivatedSuccess by remember { mutableStateOf(false) }

    fun reserveCandidate(preferred: String? = null) {
        isLoading = true
        errorMessage = null
        scope.launch {
            when (val res = SupabaseAuthService.reserveVirtualNumber(currentUserId, context, preferred)) {
                is VirtualNumberReservationResult.Success -> {
                    reservedRawNumber = res.raw8Digits
                    reservedFormatted = res.formatted
                    isLoading = false
                }
                is VirtualNumberReservationResult.Error -> {
                    isLoading = false
                    errorMessage = res.message
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        reserveCandidate()
    }

    fun confirmCandidate() {
        if (reservedRawNumber.length != 8) {
            errorMessage = "Invalid number format"
            return
        }
        isConfirming = true
        errorMessage = null
        scope.launch {
            when (val res = VirtualNumberService.confirmVirtualNumberDetailed(currentUserId, currentToken, reservedRawNumber, context)) {
                is VirtualNumberConfirmationResult.Success -> {
                    isConfirming = false
                    isActivatedSuccess = true
                    SessionManager.updateVirtualNumber(context, reservedRawNumber)
                    SecurePrefsManager.setPrivateVirtualNumber(context, reservedRawNumber, currentUserId)
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

                if (isLoading) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = HexShardTeal,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = if (strings == RussianStrings) "Подбор уникального номера..." else "Selecting unique number...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = HexTextSecondary
                        )
                    }
                } else if (isActivatedSuccess) {
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
                    }
                } else {
                    // Candidate Number Display Card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(HexDarkSurfaceInput)
                            .border(1.dp, HexShardTeal.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                            .padding(vertical = 20.dp, horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "HexShard ID (+999)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = HexTextSecondary,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = reservedFormatted.ifBlank { "+999 — — — —" },
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = HexTextPrimary,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.testTag("reserved_hexshard_number_text")
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (strings == RussianStrings) "Зарезервировано на сервере" else "Reserved on server",
                                fontSize = 11.sp,
                                color = HexShardTealLight
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
                            onClick = { reserveCandidate() },
                            enabled = !isConfirming && !isLoading,
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
                            Text(strings.reserveNewNumber, fontSize = 12.sp)
                        }

                        Button(
                            onClick = { confirmCandidate() },
                            enabled = !isConfirming && !isLoading && reservedRawNumber.isNotBlank(),
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
