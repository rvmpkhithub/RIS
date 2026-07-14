package com.ris.imagedistributor.ui.images

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ris.imagedistributor.data.local.Image

/**
 * DESIGN.md#Components (image-detail-view) — full-bleed image (no side padding, unlike the rest
 * of this screen), title/description as editable fields below it, the active/inactive toggle, and
 * a back action (an icon overlaid on the image itself, not a full app bar — keeps this screen from
 * reading as a form). Deliberately no gold-bordered card around the fields either — "this screen
 * exists to look at the photo, not to feel like a form."
 */
@Composable
fun ImageDetailScreen(
    viewModel: ImageLibraryViewModel,
    imageId: Long,
    existing: Image?,
    stillLoading: Boolean,
    onDone: () -> Unit,
) {
    if (stillLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (existing == null) {
        // Image was deleted (or never existed) — nothing to view, bail back to the list rather
        // than rendering a broken detail screen.
        LaunchedEffect(Unit) { onDone() }
        return
    }

    var title by remember { mutableStateOf(existing.title ?: "") }
    var description by remember { mutableStateOf(existing.description ?: "") }
    var active by remember { mutableStateOf(existing.active) }
    var saveError by remember { mutableStateOf<String?>(null) }

    val isSaving by viewModel.isSaving.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // ContentScale.FillWidth (not Fit + a fixed height) is what actually makes this
            // edge-to-edge for a real portrait phone photo — Fit inside a fixed-height box
            // letterboxes a tall image down to a narrow strip with empty space on both sides,
            // which is not full-bleed no matter how wide the containing Box is.
            AsyncImage(
                model = viewModel.resolveFile(existing),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = onDone,
                // A plain white-on-image label disappears against light photo content (e.g. sky,
                // a bright background) — a scrim behind the button guarantees contrast regardless
                // of what's underneath, instead of assuming every photo is dark up top.
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(color = Color.Black.copy(alpha = 0.45f), shape = MaterialTheme.shapes.small),
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
            ) {
                Text("‹ Back")
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                singleLine = false,
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Active", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = active,
                    onCheckedChange = { newActive ->
                        // Optimistic toggle with rollback — unlike the list row's Switch (which
                        // stays bound to the live images StateFlow and self-corrects on failure),
                        // this screen holds `active` in local state, so a failed setActive must be
                        // reverted explicitly or the switch would drift from the persisted value.
                        val previous = active
                        active = newActive
                        viewModel.onToggleActive(imageId, newActive) { success ->
                            if (!success) active = previous
                        }
                    },
                    modifier = Modifier.semantics { contentDescription = "Toggle active for image $imageId" },
                )
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving,
                onClick = {
                    saveError = null
                    viewModel.updateImageDetails(imageId, title, description) { success ->
                        if (success) onDone() else saveError = "Couldn't save — please try again."
                    }
                },
            ) {
                Text("Save")
            }

            saveError?.let { message ->
                Text(text = message, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
