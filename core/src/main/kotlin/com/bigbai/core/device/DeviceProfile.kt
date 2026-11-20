package com.bigbai.core.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.bigbai.core.model.PerformanceMode
import com.bigbai.core.model.RamMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class DeviceTier { LOW, MID, HIGH, FLAGSHIP }

/**
 * Snapshot of the hardware the engine is allowed to use.
 *
 * The RAM budget is a *ceiling*, not a reservation: it is the smaller of the
 * selected profile limit and what Android currently reports as available minus
 * a safety reserve. Model loading is refused when the estimate does not fit.
 */
data class DeviceProfile(
    val totalRamBytes: Long,
    val availRamBytes: Long,
    val lowRamDevice: Boolean,
    val cpuCores: Int,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
) {
    val tier: DeviceTier
        get() = when {
            lowRamDevice || totalRamBytes <= 3L * GB -> DeviceTier.LOW
            totalRamBytes <= 6L * GB -> DeviceTier.MID
            totalRamBytes <= 10L * GB -> DeviceTier.HIGH
            else -> DeviceTier.FLAGSHIP
        }

    /** Reserve kept free for Android, the UI process and transient allocations. */
    val reserveBytes: Long
        get() = when (tier) {
            DeviceTier.LOW -> 900L * MB
            DeviceTier.MID -> 1200L * MB
            DeviceTier.HIGH -> 1600L * MB
            DeviceTier.FLAGSHIP -> 2200L * MB
        }

    fun budgetBytes(mode: RamMode): Long {
        val theoretical = (totalRamBytes * mode.fraction).toLong()
        val usable = max(0L, availRamBytes - reserveBytes)
        // Never plan to use more than the theoretical profile limit, and never
        // more than what is actually free right now.
        return min(theoretical, usable)
    }

    fun recommendedThreads(mode: PerformanceMode): Int {
        val usableCores = max(1, cpuCores)
        return when (mode) {
            PerformanceMode.SAVER -> max(1, (usableCores / 2))
            PerformanceMode.BALANCED -> max(1, usableCores - 1)
            PerformanceMode.PERFORMANCE -> usableCores
        }
    }

    /** Context length that fits the KV cache budget for a model of [layers] layers
     *  with [kvHeads] key/value heads of [headDim] dimensions, at f16. */
    fun adaptiveContext(
        budgetBytes: Long,
        layers: Int,
        kvHeads: Int,
        headDim: Int,
        trainContext: Int,
    ): Int {
        if (layers <= 0 || kvHeads <= 0 || headDim <= 0) return min(trainContext, 4096)
        // 2 (K and V) * 2 bytes (f16) per element
        val bytesPerToken = 2L * 2L * layers * kvHeads * headDim
        if (bytesPerToken <= 0) return min(trainContext, 4096)
        val kvBudget = (budgetBytes * 0.35).toLong()
        val fit = (kvBudget / bytesPerToken).toInt()
        val ceiling = when (tier) {
            DeviceTier.LOW -> 2048
            DeviceTier.MID -> 4096
            DeviceTier.HIGH -> 8192
            DeviceTier.FLAGSHIP -> 16384
        }
        val target = min(trainContext, ceiling)
        val chosen = min(target, max(1024, fit))
        // Round down to a multiple of 256 for stable KV allocation.
        return (chosen / 256) * 256
    }

    fun summary(): String =
        "$manufacturer $model · ${(totalRamBytes / GB.toDouble()).format1()} GB RAM · $cpuCores core · " +
            "Android ${Build.VERSION.RELEASE} (API $sdkInt)"

    private fun Double.format1(): String = String.format(java.util.Locale.US, "%.1f", this)

    companion object {
        const val KB = 1024L
        const val MB = 1024L * 1024L
        const val GB = 1024L * 1024L * 1024L

        fun read(context: Context): DeviceProfile {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(info)
            val total = if (info.totalMem > 0) info.totalMem else fallbackTotal(activityManager)
            return DeviceProfile(
                totalRamBytes = total,
                availRamBytes = info.availMem,
                lowRamDevice = activityManager.isLowRamDevice,
                cpuCores = Runtime.getRuntime().availableProcessors(),
                sdkInt = Build.VERSION.SDK_INT,
                manufacturer = Build.MANUFACTURER ?: "Android",
                model = Build.MODEL ?: "device",
            )
        }

        private fun fallbackTotal(activityManager: ActivityManager): Long {
            val mi = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(mi)
            return if (mi.totalMem > 0) mi.totalMem else 4L * GB
        }

        fun suggestedMaxModelBytes(totalRamBytes: Long): Long {
            // Conservative default used by the simple catalogue mode.
            val fraction = when {
                totalRamBytes <= 3L * GB -> 0.20
                totalRamBytes <= 4L * GB -> 0.22
                totalRamBytes <= 6L * GB -> 0.25
                totalRamBytes <= 8L * GB -> 0.28
                else -> 0.30
            }
            return (totalRamBytes * fraction).toLong().coerceAtLeast(300L * MB)
        }
    }
}

/** Live memory sampler used by the generation HUD. Values come from the platform. */
class MemorySampler(private val context: Context) {
    fun current(): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        val usedByApp = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        return usedByApp.coerceAtLeast(0)
    }

    fun available(): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.availMem
    }

    fun pressureLevel(): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        if (info.totalMem <= 0) return 0
        val freeRatio = info.availMem.toDouble() / info.totalMem
        return when {
            freeRatio < 0.08 -> 3
            freeRatio < 0.15 -> 2
            freeRatio < 0.25 -> 1
            else -> 0
        }
    }

    fun percentOf(total: Long): Int =
        if (total <= 0) 0 else ((current().toDouble() / total) * 100).roundToInt().coerceIn(0, 100)
}
