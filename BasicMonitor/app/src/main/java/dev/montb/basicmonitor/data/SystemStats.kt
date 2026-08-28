package dev.montb.basicmonitor.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File

/** A snapshot of the system stats we can read. Values we can't read are null / -1. */
data class Stats(
    val cpuCores: Int,
    val cpuModel: String,       // e.g. "Snapdragon 8+ Gen 1 (SM8475)"
    val cpuCurMhz: List<Int>,   // per-core current MHz; -1 for a core we couldn't read
    val cpuMaxMhz: Int,         // highest max across cores, or -1
    val gpuModel: String?,      // e.g. "Adreno 730" (via OpenGL), or null if unobtainable
    val gpuCurMhz: Int?,        // null when the kernel hides GPU freq from apps (needs root)
    val gpuMaxMhz: Int?,
    val ramUsed: Long, val ramTotal: Long,
    val swapUsed: Long, val swapTotal: Long,   // combined zram + storage swap; 0 if none
    val storageFree: Long, val storageTotal: Long,
    val batteryPct: Int,        // -1 if unknown
    val batteryTempC: Float?,   // null if unknown
    val charging: Boolean
)

object SystemStats {

    fun read(context: Context): Stats {
        val cores = Runtime.getRuntime().availableProcessors()
        val cur = (0 until cores).map { c -> cpuKhz(c, "scaling_cur_freq")?.let { (it / 1000).toInt() } ?: -1 }
        val maxMhz = (0 until cores)
            .mapNotNull { cpuKhz(it, "cpuinfo_max_freq") ?: cpuKhz(it, "scaling_max_freq") }
            .maxOrNull()?.let { (it / 1000).toInt() } ?: -1

        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val ramTotal = mi.totalMem
        val ramUsed = (mi.totalMem - mi.availMem).coerceAtLeast(0)
        val swapTotalKb = readMeminfoKb("SwapTotal") ?: 0L
        val swapFreeKb = readMeminfoKb("SwapFree") ?: 0L

        val stat = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()

        val (pct, temp, charging) = battery(context)

        return Stats(
            cpuCores = cores,
            cpuModel = cpuModel(),
            cpuCurMhz = cur,
            cpuMaxMhz = maxMhz,
            gpuModel = GpuInfo.renderer(),
            gpuCurMhz = GPU_CUR.firstNotNullOfOrNull { readLong(it) }?.let { toMhz(it) },
            gpuMaxMhz = GPU_MAX.firstNotNullOfOrNull { readLong(it) }?.let { toMhz(it) },
            ramUsed = ramUsed, ramTotal = ramTotal,
            swapUsed = (swapTotalKb - swapFreeKb).coerceAtLeast(0) * 1024,
            swapTotal = swapTotalKb * 1024,
            storageFree = stat?.availableBytes ?: 0L,
            storageTotal = stat?.totalBytes ?: 0L,
            batteryPct = pct, batteryTempC = temp, charging = charging
        )
    }

    private fun cpuKhz(core: Int, file: String): Long? =
        readLong("/sys/devices/system/cpu/cpu$core/cpufreq/$file")

    private val GPU_CUR = listOf(
        "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",   // Adreno (Hz)
        "/sys/class/kgsl/kgsl-3d0/gpuclk",             // Adreno (Hz)
        "/sys/class/kgsl/kgsl-3d0/clock_mhz",          // Adreno (MHz)
        "/sys/kernel/gpu/gpu_clock",                   // some Mali (MHz)
        "/sys/class/devfreq/gpufreq/cur_freq"          // generic devfreq
    )
    private val GPU_MAX = listOf(
        "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
        "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq",
        "/sys/kernel/gpu/gpu_max_clock"
    )

    /** Normalize a raw clock value (Hz / kHz / MHz) to MHz by magnitude.
     *  internal (not private) so unit tests can exercise the heuristic directly. */
    internal fun toMhz(raw: Long): Int = when {
        raw > 1_000_000 -> (raw / 1_000_000).toInt()   // Hz
        raw > 10_000 -> (raw / 1000).toInt()           // kHz
        else -> raw.toInt()                            // already MHz
    }

    // Read the first line of a (single-value) sysfs file. bufferedReader avoids the sysfs
    // "reported length 0" gotcha; any SELinux/permission failure just yields null.
    private fun readLong(path: String): Long? = runCatching {
        File(path).bufferedReader().use { it.readLine() }?.trim()?.toLongOrNull()
    }.getOrNull()

    /** Read a /proc/meminfo value (in kB), e.g. key = "SwapTotal". */
    private fun readMeminfoKb(key: String): Long? = runCatching {
        File("/proc/meminfo").bufferedReader().useLines { lines ->
            lines.firstOrNull { it.startsWith("$key:") }
                ?.let { Regex("\\d+").find(it)?.value?.toLong() }
        }
    }.getOrNull()

    private fun battery(context: Context): Triple<Int, Float?, Boolean> {
        val bm = context.getSystemService(BatteryManager::class.java)
        val rawPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val pct = if (rawPct in 0..100) rawPct else -1
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val temp = if (tempTenths > 0) tempTenths / 10f else null
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return Triple(pct, temp, charging)
    }

    private val SOC_NAMES = mapOf(
        "SM8475" to "Snapdragon 8+ Gen 1",
        "SM8450" to "Snapdragon 8 Gen 1",
        "SM8550" to "Snapdragon 8 Gen 2",
        "SM8650" to "Snapdragon 8 Gen 3",
        "SM8350" to "Snapdragon 888",
        "SM8325" to "Snapdragon 888+",
        "SM8250" to "Snapdragon 865",
        "SM8150" to "Snapdragon 855",
        "SDM845" to "Snapdragon 845",
        "MSM8998" to "Snapdragon 835",
        "MSM8996" to "Snapdragon 820",
        "SM7325" to "Snapdragon 778G",
        "SM7250" to "Snapdragon 765G"
    )

    // Qualcomm platform codenames (Build.BOARD) -> marketing name. Needed pre-Android-12
    // where Build.SOC_MODEL is unavailable (e.g. the ROG 5 reports BOARD = "lahaina").
    private val CODENAMES = mapOf(
        "cape" to "Snapdragon 8+ Gen 1",
        "taro" to "Snapdragon 8 Gen 1",
        "kalama" to "Snapdragon 8 Gen 2",
        "pineapple" to "Snapdragon 8 Gen 3",
        "lahaina" to "Snapdragon 888",
        "kona" to "Snapdragon 865",
        "msmnile" to "Snapdragon 855",
        "sdm845" to "Snapdragon 845",
        "msm8998" to "Snapdragon 835",
        "msm8996" to "Snapdragon 820",
        "lito" to "Snapdragon 765G",
        "bengal" to "Snapdragon 662"
    )

    private fun cpuModel(): String {
        // 1. SOC_MODEL (Android 12+), e.g. "SM8475".
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val m = Build.SOC_MODEL
            if (m.isNotBlank() && m != "UNKNOWN") return matchSoc(m) ?: m
        }
        // 2. Qualcomm soc0 sysfs / cpuinfo (older devices), look for an SM/MSM code.
        for (raw in listOfNotNull(readFirstLine("/sys/devices/soc0/machine"), procHardware())) {
            matchSoc(raw)?.let { return it }
        }
        // 3. Board platform codename -> SoC (pre-12, where SOC_MODEL is absent).
        CODENAMES[Build.BOARD.lowercase()]?.let { return it }
        // 4. Last resort: prefer the codename over the generic "qcom".
        return listOfNotNull(procHardware(), Build.BOARD.takeIf { it.isNotBlank() && it != "qcom" })
            .firstOrNull() ?: Build.HARDWARE.ifBlank { "Unknown CPU" }
    }

    // internal (not private) so unit tests can exercise the SoC-code matching directly.
    internal fun matchSoc(raw: String): String? =
        SOC_NAMES.entries.firstOrNull { raw.contains(it.key, ignoreCase = true) }
            ?.let { "${it.value} (${it.key})" }

    private fun procHardware(): String? = runCatching {
        File("/proc/cpuinfo").bufferedReader().useLines { lines ->
            lines.firstOrNull { it.startsWith("Hardware", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private fun readFirstLine(path: String): String? = runCatching {
        File(path).bufferedReader().use { it.readLine() }?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
