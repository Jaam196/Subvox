package com.example.tts

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale

data class CachedPcmAudio(
    val pcmBytes: ByteArray,
    val sampleRateHz: Int
)

/**
 * Caché en disco para el audio PCM 16-bit mono generado por [NaturalSpeechEngine].
 * Indexa por hash SHA-256 de (texto normalizado + nombre de voz + instrucción de estilo),
 * con poda LRU por tamaño máximo configurable en MB y borrado manual completo.
 */
class NaturalSpeechCache(context: Context) {

    private val cacheRootDir: File = File(context.applicationContext.cacheDir, "natural_tts_pcm_cache").apply {
        if (!exists()) mkdirs()
    }

    private val lock = Any()

    companion object {
        private const val HEADER_MAGIC = 0x4E545453 // "NTTS"
        private const val HEADER_BYTES = 8

        fun computeKeyHash(
            text: String,
            modelName: String = "gemini-3.8-flash-lite-tts",
            voiceName: String,
            styleInstruction: String,
            audioFormat: String = "audio/l16"
        ): String {
            val normalizedInput = "${text.trim()}|${modelName.trim().lowercase(Locale.ROOT)}|${voiceName.trim().lowercase(Locale.ROOT)}|${styleInstruction.trim()}|${audioFormat.trim().lowercase(Locale.ROOT)}"
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(normalizedInput.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                sb.append(String.format(Locale.US, "%02x", b))
            }
            return sb.toString()
        }

        @Deprecated("Usar versión completa con modelName y audioFormat")
        fun computeKeyHash(text: String, voiceName: String, styleInstruction: String): String =
            computeKeyHash(text, "gemini-3.8-flash-lite-tts", voiceName, styleInstruction, "audio/l16")
    }

    fun get(
        text: String,
        modelName: String,
        voiceName: String,
        styleInstruction: String,
        audioFormat: String = "audio/l16"
    ): CachedPcmAudio? = synchronized(lock) {
        runCatching {
            val hash = computeKeyHash(text, modelName, voiceName, styleInstruction, audioFormat)
            val file = File(cacheRootDir, "$hash.pcm")
            if (!file.exists() || file.length() <= HEADER_BYTES) return null

            val raw = file.readBytes()
            val buffer = ByteBuffer.wrap(raw, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buffer.int
            val sampleRate = buffer.int
            if (magic != HEADER_MAGIC || sampleRate !in 8_000..96_000) {
                file.delete()
                return null
            }

            // Actualizamos timestamp para política LRU
            file.setLastModified(System.currentTimeMillis())
            val pcmPayload = raw.copyOfRange(HEADER_BYTES, raw.size)
            CachedPcmAudio(pcmBytes = pcmPayload, sampleRateHz = sampleRate)
        }.getOrNull()
    }

    fun get(text: String, voiceName: String, styleInstruction: String): CachedPcmAudio? =
        get(text, "gemini-3.8-flash-lite-tts", voiceName, styleInstruction, "audio/l16")

    fun put(
        text: String,
        modelName: String,
        voiceName: String,
        styleInstruction: String,
        audioFormat: String,
        pcmBytes: ByteArray,
        sampleRateHz: Int,
        maxCacheMb: Int
    ): Long = synchronized(lock) {
        if (pcmBytes.isEmpty()) return currentSizeBytes()
        runCatching {
            if (!cacheRootDir.exists()) cacheRootDir.mkdirs()
            val hash = computeKeyHash(text, modelName, voiceName, styleInstruction, audioFormat)
            val file = File(cacheRootDir, "$hash.pcm")

            val outBuffer = ByteBuffer.allocate(HEADER_BYTES + pcmBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            outBuffer.putInt(HEADER_MAGIC)
            outBuffer.putInt(sampleRateHz)
            outBuffer.put(pcmBytes)

            file.writeBytes(outBuffer.array())
            file.setLastModified(System.currentTimeMillis())
            trimToMaxSizeLocked(maxCacheMb)
        }
        return currentSizeBytes()
    }

    fun put(
        text: String,
        voiceName: String,
        styleInstruction: String,
        pcmBytes: ByteArray,
        sampleRateHz: Int,
        maxCacheMb: Int
    ): Long = put(
        text = text,
        modelName = "gemini-3.8-flash-lite-tts",
        voiceName = voiceName,
        styleInstruction = styleInstruction,
        audioFormat = "audio/l16",
        pcmBytes = pcmBytes,
        sampleRateHz = sampleRateHz,
        maxCacheMb = maxCacheMb
    )

    fun trimToMaxSize(maxCacheMb: Int): Long = synchronized(lock) {
        trimToMaxSizeLocked(maxCacheMb)
        currentSizeBytes()
    }

    private fun trimToMaxSizeLocked(maxCacheMb: Int) {
        val maxBytes = maxCacheMb.coerceIn(5, 500).toLong() * 1024L * 1024L
        val files = cacheRootDir.listFiles()?.filter { it.isFile }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return

        // Ordenar de más antiguo a más reciente
        files.sortBy { it.lastModified() }
        for (f in files) {
            if (total <= maxBytes) break
            val len = f.length()
            if (f.delete()) {
                total -= len
            }
        }
    }

    fun clearAll(): Long = synchronized(lock) {
        runCatching {
            cacheRootDir.listFiles()?.forEach { file ->
                runCatching { file.delete() }
            }
        }
        0L
    }

    fun currentSizeBytes(): Long = synchronized(lock) {
        runCatching {
            cacheRootDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        }.getOrDefault(0L)
    }
}
