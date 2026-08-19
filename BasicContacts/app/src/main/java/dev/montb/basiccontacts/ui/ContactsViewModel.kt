package dev.montb.basiccontacts.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.montb.basiccontacts.BasicContactsApp
import dev.montb.basiccontacts.data.ContactDetail
import dev.montb.basiccontacts.data.ContactSummary
import dev.montb.basiccontacts.data.EditableContact
import dev.montb.basiccontacts.data.VCard
import dev.montb.basiccontacts.util.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContactsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as BasicContactsApp).repository

    private val _contacts = MutableStateFlow<List<ContactSummary>>(emptyList())
    val contacts: StateFlow<List<ContactSummary>> = _contacts.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _transfer = MutableStateFlow<TransferState>(TransferState.Idle)
    val transfer: StateFlow<TransferState> = _transfer.asStateFlow()

    private var searchJob: Job? = null

    sealed interface TransferState {
        data object Idle : TransferState
        data object Running : TransferState
        data class Done(val message: String) : TransferState
    }

    /** Reload the list (call after permission granted, on resume, and after edits). */
    fun refresh() {
        val q = _query.value
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { repo.loadContacts(q) }
            _contacts.value = list
        }
    }

    fun onQueryChange(text: String) {
        _query.value = text
        // Debounce so we don't re-query the provider on every keystroke.
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(180)
            val list = withContext(Dispatchers.IO) { repo.loadContacts(text) }
            _contacts.value = list
        }
    }

    suspend fun detail(contactId: Long): ContactDetail? =
        withContext(Dispatchers.IO) { repo.loadDetail(contactId) }

    /** Load an existing contact into the editable shape (resolves its raw-contact id). */
    suspend fun editable(contactId: Long): EditableContact? = withContext(Dispatchers.IO) {
        val d = repo.loadDetail(contactId) ?: return@withContext null
        val rawId = repo.firstRawContactId(contactId)
        EditableContact(
            rawContactId = rawId,
            displayName = d.displayName,
            phones = d.phones.ifEmpty { listOf(LabeledBlankPhone) },
            emails = d.emails,
            note = d.note ?: "",
            photoUri = d.photoUri
        )
    }

    /** Read a picked image URI into contact-sized JPEG bytes (off the main thread). */
    suspend fun readPickedPhoto(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        Images.readAsContactJpeg(getApplication(), uri)
    }

    /** Save (insert if new, update if existing). Returns true on success. */
    suspend fun save(contact: EditableContact): Boolean = withContext(Dispatchers.IO) {
        val ok = if (contact.rawContactId == null) repo.insert(contact) else repo.update(contact)
        if (ok) { Images.clearThumbCache(); refresh() }
        ok
    }

    suspend fun delete(contactId: Long, lookupKey: String): Boolean = withContext(Dispatchers.IO) {
        val ok = repo.delete(contactId, lookupKey)
        if (ok) { Images.clearThumbCache(); refresh() }
        ok
    }

    fun importVcf(uri: Uri) {
        _transfer.value = TransferState.Running
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { VCard.import(getApplication(), uri, repo) }
            refresh()
            _transfer.value = TransferState.Done(
                "Imported ${result.imported} contacts" +
                    if (result.failed > 0) " (${result.failed} failed)" else ""
            )
        }
    }

    fun exportVcf(uri: Uri) {
        _transfer.value = TransferState.Running
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                val details = repo.loadContacts().mapNotNull { repo.loadDetail(it.contactId) }
                VCard.export(getApplication(), uri, details) { id -> repo.readPhotoBytes(id) }
            }
            _transfer.value = TransferState.Done("Exported $count contacts")
        }
    }

    fun clearTransfer() { _transfer.value = TransferState.Idle }

    private companion object {
        val LabeledBlankPhone = EditableContact().phones.first()
    }
}
