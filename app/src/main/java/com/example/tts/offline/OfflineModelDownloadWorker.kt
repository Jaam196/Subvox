package com.example.tts.offline

import android.content.Context
import android.os.StatFs
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Worker en segundo plano para descarga segura, verificación y extracción de modelos
 * de voz neuronales Piper/VITS para Sherpa-ONNX.
 */
class OfflineModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_STATUS_MESSAGE = "status_message"
        const val KEY_ERROR_REASON = "error_reason"

        private const val MIN_REQUIRED_FREE_SPACE_BYTES = 220L * 1024L * 1024L // 220 MB
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: OfflineVoiceCatalog.LATIN_NEUTRAL.id
        val spec = OfflineVoiceCatalog.getModelById(modelId)

        // 1. Verificación de almacenamiento disponible
        val statFs = StatFs(applicationContext.filesDir.absolutePath)
        val availableBytes = statFs.availableBytes
        if (availableBytes < MIN_REQUIRED_FREE_SPACE_BYTES) {
            val neededMb = MIN_REQUIRED_FREE_SPACE_BYTES / (1024 * 1024)
            val freeMb = availableBytes / (1024 * 1024)
            return@withContext Result.failure(
                workDataOf(
                    KEY_ERROR_REASON to "Almacenamiento insuficiente: quedan ${freeMb}MB, se requieren ${neededMb}MB"
                )
            )
        }

        val tempArchiveFile = File(applicationContext.cacheDir, "${spec.id}_download.tar.bz2")
        val targetInstallDir = OfflineVoiceCatalog.getModelInstallDir(applicationContext, spec.id)
        val stagingDir = OfflineVoiceCatalog.getStagingDir(applicationContext, spec.id)

        try {
            updateProgress(0, 0L, spec.archiveSizeBytes, "Iniciando descarga de ${spec.displayName}...")

            val request = Request.Builder()
                .url(spec.archiveUrl)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return@withContext Result.failure(
                    workDataOf(KEY_ERROR_REASON to "Error del servidor HTTP ${response.code}")
                )
            }

            val body = response.body ?: return@withContext Result.failure(
                workDataOf(KEY_ERROR_REASON to "Respuesta vacía del servidor")
            )

            val contentLength = if (body.contentLength() > 0L) body.contentLength() else spec.archiveSizeBytes
            val digest = MessageDigest.getInstance("SHA-256")

            var downloadedBytes = 0L
            var lastReportedPercent = -1

            FileOutputStream(tempArchiveFile).use { fos ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (isStopped) {
                            tempArchiveFile.delete()
                            return@withContext Result.failure(
                                workDataOf(KEY_ERROR_REASON to "Descarga interrumpida")
                            )
                        }
                        fos.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val percent = if (contentLength > 0L) {
                            ((downloadedBytes.toDouble() / contentLength) * 90).toInt().coerceIn(0, 90)
                        } else 0

                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent
                            updateProgress(
                                percent,
                                downloadedBytes,
                                contentLength,
                                "Descargando voz neuronal: $percent%"
                            )
                        }
                    }
                }
            }

            // 2. Verificación criptográfica SHA-256
            updateProgress(91, downloadedBytes, contentLength, "Verificando integridad del modelo...")
            val computedHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (!computedHash.equals(spec.sha256Hex, ignoreCase = true)) {
                tempArchiveFile.delete()
                return@withContext Result.failure(
                    workDataOf(KEY_ERROR_REASON to "Archivo corrupto (falló verificación SHA-256)")
                )
            }

            // 3. Extracción en una carpeta temporal (nunca directamente en la definitiva)
            updateProgress(94, downloadedBytes, contentLength, "Extrayendo modelo neuronal...")
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            stagingDir.mkdirs()

            extractTarBz2(tempArchiveFile, stagingDir)
            tempArchiveFile.delete()

            // 4. Validar lo extraído y escribir el archivo de control
            val modelFile = File(stagingDir, spec.modelFileName)
            val tokensFile = File(stagingDir, spec.tokensFileName)
            val dataDir = File(stagingDir, spec.dataDirName)
            if (!modelFile.isFile || modelFile.length() <= 10_000_000L ||
                !tokensFile.isFile || tokensFile.length() == 0L ||
                !dataDir.isDirectory || dataDir.listFiles().isNullOrEmpty()
            ) {
                stagingDir.deleteRecursively()
                return@withContext Result.failure(
                    workDataOf(KEY_ERROR_REASON to "El modelo extraído no contiene los archivos necesarios")
                )
            }
            OfflineVoiceCatalog.writeInstalledMarker(stagingDir, spec)

            // 5. Mover a su sitio definitivo solo cuando todo está completo
            if (targetInstallDir.exists()) targetInstallDir.deleteRecursively()
            if (!stagingDir.renameTo(targetInstallDir)) {
                stagingDir.deleteRecursively()
                return@withContext Result.failure(
                    workDataOf(KEY_ERROR_REASON to "No se pudo guardar el modelo en el almacenamiento")
                )
            }

            if (!OfflineVoiceCatalog.isModelReady(applicationContext, spec)) {
                targetInstallDir.deleteRecursively()
                return@withContext Result.failure(
                    workDataOf(KEY_ERROR_REASON to "La instalación no pasó la comprobación final")
                )
            }

            updateProgress(100, downloadedBytes, contentLength, "Voz neuronal lista para usar")
            Result.success(workDataOf("model_id" to spec.id))
        } catch (e: CancellationException) {
            tempArchiveFile.delete()
            stagingDir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            tempArchiveFile.delete()
            stagingDir.deleteRecursively()
            Result.failure(workDataOf(KEY_ERROR_REASON to (e.message ?: "Error desconocido en la descarga")))
        }
    }

    private suspend fun updateProgress(
        percent: Int,
        downloaded: Long,
        total: Long,
        message: String
    ) {
        setProgress(
            workDataOf(
                KEY_PROGRESS_PERCENT to percent,
                KEY_DOWNLOADED_BYTES to downloaded,
                KEY_TOTAL_BYTES to total,
                KEY_STATUS_MESSAGE to message
            )
        )
    }

    private fun extractTarBz2(tarBz2File: File, outputDir: File) {
        FileInputStream(tarBz2File).use { fis ->
            BufferedInputStream(fis).use { bis ->
                BZip2CompressorInputStream(bis).use { bzIn ->
                    TarArchiveInputStream(bzIn).use { tarIn ->
                        var entry: TarArchiveEntry?
                        while (tarIn.nextEntry.also { entry = it } != null) {
                            val cur = entry ?: break
                            val name = cur.name
                            // Quitar el prefijo del directorio raíz del tar si existe (ej. vits-piper-es_MX-ald-medium/...)
                            val relativePath = stripArchiveRootDir(name)
                            if (relativePath.isBlank()) continue

                            val outputFile = File(outputDir, relativePath)
                            val rootPath = outputDir.canonicalPath + File.separator
                            if (!outputFile.canonicalPath.startsWith(rootPath)) continue // evita rutas "../"
                            if (cur.isDirectory) {
                                outputFile.mkdirs()
                            } else {
                                outputFile.parentFile?.mkdirs()
                                FileOutputStream(outputFile).use { fos ->
                                    tarIn.copyTo(fos)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun stripArchiveRootDir(entryName: String): String {
        val slashIndex = entryName.indexOf('/')
        return if (slashIndex != -1 && slashIndex < entryName.length - 1) {
            entryName.substring(slashIndex + 1)
        } else {
            entryName
        }
    }
}
