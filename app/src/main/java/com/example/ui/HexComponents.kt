package com.example.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

/**
 * Responsive physical reaction modifier on touch/click:
 * Scales to 0.98f on press within 100ms, restores immediately on release.
 */
fun Modifier.hexPressEffect(
    pressedScale: Float = 0.975f,
    onClick: (() -> Unit)? = null
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = tween(durationMillis = 110),
        label = "hex_press_scale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = HexShardTeal.copy(alpha = 0.2f)),
                    onClick = onClick
                )
            } else {
                Modifier
            }
        )
}

/**
 * Reusable Layered Dark Surface Card with subtle borders.
 */
@Composable
fun HexCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    containerColor: Color = HexDarkSurface,
    borderColor: Color = HexDarkBorder,
    borderWidth: Dp = 1.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val cardModifier = if (onClick != null) {
        modifier.hexPressEffect(onClick = onClick)
    } else {
        modifier
    }

    Card(
        modifier = cardModifier,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(borderWidth, borderColor)
    ) {
        Column(content = content)
    }
}

/**
 * Primary Brand Button with smooth physical press reaction and loading state.
 */
@Composable
fun HexButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    leadingIcon: ImageVector? = null,
    containerColor: Color = HexShardTeal,
    contentColor: Color = Color.White
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.975f else 1f,
        animationSpec = tween(100),
        label = "btn_press"
    )

    Button(
        onClick = onClick,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(48.dp),
        enabled = enabled && !isLoading,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = 0.4f),
            disabledContentColor = contentColor.copy(alpha = 0.5f)
        ),
        interactionSource = interactionSource,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = contentColor,
                strokeWidth = 2.dp
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp
                )
            }
        }
    }
}

/**
 * Sleek Icon Button with minimum 48.dp touch target and subtle tactile press feedback.
 */
@Composable
fun HexIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = HexTextPrimary,
    containerColor: Color = HexDarkSurfaceHover,
    size: Dp = 38.dp,
    iconSize: Dp = 18.dp
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(size)
            .hexPressEffect()
            .background(containerColor, CircleShape)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * Category/Section Header with muted uppercase typography.
 */
@Composable
fun HexSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 12.dp)
                    .background(HexShardTeal, RoundedCornerShape(2.dp))
            )
            Text(
                text = title.uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = HexTextSecondary,
                letterSpacing = 1.1.sp
            )
        }
        trailingContent?.invoke()
    }
}

/**
 * Brand-aligned Empty State with geometric detail, concise message, and primary CTA.
 */
@Composable
fun HexEmptyState(
    title: String,
    subtitle: String,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Geometric Emblem
        Box(
            modifier = Modifier
                .size(76.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            HexShardTeal.copy(alpha = 0.16f),
                            Color.Transparent
                        )
                    ),
                    CircleShape
                )
                .background(HexDarkSurface, CircleShape)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .background(HexDarkSurfaceElevated, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = HexShardTeal,
                        modifier = Modifier.size(28.dp)
                    )
                } else {
                    HexagonLogo(size = 28.dp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = HexTextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = HexTextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )

        if (actionText != null && onActionClick != null) {
            Spacer(modifier = Modifier.height(20.dp))
            HexButton(
                text = actionText,
                onClick = onActionClick,
                modifier = Modifier.wrapContentWidth()
            )
        }
    }
}

/**
 * Sleek Avatar with custom gradient or image, initials, and optional online dot.
 */
@Composable
fun HexAvatar(
    initials: String,
    isOnline: Boolean = false,
    size: Dp = 46.dp,
    modifier: Modifier = Modifier,
    brush: Brush = Brush.linearGradient(listOf(HexShardTeal, HexShardTealDim)),
    onClick: (() -> Unit)? = null
) {
    val clickModifier = if (onClick != null) {
        modifier.hexPressEffect(onClick = onClick)
    } else {
        modifier
    }

    Box(modifier = clickModifier.size(size)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brush, CircleShape)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials.take(2).uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.36f).sp
            )
        }

        if (isOnline) {
            Box(
                modifier = Modifier
                    .size(size * 0.28f)
                    .align(Alignment.BottomEnd)
                    .background(HexDarkSurface, CircleShape)
                    .padding(1.5.dp)
                    .background(HexOnlineGreen, CircleShape)
            )
        }
    }
}

/**
 * Compact Pill Badge for unread count, AI label, or status.
 */
@Composable
fun HexBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = HexShardTeal,
    textColor: Color = Color.White
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = containerColor
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}
