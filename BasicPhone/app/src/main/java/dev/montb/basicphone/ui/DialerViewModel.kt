package dev.montb.basicphone.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import dev.montb.basicphone.data.CallRepository

class DialerViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CallRepository(app)

    /** Paged, cached call-log stream for the smooth history list. */
    val callLog = repo.callLog().cachedIn(viewModelScope)
}
