package com.epicenter.hifi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.epicenter.hifi.data.repository.MusicRepository
import com.epicenter.hifi.engine.AudioEngine
import com.epicenter.hifi.ui.EpicenterApp
import com.epicenter.hifi.viewmodel.DspViewModel
import com.epicenter.hifi.viewmodel.EqViewModel
import com.epicenter.hifi.viewmodel.LibraryViewModel
import com.epicenter.hifi.viewmodel.PlayerViewModel

class MainActivity : ComponentActivity() {

    private lateinit var audioEngine: AudioEngine
    private lateinit var musicRepository: MusicRepository

    private val playerViewModel: PlayerViewModel by viewModels {
        PlayerViewModel.Factory(audioEngine)
    }

    private val libraryViewModel: LibraryViewModel by viewModels {
        LibraryViewModel.Factory(musicRepository, audioEngine)
    }

    private val dspViewModel: DspViewModel by viewModels {
        DspViewModel.Factory(audioEngine)
    }

    private val eqViewModel: EqViewModel by viewModels {
        EqViewModel.Factory(audioEngine)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.READ_MEDIA_AUDIO] == true
        } else {
            permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        }

        if (audioGranted) {
            libraryViewModel.triggerScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inicializar repositorios y motor nativo
        musicRepository = MusicRepository(applicationContext)
        audioEngine = AudioEngine(applicationContext)

        // Comprobar y solicitar permisos si es necesario
        checkAndRequestPermissions()

        setContent {
            EpicenterApp(
                playerViewModel = playerViewModel,
                libraryViewModel = libraryViewModel,
                dspViewModel = dspViewModel,
                eqViewModel = eqViewModel
            )
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            // Permisos ya concedidos, sincronizar biblioteca
            libraryViewModel.triggerScan()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioEngine.release()
    }
}
