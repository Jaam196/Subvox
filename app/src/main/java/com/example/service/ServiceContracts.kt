package com.example.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build

object ServiceContracts {
    const val ACTION_START_CAPTURE = "com.example.action.START_CAPTURE"
    const val ACTION_PAUSE_CAPTURE = "com.example.action.PAUSE_CAPTURE"
    const val ACTION_RESUME_CAPTURE = "com.example.action.RESUME_CAPTURE"
    const val ACTION_STOP_CAPTURE = "com.example.action.STOP_CAPTURE"
    const val ACTION_RECALIBRATE_AUTO_ROI = "com.example.action.RECALIBRATE_AUTO_ROI"
    const val ACTION_TOGGLE_ROI_EDITOR = "com.example.action.TOGGLE_ROI_EDITOR"
    const val ACTION_SHOW_OVERLAY = "com.example.action.SHOW_OVERLAY"
    const val ACTION_HIDE_OVERLAY = "com.example.action.HIDE_OVERLAY"

    // Acciones Fase 1: Repetir última línea y retroceder en el buffer de subtítulos
    const val ACTION_REPEAT_LAST_SUBTITLE = "com.example.action.REPEAT_LAST_SUBTITLE"
    const val ACTION_REPEAT_PREVIOUS_SUBTITLE = "com.example.action.REPEAT_PREVIOUS_SUBTITLE"

    const val EXTRA_RESULT_CODE = "extra_media_projection_result_code"
    const val EXTRA_RESULT_DATA = "extra_media_projection_result_data"

    const val NOTIFICATION_CHANNEL_ID = "animesub_voz_reader_channel"
    const val NOTIFICATION_ID = 4102

    fun startCaptureIntent(context: Context, resultCode: Int, resultData: Intent): Intent {
        return Intent(context, SubtitleReaderService::class.java).apply {
            action = ACTION_START_CAPTURE
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_RESULT_DATA, resultData)
        }
    }

    fun commandIntent(context: Context, action: String): Intent {
        return Intent(context, SubtitleReaderService::class.java).apply {
            this.action = action
        }
    }

    fun dispatchStartCapture(context: Context, resultCode: Int, resultData: Intent) {
        if (resultCode != Activity.RESULT_OK) return
        val intent = startCaptureIntent(context, resultCode, resultData)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun dispatchCommand(context: Context, action: String) {
        val intent = commandIntent(context, action)
        context.startService(intent)
    }
}
