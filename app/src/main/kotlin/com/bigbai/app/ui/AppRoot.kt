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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DismissibleNavigationDrawer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
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
fun AppRoot(
    container: AppContainer,
    settings: AppSettings,
    widthSizeClass: WindowWidthSizeClass = WindowWidthSizeClass.Compact,
) {
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
    val expanded = widthSizeClass != WindowWidthSizeClass.Compact
    val drawerState = rememberDrawerState(if (expanded) DrawerValue.Open else DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    // On a tablet the drawer is a permanent side panel (dismissible drawer);
    // on a phone it is the usual modal drawer. This is a real layout change, not
    // a scaled-up phone screen.
    LaunchedEffect(expanded) {
        drawerState.snapTo(if (expanded) DrawerValue.Open else DrawerValue.Closed)
    }

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
                popUpTo(Routes.CHAT) { inclusive = true }
                launchSingleTop = true
            }
        } else {
            navController.navigate(target) { launchSingleTop = true }
        }
        if (!expanded) scope.launch { drawerState.close() }
    }

    val reduceMotion = settings.reduceMotion || systemAnimationsDisabled()
    val motion = if (reduceMotion) 0 else 220

    val drawerContent: @Composable () -> Unit = {
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
    }

    val content: @Composable (Modifier) -> Unit = { modifier ->
        AppNavHost(
            navController = navController,
            container = container,
            chatViewModel = chatViewModel,
            onNavigate = ::navigateTo,
            motion = motion,
            modifier = modifier,
        )
    }

    CompositionLocalProvider(
        LocalAppDrawer provides { scope.launch { drawerState.open() } },
        LocalReduceMotion provides reduceMotion,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (event.isCtrlPressed) {
                        when (event.key) {
                            Key.N -> {
                                chatViewModel.newChat()
                                navigateTo(Routes.CHAT)
                                true
                            }
                            Key.K -> {
                                navigateTo(Routes.SEARCH)
                                true
                            }
                            Key.L -> {
                                chatViewModel.toggleWebSearch()
                                true
                            }
                            else -> false
                        }
                    } else if (event.key == Key.Escape && chatState.isGenerating) {
                        chatViewModel.stop()
                        true
                    } else {
                        false
                    }
                },
        ) {
            LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
            if (expanded) {
                DismissibleNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = drawerContent,
                ) {
                    content(Modifier.fillMaxWidth().fillMaxHeight())
                }
            } else {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = drawerContent,
                ) {
                    content(Modifier.fillMaxWidth().fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    container: AppContainer,
    chatViewModel: ChatViewModel,
    onNavigate: (String) -> Unit,
    motion: Int,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        NavHost(
            navController = navController,
            startDestination = Routes.CHAT,
            // On a wide window content is capped at a readable width instead of
            // stretching a phone layout across an entire tablet.
            modifier = Modifier
                .widthIn(max = 1200.dp)
                .fillMaxHeight()
                .fillMaxWidth(),
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
                    onOpenModels = { onNavigate(Routes.MODELS) },
                    onOpenSettings = { onNavigate(Routes.SETTINGS) },
                    onOpenMemory = { onNavigate(Routes.MEMORY) },
                    onOpenCode = { onNavigate(Routes.CODE) },
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    container = container,
                    autoFocusSearch = false,
                    onOpenConversation = { id ->
                        chatViewModel.openConversation(id)
                        onNavigate(Routes.CHAT)
                    },
                )
            }
            composable(Routes.SEARCH) {
                HistoryScreen(
                    container = container,
                    autoFocusSearch = true,
                    onOpenConversation = { id ->
                        chatViewModel.openConversation(id)
                        onNavigate(Routes.CHAT)
                    },
                )
            }
            composable(Routes.MODELS) {
                ModelManagerScreen(container = container, initialTab = 0, onBack = { onNavigate(Routes.CHAT) })
            }
            composable(Routes.MODELS_DOWNLOAD) {
                ModelManagerScreen(container = container, initialTab = 1, onBack = { onNavigate(Routes.CHAT) })
            }
            composable(Routes.MODELS_INSTALLED) {
                ModelManagerScreen(container = container, initialTab = 2, onBack = { onNavigate(Routes.CHAT) })
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
