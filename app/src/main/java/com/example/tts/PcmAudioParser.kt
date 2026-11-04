package com.example.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ParsedPcmResult(
    val pcmBytes: ByteArray,
    val sampleRateHz: Int,
    val channels: Int = 1
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ParsedPcmResult

        if (!pcmBytes.contentEquals(other.pcmBytes)) return false
        if (sampleRateHz != other.sampleRateHz) return false
        if (channels != other.channels) return false

        return true
    }

    override fun hashCode(): Int {
        var result = pcmBytes.contentHashCode()
        result = 31 * result + sampleRateHz
        result = 31 * result + channels
        return result
    }
}

/**
 * Utilidad robusta para detectar cabeceras RIFF WAV generadas por modelos Gemini 3.8 TTS,
 * extraer la frecuencia de muestreo real del bloque "fmt " y devolver los bytes PCM puros sin cabecera.
 * Si el audio ya viene en formato PCM crudo (audio/l16 o sin cabecera RIFF), lo devuelve intacto.
 */
object WavPcmExtractor {

    private const val RIFF_MAGIC = 0x46464952 // "RIFF" in Little-Endian
    private const val WAVE_MAGIC = 0x45564157 // "WAVE" in Little-Endian
    private const val FMT_MAGIC  = 0x20746D66 // "fmt " in Little-Endian
    private const val DATA_MAGIC = 0x61746164 // "data" in Little-Endian

    fun extractPcm(rawBytes: ByteArray, fallbackSampleRate: Int = 24_000): ParsedPcmResult {
        if (rawBytes.size < 12) {
            return ParsedPcmResult(rawBytes, fallbackSampleRate)
        }

        val buffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
        val riff = buffer.int
        val fileSizeMinus8 = buffer.int
        val wave = buffer.int

        if (riff != RIFF_MAGIC || wave != WAVE_MAGIC) {
            // No es un contenedor RIFF WAV: es PCM crudo (audio/l16)
            return ParsedPcmResult(rawBytes, fallbackSampleRate)
        }

        var detectedSampleRate = fallbackSampleRate
        var channels = 1
        var pcmBytes: ByteArray? = null

        // Recorrer los chunks del contenedor RIFF
        while (buffer.remaining() >= 8) {
            val chunkId = buffer.int
            val chunkSize = buffer.int

            if (chunkSize < 0 || chunkSize > buffer.remaining()) {
                // Chunk malformado o truncado: extraer lo que queda si es data
                if (chunkId == DATA_MAGIC) {
                    val remaining = buffer.remaining()
                    val data = ByteArray(remaining)
                    buffer.get(data)
                    pcmBytes = data
                }
                break
            }

            val nextChunkPosition = buffer.position() + chunkSize

            when (chunkId) {
                FMT_MAGIC -> {
                    if (chunkSize >= 16) {
                        val audioFormat = buffer.short.toInt() and 0xFFFF
                        channels = buffer.short.toInt() and 0xFFFF
                        val sampleRate = buffer.int
                        val byteRate = buffer.int
                        val blockAlign = buffer.short.toInt() and 0xFFFF
                        val bitsPerSample = buffer.short.toInt() and 0xFFFF

                        if (sampleRate in 8_000..96_000) {
                            detectedSampleRate = sampleRate
                        }
                    }
                    buffer.position(nextChunkPosition)
                }
                DATA_MAGIC -> {
                    val data = ByteArray(chunkSize)
                    buffer.get(data)
                    pcmBytes = data
                    break
                }
                else -> {
                    // Ignorar otros chunks (JUNK, LIST, etc.)
                    buffer.position(nextChunkPosition)
                }
            }
        }

        val finalPcm = pcmBytes ?: rawBytes.copyOfRange(12.coerceAtMost(rawBytes.size), rawBytes.size)
        return ParsedPcmResult(
            pcmBytes = finalPcm,
            sampleRateHz = detectedSampleRate,
            channels = channels
        )
    }
}
