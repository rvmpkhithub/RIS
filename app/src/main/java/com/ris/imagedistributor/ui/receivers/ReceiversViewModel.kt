package com.ris.imagedistributor.ui.receivers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ris.imagedistributor.data.local.Receiver
import com.ris.imagedistributor.data.local.ReceiverWithSchedules
import com.ris.imagedistributor.data.repository.ImageRepository
import com.ris.imagedistributor.data.repository.ReceiverRepository
import com.ris.imagedistributor.di.AppContainer
import com.ris.imagedistributor.domain.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReceiversViewModel(
    private val repository: ReceiverRepository,
    private val imageRepository: ImageRepository,
) : ViewModel() {

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
     * Save-time guard (operator-requested, 2026-07-14) for the dispatch-tick allocation's own
     * defensive fallback in [com.ris.imagedistributor.domain.ImageSelectionEngine] — if the sum
     * of every receiver's minimum ever exceeds the active image count, no allocation can satisfy
     * every receiver's minimum without repeating an image across two receivers in the same round.
     * This keeps that fallback unreachable in normal operation by refusing to save a receiver
     * whose own minimum would push the combined total past what's actually available right now.
     *
     * [excludingReceiverId] is the receiver being edited (excluded from the "everyone else" sum
     * so editing a receiver's own minimum compares against the other receivers only, not itself
     * twice) — null when adding a brand-new receiver.
     *
     * Returns a user-facing error message, or null if the save is within budget. Reads
     * `receivers.value` — a live snapshot — rather than re-querying, since this is only ever
     * called from a Save action already downstream of the list having loaded.
     */
    suspend fun validateMinCountBudget(candidateMinCount: Int, excludingReceiverId: Long?): String? {
        val otherReceiversMinSum = receivers.value.orEmpty()
            .filter { it.receiver.id != excludingReceiverId }
            .sumOf { it.receiver.minCount }
        val activeImageCount = when (val result = imageRepository.getActiveImages()) {
            is AppResult.Success -> result.value.size
            is AppResult.Failure -> return "Couldn't check active images — please try again."
        }
        val requiredTotal = otherReceiversMinSum + candidateMinCount
        return if (requiredTotal > activeImageCount) {
            "Not enough active images ($activeImageCount) to guarantee every receiver's minimum " +
                "($requiredTotal needed across all receivers). Add more images or lower a minimum."
        } else {
            null
        }
    }

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

    /**
     * Combines [validateMinCountBudget] and [saveReceiver] into one viewModelScope-launched flow
     * — same reasoning as [save]'s own fix above: the *whole* save attempt (budget check, then
     * persist) must survive the calling composable leaving composition mid-check, not just the
     * persist half. A screen that ran the budget check on its own `rememberCoroutineScope` before
     * calling [save] would silently drop the save if the operator navigated away while the
     * (suspending) budget check was still in flight.
     *
     * [onResult] receives `null` on success, or a user-facing error message otherwise — either
     * [validateMinCountBudget]'s own message, or the generic "couldn't save" for a downstream
     * repository failure, matching [save]'s existing failure message.
     */
    fun saveWithBudgetCheck(
        receiver: Receiver,
        scheduleTimes: List<Int>,
        isNew: Boolean,
        excludingReceiverId: Long?,
        onResult: (String?) -> Unit,
    ) {
        if (_isSaving.value) return
        _isSaving.value = true
        viewModelScope.launch {
            try {
                val budgetError = validateMinCountBudget(receiver.minCount, excludingReceiverId)
                if (budgetError != null) {
                    onResult(budgetError)
                    return@launch
                }
                val success = saveReceiver(receiver, scheduleTimes, isNew)
                onResult(if (success) null else "Couldn't save — please try again.")
            } finally {
                _isSaving.value = false
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ReceiversViewModel(container.receiverRepository, container.imageRepository) }
        }
    }
}
