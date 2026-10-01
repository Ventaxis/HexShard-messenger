package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
fun TermsOfServiceScreen(onBack: () -> Unit) {
    val currentLang = LocalAppLanguage.current
    val strings = LocalStrings.current
    val sections = LegalContent.getTermsOfService(currentLang)

    Scaffold(
        containerColor = HexDarkBg,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        strings.termsService,
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
            Text(
                text = strings.termsService,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = HexTextPrimary
            )
            Text(
                text = if (currentLang == AppLanguage.RUSSIAN) "Версия редакции: Сентябрь 2026" else "Version: September 2026",
                fontSize = 12.sp,
                color = HexTextSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )

            sections.forEach { section ->
                HexCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    containerColor = HexDarkSurface,
                    borderColor = HexDarkBorder
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = section.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = HexShardTeal
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        section.paragraphs.forEach { paragraph ->
                            Text(
                                text = paragraph,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                color = HexTextSecondary,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
