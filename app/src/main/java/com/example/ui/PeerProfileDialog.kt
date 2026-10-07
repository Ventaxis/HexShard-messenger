package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.rememberAsyncImagePainter
import com.example.data.repository.UserRepository
import com.example.ui.theme.*

@Composable
fun PeerProfileDialog(
    profile: UserRepository.PeerProfileInfo,
    isDark: Boolean,
    onDismiss: () -> Unit
) {
    val strings = LocalStrings.current
    val bg = if (isDark) DarkBgSurface else LightBgSurface
    val txtMain = if (isDark) DarkTxtMain else LightTxtMain
    val txtSec = if (isDark) DarkTxtSec else LightTxtSec
    val cardBg = if (isDark) DarkBgCard else LightBgCard

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = bg,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .padding(vertical = 24.dp)
                .testTag("peer_profile_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Header with background image or gradient
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                ) {
                    val bgPath = profile.backgroundPath
                    if (!bgPath.isNullOrBlank()) {
                        Image(
                            painter = rememberAsyncImagePainter(model = bgPath),
                            contentDescription = "Profile Background",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color(0xFF1E3A2B),
                                            Color(0xFF121212)
                                        )
                                    )
                                )
                        )
                    }

                    // Top dark scrim
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0x70000000),
                                        Color.Transparent,
                                        Color(0xCC121212)
                                    )
                                )
                            )
                    )

                    // Close button
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .testTag("close_peer_profile_btn")
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0x66000000),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Avatar overlapping bottom of hero
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 20.dp, bottom = 12.dp)
                    ) {
                        val avatarUrl = profile.avatarUrl
                        if (!avatarUrl.isNullOrBlank()) {
                            Image(
                                painter = rememberAsyncImagePainter(model = avatarUrl),
                                contentDescription = "User Avatar",
                                modifier = Modifier
                                    .size(76.dp)
                                    .clip(CircleShape)
                                    .testTag("peer_avatar_img"),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            val initials = profile.displayName.ifBlank { profile.username }.take(2).uppercase()
                            Box(
                                modifier = Modifier
                                    .size(76.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(SpotifyGreen, Color(0xFF00897B))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = initials,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 26.sp
                                )
                            }
                        }
                    }
                }

                // Profile Info Body
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Display Name & Username
                    Column {
                        Text(
                            text = profile.displayName.ifBlank { profile.username },
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = txtMain,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (profile.username.isNotBlank()) {
                            Text(
                                text = "@${profile.username}",
                                fontSize = 14.sp,
                                color = HexShardTealLight,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    HorizontalDivider(color = (if (isDark) DarkBorder else LightBorder).copy(alpha = 0.5f))

                    // Virtual Number (+999)
                    if (profile.virtualNumber.isNotBlank()) {
                        ProfileInfoRow(
                            icon = Icons.Default.Phone,
                            title = "HexShard ID (+999)",
                            value = profile.virtualNumber,
                            isMonospace = true,
                            valueColor = SpotifyGreen,
                            txtMain = txtMain,
                            txtSec = txtSec,
                            cardBg = cardBg
                        )
                    }

                    // About / Bio
                    if (profile.bio.isNotBlank()) {
                        ProfileInfoRow(
                            icon = Icons.Default.Info,
                            title = strings.bio,
                            value = profile.bio,
                            isMonospace = false,
                            valueColor = txtMain,
                            txtMain = txtMain,
                            txtSec = txtSec,
                            cardBg = cardBg
                        )
                    }

                    // Date of Birth
                    if (profile.dateOfBirth.isNotBlank()) {
                        ProfileInfoRow(
                            icon = Icons.Default.Cake,
                            title = strings.dateOfBirth,
                            value = profile.dateOfBirth,
                            isMonospace = false,
                            valueColor = txtMain,
                            txtMain = txtMain,
                            txtSec = txtSec,
                            cardBg = cardBg
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(strings.continueAction, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    isMonospace: Boolean,
    valueColor: Color,
    txtMain: Color,
    txtSec: Color,
    cardBg: Color
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = cardBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = SpotifyGreen,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 11.sp,
                    color = txtSec,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = value,
                    fontSize = 14.sp,
                    color = valueColor,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default
                )
            }
        }
    }
}
