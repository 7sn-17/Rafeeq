package com.example.androidassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.androidassistant.ui.MainScreen
import com.example.androidassistant.ui.MainViewModel

/**
 * Single activity entry point. The activity is only responsible for wiring the runtime
 * permission launcher to [MainViewModel]; every other decision lives in the ViewModel/skills.
 */
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ActivityResultLauncher registration must happen before the activity is STARTED.
        val audioPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            mainViewModel.onAudioPermissionResult(isGranted)
        }

        // VoiceRecognitionManager owns the permission policy; it only needs a way to actually
        // launch the system permission dialog, which can only be done by the activity.
        mainViewModel.setAudioPermissionRequester { permission ->
            audioPermissionLauncher.launch(permission)
        }

        setContent {
            MaterialTheme {
                val state by mainViewModel.state.collectAsStateWithLifecycle()
                MainScreen(
                    state = state,
                    onListenClick = mainViewModel::startListening
                )
            }
        }
    }
}
