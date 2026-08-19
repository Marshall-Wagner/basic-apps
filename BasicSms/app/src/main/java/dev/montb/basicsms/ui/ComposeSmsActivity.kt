package dev.montb.basicsms.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Entry point when another app fires an sms:/smsto: intent (e.g. tapping a phone
 * number in the browser). Opens straight into that conversation thread.
 */
class ComposeSmsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // sms:+15551234567?body=... -> strip the query, keep the number.
        val address = intent?.data?.schemeSpecificPart
            ?.substringBefore('?')
            ?.trim()
            .orEmpty()

        setContent {
            BasicSmsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val vm: MessagesViewModel = viewModel()
                    ThreadScreen(vm, address, onBack = { finish() })
                }
            }
        }
    }
}
