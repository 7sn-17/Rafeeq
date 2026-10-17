package com.rafeeq.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rafeeq.assistant.service.RafeeqBackgroundService
import com.rafeeq.assistant.ui.MainScreen
import com.rafeeq.assistant.ui.MainViewModel
import com.rafeeq.assistant.ui.SettingsScreen
import com.rafeeq.assistant.ui.ThemeMode
import com.rafeeq.assistant.ui.theme.RafeeqTheme

class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        mainViewModel.onAudioPermissionResult(isGranted)
        if (isGranted) startBackgroundServiceIfNeeded()
    }

    private val multiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        result.forEach { (permission, granted) ->
            mainViewModel.onPermissionResult(permission, granted)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lets the chat background and the input bar flow behind the system bars, so the
        // Compose insets (statusBarsPadding / navigationBarsPadding / imePadding) apply.
        enableEdgeToEdge()

        mainViewModel.setPermissionRequester { permission ->
            audioPermissionLauncher.launch(permission)
        }

        // Skills (e.g. the image organiser) request their permissions through the
        // multiple-permission launcher, whose results are routed back to SkillManager.
        mainViewModel.setSkillPermissionRequester { permission ->
            multiPermissionLauncher.launch(arrayOf(permission))
        }

        requestNotificationPermissionIfNeeded()
        startBackgroundServiceIfNeeded()

        setContent {
            val themeMode by mainViewModel.themeMode.collectAsStateWithLifecycle()
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            RafeeqTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showSettings by rememberSaveable { mutableStateOf(false) }

                    if (showSettings) {
                        val ttsEnabled by mainViewModel.ttsEnabled.collectAsStateWithLifecycle()
                        val ttsMuted by mainViewModel.ttsMuted.collectAsStateWithLifecycle()
                        val voices by mainViewModel.ttsVoices.collectAsStateWithLifecycle()
                        val selectedVoice by mainViewModel.selectedVoice.collectAsStateWithLifecycle()

                        SettingsScreen(
                            themeMode = themeMode,
                            ttsEnabled = ttsEnabled,
                            ttsMuted = ttsMuted,
                            voices = voices,
                            selectedVoice = selectedVoice,
                            appVersion = mainViewModel.appVersion,
                            onThemeModeChange = mainViewModel::setThemeMode,
                            onTtsEnabledChange = mainViewModel::setTtsEnabled,
                            onToggleMute = mainViewModel::toggleTtsMuted,
                            onVoiceChange = mainViewModel::selectVoice,
                            onBack = { showSettings = false }
                        )
                    } else {
                        val state by mainViewModel.state.collectAsStateWithLifecycle()
                        val messages by mainViewModel.messages.collectAsStateWithLifecycle()
                        val statusText by mainViewModel.statusText.collectAsStateWithLifecycle()
                        val ttsMuted by mainViewModel.ttsMuted.collectAsStateWithLifecycle()

                        MainScreen(
                            state = state,
                            messages = messages,
                            statusText = statusText,
                            ttsMuted = ttsMuted,
                            onSendText = mainViewModel::sendText,
                            onToggleMic = mainViewModel::toggleRecording,
                            onToggleMute = mainViewModel::toggleTtsMuted,
                            onNewChat = mainViewModel::newChat,
                            onClearHistory = mainViewModel::clearHistory,
                            onOpenSettings = { showSettings = true }
                        )
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            multiPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    private fun startBackgroundServiceIfNeeded() {
        // The service declares foregroundServiceType="microphone", which may only be
        // started while RECORD_AUDIO is granted (Android 14+ throws SecurityException
        // otherwise). It is therefore started here and again once the user grants mic.
        val canRecord = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!canRecord) return

        val intent = Intent(this, RafeeqBackgroundService::class.java).apply {
            action = RafeeqBackgroundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
