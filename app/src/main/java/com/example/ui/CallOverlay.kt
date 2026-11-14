package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.calls.CallManager
import com.example.calls.CallState
import com.example.ui.theme.HexShardTeal
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val CallOverlayBg = com.example.ui.theme.HexDarkBg
private val CallButtonBg = com.example.ui.theme.HexDarkSurfaceHover
private val CallAcceptGreen = com.example.ui.theme.HexShardTeal
private val CallDeclineRed = com.example.ui.theme.HexDanger

@Composable
fun CallOverlay() {
    val callState by CallManager.state.collectAsStateWithLifecycle()
    val isMuted by CallManager.isMicMuted.collectAsStateWithLifecycle()
    val speakerOn by CallManager.isSpeakerOn.collectAsStateWithLifecycle()

    when (val s = callState) {
        is CallState.Idle -> Unit
        is CallState.Calling -> CallOverlayContent(
            peerName = s.peerName,
            statusText = callOverlayCallingStatus(),
            showAccept = false,
            isMuted = isMuted,
            speakerOn = speakerOn
        )
        is CallState.Ringing -> CallOverlayContent(
            peerName = s.peerName,
            statusText = if (isRussianLocale()) "Входящий звонок" else "Incoming call",
            showAccept = true,
            isMuted = isMuted,
            speakerOn = speakerOn
        )
        is CallState.Active -> CallOverlayContent(
            peerName = s.peerName,
            statusText = callOverlayActiveStatus(),
            showAccept = false,
            isMuted = isMuted,
            speakerOn = speakerOn
        )
        is CallState.Ended -> CallOverlayContent(
            peerName = "",
            statusText = localizedEndReason(s.reason, isRussian),
            showAccept = false,
            endedOnly = true,
            isMuted = false,
            speakerOn = true
        )
    }
}

@Composable
private fun CallOverlayContent(
    peerName: String,
    statusText: String,
    showAccept: Boolean,
    endedOnly: Boolean = false,
    isMuted: Boolean,
    speakerOn: Boolean
) {
    val isRussian = isRussianLocale()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CallOverlayBg)
            .testTag("call_overlay")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!endedOnly) {
                // Avatar with peer initials
                Box(
                    modifier = Modifier.size(112.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(HexShardTeal, shape = CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = callInitials(peerName),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 36.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = peerName,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    maxLines = 1
                )
            } else {
                Icon(
                    imageVector = Icons.Default.CallEnd,
                    contentDescription = null,
                    tint = CallDeclineRed,
                    modifier = Modifier.size(64.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = statusText,
                color = com.example.ui.theme.HexTextSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }

        // Control dock
        if (!endedOnly) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 48.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mute / unmute microphone
                CallControlButton(
                    icon = if (isMuted) Icons.Default.Mic else Icons.Default.MicOff,
                    contentDescription = if (isMuted) {
                        if (isRussian) "Включить микрофон" else "Unmute"
                    } else {
                        if (isRussian) "Выключить микрофон" else "Mute"
                    },
                    tint = if (isMuted) CallDeclineRed else Color.White,
                    modifier = Modifier.testTag("call_mute")
                ) { CallManager.toggleMic() }

                Spacer(modifier = Modifier.size(18.dp))

                if (showAccept) {
                    // Accept incoming call
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(CallAcceptGreen, shape = CircleShape)
                            .clickable { CallManager.acceptIncomingCall() }
                            .testTag("call_accept"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = if (isRussian) "Принять звонок" else "Accept",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    Spacer(modifier = Modifier.size(18.dp))
                }

                // Speaker toggle
                CallControlButton(
                    icon = if (speakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                    contentDescription = if (speakerOn) {
                        if (isRussian) "Отключить динамик" else "Speaker off"
                    } else {
                        if (isRussian) "Включить динамик" else "Speaker on"
                    },
                    tint = if (speakerOn) CallAcceptGreen else Color.White,
                    modifier = Modifier.testTag("call_speaker")
                ) { CallManager.toggleSpeaker() }

                Spacer(modifier = Modifier.size(18.dp))

                // Decline incoming / hang up active
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(CallDeclineRed, shape = CircleShape)
                        .clickable {
                            if (showAccept) CallManager.declineIncomingCall() else CallManager.hangUp()
                        }
                        .testTag("call_decline"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = if (isRussian) "Завершить звонок" else "End call",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CallControlButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .size(56.dp)
            .background(CallButtonBg, shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(26.dp)
        )
    }
}

@Composable
private fun callOverlayCallingStatus(): String {
    val isRussian = isRussianLocale()
    var dots by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(500)
            dots = (dots + 1) % 4
        }
    }
    val base = if (isRussian) "Вызов" else "Calling"
    return base + ".".repeat(dots)
}

@Composable
private fun callOverlayActiveStatus(): String {
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        elapsedSeconds = 0
        while (isActive) {
            delay(1000)
            elapsedSeconds++
        }
    }
    val minutes = elapsedSeconds / 60
    val seconds = elapsedSeconds % 60
    val label = if (isRussianLocale()) "Разговор" else "Call"
    return "$label %02d:%02d".format(minutes, seconds)
}

@Composable
private fun isRussianLocale(): Boolean = LocalStrings.current == RussianStrings

private fun callInitials(peerName: String): String {
    val words = peerName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size >= 2 -> (words[0].first().toString() + words[1].first().toString()).uppercase()
        else -> words[0].take(2).uppercase()
    }
}


/** Review standards: CallState.Ended carries stable keys; text resolves in the UI layer. */
private fun localizedEndReason(key: String, isRussian: Boolean): String = when (key) {
    "busy" -> if (isRussian) "Линия занята" else "Line busy"
    "audio_init_failed" -> if (isRussian) "Не удалось инициализировать аудио" else "Audio initialization failed"
    "mic_denied" -> if (isRussian) "Нет доступа к микрофону" else "Microphone access denied"
    "peer_unavailable" -> if (isRussian) "Абонент недоступен" else "Peer unavailable"
    "signaling_failed" -> if (isRussian) "Нет связи с сигналингом" else "Signaling unavailable"
    "connection_setup_failed" -> if (isRussian) "Не удалось установить соединение" else "Connection setup failed"
    "incoming_stale" -> if (isRussian) "Входящий звонок устарел" else "Incoming call expired"
    "declined" -> if (isRussian) "Звонок отклонён" else "Call declined"
    "call_cancelled" -> if (isRussian) "Звонок отменён" else "Call cancelled"
    "connection_lost" -> if (isRussian) "Соединение потеряно" else "Connection lost"
    "missed_call" -> if (isRussian) "Пропущенный звонок" else "Missed call"
    "call_ended" -> if (isRussian) "Звонок завершён" else "Call ended"
    else -> if (isRussian) "Звонок завершён" else "Call ended"
}
