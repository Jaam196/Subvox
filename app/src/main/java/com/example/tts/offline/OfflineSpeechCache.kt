package com.example.tts.offline

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Caché en disco de síntesis neuronal offline de Sherpa-ONNX.
 * Indexada por hash SHA-256 de (texto + id de voz + velocidad).
 * Permite evitar síntesis redundante en frases repetidas de anime y ahorra CPU/batería.
 */
class OfflineSpeechCache(context: Context) {

    private val cacheDir = File(context.cacheDir, "offline_sherpa_cache").apply {
        if (!exists()) mkdirs()
    }

    private val memoryIndex = ConcurrentHashMap<String, File>()

    init {
        rebuildIndex()
    }

    private fun rebuildIndex() {
        memoryIndex.clear()
        cacheDir.listFiles()?.filter { it.extension == "pcmcache" }?.forEach { file ->
            val key = file.nameWithoutExtension
            memoryIndex[key] = file
        }
    }

    private fun computeKey(text: String, voiceId: String, speed: Float): String {
        val roundedSpeed = (speed * 100).toInt()
        val raw = "${text.trim()}|$voiceId|$roundedSpeed|p2"
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun get(text: String, voiceId: String, speed: Float): Pair<ByteArray, Int>? {
        val key = computeKey(text, voiceId, speed)
        val file = memoryIndex[key] ?: File(cacheDir, "$key.pcmcache").takeIf { it.exists() } ?: return null

        return runCatching {
            file.setLastModified(System.currentTimeMillis())
            FileInputStream(file).use { fis ->
                DataInputStream(fis).use { dis ->
                    val sampleRate = dis.readInt()
                    val pcmLength = dis.readInt()
                    val bytes = ByteArray(pcmLength)
                    dis.readFully(bytes)
                    bytes to sampleRate
                }
            }
        }.getOrNull()
    }

    fun put(
        text: String,
        voiceId: String,
        speed: Float,
        sampleRate: Int,
        pcmBytes: ByteArray,
        maxCacheMb: Int
    ) {
        if (pcmBytes.isEmpty()) return
        val key = computeKey(text, voiceId, speed)
        val file = File(cacheDir, "$key.pcmcache")

        runCatching {
            FileOutputStream(file).use { fos ->
                DataOutputStream(fos).use { dos ->
                    dos.writeInt(sampleRate)
                    dos.writeInt(pcmBytes.size)
                    dos.write(pcmBytes)
                }
            }
            memoryIndex[key] = file
            enforceCacheLimit(maxCacheMb)
        }
    }

    fun getUsedSizeBytes(): Long {
        var total = 0L
        cacheDir.listFiles()?.forEach { total += it.length() }
        return total
    }

    fun clearCache(): Boolean {
        memoryIndex.clear()
        var allDeleted = true
        cacheDir.listFiles()?.forEach { file ->
            if (!file.delete()) allDeleted = false
        }
        return allDeleted
    }

    private fun enforceCacheLimit(maxMb: Int) {
        val maxBytes = maxMb.toLong() * 1024L * 1024L
        val files = cacheDir.listFiles()?.filter { it.extension == "pcmcache" } ?: return
        var currentTotal = files.sumOf { it.length() }

        if (currentTotal <= maxBytes) return

        val sortedByAccess = files.sortedBy { it.lastModified() }
        for (f in sortedByAccess) {
            val length = f.length()
            val name = f.nameWithoutExtension
            if (f.delete()) {
                memoryIndex.remove(name)
                currentTotal -= length
                if (currentTotal <= maxBytes) break
            }
        }
    }
}
