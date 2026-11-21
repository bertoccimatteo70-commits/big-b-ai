package com.bigbai.app.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bigbai.app.AppContainer
import com.bigbai.core.model.MemoryCategory
import com.bigbai.core.model.MemoryItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MemoryViewModel(private val container: AppContainer) : ViewModel() {

    val memories: StateFlow<List<MemoryItem>> = container.memory.memories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<MemoryCategory>> = container.memory.categories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _searchResults = MutableStateFlow<List<MemoryItem>?>(null)
    val searchResults: StateFlow<List<MemoryItem>?> = _searchResults.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch { container.memory.ensureDefaultCategories() }
    }

    fun search(query: String) {
        viewModelScope.launch {
            _searchResults.value = if (query.isBlank()) null else container.memory.search(query)
        }
    }

    fun add(content: String, category: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            val chosen = category.ifBlank { "Personale" }
            val id = container.memory.add(content, chosen)
            container.semantic.indexMemory(id, content, chosen)
            _message.value = "Ricordo salvato"
        }
    }

    fun update(id: Long, content: String) {
        viewModelScope.launch {
            container.memory.update(id, content)
            container.semantic.indexMemory(id, content, "Memoria")
            _message.value = "Ricordo aggiornato"
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            container.memory.delete(id)
            container.semantic.removeMemory(id)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            container.memory.clear()
            container.semantic.clearMemories()
            _message.value = "Memoria svuotata"
        }
    }

    fun createCategory(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { container.memory.createCategory(name) }
    }

    fun deleteCategory(id: Long) {
        viewModelScope.launch { container.memory.deleteCategory(id) }
    }

    fun dismissMessage() {
        _message.value = null
    }
}
