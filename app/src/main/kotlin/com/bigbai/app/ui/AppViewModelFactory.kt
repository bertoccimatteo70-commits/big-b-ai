package com.bigbai.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bigbai.app.AppContainer
import com.bigbai.app.ui.chat.ChatViewModel
import com.bigbai.app.ui.code.CodeViewModel
import com.bigbai.app.ui.diagnostics.DiagnosticsViewModel
import com.bigbai.app.ui.memory.MemoryViewModel
import com.bigbai.app.ui.models.ModelManagerViewModel
import com.bigbai.app.ui.settings.SettingsViewModel

class AppViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val viewModel: ViewModel = when {
            modelClass.isAssignableFrom(ChatViewModel::class.java) -> ChatViewModel(container)
            modelClass.isAssignableFrom(ModelManagerViewModel::class.java) -> ModelManagerViewModel(container)
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(container)
            modelClass.isAssignableFrom(MemoryViewModel::class.java) -> MemoryViewModel(container)
            modelClass.isAssignableFrom(CodeViewModel::class.java) -> CodeViewModel(container)
            modelClass.isAssignableFrom(DiagnosticsViewModel::class.java) -> DiagnosticsViewModel(container)
            else -> error("Unknown ViewModel: ${modelClass.name}")
        }
        return viewModel as T
    }
}
