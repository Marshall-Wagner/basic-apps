package dev.montb.basickeyboard.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.montb.basickeyboard.ime.KeyboardPrefs
import dev.montb.basickeyboard.ime.PasswordManagers

/**
 * Setup screen. A keyboard can't enable itself (Android security), so this walks the
 * user through the two steps: enable in settings, then pick it as the active keyboard.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SetupScreen(
                        onEnable = {
                            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        },
                        onChoose = {
                            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                                .showInputMethodPicker()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupScreen(onEnable: () -> Unit, onChoose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Basic Keyboard", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Offline keyboard — English & Russian, number row, symbols, emoji. " +
                "No internet access, so nothing you type can leave the device.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Text("1. Turn the keyboard on:", style = MaterialTheme.typography.titleSmall)
        Button(onClick = onEnable, modifier = Modifier.padding(top = 8.dp)) {
            Text("Open keyboard settings")
        }
        Spacer(Modifier.height(20.dp))
        Text("2. Then select it as your keyboard:", style = MaterialTheme.typography.titleSmall)
        Button(onClick = onChoose, modifier = Modifier.padding(top = 8.dp)) {
            Text("Choose input method")
        }
        Spacer(Modifier.height(24.dp))
        Text("Keyboard height:", style = MaterialTheme.typography.titleSmall)
        Text(
            "Pick what fits your typing. Applies next time the keyboard opens.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(Modifier.height(8.dp))
        SizePicker()

        Spacer(Modifier.height(16.dp))
        NarrowModifiersToggle()

        Spacer(Modifier.height(16.dp))
        SquareKeysToggle()

        Spacer(Modifier.height(16.dp))
        CompactGridToggle()

        Spacer(Modifier.height(16.dp))
        VibrationToggle()

        PasswordManagerPicker()

        Spacer(Modifier.height(24.dp))
        Text(
            "Tip: tap the 🌐 globe key on the keyboard to switch English ⇄ Russian. " +
                "Long-press a letter for accents.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PasswordManagerPicker() {
    val context = LocalContext.current
    val installed = remember { PasswordManagers.installed(context) }
    if (installed.size < 2) return   // nothing to choose between
    var selected by remember { mutableStateOf(KeyboardPrefs.passwordManager(context)) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        Text("Password key opens", style = MaterialTheme.typography.bodyMedium)
        Text(
            "More than one password manager is installed — pick which the 🔑 key opens.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selected == null,
                onClick = { selected = null; KeyboardPrefs.setPasswordManager(context, null) },
                label = { Text("Auto") }
            )
            installed.forEach { pm ->
                FilterChip(
                    selected = selected == pm.pkg,
                    onClick = { selected = pm.pkg; KeyboardPrefs.setPasswordManager(context, pm.pkg) },
                    label = { Text(pm.label) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SizePicker() {
    val context = LocalContext.current
    var selected by remember { mutableIntStateOf(KeyboardPrefs.rowHeightDp(context)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KeyboardPrefs.HEIGHT_OPTIONS.forEach { (dp, label) ->
            FilterChip(
                selected = selected == dp,
                onClick = {
                    selected = dp
                    KeyboardPrefs.setRowHeightDp(context, dp)
                },
                label = { Text("$label ($dp)") }
            )
        }
    }
}

@Composable
private fun NarrowModifiersToggle() {
    val context = LocalContext.current
    var narrow by remember { mutableStateOf(KeyboardPrefs.narrowModifiers(context)) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Smaller Shift / Backspace / 123 keys", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Makes the wide keys the same width as letters.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = narrow,
            onCheckedChange = {
                narrow = it
                KeyboardPrefs.setNarrowModifiers(context, it)
            }
        )
    }
}

@Composable
private fun SquareKeysToggle() {
    val context = LocalContext.current
    var square by remember { mutableStateOf(KeyboardPrefs.squareKeys(context)) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Square keys (HTC style)", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Less rounded, slightly smaller keys instead of the big bubbly look.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = square,
            onCheckedChange = {
                square = it
                KeyboardPrefs.setSquareKeys(context, it)
            }
        )
    }
}

@Composable
private fun CompactGridToggle() {
    val context = LocalContext.current
    var compact by remember { mutableStateOf(KeyboardPrefs.compactGrid(context)) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Simple Keyboard layout", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Remove the gaps between keys and center each row, like the F-Droid Simple Keyboard.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = compact,
            onCheckedChange = {
                compact = it
                KeyboardPrefs.setCompactGrid(context, it)
            }
        )
    }
}

@Composable
private fun VibrationToggle() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(KeyboardPrefs.vibrationEnabled(context)) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Vibrate on keypress", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Haptic feedback when you tap a key. Turn off to type silently.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = {
                enabled = it
                KeyboardPrefs.setVibrationEnabled(context, it)
            }
        )
    }
}
