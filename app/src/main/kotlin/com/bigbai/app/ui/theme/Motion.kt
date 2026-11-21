package com.bigbai.app.ui.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * True when the user asked the system (or the app) to reduce animations.
 * Animations check this local and fall back to an instant state change.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * Reads the system "remove animations" / animator duration scale. A scale of 0
 * means the user disabled animations at OS level, and the app must honour it.
 */
@Composable
fun systemAnimationsDisabled(): Boolean {
    val context = LocalContext.current
    val scale = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
    return scale == 0f
}
