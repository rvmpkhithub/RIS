package com.ris.imagedistributor.ui.compliance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private const val HALT_MESSAGE = "Not compliant. Contact admin."
private const val HALT_BODY = "This install can't be used right now. Contact the person who set up this app for you."

/**
 * DESIGN.md#Components (compliance-halt-screen) — surface-variant background, no red, no
 * warning icon, no retry/dismiss button. Deliberately styled as a calm gate, not an error state,
 * so the operator doesn't think they broke something. [DESIGN.md#Do's and Don'ts]
 *
 * EXPERIENCE.md#Accessibility Floor — full message announced automatically on screen entry;
 * this is the one screen where the operator has no other context to rely on.
 */
@Composable
fun ComplianceHaltScreen() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Assertive
                    contentDescription = "$HALT_MESSAGE $HALT_BODY"
                },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = HALT_MESSAGE,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = HALT_BODY,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
