package com.ris.imagedistributor.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ris.imagedistributor.ui.theme.GoldBorder

/**
 * DESIGN.md#Components, #Colors — {colors.primary} on Continue; form sits on a gold-bordered
 * "paper card" (DESIGN.md#Elevation & Depth, revised) echoing the keepsake's card treatment.
 * EXPERIENCE.md#Key Flows Flow 1 — one-time, permanent choice, no edit path after Continue.
 */
@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    onProceedToMainApp: () -> Unit,
    onProceedToComplianceHalt: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val navEvent by viewModel.navEvent.collectAsState()

    LaunchedEffect(navEvent) {
        when (navEvent) {
            SetupNavEvent.ToMainApp -> onProceedToMainApp()
            SetupNavEvent.ToComplianceHalt -> onProceedToComplianceHalt()
            null -> Unit
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(text = "Set up", style = MaterialTheme.typography.headlineSmall)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(1.5.dp, GoldBorder),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = uiState.nickname,
                    onValueChange = viewModel::onNicknameChange,
                    label = { Text("First name / nickname") },
                    enabled = !uiState.submitting,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = uiState.city,
                    onValueChange = viewModel::onCityChange,
                    label = { Text("City") },
                    enabled = !uiState.submitting,
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = viewModel::onContinue,
                    enabled = uiState.canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (uiState.submitting) "Please wait…" else "Continue")
                }

                uiState.error?.let { message ->
                    Text(text = message, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
