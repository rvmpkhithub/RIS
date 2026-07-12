package com.ris.imagedistributor.ui.receivers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ris.imagedistributor.data.local.Receiver
import com.ris.imagedistributor.data.local.ReceiverWithSchedules
import com.ris.imagedistributor.data.repository.ReceiverRepository
import com.ris.imagedistributor.di.AppContainer
import com.ris.imagedistributor.domain.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReceiversViewModel(private val repository: ReceiverRepository) : ViewModel() {

    /** null = not yet loaded (distinct from a genuinely empty list) — [Review][Patch] fix for
     * the edit screen silently treating "not loaded yet" the same as "add new". */
    val receivers: StateFlow<List<ReceiverWithSchedules>?> = repository.observeReceivers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    /**
     * Fire-and-forget delete for the row's own coroutine scope isn't used here — the row needs
     * the result to reset its swipe state on failure, so this is a direct suspend call.
     */
    suspend fun deleteReceiver(id: Long): Boolean =
        repository.deleteReceiver(id) is AppResult.Success

    /**
     * Saves via the repository and only reports success on AppResult.Success — the caller must
     * not navigate back to the list until this returns true. [Story 1.2 lesson: don't proceed
     * past an operation that hasn't actually succeeded yet]
     */
    suspend fun saveReceiver(receiver: Receiver, scheduleTimes: List<Int>, isNew: Boolean): Boolean {
        val result = if (isNew) {
            repository.addReceiver(receiver, scheduleTimes)
        } else {
            repository.updateReceiver(receiver, scheduleTimes)
        }
        return result is AppResult.Success
    }

    /**
     * viewModelScope-launched wrapper around [saveReceiver] — [Review][Patch] fix: the caller
     * (the edit screen) previously launched this on its own rememberCoroutineScope, which is
     * cancelled if the composable leaves composition mid-save. Also guards against a double-tap
     * firing a second concurrent save while one is already in flight.
     */
    fun save(receiver: Receiver, scheduleTimes: List<Int>, isNew: Boolean, onResult: (Boolean) -> Unit) {
        // Set synchronously, before launching — a coroutine that hasn't started running yet
        // wouldn't otherwise block a second rapid call from also passing the guard.
        if (_isSaving.value) return
        _isSaving.value = true
        viewModelScope.launch {
            // [Review][Patch] finally ensures the flag always clears, even if saveReceiver ever
            // throws unexpectedly — without it, an uncaught exception would leave every future
            // save permanently locked out.
            try {
                val success = saveReceiver(receiver, scheduleTimes, isNew)
                onResult(success)
            } finally {
                _isSaving.value = false
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ReceiversViewModel(container.receiverRepository) }
        }
    }
}
