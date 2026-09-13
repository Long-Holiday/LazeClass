package com.voiceqa.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.voiceqa.app.ui.history.HistoryScreen
import com.voiceqa.app.ui.history.HistoryViewModel
import com.voiceqa.app.ui.home.HomeScreen
import com.voiceqa.app.ui.home.HomeViewModel
import com.voiceqa.app.ui.settings.SettingsScreen
import com.voiceqa.app.ui.settings.SettingsViewModel

enum class Screen {
    HOME,
    SETTINGS,
    HISTORY
}

class MainActivity : ComponentActivity() {

    private val homeViewModel: HomeViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val historyViewModel: HistoryViewModel by viewModels()
    private var startListeningAfterPermissionGrant = false

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO]
            ?: hasRecordAudioPermission()
        if (recordAudioGranted && startListeningAfterPermissionGrant) {
            startListeningAfterPermissionGrant = false
            homeViewModel.onStartListening()
        } else if (!recordAudioGranted) {
            startListeningAfterPermissionGrant = false
            Toast.makeText(this, "需要麦克风录音权限才能使用语音问答功能", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(
                        homeViewModel = homeViewModel,
                        settingsViewModel = settingsViewModel,
                        historyViewModel = historyViewModel,
                        onStartListening = { startListeningWithPermission() }
                    )
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestAudioPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startListeningWithPermission() {
        if (hasRecordAudioPermission()) {
            homeViewModel.onStartListening()
            return
        }

        startListeningAfterPermissionGrant = true
        requestAudioPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
    }
}

@Composable
fun AppNavigation(
    homeViewModel: HomeViewModel,
    settingsViewModel: SettingsViewModel,
    historyViewModel: HistoryViewModel,
    onStartListening: () -> Unit
) {
    var currentScreen by remember { mutableStateOf(Screen.HOME) }

    Crossfade(targetState = currentScreen, label = "screen_crossfade") { screen ->
        when (screen) {
            Screen.HOME -> HomeScreen(
                viewModel = homeViewModel,
                onNavigateToSettings = { currentScreen = Screen.SETTINGS },
                onNavigateToHistory = { currentScreen = Screen.HISTORY },
                onStartListening = onStartListening
            )
            Screen.SETTINGS -> SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = { currentScreen = Screen.HOME }
            )
            Screen.HISTORY -> HistoryScreen(
                viewModel = historyViewModel,
                onNavigateBack = { currentScreen = Screen.HOME }
            )
        }
    }
}
