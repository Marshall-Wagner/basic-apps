package dev.montb.basicsms.ui

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import android.app.Application
import android.net.Uri
import dev.montb.basicsms.BasicSmsApp
import dev.montb.basicsms.data.Conversation
import dev.montb.basicsms.data.SmsImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MessagesViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as BasicSmsApp).repository

    val conversations = repo.conversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<Conversation>())

    /** null = idle; otherwise the in-progress/finished import status for the UI. */
    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState

    fun thread(address: String) = repo.messagesFor(address).cachedIn(viewModelScope)

    fun markRead(address: String) = viewModelScope.launch { repo.markRead(address) }

    fun send(address: String, body: String, subId: Int) =
        viewModelScope.launch { repo.sendMessage(address, body, subId) }

    fun importBackup(zipUri: Uri) {
        if (_importState.value is ImportState.Running) return
        _importState.value = ImportState.Running
        viewModelScope.launch(Dispatchers.IO) {
            _importState.value = try {
                val r = repo.importBackup(zipUri)
                ImportState.Done(r)
            } catch (e: Exception) {
                ImportState.Failed(e.message ?: "Unknown error")
            }
        }
    }

    fun clearImportState() { _importState.value = ImportState.Idle }

    sealed interface ImportState {
        data object Idle : ImportState
        data object Running : ImportState
        data class Done(val result: SmsImporter.Result) : ImportState
        data class Failed(val message: String) : ImportState
    }
}
