package dev.montb.basicmonitor.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.montb.basicmonitor.data.Stats
import dev.montb.basicmonitor.data.SystemStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BasicMonitorTheme { MonitorScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonitorScreen() {
    val context = LocalContext.current
    var stats by remember { mutableStateOf<Stats?>(null) }
    // Refresh once a second; the sysfs / file reads run off the main thread.
    LaunchedEffect(Unit) {
        while (true) {
            stats = withContext(Dispatchers.IO) { SystemStats.read(context) }
            delay(1000)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Basic Monitor") }) }) { padding ->
        val s = stats
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (s == null) {
                Text("Reading…", style = MaterialTheme.typography.bodyMedium)
            } else {
                CpuCard(s)
                GpuCard(s)
                MemoryCard(s)
                SwapCard(s)
                StorageCard(s)
                BatteryCard(s)
            }
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    subtitle: String? = null,
    progress: Float? = null,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.headlineMedium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            progress?.let {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { it.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            content()
        }
    }
}

@Composable
private fun CpuCard(s: Stats) {
    val curTop = s.cpuCurMhz.filter { it > 0 }.maxOrNull()
    StatCard(
        title = "CPU",
        value = curTop?.let { clock(it) } ?: if (s.cpuMaxMhz > 0) clock(s.cpuMaxMhz) else "—",
        subtitle = s.cpuModel,
        progress = if (curTop != null && s.cpuMaxMhz > 0) curTop.toFloat() / s.cpuMaxMhz else null
    ) {
        Spacer(Modifier.height(6.dp))
        Text(
            buildString {
                append("${s.cpuCores} cores")
                if (s.cpuMaxMhz > 0) append("  ·  up to ${clock(s.cpuMaxMhz)}")
                if (curTop == null) append("  ·  live freq unavailable")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (curTop != null) {
            Text(
                "per-core: " + s.cpuCurMhz.joinToString("  ") { if (it > 0) it.toString() else "—" } + " MHz",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GpuCard(s: Stats) {
    val clockSub = if (s.gpuCurMhz != null) {
        clock(s.gpuCurMhz) + (s.gpuMaxMhz?.let { "  ·  up to ${clock(it)}" } ?: "")
    } else {
        "Clock hidden from apps (root required)"
    }
    StatCard(
        title = "GPU",
        value = s.gpuModel ?: "Unavailable",
        subtitle = clockSub,
        progress = if (s.gpuCurMhz != null && s.gpuMaxMhz != null && s.gpuMaxMhz > 0)
            s.gpuCurMhz.toFloat() / s.gpuMaxMhz else null
    )
}

@Composable
private fun MemoryCard(s: Stats) {
    StatCard(
        title = "MEMORY",
        value = "${gb(s.ramUsed)} / ${gb(s.ramTotal)}",
        subtitle = "${gb(s.ramTotal - s.ramUsed)} free",
        progress = if (s.ramTotal > 0) s.ramUsed.toFloat() / s.ramTotal else null
    )
}

@Composable
private fun SwapCard(s: Stats) {
    val hasSwap = s.swapTotal > 0L
    StatCard(
        title = "SWAP (zram / virtual RAM)",
        value = if (hasSwap) "${gb(s.swapUsed)} / ${gb(s.swapTotal)}" else "None active",
        subtitle = if (hasSwap)
            "${gb(s.swapTotal - s.swapUsed)} free  ·  compressed/overflow memory, not physical RAM"
        else
            "No zram or virtual-RAM swap is configured on this device",
        progress = if (hasSwap) s.swapUsed.toFloat() / s.swapTotal else null
    )
}

@Composable
private fun StorageCard(s: Stats) {
    val used = s.storageTotal - s.storageFree
    StatCard(
        title = "STORAGE",
        value = "${gb(s.storageFree)} free",
        subtitle = "of ${gb(s.storageTotal)} total",
        progress = if (s.storageTotal > 0) used.toFloat() / s.storageTotal else null
    )
}

@Composable
private fun BatteryCard(s: Stats) {
    val sub = buildString {
        s.batteryTempC?.let { append("%.1f°C".format(it)) }
        if (s.charging) { if (isNotEmpty()) append("  ·  "); append("charging") }
    }.ifEmpty { "battery" }
    StatCard(
        title = "BATTERY",
        value = if (s.batteryPct >= 0) "${s.batteryPct}%" else "—",
        subtitle = sub,
        progress = if (s.batteryPct >= 0) s.batteryPct / 100f else null
    )
}

private fun clock(mhz: Int): String = if (mhz >= 1000) "%.2f GHz".format(mhz / 1000f) else "$mhz MHz"
private fun gb(bytes: Long): String = "%.1f GB".format(bytes / 1073741824.0)
