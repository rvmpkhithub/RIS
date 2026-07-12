package com.ris.imagedistributor.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ris.imagedistributor.data.repository.ComplianceRepository
import com.ris.imagedistributor.di.AppContainer
import com.ris.imagedistributor.domain.AppResult
import com.ris.imagedistributor.domain.ComplianceGate
import com.ris.imagedistributor.domain.GateResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class SetupNavEvent {
    data object ToMainApp : SetupNavEvent()
    data object ToComplianceHalt : SetupNavEvent()
}

private const val MAX_FIELD_LENGTH = 100

data class SetupUiState(
    val nickname: String = "",
    val city: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean get() = nickname.trim().isNotEmpty() && city.trim().isNotEmpty() && !submitting
}

/**
 * ViewModel calls ComplianceGate (not Repository directly) for the gating decision, per
 * AD-5's amended rule — compliance evaluation is ComplianceGate's job. [AD-5]
 *
 * ASSUMPTION: registration POST failure doesn't block Setup submission (treated consistently
 * with the compliance check's fail-open philosophy) — see Story 1.1 Task 6 note. A
 * lockRegistration DB failure, by contrast, DOES stop the flow (see onContinue) — without a
 * confirmed local lock the CAP-6 "no edit path afterward" invariant can't be guaranteed, and
 * retrying is safe since lockRegistration no-ops if already locked.
 */
class SetupViewModel(
    private val repository: ComplianceRepository,
    private val complianceGate: ComplianceGate,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    private val _navEvent = MutableStateFlow<SetupNavEvent?>(null)
    val navEvent: StateFlow<SetupNavEvent?> = _navEvent.asStateFlow()

    fun onNicknameChange(value: String) {
        _uiState.value = _uiState.value.copy(nickname = value.take(MAX_FIELD_LENGTH))
    }

    fun onCityChange(value: String) {
        _uiState.value = _uiState.value.copy(city = value.take(MAX_FIELD_LENGTH))
    }

    fun onContinue() {
        val state = _uiState.value
        if (!state.canSubmit) return
        val nickname = state.nickname.trim()
        val city = state.city.trim()
        _uiState.value = state.copy(submitting = true, error = null)

        viewModelScope.launch {
            // Lock is persisted immediately, independent of what follows — this is what makes
            // "locked with no edit path afterward" true even if registration/compliance fail.
            when (repository.lockRegistration(nickname, city)) {
                is AppResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        submitting = false,
                        error = "Couldn't save — please try again.",
                    )
                    return@launch
                }
                is AppResult.Success -> Unit
            }

            // Registration POST failure is not blocking — see class doc ASSUMPTION.
            repository.register(nickname, city)

            val result = complianceGate.evaluate(nickname = nickname, city = city)
            _navEvent.value = when (result) {
                GateResult.Proceed -> SetupNavEvent.ToMainApp
                GateResult.Halt -> SetupNavEvent.ToComplianceHalt
            }
        }
    }

    companion object {
        /** ViewModelProvider.Factory so this survives config changes via the normal ViewModelStore. */
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SetupViewModel(container.complianceRepository, container.complianceGate) }
        }
    }
}
