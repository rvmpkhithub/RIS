package com.ris.imagedistributor.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ris.imagedistributor.data.local.DeliveryRecord
import com.ris.imagedistributor.data.local.ReceiverWithSchedules
import com.ris.imagedistributor.data.repository.ReceiverRepository
import com.ris.imagedistributor.data.repository.TransmissionRepository
import com.ris.imagedistributor.di.AppContainer
import com.ris.imagedistributor.domain.RetentionPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(
    private val receiverRepository: ReceiverRepository,
    private val transmissionRepository: TransmissionRepository,
    private val retentionPolicy: RetentionPolicy,
) : ViewModel() {

    /** null = not yet loaded, distinct from a genuinely empty list — same discipline as ReceiversViewModel.receivers. */
    val receivers: StateFlow<List<ReceiverWithSchedules>?> = receiverRepository.observeReceivers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _selectedReceiverId = MutableStateFlow<Long?>(null)
    val selectedReceiverId: StateFlow<Long?> = _selectedReceiverId.asStateFlow()

    init {
        // Auto-select the first receiver (existing name-ASC ordering) once loaded, if nothing is
        // selected yet — the picker should never sit empty-selected when receivers already exist.
        // Also re-selects if the currently-selected receiver disappears from the list (e.g.
        // deleted via the Receivers tab while this screen wasn't visible) — otherwise the picker
        // would show a blank selection and `history` would keep querying a stale receiverId forever.
        viewModelScope.launch {
            receivers.collect { list ->
                val currentId = _selectedReceiverId.value
                val currentStillExists = list?.any { it.receiver.id == currentId } == true
                if (!list.isNullOrEmpty() && (currentId == null || !currentStillExists)) {
                    _selectedReceiverId.value = list.first().receiver.id
                } else if (list != null && list.isEmpty()) {
                    _selectedReceiverId.value = null
                }
            }
        }
    }

    fun onSelectReceiver(id: Long) {
        _selectedReceiverId.value = id
    }

    /**
     * null = no receiver selected yet; empty list = selected receiver genuinely has no SENT
     * history in the window. The window itself is the live, operator-configurable
     * `RetentionSetting` value (Story 3.2) — not a hardcoded literal, per Story 3.1's own
     * explicit deferral of this decision ("Story 3.2's call to make when it exists").
     *
     * [Review][Patch] the cutoff Instant comes from [RetentionPolicy.observeCutoff] rather than
     * being computed here — the earlier version read `retentionRepository.observeRetentionDays()`
     * and subtracted the days itself, duplicating the exact formula [RetentionPolicy.purgeExpired]
     * already computes, which is precisely what AD-10 says must live in exactly one place.
     */
    val history: StateFlow<List<DeliveryRecord>?> = combine(
        _selectedReceiverId,
        retentionPolicy.observeCutoff(),
    ) { id, cutoff -> id to cutoff }
        .flatMapLatest { (id, cutoff) ->
            if (id == null) {
                flowOf(null)
            } else {
                transmissionRepository.observeSentHistory(id, cutoff)
                    .map<List<DeliveryRecord>, List<DeliveryRecord>?> { it }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DashboardViewModel(container.receiverRepository, container.transmissionRepository, container.retentionPolicy)
            }
        }
    }
}
