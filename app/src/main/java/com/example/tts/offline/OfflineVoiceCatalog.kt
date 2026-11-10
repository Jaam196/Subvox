package com.example.tts.offline

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Metadata oficial de modelos de voz Piper (VITS) compatibles con Sherpa-ONNX.
 *
 * Fuente oficial:
 * Repositorio k2-fsa/sherpa-onnx y releases de tts-models.
 * Modelos verificados con hashes SHA-256 reales y tamaños exactos.
 */
data class OfflineVoiceModelSpec(
    val id: String,
    val displayName: String,
    val localeTag: String,
    val description: String,
    val archiveUrl: String,
    val archiveSizeBytes: Long,
    val sha256Hex: String,
    val modelFileName: String,
    val tokensFileName: String = "tokens.txt",
    val dataDirName: String = "espeak-ng-data"
)

object OfflineVoiceCatalog {

    /**
     * Voz Piper VITS de calidad ALTA (español de México). Recomendada por defecto: el modelo "high"
     * tiene más capacidad que los "medium", así que la entonación suena más natural.
     * Licencia del dataset: Apache-2.0.
     */
    val CLAUDE_HIGH = OfflineVoiceModelSpec(
        id = "vits-piper-es_MX-claude-high",
        displayName = "Español Latino • Claude (Alta calidad)",
        localeTag = "es-MX",
        description = "Calidad alta, entonación natural y buena dicción. Recomendada para anime y diálogos.",
        archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-es_MX-claude-high.tar.bz2",
        archiveSizeBytes = 67207890L,
        sha256Hex = "ec33fb689c248fe64810aab564cba97babf0f506672cfd404928d46e751a4721",
        modelFileName = "es_MX-claude-high.onnx"
    )

    /**
     * Voz Piper VITS de calidad ALTA (español de Argentina). Modelo más grande: mejor calidad pero
     * más lento de sintetizar en móviles modestos. Licencia del dataset: CC BY-SA 4.0 (OpenSLR 61).
     */
    val DANIELA_HIGH = OfflineVoiceModelSpec(
        id = "vits-piper-es_AR-daniela-high",
        displayName = "Español Rioplatense • Daniela (Alta calidad)",
        localeTag = "es-AR",
        description = "Calidad alta con acento rioplatense. Modelo grande: ideal para móviles potentes.",
        archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-es_AR-daniela-high.tar.bz2",
        archiveSizeBytes = 115562134L,
        sha256Hex = "71cbf6b7f646ab74f3c51336151abef41e6c54467ac929ffdb19ed706a07dd7b",
        modelFileName = "es_AR-daniela-high.onnx"
    )

    /**
     * Modelo estrella: Español Femenina Natural (Sharvard - Piper VITS).
     * Voz femenina dulce, cálida y de alta inteligibilidad. La más recomendada para anime.
     */
    val SHARVARD_FEMALE = OfflineVoiceModelSpec(
        id = "vits-piper-es_ES-sharvard-medium",
        displayName = "Español Femenina Dulce (Sharvard • Piper VITS)",
        localeTag = "es-ES",
        description = "Voz femenina dulce, cálida y muy natural sin conexión. La mejor para anime y novelas ligeras.",
        archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-es_ES-sharvard-medium.tar.bz2",
        archiveSizeBytes = 80318184L,
        sha256Hex = "b30a7a83df0518f0ee1c7039506648cade99f1f9b498fc49ed2ced2e2536bb5a",
        modelFileName = "es_ES-sharvard-medium.onnx"
    )

    /**
     * Modelo prioritario: Español Latino Neutro (Ald - Piper VITS).
     * Calidad medium, 22.05 kHz, entrenado para dicción clara y neutral.
     */
    val LATIN_NEUTRAL = OfflineVoiceModelSpec(
        id = "vits-piper-es_MX-ald-medium",
        displayName = "Español Latino Neutro (Ald • Piper VITS)",
        localeTag = "es-MX",
        description = "Voz neutral latinoamericana, clara y fluida. Ideal para anime y diálogos sin conexión.",
        archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-es_MX-ald-medium.tar.bz2",
        archiveSizeBytes = 67196497L,
        sha256Hex = "c60cf7bc853eb01a875fba0756523fecf9d9f46c763b19785d223fc981fb4fb6",
        modelFileName = "es_MX-ald-medium.onnx"
    )

    /**
     * Modelo alternativo: Español Castellano (Davefx - Piper VITS).
     * Calidad medium, 22.05 kHz.
     */
    val SPAIN_CASTILIAN = OfflineVoiceModelSpec(
        id = "vits-piper-es_ES-davefx-medium",
        displayName = "Español Castellano (Davefx • Piper VITS)",
        localeTag = "es-ES",
        description = "Voz de España (castellana), excelente articulación y naturalidad.",
        archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-es_ES-davefx-medium.tar.bz2",
        archiveSizeBytes = 67184952L,
        sha256Hex = "a3f6beb54a9cb893279f72978a22f807a4d9fc9c7848157b524d5cc7b7f58b22",
        modelFileName = "es_ES-davefx-medium.onnx"
    )

    val CANDIDATE_VOICES: List<OfflineVoiceModelSpec> = listOf(
        CLAUDE_HIGH,
        DANIELA_HIGH,
        SHARVARD_FEMALE,
        LATIN_NEUTRAL,
        SPAIN_CASTILIAN
    )

    fun getModelById(id: String?): OfflineVoiceModelSpec {
        if (id.isNullOrBlank()) return CLAUDE_HIGH
        return CANDIDATE_VOICES.firstOrNull { it.id == id } ?: CLAUDE_HIGH
    }

    fun getModelsRootDir(context: Context): File {
        val dir = File(context.filesDir, "sherpa_models")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getModelInstallDir(context: Context, modelId: String): File {
        return File(getModelsRootDir(context), modelId)
    }

    fun getModelFile(context: Context, spec: OfflineVoiceModelSpec): File {
        return File(getModelInstallDir(context, spec.id), spec.modelFileName)
    }

    fun getTokensFile(context: Context, spec: OfflineVoiceModelSpec): File {
        return File(getModelInstallDir(context, spec.id), spec.tokensFileName)
    }

    fun getDataDir(context: Context, spec: OfflineVoiceModelSpec): File {
        return File(getModelInstallDir(context, spec.id), spec.dataDirName)
    }

    // --- Instalación atómica -------------------------------------------------------------------
    // Una voz solo cuenta como instalada si existe el archivo de control ".installed", que el
    // trabajador de descarga escribe al terminar de extraer y validar TODO. Así una descarga
    // cortada o una extracción a medias nunca llega a cargarse en el código nativo de Sherpa
    // (un fallo ahí cierra la app entera y no se puede capturar con try/catch).

    const val INSTALLED_MARKER = ".installed"
    const val LOAD_GUARD = "loading.lock"
    private const val CRASH_STRIKE = "crash.strike"

    fun getMarkerFile(context: Context, modelId: String): File =
        File(getModelInstallDir(context, modelId), INSTALLED_MARKER)

    /** Carpeta temporal donde se extrae el modelo antes de moverlo a su sitio definitivo. */
    fun getStagingDir(context: Context, modelId: String): File =
        File(getModelsRootDir(context), "$modelId.tmp")

    /** Escribe el archivo de control: tamaño del .onnx y hash del paquete descargado. */
    fun writeInstalledMarker(dir: File, spec: OfflineVoiceModelSpec) {
        val modelSize = File(dir, spec.modelFileName).length()
        File(dir, INSTALLED_MARKER).writeText("v1\n$modelSize\n${spec.sha256Hex}\n")
    }

    /**
     * Verifica que el modelo esté completamente instalado e íntegro en disco.
     */
    fun isModelReady(context: Context, spec: OfflineVoiceModelSpec): Boolean {
        val marker = getMarkerFile(context, spec.id)
        if (!marker.isFile) return false
        val lines = runCatching { marker.readLines() }.getOrNull() ?: return false
        if (lines.size < 3 || lines[0] != "v1") return false
        if (!lines[2].equals(spec.sha256Hex, ignoreCase = true)) return false

        val modelFile = getModelFile(context, spec)
        val tokensFile = getTokensFile(context, spec)
        val dataDir = getDataDir(context, spec)

        return modelFile.isFile && modelFile.length() > 10_000_000L &&
                modelFile.length() == lines[1].toLongOrNull() &&
                tokensFile.isFile && tokensFile.length() > 0L &&
                dataDir.isDirectory && (dataDir.listFiles()?.isNotEmpty() == true)
    }

    /** Marca que se va a cargar el modelo en código nativo. Si la app muere, el aviso queda en disco. */
    fun beginLoadGuard(context: Context, modelId: String, stage: String = "carga del modelo") {
        runCatching { File(getModelInstallDir(context, modelId), LOAD_GUARD).writeText(stage) }
    }

    fun endLoadGuard(context: Context, modelId: String) {
        runCatching { File(getModelInstallDir(context, modelId), LOAD_GUARD).delete() }
        runCatching { File(getModelInstallDir(context, modelId), CRASH_STRIKE).takeIf { it.exists() }?.delete() }
    }

    /**
     * Se ejecuta una vez al arrancar el proceso:
     *  - borra instalaciones sin archivo de control (descargas antiguas o a medias);
     *  - si una carga anterior mató la app (queda loading.lock), borra esa voz para no entrar en un
     *    bucle de cierres. Devuelve los ids de las voces retiradas por este motivo.
     */
    fun recoverAtStartup(
        context: Context,
        previousExitWasNativeCrash: Boolean? = null
    ): List<Pair<String, String>> {
        val removedAfterCrash = mutableListOf<Pair<String, String>>()
        val root = getModelsRootDir(context)
        root.listFiles()?.forEach { entry ->
            if (!entry.isDirectory) return@forEach
            when {
                entry.name.endsWith(".tmp") -> Unit // lo gestiona el trabajador de descarga
                File(entry, LOAD_GUARD).exists() -> {
                    val guard = File(entry, LOAD_GUARD)
                    val strike = File(entry, CRASH_STRIKE)
                    val stage = runCatching { guard.readText() }.getOrDefault("").ifBlank { "carga del modelo" }
                    // El aviso también queda si el usuario cierra la app o el sistema la mata mientras
                    // habla. Solo se retira la voz si Android confirma un fallo nativo; si no se sabe
                    // (Android 8-10), hace falta que ocurra dos veces seguidas.
                    val confirmed = when (previousExitWasNativeCrash) {
                        true -> true
                        false -> false
                        null -> strike.exists()
                    }
                    if (confirmed) {
                        entry.deleteRecursively()
                        removedAfterCrash += entry.name to stage
                    } else {
                        runCatching { guard.delete() }
                        if (previousExitWasNativeCrash == null) runCatching { strike.writeText(stage) }
                    }
                }
                !File(entry, INSTALLED_MARKER).exists() -> entry.deleteRecursively()
            }
        }
        return removedAfterCrash
    }

    /**
     * Calcula el hash SHA-256 de un archivo en disco.
     */
    fun verifySha256(file: File, expectedSha256: String): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val computedHex = digest.digest().joinToString("") { "%02x".format(it) }
        return computedHex.equals(expectedSha256, ignoreCase = true)
    }

    /**
     * Elimina todos los archivos del modelo para liberar espacio en disco.
     */
    fun deleteModel(context: Context, modelId: String): Boolean {
        val dir = getModelInstallDir(context, modelId)
        val staging = getStagingDir(context, modelId)
        if (staging.exists()) staging.deleteRecursively()
        return if (dir.exists()) {
            dir.deleteRecursively()
        } else true
    }
}
