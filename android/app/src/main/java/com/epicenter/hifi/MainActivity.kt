package com.epicenter.hifi

import android.Manifest
import android.content.pm.PackageManager
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.epicenter.hifi.data.repository.MusicRepository
import com.epicenter.hifi.data.repository.FavoritesRepository
import com.epicenter.hifi.engine.AudioEngine
import com.epicenter.hifi.ui.EpicenterApp
import com.epicenter.hifi.viewmodel.DspViewModel
import com.epicenter.hifi.viewmodel.EqViewModel
import com.epicenter.hifi.viewmodel.LibraryViewModel
import com.epicenter.hifi.viewmodel.PlayerViewModel

class MainActivity : ComponentActivity() {

    private lateinit var audioEngine: AudioEngine
    private lateinit var musicRepository: MusicRepository
    private lateinit var favoritesRepository: FavoritesRepository
    private var playbackService: EpicenterPlaybackService? = null
    private var isPlaybackServiceBound = false
    private var playbackServiceReady by mutableStateOf(false)
    private var launchAnimationFinished by mutableStateOf(false)
    private var startupCompleted = false

    private val playbackServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? EpicenterPlaybackService.LocalBinder)?.service ?: return
            playbackService = service
            audioEngine = AudioEngine(applicationContext, service.playbackController)
            playbackServiceReady = true
            completeStartupWhenReady()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            if (::audioEngine.isInitialized) audioEngine.release()
            playbackService = null
        }
    }

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
    ) { _ ->
        val audioGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

        if (audioGranted) {
            libraryViewModel.triggerScan()
        }
    }

    private val importAudioLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        uris.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some document providers return temporary grants only.
            }
        }
        libraryViewModel.importUris(uris) { result ->
            val message = when {
                result.tracks.isEmpty() && result.duplicateCount > 0 -> "Las canciones ya estaban en tu biblioteca"
                result.tracks.size == 1 -> "Canción agregada a tu biblioteca"
                result.tracks.isNotEmpty() -> "${result.tracks.size} canciones agregadas a tu biblioteca"
                else -> "No se pudo importar el archivo de audio"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // La reproducción pertenece al servicio para seguir activa al cerrar
        // la interfaz y conservar sesión/controles de notificación.
        musicRepository = MusicRepository(applicationContext)
        favoritesRepository = FavoritesRepository(applicationContext)
        setContent {
            Box {
                if (playbackServiceReady) {
                    EpicenterApp(
                        playerViewModel = playerViewModel,
                        libraryViewModel = libraryViewModel,
                        favoritesRepository = favoritesRepository,
                        dspViewModel = dspViewModel,
                        eqViewModel = eqViewModel,
                        audioEngine = audioEngine,
                        onImportAudio = { importAudioLauncher.launch(arrayOf("audio/*")) }
                    )
                }
                if (!launchAnimationFinished) {
                    com.epicenter.hifi.ui.NativeLaunchScreen {
                        launchAnimationFinished = true
                        completeStartupWhenReady()
                    }
                }
            }
        }

        startService(Intent(this, EpicenterPlaybackService::class.java))
        isPlaybackServiceBound = bindService(
            Intent(this, EpicenterPlaybackService::class.java).setAction(EpicenterPlaybackService.ACTION_LOCAL_BIND),
            playbackServiceConnection,
            BIND_AUTO_CREATE
        )
    }

    private fun completeStartupWhenReady() {
        if (startupCompleted || !playbackServiceReady || !launchAnimationFinished) return
        startupCompleted = true
        checkAndRequestPermissions()
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
        if (::audioEngine.isInitialized) audioEngine.release()
        if (isPlaybackServiceBound) {
            unbindService(playbackServiceConnection)
            isPlaybackServiceBound = false
        }
        super.onDestroy()
    }
}
