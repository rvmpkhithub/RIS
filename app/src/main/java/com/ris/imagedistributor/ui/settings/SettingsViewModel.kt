package com.ris.imagedistributor.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ris.imagedistributor.data.repository.MasterScheduleRepository
import com.ris.imagedistributor.data.repository.RetentionRepository
import com.ris.imagedistributor.di.AppContainer
import com.ris.imagedistributor.domain.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val retentionRepository: RetentionRepository,
    private val masterScheduleRepository: MasterScheduleRepository,
) : ViewModel() {

    val retentionDays: StateFlow<Int?> =
        retentionRepository.observeRetentionDays()
            .map<Int, Int?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val masterScheduleTimes: StateFlow<List<Int>?> =
        masterScheduleRepository.observeScheduleTimes()
            .map<List<Int>, List<Int>?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    /**
     * viewModelScope-launched, guarded against a double-tap firing a second concurrent save
     * while one is already in flight — same shape as ReceiversViewModel.save.
     */
    fun onSave(days: Int, onResult: (Boolean) -> Unit) {
        if (_isSaving.value) return
        _isSaving.value = true
        viewModelScope.launch {
            try {
                val result = retentionRepository.setRetentionDays(days)
                onResult(result is AppResult.Success)
            } finally {
                _isSaving.value = false
            }
        }
    }

    /**
     * Same shape as [onSave] — reuses the single [_isSaving] guard shared across both dialogs on
     * this screen, since only one can ever be open at a time.
     */
    fun onSaveMasterSchedule(times: List<Int>, onResult: (Boolean) -> Unit) {
        if (_isSaving.value) return
        _isSaving.value = true
        viewModelScope.launch {
            try {
                val result = masterScheduleRepository.setScheduleTimes(times)
                onResult(result is AppResult.Success)
            } finally {
                _isSaving.value = false
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(container.retentionRepository, container.masterScheduleRepository) }
        }
    }
}
