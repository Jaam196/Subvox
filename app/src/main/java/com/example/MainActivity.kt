package com.example

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.setValue
import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.domain.model.ServiceStatus
import com.example.profiles.ForegroundAppDetector
import com.example.ui.MainViewModel
import com.example.ui.PlatformActions
import com.example.ui.SubVoxApp
import com.example.ui.theme.SubVoxTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        initNotificationChannel()

        setContent {
            val themeSettings by viewModel.settingsState.collectAsStateWithLifecycle()
            SubVoxTheme(
                themeId = themeSettings.selectedThemeId,
                animationsEnabled = themeSettings.themeAnimationsEnabled
            ) {
                var hasOverlayPerm by remember { mutableStateOf(checkOverlayPermission()) }
                var hasNotificationPerm by remember { mutableStateOf(checkNotificationPermission()) }
                var hasUsageStatsPerm by remember {
                    mutableStateOf(ForegroundAppDetector.hasUsageStatsPermission(this@MainActivity))
                }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            hasOverlayPerm = checkOverlayPermission()
                            hasNotificationPerm = checkNotificationPermission()
                            hasUsageStatsPerm =
                                ForegroundAppDetector.hasUsageStatsPermission(this@MainActivity)
                            viewModel.refreshTranslationModels()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val mediaProjectionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    val data = result.data
                    if (result.resultCode == Activity.RESULT_OK && data != null) {
                        viewModel.startCaptureWithPermissionResult(result.resultCode, data)
                    }
                }

                val notificationSettingsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) {
                    hasNotificationPerm = checkNotificationPermission()
                }

                var pendingStartAfterNotification by remember { mutableStateOf(false) }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    hasNotificationPerm = granted || checkNotificationPermission()
                    if (pendingStartAfterNotification) {
                        pendingStartAfterNotification = false
                        val captureIntent = projectionManager.createScreenCaptureIntent()
                        mediaProjectionLauncher.launch(captureIntent)
                    } else if (!granted && !checkNotificationPermission()) {
                        openNotificationSettings(notificationSettingsLauncher::launch)
                    }
                }

                val overlaySettingsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) {
                    hasOverlayPerm = checkOverlayPermission()
                }

                val usageStatsSettingsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) {
                    hasUsageStatsPerm =
                        ForegroundAppDetector.hasUsageStatsPermission(this@MainActivity)
                }

                val platform = PlatformActions(
                    hasOverlayPermission = hasOverlayPerm,
                    hasNotificationPermission = hasNotificationPerm,
                    hasUsageStatsPermission = hasUsageStatsPerm,
                    onStartReader = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !checkNotificationPermission()) {
                            pendingStartAfterNotification = true
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                        }
                    },
                    onRequestOverlayPermission = {
                        openOverlayPermissionSettings(overlaySettingsLauncher::launch)
                    },
                    onRequestNotificationPermission = {
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                        val systemEnabled = nm?.areNotificationsEnabled() ?: true
                        if (!systemEnabled) {
                            openNotificationSettings(notificationSettingsLauncher::launch)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            openNotificationSettings(notificationSettingsLauncher::launch)
                        }
                    },
                    onRequestUsageStats = {
                        openUsageAccessSettings(usageStatsSettingsLauncher::launch)
                    },
                    onInstallTtsData = { openInstallTtsData() },
                    onOpenSystemTtsSettings = { openSystemTtsSettings() }
                )

                SubVoxApp(viewModel = viewModel, platform = platform)
            }
        }
    }

    /**
     * Función 10c: Atajo opcional con botones físicos de volumen mientras la app está abierta,
     * sin solicitar AccessibilityService.
     * - Volumen Arriba: repetir última línea leída.
     * - Volumen Abajo: pausar / reanudar el lector si está en ejecución.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val currentSettings = viewModel.settingsState.value
        if (currentSettings.volumeButtonsShortcutEnabled) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    viewModel.repeatLastSubtitle()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    val status = viewModel.readerState.value.serviceStatus
                    if (status == ServiceStatus.ACTIVE_SCANNING) {
                        viewModel.pauseReader()
                        return true
                    } else if (status == ServiceStatus.PAUSED) {
                        viewModel.resumeReader()
                        return true
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun initNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                com.example.service.ServiceContracts.NOTIFICATION_CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun checkNotificationPermission(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val areEnabled = nm?.areNotificationsEnabled() ?: true
        if (!areEnabled) return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun openNotificationSettings(launch: (Intent) -> Unit) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        }
        runCatching { launch(intent) }
    }

    private fun openOverlayPermissionSettings(launch: (Intent) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            runCatching { launch(intent) }
        }
    }

    private fun openUsageAccessSettings(launch: (Intent) -> Unit) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        runCatching { launch(intent) }
    }

    private fun openInstallTtsData() {
        runCatching {
            val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }.onFailure {
            openSystemTtsSettings()
        }
    }

    private fun openSystemTtsSettings() {
        runCatching {
            val intent = Intent("com.android.settings.TTS_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
    }
}
