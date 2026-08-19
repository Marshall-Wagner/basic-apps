package dev.montb.basicphone.data

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow

class CallRepository(private val context: Context) {
    fun callLog(): Flow<PagingData<CallLogEntry>> =
        Pager(PagingConfig(pageSize = 50, enablePlaceholders = false)) {
            CallLogPagingSource(context)
        }.flow
}
