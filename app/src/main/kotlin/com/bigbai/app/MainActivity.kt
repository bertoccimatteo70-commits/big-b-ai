package com.bigbai.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.os.LocaleListCompat
import com.bigbai.app.ui.AppRoot
import com.bigbai.app.ui.theme.ItaGptTheme
import com.bigbai.core.model.AppSettings

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ItaGptApplication).container

        setContent {
            val settings by container.settings.settings.collectAsState(initial = null)
            val current = settings
            if (current == null) {
                ItaGptTheme(AppSettings().theme) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            } else {
                LaunchedEffect(current.language) { applyLanguage(current.language) }
                ItaGptTheme(current.theme) {
                    // One app-wide text scale, applied at the density level so
                    // every screen, dialog and chip honours it consistently.
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, current.fontScale),
                    ) {
                        AppRoot(container = container, settings = current)
                    }
                }
            }
        }
    }

    private fun applyLanguage(language: String) {
        val desired = if (language == "system") {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(language)
        }
        if (AppCompatDelegate.getApplicationLocales() != desired) {
            AppCompatDelegate.setApplicationLocales(desired)
        }
    }
}
