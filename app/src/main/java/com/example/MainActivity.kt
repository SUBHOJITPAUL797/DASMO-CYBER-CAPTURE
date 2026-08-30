package com.example

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.CyberDashboardScreen
import com.example.ui.screens.CyberSettingsScreen
import com.example.ui.theme.CyberBlack
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.CyberCaptureViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val navController = rememberNavController()
                val viewModel: CyberCaptureViewModel = viewModel()
                val config by viewModel.config.collectAsState()
                val pairedDevices by viewModel.pairedDevices.collectAsState()

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                    containerColor = CyberBlack
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "dashboard",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("dashboard") {
                            CyberDashboardScreen(
                                viewModel = viewModel,
                                onNavigateToSettings = {
                                    navController.navigate("settings")
                                }
                            )
                        }

                        composable("settings") {
                            CyberSettingsScreen(
                                config = config,
                                pairedDevices = pairedDevices,
                                onResolutionChanged = { viewModel.setResolution(it) },
                                onToggleMirror = { viewModel.toggleMirror() },
                                onToggleGrid = { viewModel.toggleGrid() },
                                onAddDevice = { name, ip -> viewModel.addPairedDevice(name, ip) },
                                onRemoveDevice = { viewModel.removePairedDevice(it) },
                                onCheckUpdatesClick = { viewModel.checkAppUpdates(isManual = true) },
                                onBackClick = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}

