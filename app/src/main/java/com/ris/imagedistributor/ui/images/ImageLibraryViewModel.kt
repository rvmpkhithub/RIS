package com.ris.imagedistributor.ui.images

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.data.repository.ImageRepository
import com.ris.imagedistributor.di.AppContainer
import com.ris.imagedistributor.domain.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ImageLibraryViewModel(private val repository: ImageRepository) : ViewModel() {

    private val _hasLoaded = MutableStateFlow(false)

    /**
     * True once `images` has emitted at least one real value from the repository — distinct from
     * `images` itself being empty, which is also the StateFlow's initial seed value before the
     * underlying query has run. Lets callers (e.g. ImageDetailScreen via ImagesTab) tell "still
     * loading" apart from "genuinely no such image" instead of treating both as not-found.
     */
    val hasLoaded: StateFlow<Boolean> = _hasLoaded.asStateFlow()

    val images: StateFlow<List<Image>> = repository.observeImages()
        .onEach { _hasLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    /**
     * `uri == null` means the user backed out of the picker without selecting anything — no-op.
     * On success, [onResult] receives the freshly-uploaded [Image], fetched via a direct one-shot
     * [ImageRepository.getImageById] rather than trusting [images] to already contain the new row
     * — [images] is backed by a Room `Flow` whose re-emission after this insert is asynchronous
     * relative to this suspend call returning, so a caller navigating straight to that image's
     * detail view can't safely rely on [images] having caught up yet. On any failure, [onResult]
     * receives `null`.
     */
    fun onImagePicked(uri: Uri?, onResult: (Image?) -> Unit) {
        if (uri == null) return
        viewModelScope.launch {
            val uploadResult = repository.uploadImage(uri)
            val id = (uploadResult as? AppResult.Success)?.value
            val image = id?.let { (repository.getImageById(it) as? AppResult.Success)?.value }
            onResult(image)
        }
    }

    fun onToggleActive(id: Long, active: Boolean, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch { onResult(repository.setActive(id, active) is AppResult.Success) }
    }

    fun resolveFile(image: Image) = repository.resolveFile(image.filePath)

    /**
     * viewModelScope-launched, guarded against a double-tap firing a second concurrent save
     * while one is already in flight — same shape as SettingsViewModel.onSave.
     */
    fun updateImageDetails(id: Long, title: String?, description: String?, onResult: (Boolean) -> Unit) {
        if (_isSaving.value) return
        _isSaving.value = true
        viewModelScope.launch {
            try {
                val result = repository.updateImageDetails(id, title, description)
                onResult(result is AppResult.Success)
            } finally {
                _isSaving.value = false
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ImageLibraryViewModel(container.imageRepository) }
        }
    }
}
