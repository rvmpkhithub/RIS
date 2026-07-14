package com.ris.imagedistributor.ui.images

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ris.imagedistributor.di.AppContainer

/**
 * Top-level Images tab — holds the list/detail route (mirrors ReceiversTab's pattern, no nav
 * library) and a single shared ImageLibraryViewModel across both sub-screens.
 */
@Composable
fun ImagesTab(container: AppContainer) {
    val viewModel: ImageLibraryViewModel = viewModel(factory = ImageLibraryViewModel.factory(container))
    var route by rememberSaveable(stateSaver = ImagesRouteSaver) { mutableStateOf<ImagesRoute>(ImagesRoute.List) }

    BackHandler(enabled = route is ImagesRoute.Detail) {
        route = ImagesRoute.List
    }

    when (val current = route) {
        is ImagesRoute.List -> ImageLibraryScreen(
            viewModel = viewModel,
            onViewImage = { id -> route = ImagesRoute.Detail(id) },
            onImageUploaded = { image -> route = ImagesRoute.Detail(image.id, preloaded = image) },
        )
        is ImagesRoute.Detail -> {
            val images by viewModel.images.collectAsState()
            val hasLoaded by viewModel.hasLoaded.collectAsState()
            ImageDetailScreen(
                viewModel = viewModel,
                imageId = current.imageId,
                existing = current.preloaded ?: images.find { it.id == current.imageId },
                stillLoading = !hasLoaded,
                onDone = { route = ImagesRoute.List },
            )
        }
    }
}
