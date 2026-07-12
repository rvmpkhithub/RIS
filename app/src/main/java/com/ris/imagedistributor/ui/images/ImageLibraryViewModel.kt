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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ImageLibraryViewModel(private val repository: ImageRepository) : ViewModel() {

    val images: StateFlow<List<Image>> = repository.observeImages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onImagesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch { repository.uploadImages(uris) }
    }

    fun onToggleActive(id: Long, active: Boolean) {
        viewModelScope.launch { repository.setActive(id, active) }
    }

    fun resolveFile(image: Image) = repository.resolveFile(image.filePath)

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ImageLibraryViewModel(container.imageRepository) }
        }
    }
}
