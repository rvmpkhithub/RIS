package com.ris.imagedistributor.ui.images

import androidx.compose.runtime.saveable.Saver
import com.ris.imagedistributor.data.local.Image

/** Mirrors ReceiversRoute.kt's pattern — simple sealed-class routing, no navigation library. */
sealed class ImagesRoute {
    data object List : ImagesRoute()

    /**
     * [preloaded], when present, is the already-known [Image] for [imageId] — used by the
     * upload flow to hand off the freshly-inserted row directly, avoiding a race against
     * [ImageLibraryViewModel.images]'s Flow re-emission (see [ImageLibraryViewModel.onImagePicked]).
     * Deliberately excluded from [ImagesRouteSaver]'s persisted string — on restoration the image
     * was already inserted in a previous session, so it's unconditionally present in `images` by
     * the time `hasLoaded` is true, and the race this field solves only exists within-session,
     * in the moments immediately after an insert.
     */
    data class Detail(val imageId: Long, val preloaded: Image? = null) : ImagesRoute()
}

/**
 * A malformed/foreign saved-state string must not crash route restoration — falls back to List,
 * same safety net ReceiversTab established for its own Edit route. Extracted as a named val
 * (rather than inlined in ImagesTab) so this fallback behavior is directly unit-testable.
 */
val ImagesRouteSaver: Saver<ImagesRoute, String> = Saver(
    save = { r -> when (r) { is ImagesRoute.List -> "list"; is ImagesRoute.Detail -> "detail:${r.imageId}" } },
    restore = { s ->
        runCatching {
            if (s == "list") ImagesRoute.List
            else ImagesRoute.Detail(s.removePrefix("detail:").toLong())
        }.getOrDefault(ImagesRoute.List)
    },
)
