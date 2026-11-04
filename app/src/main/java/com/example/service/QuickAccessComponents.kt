package com.example.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.domain.model.ServiceStatus

/**
 * Función 10a: Quick Settings Tile nativo en el panel de ajustes rápidos de Android.
 * Permite Pausar / Reanudar el lector si está activo, o abrir la app para iniciar MediaProjection
 * cuando el servicio está inactivo.
 */
class SubtitleQuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTileState()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val state = ReaderStateHolder.state.value
        when (state.serviceStatus) {
            ServiceStatus.ACTIVE_SCANNING -> {
                ServiceContracts.dispatchCommand(applicationContext, ServiceContracts.ACTION_PAUSE_CAPTURE)
            }
            ServiceStatus.PAUSED -> {
                ServiceContracts.dispatchCommand(applicationContext, ServiceContracts.ACTION_RESUME_CAPTURE)
            }
            ServiceStatus.INACTIVE -> {
                val launchIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pi = PendingIntent.getActivity(
                        this,
                        401,
                        launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    runCatching { startActivityAndCollapse(pi) }
                } else {
                    @Suppress("DEPRECATION")
                    runCatching { startActivityAndCollapse(launchIntent) }
                }
            }
        }
        syncTileState()
    }

    private fun syncTileState() {
        val tile = qsTile ?: return
        val readerState = ReaderStateHolder.state.value
        when (readerState.serviceStatus) {
            ServiceStatus.ACTIVE_SCANNING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "AnimeSub: Activo"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Toca para pausar"
                }
            }
            ServiceStatus.PAUSED -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "AnimeSub: En pausa"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Toca para reanudar"
                }
            }
            ServiceStatus.INACTIVE -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "comsamux"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Abrir lector"
                }
            }
        }
        runCatching { tile.updateTile() }
    }

    companion object {
        fun requestListeningUpdate(context: Context) {
            runCatching {
                requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, SubtitleQuickTileService::class.java)
                )
            }
        }
    }
}

/**
 * Función 10b: Widget de pantalla de inicio (AppWidgetProvider) con botones directos para:
 * - Play / Pausa (o abrir la app si está inactivo)
 * - Repetir última línea (🔁)
 * - Detener lector (⏹)
 */
class SubtitleReaderWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateSingleWidget(context, appWidgetManager, widgetId)
        }
    }

    companion object {
        fun notifyWidgetsChanged(context: Context) {
            runCatching {
                val appContext = context.applicationContext
                val manager = AppWidgetManager.getInstance(appContext)
                val ids = manager.getAppWidgetIds(
                    ComponentName(appContext, SubtitleReaderWidgetProvider::class.java)
                )
                for (id in ids) {
                    updateSingleWidget(appContext, manager, id)
                }
            }
        }

        private fun updateSingleWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val state = ReaderStateHolder.state.value
            val views = RemoteViews(context.packageName, R.layout.widget_subtitle_reader)

            val statusLabel = when (state.serviceStatus) {
                ServiceStatus.ACTIVE_SCANNING -> "● Lector Activo (${state.subtitlesReadCount})"
                ServiceStatus.PAUSED -> "⏸ Lector en Pausa"
                ServiceStatus.INACTIVE -> "○ Inactivo • Toca para abrir"
            }
            val subPreview = state.lastDetectedSubtitle.ifBlank {
                "Listo para leer subtítulos de anime en voz alta"
            }

            views.setTextViewText(R.id.widget_status_title, statusLabel)
            views.setTextViewText(R.id.widget_subtitle_preview, subPreview)
            views.setTextViewText(
                R.id.widget_btn_play_pause,
                when (state.serviceStatus) {
                    ServiceStatus.ACTIVE_SCANNING -> "⏸ Pausar"
                    ServiceStatus.PAUSED -> "▶ Reanudar"
                    ServiceStatus.INACTIVE -> "▶ Abrir"
                }
            )

            // Abrir app al tocar el contenedor
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openAppPi = PendingIntent.getActivity(
                context,
                500,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root_container, openAppPi)

            // Play/Pausa o Abrir si inactivo
            val playPausePi = if (state.serviceStatus == ServiceStatus.INACTIVE) {
                openAppPi
            } else {
                val toggleAction = if (state.serviceStatus == ServiceStatus.PAUSED) {
                    ServiceContracts.ACTION_RESUME_CAPTURE
                } else {
                    ServiceContracts.ACTION_PAUSE_CAPTURE
                }
                PendingIntent.getService(
                    context,
                    501,
                    ServiceContracts.commandIntent(context, toggleAction),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            views.setOnClickPendingIntent(R.id.widget_btn_play_pause, playPausePi)

            // Repetir última línea
            val repeatPi = PendingIntent.getService(
                context,
                502,
                ServiceContracts.commandIntent(context, ServiceContracts.ACTION_REPEAT_LAST_SUBTITLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_repeat, repeatPi)

            // Detener lector
            val stopPi = PendingIntent.getService(
                context,
                503,
                ServiceContracts.commandIntent(context, ServiceContracts.ACTION_STOP_CAPTURE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_stop, stopPi)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
