package com.bigbai.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bigbai.app.AppContainer
import com.bigbai.app.ui.chat.ChatScreen
import com.bigbai.app.ui.code.CodeScreen
import com.bigbai.app.ui.memory.MemoryScreen
import com.bigbai.app.ui.models.ModelManagerScreen
import com.bigbai.app.ui.onboarding.OnboardingScreen
import com.bigbai.app.ui.settings.SettingsScreen
import com.bigbai.core.model.AppSettings

object Routes {
    const val CHAT = "chat"
    const val MODELS = "models"
    const val MEMORY = "memory"
    const val SETTINGS = "settings"
    const val CODE = "code"
}

@Composable
fun AppRoot(container: AppContainer, settings: AppSettings) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !container.notifications.canNotify()) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (!settings.onboardingComplete) {
        OnboardingScreen(container = container, settings = settings)
        return
    }

    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.CHAT) {
        composable(Routes.CHAT) {
            ChatScreen(
                container = container,
                onOpenModels = { navController.navigate(Routes.MODELS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenMemory = { navController.navigate(Routes.MEMORY) },
                onOpenCode = { navController.navigate(Routes.CODE) },
            )
        }
        composable(Routes.MODELS) {
            ModelManagerScreen(container = container, onBack = { navController.popBackStack() })
        }
        composable(Routes.MEMORY) {
            MemoryScreen(container = container, onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(container = container, onBack = { navController.popBackStack() })
        }
        composable(Routes.CODE) {
            CodeScreen(container = container, onBack = { navController.popBackStack() })
        }
    }
}
