package com.example.domain.pipeline

import com.example.domain.pipeline.SubtitleNormalizer.NormalizedSubtitle
import kotlin.math.max
import kotlin.math.min

/**
 * Máquina de estados para deduplicación temporal y filtrado de subtítulos:
 * - Requiere [minStableFrames] frames coherentes antes de emitir un subtítulo nuevo (evita leer
 *   artefactos de transición o frames a medio renderizar).
 * - Mientras el mismo subtítulo permanece en pantalla durante N frames (con similitud >= [similarityThreshold]),
 *   se emite UNA SOLA VEZ.
 * - Un frame vacío (o N frames vacíos consecutivos) cierra el subtítulo activo actual.
 * - Mantiene un historial con TTL ([historyTtlMs]) para no volver a leer un subtítulo repetido
 *   recientemente (con TTL abreviado para interjecciones muy cortas como "¡Sí!", "¿Qué?").
 */
class SubtitleDeduplicator(
    similarityThreshold: Float = 0.82f,
    minStableFrames: Int = 1,
    historyTtlMs: Long = 90_000L,
    emptyFramesToClose: Int = 3,
    reappearGapMs: Long = 1_200L
) {
    sealed class Decision {
        data class SpeakNew(val speakableText: String, val comparisonKey: String) : Decision()
        data object IgnoreDuplicateOrUnstable : Decision()
        data object SubtitleClosed : Decision()
    }

    private data class HistoryEntry(
        val comparisonKey: String,
        val speakableText: String,
        val lastSeenTimestampMs: Long
    )

    @Volatile
    var similarityThreshold: Float = similarityThreshold.coerceIn(0.60f, 0.96f)

    @Volatile
    var minStableFrames: Int = minStableFrames.coerceIn(1, 5)

    @Volatile
    var historyTtlMs: Long = historyTtlMs.coerceAtLeast(5_000L)

    @Volatile
    var emptyFramesToClose: Int = emptyFramesToClose.coerceIn(2, 6)

    /**
     * Si un subtítulo desaparece de pantalla y vuelve a aparecer con el mismo texto pasado este
     * tiempo, se considera una frase NUEVA (otro personaje repitiendo "¿Qué?") y se vuelve a leer,
     * aunque siga dentro del TTL del historial.
     */
    @Volatile
    var reappearGapMs: Long = reappearGapMs.coerceAtLeast(300L)

    private var activeSubtitle: NormalizedSubtitle? = null
    private var candidateSubtitle: NormalizedSubtitle? = null
    private var candidateStableCount: Int = 0
    private var consecutiveEmptyFrames: Int = 0
    private var lastClosedAtMs: Long = 0L

    /** true si hay un candidato esperando confirmación (el servicio pedirá otra lectura OCR). */
    val hasPendingCandidate: Boolean
        @Synchronized get() = candidateSubtitle != null

    private val recentHistory = ArrayDeque<HistoryEntry>()
    private val maxHistorySize = 40

    @Synchronized
    fun onFrameText(
        rawText: String,
        timestampMs: Long = System.currentTimeMillis(),
        requiredStableFrames: Int = minStableFrames
    ): Decision {
        pruneExpiredHistory(timestampMs)
        var stableNeeded = requiredStableFrames.coerceIn(1, 5)

        val normalized = SubtitleNormalizer.normalize(rawText)
        if (normalized.isEmpty) {
            consecutiveEmptyFrames++
            candidateSubtitle = null
            candidateStableCount = 0

            if (activeSubtitle != null && consecutiveEmptyFrames >= emptyFramesToClose) {
                activeSubtitle = null
                lastClosedAtMs = timestampMs
                return Decision.SubtitleClosed
            }
            return Decision.IgnoreDuplicateOrUnstable
        }

        consecutiveEmptyFrames = 0

        // 1. ¿Sigue siendo el mismo subtítulo que ya está activo en pantalla o una expansión?
        val currentActive = activeSubtitle
        if (currentActive != null) {
            val keyActive = currentActive.comparisonKey
            val keyNorm = normalized.comparisonKey

            val isContained = (keyActive.length >= 4 && keyNorm.contains(keyActive)) ||
                    (keyNorm.length >= 4 && keyActive.contains(keyNorm))

            val simWithActive = TextSimilarityCalculator.computeSimilarity(keyActive, keyNorm)

            // El subtítulo activo se leyó incompleto (p. ej. aparecía con fundido) y ahora llega
            // la frase completa: se trata como frase nueva para leerla entera.
            val isExtension = keyActive.length >= 4 &&
                keyNorm.length >= (keyActive.length * 1.4f).toInt() + 6 &&
                keyNorm.contains(keyActive)

            if ((simWithActive >= similarityThreshold || isContained) && !isExtension) {
                // Si el nuevo frame tiene un texto ligeramente más completo (p. ej. con mejor puntuación),
                // actualizamos la referencia activa sin volver a disparar el TTS para no interrumpir el audio en curso.
                if (normalized.speakableText.length >= currentActive.speakableText.length) {
                    activeSubtitle = normalized
                }
                refreshHistoryTimestamp(normalized.comparisonKey, timestampMs)
                candidateSubtitle = null
                candidateStableCount = 0
                return Decision.IgnoreDuplicateOrUnstable
            }

            // Lectura "parecida pero distinta" del subtítulo activo: casi seguro es un fallo de OCR
            // (palabra perdida, tilde mal leída). Se exige confirmar con una segunda lectura.
            if (simWithActive >= 0.55f) {
                stableNeeded = maxOf(stableNeeded, 2)
            }
        }

        // 2. Es distinto del subtítulo activo -> verificar estabilidad temporal entre frames consecutivos
        val currentCandidate = candidateSubtitle
        if (currentCandidate != null) {
            val simWithCandidate = TextSimilarityCalculator.computeSimilarity(
                currentCandidate.comparisonKey,
                normalized.comparisonKey
            )
            if (simWithCandidate >= similarityThreshold) {
                candidateStableCount++
                // Conservar la versión más rica en caracteres acentuados / longitud
                if (scoreSpeakableQuality(normalized.speakableText) >= scoreSpeakableQuality(currentCandidate.speakableText)) {
                    candidateSubtitle = normalized
                }
            } else {
                candidateSubtitle = normalized
                candidateStableCount = 1
            }
        } else {
            candidateSubtitle = normalized
            candidateStableCount = 1
        }

        if (candidateStableCount < stableNeeded) {
            return Decision.IgnoreDuplicateOrUnstable
        }

        val confirmed = candidateSubtitle ?: normalized
        candidateSubtitle = null
        candidateStableCount = 0

        // 3. Comprobar historial con TTL para no repetir frases ya leídas recientemente
        val reappearedAfterClose = lastClosedAtMs > 0L && timestampMs - lastClosedAtMs >= reappearGapMs
        if (!reappearedAfterClose && isRecentlySpokenInHistory(confirmed.comparisonKey, timestampMs)) {
            activeSubtitle = confirmed
            return Decision.IgnoreDuplicateOrUnstable
        }

        // 4. Confirmado como subtítulo nuevo listo para leer en voz alta
        activeSubtitle = confirmed
        recordInHistory(confirmed, timestampMs)
        return Decision.SpeakNew(
            speakableText = confirmed.speakableText,
            comparisonKey = confirmed.comparisonKey
        )
    }

    @Synchronized
    fun reset() {
        activeSubtitle = null
        candidateSubtitle = null
        candidateStableCount = 0
        consecutiveEmptyFrames = 0
        lastClosedAtMs = 0L
        recentHistory.clear()
    }

    @Synchronized
    fun clearActiveOnly() {
        activeSubtitle = null
        candidateSubtitle = null
        candidateStableCount = 0
        consecutiveEmptyFrames = 0
    }

    private fun isRecentlySpokenInHistory(candidateKey: String, nowMs: Long): Boolean {
        // Las frases muy cortas ("si", "no", "que", "vamos") tienen un TTL más corto (8 s)
        // para permitir que se lean si otro personaje las dice poco después tras cerrarse el subtítulo
        val effectiveTtl = if (candidateKey.length <= 8) {
            min(historyTtlMs, 8_000L)
        } else {
            historyTtlMs
        }

        for (entry in recentHistory) {
            if (nowMs - entry.lastSeenTimestampMs <= effectiveTtl) {
                if (entry.comparisonKey == candidateKey) {
                    return true
                }
                val sim = TextSimilarityCalculator.computeSimilarity(entry.comparisonKey, candidateKey)
                if (sim >= (similarityThreshold * 0.90f).coerceAtLeast(0.70f)) {
                    return true
                }
                // Si el candidato es una subcadena o fragmento de una frase leída hace poco
                if (entry.comparisonKey.length >= 5 && candidateKey.length >= 5) {
                    if (entry.comparisonKey.contains(candidateKey) || candidateKey.contains(entry.comparisonKey)) {
                        val minLen = min(entry.comparisonKey.length, candidateKey.length).toFloat()
                        val maxLen = max(entry.comparisonKey.length, candidateKey.length).toFloat()
                        if (minLen / maxLen >= 0.55f) {
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    private fun recordInHistory(sub: NormalizedSubtitle, nowMs: Long) {
        recentHistory.addFirst(
            HistoryEntry(
                comparisonKey = sub.comparisonKey,
                speakableText = sub.speakableText,
                lastSeenTimestampMs = nowMs
            )
        )
        while (recentHistory.size > maxHistorySize) {
            recentHistory.removeLast()
        }
    }

    private fun refreshHistoryTimestamp(comparisonKey: String, nowMs: Long) {
        val index = recentHistory.indexOfFirst {
            TextSimilarityCalculator.computeSimilarity(it.comparisonKey, comparisonKey) >= similarityThreshold
        }
        if (index >= 0) {
            val old = recentHistory[index]
            recentHistory[index] = old.copy(lastSeenTimestampMs = nowMs)
        }
    }

    private fun pruneExpiredHistory(nowMs: Long) {
        val iterator = recentHistory.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (nowMs - item.lastSeenTimestampMs > historyTtlMs) {
                iterator.remove()
            }
        }
    }

    private fun scoreSpeakableQuality(text: String): Int {
        var score = text.length
        for (c in text) {
            if (c in "áéíóúüñÁÉÍÓÚÜÑ¿?¡!") score += 2
        }
        return score
    }
}
