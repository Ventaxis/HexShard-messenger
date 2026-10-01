package com.example.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.*
import com.example.util.DateOfBirthFormatter
import java.util.Calendar

/**
 * Modern 3-drum / wheel date picker ("катушка") for selecting Date of Birth:
 * - Section 1: Day (1..31)
 * - Section 2: Month in words (localized in RU / EN)
 * - Section 3: Year (1920..current year)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WheelColumn(
    items: List<String>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    visibleItemsCount: Int = 5,
    itemHeight: Int = 42
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    )
    val snapBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    // Detect center item on scroll
    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        val centerIndex = listState.firstVisibleItemIndex + if (listState.firstVisibleItemScrollOffset > (itemHeight * 1.5)) 1 else 0
        val clamped = centerIndex.coerceIn(0, items.size - 1)
        if (clamped != selectedIndex && clamped in items.indices) {
            onItemSelected(clamped)
        }
    }

    // Scroll to position when selectedIndex changes externally
    LaunchedEffect(selectedIndex) {
        if (selectedIndex in items.indices && listState.firstVisibleItemIndex != selectedIndex) {
            listState.animateScrollToItem(selectedIndex)
        }
    }

    Box(
        modifier = modifier
            .height((itemHeight * visibleItemsCount).dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        // Center selection highlight band
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .height(itemHeight.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(HexShardTealContainer.copy(alpha = 0.65f))
                .border(1.dp, HexShardTeal.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
        )

        LazyColumn(
            state = listState,
            flingBehavior = snapBehavior,
            contentPadding = PaddingValues(vertical = (itemHeight * (visibleItemsCount / 2)).dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items.size) { index ->
                val isSelected = index == selectedIndex
                val textAlpha by animateFloatAsState(
                    targetValue = if (isSelected) 1f else 0.45f,
                    label = "wheelAlpha"
                )
                val fontSize = if (isSelected) 17.sp else 14.sp
                val fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                val textColor = if (isSelected) HexShardTealLight else HexTextSecondary

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight.dp)
                        .clickable { onItemSelected(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = items[index],
                        fontSize = fontSize,
                        fontWeight = fontWeight,
                        color = textColor,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .alpha(textAlpha)
                            .padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun BirthDateWheelPickerDialog(
    initialDateStr: String,
    isRussian: Boolean,
    onDismissRequest: () -> Unit,
    onDateSelected: (formattedDate: String) -> Unit
) {
    val strings = LocalStrings.current
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)

    // Parse initial date or default to 18-25 years ago
    val parsed = DateOfBirthFormatter.parseParts(initialDateStr)
    var selectedYear by remember { mutableStateOf(parsed?.third ?: (currentYear - 22)) }
    var selectedMonth by remember { mutableStateOf(parsed?.second ?: 4) } // 1-based: April default
    var selectedDay by remember { mutableStateOf(parsed?.first ?: 13) }

    // Year items: 1920..currentYear
    val years = remember(currentYear) { (1920..currentYear).map { it.toString() } }
    val yearIndex = (selectedYear - 1920).coerceIn(0, years.size - 1)

    // Month items in text words (1..12)
    val months = remember(isRussian) {
        (1..12).map { m -> DateOfBirthFormatter.getMonthName(m, isRussian) }
    }
    val monthIndex = (selectedMonth - 1).coerceIn(0, 11)

    // Dynamic days according to selected month & year
    val maxDays = DateOfBirthFormatter.getDaysInMonth(selectedMonth, selectedYear)
    val days = remember(maxDays) { (1..maxDays).map { it.toString() } }
    val dayIndex = (selectedDay - 1).coerceIn(0, maxDays - 1)

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .wrapContentHeight()
                .testTag("birth_date_wheel_picker_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = HexDarkSurfaceElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, HexDarkBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = strings.selectBirthDate,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = HexTextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Real-time preview pill with month in words
                val previewDate = DateOfBirthFormatter.formatFromParts(
                    selectedDay.coerceIn(1, maxDays),
                    selectedMonth,
                    selectedYear,
                    isRussian
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = HexDarkSurfaceInput,
                    border = androidx.compose.foundation.BorderStroke(1.dp, HexDarkBorderSubtle)
                ) {
                    Text(
                        text = previewDate,
                        color = HexShardTealLight,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Three wheel drums: Day, Month, Year
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Day Column
                    Box(modifier = Modifier.weight(0.9f)) {
                        WheelColumn(
                            items = days,
                            selectedIndex = dayIndex,
                            onItemSelected = { idx ->
                                selectedDay = idx + 1
                            }
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // 2. Month Column (text words)
                    Box(modifier = Modifier.weight(1.5f)) {
                        WheelColumn(
                            items = months,
                            selectedIndex = monthIndex,
                            onItemSelected = { idx ->
                                selectedMonth = idx + 1
                                val newMax = DateOfBirthFormatter.getDaysInMonth(selectedMonth, selectedYear)
                                if (selectedDay > newMax) selectedDay = newMax
                            }
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // 3. Year Column
                    Box(modifier = Modifier.weight(1.1f)) {
                        WheelColumn(
                            items = years,
                            selectedIndex = yearIndex,
                            onItemSelected = { idx ->
                                selectedYear = 1920 + idx
                                val newMax = DateOfBirthFormatter.getDaysInMonth(selectedMonth, selectedYear)
                                if (selectedDay > newMax) selectedDay = newMax
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Dialog Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismissRequest) {
                        Text(strings.cancel, color = HexTextSecondary)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = {
                            val finalResult = DateOfBirthFormatter.formatFromParts(
                                selectedDay.coerceIn(1, maxDays),
                                selectedMonth,
                                selectedYear,
                                isRussian
                            )
                            onDateSelected(finalResult)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = HexShardTeal,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(strings.ok, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
