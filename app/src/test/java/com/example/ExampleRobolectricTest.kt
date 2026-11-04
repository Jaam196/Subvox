package com.example

import android.content.Context
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.example.capture.RoiDetector
import com.example.domain.model.OcrBlockCandidate
import com.example.domain.model.OcrLineItem
import com.example.domain.model.ServiceStatus
import com.example.service.ReaderStateHolder
import com.example.service.ServiceContracts
import com.example.service.SubtitleReaderService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Before
    fun resetState() {
        ReaderStateHolder.resetSessionStats()
    }

    @Test
    fun `el nombre de la aplicacion es SubVox`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("SubVox", context.getString(R.string.app_name))
    }

    @Test
    fun `ciclo de vida del servicio pausa reanudacion cambio de pagina y parada`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = Robolectric.buildService(SubtitleReaderService::class.java).create()
        val service = controller.get()

        // Simular estado activo
        ReaderStateHolder.updateServiceStatus(ServiceStatus.ACTIVE_SCANNING)
        ReaderStateHolder.onSubtitleSpoken(
            text = "¡Cuidado con el ataque enemigo!",
            normalizedKey = "cuidado con el ataque enemigo",
            timestampMs = 5_000L
        )
        assertEquals(ServiceStatus.ACTIVE_SCANNING, ReaderStateHolder.state.value.serviceStatus)
        assertEquals(1, ReaderStateHolder.state.value.subtitlesReadCount)

        // Pausar servicio
        service.onStartCommand(
            ServiceContracts.commandIntent(context, ServiceContracts.ACTION_PAUSE_CAPTURE),
            0,
            1
        )
        assertEquals(ServiceStatus.PAUSED, ReaderStateHolder.state.value.serviceStatus)

        // Reanudar servicio (p. ej. tras cambiar de pestaña/episodio en el navegador)
        service.onStartCommand(
            ServiceContracts.commandIntent(context, ServiceContracts.ACTION_RESUME_CAPTURE),
            0,
            2
        )
        assertEquals(ServiceStatus.ACTIVE_SCANNING, ReaderStateHolder.state.value.serviceStatus)

        // Recalibrar ROI automática al cambiar de reproductor/orientación
        service.onStartCommand(
            ServiceContracts.commandIntent(context, ServiceContracts.ACTION_RECALIBRATE_AUTO_ROI),
            0,
            3
        )
        assertEquals(false, ReaderStateHolder.state.value.isAutoRoiLocked)

        // Detener servicio
        service.onStartCommand(
            ServiceContracts.commandIntent(context, ServiceContracts.ACTION_STOP_CAPTURE),
            0,
            4
        )
        assertEquals(ServiceStatus.INACTIVE, ReaderStateHolder.state.value.serviceStatus)
        controller.destroy()
    }

    @Test
    fun `deteccion automatica de ROI ignora barra superior y selecciona subtitulo inferior en horizontal y vertical`() {
        // Simulamos pantalla horizontal 1920x1080 con ruido en la parte superior y subtítulo de 2 líneas abajo
        val blocks = listOf(
            OcrBlockCandidate(
                text = "10:24 / 23:55 1080p",
                boundingBox = Rect(50, 980, 320, 1020),
                lines = listOf(OcrLineItem("10:24 / 23:55 1080p", Rect(50, 980, 320, 1020), 0.9f))
            ),
            OcrBlockCandidate(
                text = "Episodio 12 - El despertar",
                boundingBox = Rect(80, 40, 620, 95),
                lines = listOf(OcrLineItem("Episodio 12 - El despertar", Rect(80, 40, 620, 95), 0.9f))
            ),
            OcrBlockCandidate(
                text = "¡No permitiré que destruyas esta ciudad!\n¡Te detendré aquí mismo!",
                boundingBox = Rect(420, 830, 1500, 955),
                lines = listOf(
                    OcrLineItem("¡No permitiré que destruyas esta ciudad!", Rect(440, 830, 1480, 888), 0.98f),
                    OcrLineItem("¡Te detendré aquí mismo!", Rect(620, 895, 1300, 955), 0.97f)
                )
            )
        )

        val detection = RoiDetector.detectSubtitleRoiFromFullFrame(
            blocks = blocks,
            frameWidth = 1920,
            frameHeight = 1080
        )

        assertNotNull(detection.detectedRoi)
        assertTrue(detection.bestSubtitleText.contains("¡No permitiré que destruyas esta ciudad!"))
        assertTrue(detection.bestSubtitleText.contains("¡Te detendré aquí mismo!"))
        assertTrue(detection.detectedRoi!!.top >= 0.65f)
    }
}
