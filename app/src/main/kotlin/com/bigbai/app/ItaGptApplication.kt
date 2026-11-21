package com.bigbai.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import kotlinx.coroutines.launch

class ItaGptApplication : Application(), ComponentCallbacks2 {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifications.createChannels()
    }

    /**
     * The embedding model is the only large resource the app can release without
     * interrupting the user's answer. When Android reports pressure, it is
     * unloaded; retrieval then falls back to lexical memory until there is room
     * again. The chat model is never freed behind the user's back: that would
     * turn a slow device into a failed answer.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            container.scope.launch { container.embedder.close() }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        container.scope.launch { container.embedder.close() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }
}
