package com.rafeeq.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rafeeq.assistant.service.RafeeqBackgroundService
import com.rafeeq.assistant.ui.MainScreen
import com.rafeeq.assistant.ui.MainViewModel

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
            MaterialTheme {
                val state by mainViewModel.state.collectAsStateWithLifecycle()
                val messages by mainViewModel.messages.collectAsStateWithLifecycle()
                val errorMessage by mainViewModel.errorMessage.collectAsStateWithLifecycle()
                MainScreen(
                    state = state,
                    messages = messages,
                    errorMessage = errorMessage,
                    onListenClick = mainViewModel::startListening
                )
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
