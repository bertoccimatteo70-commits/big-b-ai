package com.bigbai.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bigbai.app.AppContainer
import com.bigbai.app.ui.about.AboutScreen
import com.bigbai.app.ui.chat.ChatScreen
import com.bigbai.app.ui.chat.ChatViewModel
import com.bigbai.app.ui.code.CodeScreen
import com.bigbai.app.ui.diagnostics.DiagnosticsScreen
import com.bigbai.app.ui.history.HistoryScreen
import com.bigbai.app.ui.memory.MemoryScreen
import com.bigbai.app.ui.models.ModelManagerScreen
import com.bigbai.app.ui.onboarding.OnboardingScreen
import com.bigbai.app.ui.plugins.PluginsScreen
import com.bigbai.app.ui.settings.SettingsScreen
import com.bigbai.app.ui.theme.LocalReduceMotion
import com.bigbai.app.ui.theme.systemAnimationsDisabled
import com.bigbai.core.model.AppSettings
import kotlinx.coroutines.launch

object Routes {
    const val CHAT = "chat"
    const val HISTORY = "history"
    const val SEARCH = "search"
    const val MODELS = "models/library"
    const val MODELS_DOWNLOAD = "models/download"
    const val MODELS_INSTALLED = "models/installed"
    const val MEMORY = "memory"
    const val SETTINGS = "settings"
    const val CODE = "code"
    const val DIAGNOSTICS = "diagnostics"
    const val PLUGINS = "plugins"
    const val ABOUT = "about"

    fun models(tab: Int): String = when (tab) {
        1 -> MODELS_DOWNLOAD
        2 -> MODELS_INSTALLED
        else -> MODELS
    }
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
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.CHAT
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // The chat ViewModel is hosted here, above the navigation graph: the drawer
    // can start a new chat from any screen, and the conversation survives a trip
    // to the model manager instead of being recreated.
    val chatViewModel: ChatViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ChatViewModel(container, createSavedStateHandle()) }
        },
    )
    val chatState by chatViewModel.state.collectAsState()
    val conversations by chatViewModel.conversations.collectAsState()
    val storedQuota by container.settings.codeQuota.collectAsState(initial = null to 0)
    val codeQuota = com.bigbai.code.CodeQuota.of(
        com.bigbai.code.CodeQuota.today(),
        storedQuota.first,
        storedQuota.second,
    )

    fun navigateTo(target: String) {
        if (target == Routes.CHAT) {
            navController.navigate(Routes.CHAT) {
                popUpTo(Routes.CHAT) { inclusive = false }
                launchSingleTop = true
            }
        } else {
            navController.navigate(target) { launchSingleTop = true }
        }
        scope.launch { drawerState.close() }
    }

    val reduceMotion = settings.reduceMotion || systemAnimationsDisabled()
    val motion = if (reduceMotion) 0 else 220

    CompositionLocalProvider(
        LocalAppDrawer provides { scope.launch { drawerState.open() } },
        LocalReduceMotion provides reduceMotion,
    ) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                AppDrawer(
                    currentBaseRoute = currentRoute,
                    currentConversationId = chatState.conversationId,
                    conversations = conversations,
                    onNewChat = {
                        chatViewModel.newChat()
                        navigateTo(Routes.CHAT)
                    },
                    onOpenConversation = { id ->
                        chatViewModel.openConversation(id)
                        navigateTo(Routes.CHAT)
                    },
                    onNavigate = ::navigateTo,
                    codeQuotaRemaining = codeQuota.remaining,
                    codeQuotaLimit = codeQuota.limit,
                )
            },
        ) {
            NavHost(
                navController = navController,
                startDestination = Routes.CHAT,
                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                enterTransition = {
                    fadeIn(tween(motion)) + slideInHorizontally(tween(motion), initialOffsetX = { it / 16 })
                },
                exitTransition = { fadeOut(tween(motion / 2)) },
                popEnterTransition = {
                    fadeIn(tween(motion)) + slideInHorizontally(tween(motion), initialOffsetX = { -it / 16 })
                },
                popExitTransition = {
                    fadeOut(tween(motion)) + slideOutHorizontally(tween(motion), targetOffsetX = { it / 16 })
                },
            ) {
                composable(Routes.CHAT) {
                    ChatScreen(
                        container = container,
                        viewModel = chatViewModel,
                        onOpenModels = { navigateTo(Routes.MODELS) },
                        onOpenSettings = { navigateTo(Routes.SETTINGS) },
                        onOpenMemory = { navigateTo(Routes.MEMORY) },
                        onOpenCode = { navigateTo(Routes.CODE) },
                    )
                }
                composable(Routes.HISTORY) {
                    HistoryScreen(
                        container = container,
                        autoFocusSearch = false,
                        onOpenConversation = { id ->
                            chatViewModel.openConversation(id)
                            navigateTo(Routes.CHAT)
                        },
                    )
                }
                composable(Routes.SEARCH) {
                    HistoryScreen(
                        container = container,
                        autoFocusSearch = true,
                        onOpenConversation = { id ->
                            chatViewModel.openConversation(id)
                            navigateTo(Routes.CHAT)
                        },
                    )
                }
                composable(Routes.MODELS) {
                    ModelManagerScreen(container = container, initialTab = 0, onBack = { navigateTo(Routes.CHAT) })
                }
                composable(Routes.MODELS_DOWNLOAD) {
                    ModelManagerScreen(container = container, initialTab = 1, onBack = { navigateTo(Routes.CHAT) })
                }
                composable(Routes.MODELS_INSTALLED) {
                    ModelManagerScreen(container = container, initialTab = 2, onBack = { navigateTo(Routes.CHAT) })
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
                composable(Routes.DIAGNOSTICS) {
                    DiagnosticsScreen(container = container, onBack = { navController.popBackStack() })
                }
                composable(Routes.PLUGINS) {
                    PluginsScreen(container = container, onBack = { navController.popBackStack() })
                }
                composable(Routes.ABOUT) {
                    AboutScreen(container = container, onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
