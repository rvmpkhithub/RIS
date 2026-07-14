package com.ris.imagedistributor.ui.images

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ris.imagedistributor.data.local.Image
import com.ris.imagedistributor.ui.theme.GoldBorder

/**
 * DESIGN.md#Components (image-list-item) — titled row, no thumbnail: title (or "Untitled") on
 * the left, a "View" button and the active/inactive toggle on the right.
 * EXPERIENCE.md#State Patterns — empty-library message.
 */
@Composable
fun ImageLibraryScreen(viewModel: ImageLibraryViewModel, onViewImage: (Long) -> Unit, onImageUploaded: (Image) -> Unit) {
    val images by viewModel.images.collectAsState()

    // Single-select contract, not PickMultipleVisualMedia — uploading is one image at a time,
    // each immediately followed by a trip to the tagging screen (onImageUploaded).
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> viewModel.onImagePicked(uri) { image -> if (image != null) onImageUploaded(image) } },
    )

    // [Review][Patch] the list and the Upload button are now Column siblings (list weighted to
    // fill remaining space) instead of both being absolutely positioned inside one Box — the
    // previous version let the always-visible, bottom-pinned Button render on top of the grid's
    // last row with no space reserved for it, obscuring content once the list filled the screen.
    // Same Column+weight(1f) fix already established by this app's Dashboard screen.
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
                        text = "No images yet — upload one to get started.",
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(images, key = { it.id }) { image ->
                    ImageListItem(
                        image = image,
                        onToggleActive = { active -> viewModel.onToggleActive(image.id, active) },
                        onView = { onViewImage(image.id) },
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

@Composable
private fun ImageListItem(image: Image, onToggleActive: (Boolean) -> Unit, onView: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val displayTitle = image.title?.takeIf { it.isNotBlank() } ?: "Untitled"
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.titleMedium,
                color = if (image.active) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onView,
                // [Review][Patch] a bare "View" is indistinguishable across several "Untitled"
                // rows for a screen reader — name which image this button opens.
                modifier = Modifier.semantics { contentDescription = "View $displayTitle" },
            ) {
                Text("View")
            }
            Switch(
                checked = image.active,
                onCheckedChange = onToggleActive,
                // [Review][Patch] EXPERIENCE.md#Accessibility Floor requires every interactive
                // element labeled with role + state, explicitly naming this exact toggle — with
                // multiple switches on screen, an unlabeled one is indistinguishable from any
                // other to TalkBack.
                modifier = Modifier.semantics { contentDescription = "Toggle active for image ${image.id}" },
            )
        }
    }
}
