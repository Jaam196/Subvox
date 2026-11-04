package com.example

import android.app.Application
import com.example.diagnostics.CrashReporter

class SubVoxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        // Pregunta a Android por qué murió la vez anterior (cierres nativos / falta de memoria)
        Thread { CrashReporter.recordPreviousProcessExit(this) }.start()
    }
}
