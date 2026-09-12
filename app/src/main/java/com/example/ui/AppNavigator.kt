package com.example.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun AppNavigator(viewModel: ChatViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val initialDestination = remember {
        val uid = com.example.data.SecurePrefsManager.getUserId(context)
        val username = com.example.data.SecurePrefsManager.getUsername(context)
        val token = com.example.data.SecurePrefsManager.getSupabaseAccessToken(context)
        val hasSession = uid.isNotBlank() && username.isNotBlank() && username != "user" && token.isNotBlank()
        if (hasSession) "chatList" else "auth"
    }

    val navController = rememberNavController()
    val authState by com.example.network.supabase.SessionManager.authState.collectAsState()

    LaunchedEffect(Unit) {
        val currentUid = com.example.data.SecurePrefsManager.getUserId(context)
        val currentToken = com.example.data.SecurePrefsManager.getSupabaseAccessToken(context)
        if (currentUid.isNotBlank() && currentToken.isNotBlank()) {
            com.example.network.supabase.SessionManager.checkSession(context)
        }
    }

    LaunchedEffect(authState) {
        if (authState == com.example.network.supabase.AuthState.UNAUTHENTICATED) {
            val currentRoute = navController.currentBackStackEntry?.destination?.route
            if (currentRoute != null && currentRoute != "auth") {
                navController.navigate("auth") {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = initialDestination,
        enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)) },
        exitTransition = { slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)) },
        popEnterTransition = { slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)) },
        popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)) }
    ) {
        composable(
            "auth",
            enterTransition = { fadeIn(animationSpec = tween(500)) },
            exitTransition = { fadeOut(animationSpec = tween(500)) }
        ) {
            AuthScreen(
                onAuthComplete = {
                    viewModel.onAccountChanged()
                    navController.navigate("chatList") {
                        popUpTo("auth") { inclusive = true }
                    }
                }
            )
        }
        composable(
            "chatList",
            enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(400)) + fadeIn(animationSpec = tween(400)) },
            exitTransition = { fadeOut(animationSpec = tween(300)) },
            popEnterTransition = { fadeIn(animationSpec = tween(300)) },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(400)) + fadeOut(animationSpec = tween(400)) }
        ) {
            HexShardApp(
                viewModel = viewModel,
                onNavigateToSettings = {
                    navController.navigate("settings")
                }
            )
        }
        composable(
            "settings",
            enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(400)) + fadeIn(animationSpec = tween(400)) },
            exitTransition = { slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(400)) + fadeOut(animationSpec = tween(400)) },
            popEnterTransition = { slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(400)) + fadeIn(animationSpec = tween(400)) },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(400)) + fadeOut(animationSpec = tween(400)) }
        ) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToFaq = { navController.navigate("faq") },
                onNavigateToPrivacy = { navController.navigate("privacy") },
                onNavigateToTerms = { navController.navigate("terms") },
                onLogout = {
                    viewModel.logout(context) {
                        navController.navigate("auth") {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onDeleteAccount = {
                    viewModel.deleteAccount(context) {
                        navController.navigate("auth") {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }
            )
        }
        composable(
            "faq",
            enterTransition = { slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)) + fadeIn() },
            popExitTransition = { slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400)) + fadeOut() }
        ) {
            FaqScreen(onBack = { navController.popBackStack() })
        }
        composable(
            "privacy",
            enterTransition = { slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)) + fadeIn() },
            popExitTransition = { slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400)) + fadeOut() }
        ) {
            PrivacyPolicyScreen(onBack = { navController.popBackStack() })
        }
        composable(
            "terms",
            enterTransition = { slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)) + fadeIn() },
            popExitTransition = { slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400)) + fadeOut() }
        ) {
            TermsOfServiceScreen(onBack = { navController.popBackStack() })
        }
    }
}

