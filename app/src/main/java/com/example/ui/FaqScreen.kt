package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaqScreen(onBack: () -> Unit) {
    val currentLang = LocalAppLanguage.current
    val strings = LocalStrings.current
    val faqItems = LegalContent.getFaq(currentLang)

    Scaffold(
        containerColor = HexDarkBg,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        strings.helpCenter,
                        fontWeight = FontWeight.Bold,
                        color = HexTextPrimary
                    ) 
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.hexPressEffect()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = HexTextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = HexDarkBg,
                    titleContentColor = HexTextPrimary,
                    navigationIconContentColor = HexTextPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            faqItems.forEach { entry ->
                HexCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    containerColor = HexDarkSurface,
                    borderColor = HexDarkBorder
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = entry.question,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = HexTextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = entry.answer,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = HexTextSecondary
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
