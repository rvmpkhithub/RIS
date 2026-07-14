package com.ris.imagedistributor.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ris.imagedistributor.di.AppContainer
import com.ris.imagedistributor.ui.compliance.ComplianceHaltScreen
import com.ris.imagedistributor.ui.dashboard.DashboardScreen
import com.ris.imagedistributor.ui.images.ImagesTab
import com.ris.imagedistributor.ui.receivers.ReceiversTab
import com.ris.imagedistributor.ui.settings.SettingsScreen
import com.ris.imagedistributor.ui.setup.SetupScreen
import com.ris.imagedistributor.ui.setup.SetupViewModel
import com.ris.imagedistributor.ui.theme.appBackgroundBrush

/**
 * Launch routing (Story 1.1 Task 8) — decision logic lives in AppRouter.kt (unit-testable);
 * this composable only wires it up and renders. Route survives rotation via rememberSaveable;
 * the effect skips re-deriving (and re-hitting the network) if a route was already restored.
 * [EXPERIENCE.md#Information Architecture]
 */
@Composable
fun ImageDropApp(container: AppContainer) {
    var route by rememberSaveable(stateSaver = AppRouteSaver) { mutableStateOf<AppRoute>(AppRoute.Loading) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (route != AppRoute.Loading) return@LaunchedEffect // restored from rotation — don't re-derive
        route = determineRoute(container.complianceRepository, container.complianceGate)
    }

    // Single top-level background so every route paints consistently (Loading/Setup previously
    // fell back to the Activity's XML window background, flashing the wrong color before Compose
    // drew). A subtle warm radial gradient instead of a flat fill — DESIGN.md#Elevation & Depth.
    Box(modifier = Modifier.fillMaxSize().background(appBackgroundBrush())) {
        when (route) {
            AppRoute.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            AppRoute.Setup -> {
                val setupViewModel: SetupViewModel = viewModel(factory = SetupViewModel.factory(container))
                SetupScreen(
                    viewModel = setupViewModel,
                    onProceedToMainApp = {
                        requestIgnoreBatteryOptimizationsOnce(context)
                        route = AppRoute.MainApp
                    },
                    onProceedToComplianceHalt = { route = AppRoute.ComplianceHalt },
                )
            }
            AppRoute.MainApp -> MainAppPlaceholder(container)
            AppRoute.ComplianceHalt -> ComplianceHaltScreen()
        }
    }
}

/**
 * Fires once, right after Setup completes — [AD-4] requests the "ignore battery optimizations"
 * exemption so WorkManager's periodic SendWorker (Story 2.2) isn't delayed/throttled by Doze.
 * No result handling: if the operator declines, there's nothing else to do about it, same
 * "operator's own responsibility" shape as e.g. phone-number correctness elsewhere in this app.
 */
private fun requestIgnoreBatteryOptimizationsOnce(context: Context) {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
    if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) return
    val intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Some OEM ROMs don't resolve this system intent — nothing else to do about it.
    }
}

/** Bottom-nav shell — Images/Receivers/Dashboard/Settings all have real content as of Story 3.2. */
@Composable
private fun MainAppPlaceholder(container: AppContainer) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Images", "Receivers", "Dashboard", "Settings")

    Scaffold(
        containerColor = Color.Transparent, // let the gradient background behind show through
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                tabs.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { },
                        label = { Text(label) },
                    )
                }
            }
        }
    ) { padding ->
        when (selectedTab) {
            0 -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    ImagesTab(container = container)
                }
            }
            1 -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    ReceiversTab(container = container)
                }
            }
            2 -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    DashboardScreen(container = container)
                }
            }
            else -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    SettingsScreen(container = container)
                }
            }
        }
    }
}
