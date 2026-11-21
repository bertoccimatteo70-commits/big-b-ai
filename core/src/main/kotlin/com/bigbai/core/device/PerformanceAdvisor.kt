package com.bigbai.core.device

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.bigbai.core.model.PerformanceMode
import kotlin.math.max
import kotlin.math.min

/**
 * Turns the device's real state (cores, thermal throttling, battery saver) into
 * a conservative inference profile.
 *
 * The same model on a tablet and on a phone can differ by 4×: the tablet has
 * more cores and more thermal headroom. Treating every device as identical is
 * what produced unusable latency. This object never promises GPU acceleration:
 * it only reduces threads and context before Android starts throttling, and
 * returns an explicit note the UI can show instead of silently getting slower.
 *
 * Pure Kotlin so the policy is unit-tested without a device.
 */
object PerformanceAdvisor {

    data class Advice(
        val threads: Int,
        val contextFactor: Float,
        val thermalStatus: Int,
        val powerSaver: Boolean,
        val note: String?,
    )

    /** Same values as PowerManager.THERMAL_STATUS_* (API 29+). */
    fun thermalStatus(context: Context): Int = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            power.currentThermalStatus
        } else {
            0
        }
    }.getOrDefault(0)

    fun powerSaver(context: Context): Boolean = runCatching {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        power.isPowerSaveMode
    }.getOrDefault(false)

    fun label(status: Int): String = when (status) {
        0 -> "Normale"
        1 -> "Leggero"
        2 -> "Moderato"
        3 -> "Severo"
        4 -> "Critico"
        5 -> "Emergenza"
        6 -> "Spegnimento"
        else -> "Sconosciuto"
    }

    fun recommend(
        cores: Int,
        thermalStatus: Int,
        powerSaver: Boolean,
        mode: PerformanceMode = PerformanceMode.BALANCED,
    ): Advice {
        val usable = max(1, cores)
        val base = when (mode) {
            PerformanceMode.SAVER -> max(1, usable / 2)
            PerformanceMode.BALANCED -> max(1, usable - 1)
            PerformanceMode.PERFORMANCE -> usable
        }
        var cap = usable
        var factor = 1f
        var reason: String? = null
        when {
            thermalStatus >= 4 -> {
                cap = max(1, usable / 2)
                factor = 0.45f
                reason = "Dispositivo molto caldo: riduco thread e contesto per evitare blocchi."
            }
            thermalStatus == 3 -> {
                cap = max(1, usable - 2)
                factor = 0.6f
                reason = "Dispositivo caldo: prestazioni ridotte per mantenere la stabilità."
            }
            thermalStatus == 2 -> {
                cap = max(1, usable - 1)
                factor = 0.8f
                reason = "Temperatura moderata: contesto ridotto automaticamente."
            }
            thermalStatus == 1 -> {
                factor = 0.9f
            }
        }
        if (powerSaver) {
            cap = min(cap, max(1, usable / 2))
            factor = min(factor, 0.8f)
            if (reason == null) reason = "Risparmio energetico attivo: profilo più leggero."
        }
        if (mode == PerformanceMode.SAVER) {
            factor = min(factor, 0.8f)
        }
        val threads = base.coerceAtMost(cap).coerceAtLeast(1)
        return Advice(
            threads = threads,
            contextFactor = factor,
            thermalStatus = thermalStatus,
            powerSaver = powerSaver,
            note = reason,
        )
    }
}
