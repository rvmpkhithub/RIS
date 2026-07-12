package com.ris.imagedistributor.ui.images

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.ui.theme.GoldBorder
import java.io.File

/**
 * DESIGN.md#Components (image-grid-item) — square thumbnail, {rounded.sm} corners, active/inactive
 * toggle overlaid bottom-right. Inactive = desaturated + 60% opacity overlay, no red X.
 * EXPERIENCE.md#State Patterns — empty-library message.
 */
@Composable
fun ImageLibraryScreen(viewModel: ImageLibraryViewModel) {
    val images by viewModel.images.collectAsState()

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
        onResult = viewModel::onImagesPicked,
    )

    // [Review][Patch] the grid and the Upload button are now Column siblings (grid weighted to
    // fill remaining space) instead of both being absolutely positioned inside one Box — the
    // previous version let the always-visible, bottom-pinned Button render on top of the grid's
    // last row with no space reserved for it, obscuring thumbnails once the grid filled the
    // screen. Same Column+weight(1f) fix already established by this app's Dashboard screen.
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        if (images.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                Card(
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.5.dp, GoldBorder),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                ) {
                    Text(
                        text = "No images yet — upload some to get started.",
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                modifier = Modifier.fillMaxSize().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(images, key = { it.id }) { image ->
                    ImageGridItem(
                        image = image,
                        file = viewModel.resolveFile(image),
                        onToggleActive = { active -> viewModel.onToggleActive(image.id, active) },
                    )
                }
            }
        }

        Button(
            onClick = {
                pickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            Text("Upload images")
        }
    }
}

private val GrayscaleFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable
private fun ImageGridItem(image: Image, file: File, onToggleActive: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small),
    ) {
        // A solid-color placeholder/error painter, matching the theme's own surface variant —
        // without one, a cell being loaded (or one whose file fails to resolve) briefly reports
        // no content, which can momentarily throw off the surrounding grid row's height/alignment
        // until Coil resolves the real image. Kept subtle rather than an attention-grabbing
        // color, since this is a loading/fallback state, not an error banner.
        val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
        AsyncImage(
            model = file,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = if (image.active) null else GrayscaleFilter,
            placeholder = placeholder,
            error = placeholder,
            modifier = Modifier.fillMaxSize(),
        )

        if (!image.active) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
            )
        }

        Switch(
            checked = image.active,
            onCheckedChange = onToggleActive,
            // [Review][Patch] EXPERIENCE.md#Accessibility Floor requires every interactive
            // element labeled with role + state, explicitly naming this exact toggle — with
            // multiple switches on screen, an unlabeled one is indistinguishable from any other
            // to TalkBack. The image thumbnail itself stays contentDescription = null (decorative
            // — this label already identifies which image the switch belongs to).
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                .semantics { contentDescription = "Toggle active for image ${image.id}" },
        )
    }
}
