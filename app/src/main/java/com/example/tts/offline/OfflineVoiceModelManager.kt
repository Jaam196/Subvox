package com.example.tts.offline

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

sealed class OfflineDownloadState {
    object Idle : OfflineDownloadState()
    data class Downloading(
        val progressPercent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val statusMessage: String
    ) : OfflineDownloadState()
    object Ready : OfflineDownloadState()
    data class Error(val reason: String) : OfflineDownloadState()
}

class OfflineVoiceModelManager(private val context: Context) {

    companion object {
        const val WORK_NAME_PREFIX = "sherpa_voice_model_download_"
        const val TAG_ALL = "sherpa_tts_download"
        private const val TAG_MODEL_PREFIX = "model:"

        private val recovered = AtomicBoolean(false)
        /** Voces retiradas al arrancar porque una carga anterior cerró la app. */
        private val crashedModelIds = MutableStateFlow<Set<String>>(emptySet())

        fun workNameFor(modelId: String) = WORK_NAME_PREFIX + modelId
    }

    private val workManager: WorkManager? = try {
        WorkManager.getInstance(context)
    } catch (_: Throwable) {
        null
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Estado de descarga POR VOZ (clave = id del modelo). Una voz sin entrada no tiene descarga. */
    private val _statesFlow = MutableStateFlow<Map<String, OfflineDownloadState>>(emptyMap())
    val statesFlow: StateFlow<Map<String, OfflineDownloadState>> = _statesFlow.asStateFlow()

    init {
        // Una sola vez por proceso y antes de cargar ningún modelo: limpia instalaciones a medias
        // y retira la voz que provocó un cierre de la app en la carga anterior.
        if (recovered.compareAndSet(false, true)) {
            val removed = OfflineVoiceCatalog.recoverAtStartup(context)
            if (removed.isNotEmpty()) {
                crashedModelIds.value = removed.map { it.first }.toSet()
                com.example.diagnostics.CrashReporter.recordNative(
                    context,
                    removed.joinToString("; ") { "${it.second} (voz ${it.first})" }
                )
            }
            runCatching { WorkManager.getInstance(context).pruneWork() }
        }
        observeWorkManager()
    }

    private fun modelIdOf(info: WorkInfo): String? =
        info.tags.firstOrNull { it.startsWith(TAG_MODEL_PREFIX) }?.removePrefix(TAG_MODEL_PREFIX)

    private fun observeWorkManager() {
        val wm = workManager
        if (wm != null) {
            scope.launch {
                wm.getWorkInfosByTagFlow(TAG_ALL).collect { infos ->
                    val map = mutableMapOf<String, OfflineDownloadState>()
                    infos.forEach { info ->
                        val id = modelIdOf(info) ?: return@forEach
                        val state: OfflineDownloadState? = when (info.state) {
                            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> {
                                val p = info.progress
                                OfflineDownloadState.Downloading(
                                    progressPercent = p.getInt(OfflineModelDownloadWorker.KEY_PROGRESS_PERCENT, 0),
                                    downloadedBytes = p.getLong(OfflineModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L),
                                    totalBytes = p.getLong(OfflineModelDownloadWorker.KEY_TOTAL_BYTES, 0L),
                                    statusMessage = p.getString(OfflineModelDownloadWorker.KEY_STATUS_MESSAGE)
                                        ?: "Descargando voz neuronal..."
                                )
                            }
                            WorkInfo.State.BLOCKED -> OfflineDownloadState.Downloading(
                                0, 0L, 0L, "Esperando conexión adecuada..."
                            )
                            WorkInfo.State.SUCCEEDED -> OfflineDownloadState.Ready
                            WorkInfo.State.FAILED -> OfflineDownloadState.Error(
                                info.outputData.getString(OfflineModelDownloadWorker.KEY_ERROR_REASON)
                                    ?: "Fallo en la descarga del modelo neuronal"
                            )
                            WorkInfo.State.CANCELLED -> null
                        }
                        if (state != null) map[id] = state
                    }
                    _statesFlow.value = withCrashNotices(map)
                }
            }
        }
        scope.launch {
            crashedModelIds.collect { _statesFlow.value = withCrashNotices(_statesFlow.value) }
        }
    }

    private fun withCrashNotices(map: Map<String, OfflineDownloadState>): Map<String, OfflineDownloadState> {
        val crashed = crashedModelIds.value
        if (crashed.isEmpty()) return map
        val out = map.toMutableMap()
        crashed.forEach { id ->
            val current = out[id]
            if (current == null || current is OfflineDownloadState.Ready || current is OfflineDownloadState.Error) {
                out[id] = OfflineDownloadState.Error(
                    "Esta voz cerró la app en tu móvil y se ha quitado. Prueba otra voz (por ejemplo Ald o Davefx, de calidad media) o usa la voz del sistema. Los detalles están en Más → Acerca de."
                )
            }
        }
        return out
    }

    private fun setState(modelId: String, state: OfflineDownloadState?) {
        val map = _statesFlow.value.toMutableMap()
        if (state == null) map.remove(modelId) else map[modelId] = state
        _statesFlow.value = map
    }

    fun isModelInstalled(modelId: String): Boolean {
        val spec = OfflineVoiceCatalog.getModelById(modelId)
        return OfflineVoiceCatalog.isModelReady(context, spec)
    }

    fun isDownloading(modelId: String): Boolean =
        _statesFlow.value[modelId] is OfflineDownloadState.Downloading

    fun enqueueDownload(modelId: String, wifiOnly: Boolean = true) {
        if (isModelInstalled(modelId)) return
        // Si ya se está descargando esta voz, no la reinicies (KEEP lo garantiza también en WorkManager).
        if (isDownloading(modelId)) return
        crashedModelIds.value = crashedModelIds.value - modelId

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<OfflineModelDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(OfflineModelDownloadWorker.KEY_MODEL_ID to modelId)
            )
            .addTag(TAG_ALL)
            .addTag(TAG_MODEL_PREFIX + modelId)
            .build()

        setState(
            modelId,
            OfflineDownloadState.Downloading(0, 0L, 0L, "Preparando descarga...")
        )

        // Un trabajo único POR VOZ: descargar otra voz no cancela ni pisa esta.
        workManager?.enqueueUniqueWork(
            workNameFor(modelId),
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /** Cancela la descarga de UNA voz. */
    fun cancelDownload(modelId: String) {
        workManager?.cancelUniqueWork(workNameFor(modelId))
        OfflineVoiceCatalog.getStagingDir(context, modelId).let { if (it.exists()) it.deleteRecursively() }
        setState(modelId, null)
    }

    fun deleteModel(modelId: String): Boolean {
        cancelDownload(modelId)
        crashedModelIds.value = crashedModelIds.value - modelId
        val success = OfflineVoiceCatalog.deleteModel(context, modelId)
        runCatching { workManager?.pruneWork() }
        setState(modelId, null)
        return success
    }

    fun getInstalledModelSizeBytes(modelId: String): Long {
        val dir = OfflineVoiceCatalog.getModelInstallDir(context, modelId)
        if (!dir.exists()) return 0L
        return getFolderSize(dir)
    }

    private fun getFolderSize(dir: File): Long {
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) getFolderSize(file) else file.length()
        }
        return size
    }
}
