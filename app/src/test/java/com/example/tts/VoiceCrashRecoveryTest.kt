package com.example.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.tts.offline.OfflineVoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Una voz solo debe retirarse si de verdad cerró la app, no por cerrar la app mientras habla. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceCrashRecoveryTest {

    private lateinit var context: Context
    private val id = "voz-de-prueba"

    private fun dir(): File = OfflineVoiceCatalog.getModelInstallDir(context, id)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        OfflineVoiceCatalog.getModelsRootDir(context).deleteRecursively()
        dir().mkdirs()
        File(dir(), OfflineVoiceCatalog.INSTALLED_MARKER).writeText("v1\n1\nabc\n")
        OfflineVoiceCatalog.beginLoadGuard(context, id, "síntesis")
    }

    @Test
    fun keptWhenProcessWasJustKilled() {
        val removed = OfflineVoiceCatalog.recoverAtStartup(context, previousExitWasNativeCrash = false)
        assertTrue(removed.isEmpty())
        assertTrue(dir().isDirectory)
        assertFalse(File(dir(), OfflineVoiceCatalog.LOAD_GUARD).exists())
    }

    @Test
    fun removedWhenNativeCrashConfirmed() {
        val removed = OfflineVoiceCatalog.recoverAtStartup(context, previousExitWasNativeCrash = true)
        assertEquals(listOf(id), removed.map { it.first })
        assertFalse(dir().exists())
    }

    @Test
    fun unknownReasonNeedsTwoStrikes() {
        assertTrue(OfflineVoiceCatalog.recoverAtStartup(context, null).isEmpty())
        assertTrue(dir().isDirectory)
        OfflineVoiceCatalog.beginLoadGuard(context, id, "síntesis")
        assertEquals(1, OfflineVoiceCatalog.recoverAtStartup(context, null).size)
        assertFalse(dir().exists())
    }

    @Test
    fun successfulSynthesisClearsStrike() {
        OfflineVoiceCatalog.recoverAtStartup(context, null)
        OfflineVoiceCatalog.beginLoadGuard(context, id, "síntesis")
        OfflineVoiceCatalog.endLoadGuard(context, id)
        OfflineVoiceCatalog.beginLoadGuard(context, id, "síntesis")
        assertTrue(OfflineVoiceCatalog.recoverAtStartup(context, null).isEmpty())
        assertTrue(dir().isDirectory)
    }
}
