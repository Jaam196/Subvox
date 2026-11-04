package com.example.translate

import com.example.domain.model.TranslationReadMode
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await

data class TranslationLanguageModelStatus(
    val languageCode: String,
    val displayName: String,
    val isDownloaded: Boolean = false,
    val isDownloading: Boolean = false,
    val errorMessage: String? = null
)

data class LiveTranslationOutcome(
    val originalText: String,
    val translatedText: String,
    val detectedSourceLanguage: String,
    val textToSpeak: String,
    val translationSucceeded: Boolean
)

/**
 * Gestor 100% local/offline de detección de idioma (ML Kit Language ID) y traducción
 * en vivo (ML Kit On-Device Translation), más descarga/borrado explícito de modelos.
 *
 * Si la traducción falla o el modelo aún no está descargado, devuelve el texto original
 * sin interrumpir ni bloquear el flujo del lector de subtítulos.
 */
class SubtitleTranslatorManager {

    companion object {
        val SUPPORTED_MODELS: List<Pair<String, String>> = listOf(
            TranslateLanguage.SPANISH to "Español (es)",
            TranslateLanguage.ENGLISH to "Inglés (en)",
            TranslateLanguage.JAPANESE to "Japonés (ja)",
            TranslateLanguage.KOREAN to "Coreano (ko)",
            TranslateLanguage.CHINESE to "Chino (zh)",
            TranslateLanguage.PORTUGUESE to "Portugués (pt)",
            TranslateLanguage.FRENCH to "Francés (fr)",
            TranslateLanguage.GERMAN to "Alemán (de)",
            TranslateLanguage.ITALIAN to "Italiano (it)"
        )
    }

    private val modelManager: RemoteModelManager? by lazy {
        runCatching { RemoteModelManager.getInstance() }.getOrNull()
    }
    private var languageIdentifier: LanguageIdentifier? = null

    private var activeTranslatorKey: String? = null
    private var activeTranslator: Translator? = null

    private val _modelsState = MutableStateFlow<List<TranslationLanguageModelStatus>>(
        SUPPORTED_MODELS.map { (code, name) ->
            TranslationLanguageModelStatus(languageCode = code, displayName = name)
        }
    )
    val modelsState: StateFlow<List<TranslationLanguageModelStatus>> = _modelsState.asStateFlow()

    private fun getOrCreateLanguageIdentifier(): LanguageIdentifier {
        val existing = languageIdentifier
        if (existing != null) return existing
        val created = LanguageIdentification.getClient()
        languageIdentifier = created
        return created
    }

    suspend fun refreshDownloadedModels() {
        runCatching {
            val manager = modelManager ?: return
            val downloadedSet = manager
                .getDownloadedModels(TranslateRemoteModel::class.java)
                .await()
                .map { it.language }
                .toSet()

            _modelsState.update { currentList ->
                currentList.map { item ->
                    item.copy(
                        isDownloaded = item.languageCode in downloadedSet,
                        errorMessage = null
                    )
                }
            }
        }
    }

    suspend fun downloadLanguageModel(languageCode: String, requireWifi: Boolean): Boolean {
        val validCode = TranslateLanguage.fromLanguageTag(languageCode) ?: languageCode
        _modelsState.update { list ->
            list.map {
                if (it.languageCode == validCode) it.copy(isDownloading = true, errorMessage = null) else it
            }
        }

        return try {
            val manager = modelManager ?: error("ML Kit ModelManager no disponible")
            val model = TranslateRemoteModel.Builder(validCode).build()
            val conditionsBuilder = DownloadConditions.Builder()
            if (requireWifi) {
                conditionsBuilder.requireWifi()
            }
            manager.download(model, conditionsBuilder.build()).await()
            refreshDownloadedModels()
            _modelsState.update { list ->
                list.map {
                    if (it.languageCode == validCode) it.copy(isDownloading = false, isDownloaded = true) else it
                }
            }
            true
        } catch (t: Throwable) {
            _modelsState.update { list ->
                list.map {
                    if (it.languageCode == validCode) {
                        it.copy(
                            isDownloading = false,
                            errorMessage = t.localizedMessage ?: "Error al descargar modelo ($validCode)"
                        )
                    } else {
                        it
                    }
                }
            }
            false
        }
    }

    suspend fun deleteLanguageModel(languageCode: String): Boolean {
        val validCode = TranslateLanguage.fromLanguageTag(languageCode) ?: languageCode
        return try {
            val manager = modelManager ?: return false
            val model = TranslateRemoteModel.Builder(validCode).build()
            manager.deleteDownloadedModel(model).await()
            refreshDownloadedModels()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Detecta el idioma del subtítulo con ML Kit Language ID (o heurística CJK inmediata)
     * y traduce al idioma destino si los modelos están disponibles.
     * Nunca lanza excepciones: en caso de fallo, devuelve el texto original para no detener el TTS.
     */
    suspend fun processSubtitle(
        originalText: String,
        sourceLanguageSetting: String,
        targetLanguageSetting: String,
        readMode: TranslationReadMode,
        allowAutoDownloadIfMissing: Boolean = true,
        requireWifiForDownload: Boolean = true
    ): LiveTranslationOutcome {
        if (originalText.isBlank()) {
            return LiveTranslationOutcome(
                originalText = "",
                translatedText = "",
                detectedSourceLanguage = "",
                textToSpeak = "",
                translationSucceeded = false
            )
        }

        val targetLang = TranslateLanguage.fromLanguageTag(targetLanguageSetting)
            ?: TranslateLanguage.SPANISH

        val resolvedSourceLang = resolveSourceLanguage(originalText, sourceLanguageSetting)

        // Si el idioma de origen ya coincide con el idioma de salida, no hace falta traducir
        if (resolvedSourceLang.equals(targetLang, ignoreCase = true)) {
            return LiveTranslationOutcome(
                originalText = originalText,
                translatedText = originalText,
                detectedSourceLanguage = resolvedSourceLang,
                textToSpeak = originalText,
                translationSucceeded = true
            )
        }

        return try {
            val translator = getOrCreateTranslator(resolvedSourceLang, targetLang)
            if (allowAutoDownloadIfMissing) {
                val conditionsBuilder = DownloadConditions.Builder()
                if (requireWifiForDownload) {
                    conditionsBuilder.requireWifi()
                }
                translator.downloadModelIfNeeded(conditionsBuilder.build()).await()
            }

            val translated = translator.translate(originalText).await().trim()
            if (translated.isBlank()) {
                LiveTranslationOutcome(
                    originalText = originalText,
                    translatedText = "",
                    detectedSourceLanguage = resolvedSourceLang,
                    textToSpeak = originalText,
                    translationSucceeded = false
                )
            } else {
                val finalSpeak = when (readMode) {
                    TranslationReadMode.TRANSLATED_ONLY -> translated
                    TranslationReadMode.ORIGINAL_ONLY -> originalText
                    TranslationReadMode.BOTH_ORIGINAL_AND_TRANSLATED ->
                        if (originalText.equals(translated, ignoreCase = true)) {
                            translated
                        } else {
                            "$originalText. $translated"
                        }
                }
                LiveTranslationOutcome(
                    originalText = originalText,
                    translatedText = translated,
                    detectedSourceLanguage = resolvedSourceLang,
                    textToSpeak = finalSpeak,
                    translationSucceeded = true
                )
            }
        } catch (_: Throwable) {
            // Fallback seguro: si falla la traducción offline, leemos el texto original sin romper el flujo
            LiveTranslationOutcome(
                originalText = originalText,
                translatedText = "",
                detectedSourceLanguage = resolvedSourceLang,
                textToSpeak = originalText,
                translationSucceeded = false
            )
        }
    }

    private suspend fun resolveSourceLanguage(text: String, configuredSource: String): String {
        if (configuredSource.isNotBlank() && !configuredSource.equals("auto", ignoreCase = true)) {
            return TranslateLanguage.fromLanguageTag(configuredSource) ?: configuredSource
        }

        // Heurística rápida para scripts CJK inequívocos (Hiragana/Katakana/Hangul)
        if (text.any { it.code in 0x3040..0x30FF }) {
            return TranslateLanguage.JAPANESE
        }
        if (text.any { it.code in 0xAC00..0xD7AF || it.code in 0x1100..0x11FF }) {
            return TranslateLanguage.KOREAN
        }

        val identified = runCatching {
            getOrCreateLanguageIdentifier().identifyLanguage(text).await()
        }.getOrDefault("und")

        if (identified != "und" && identified.isNotBlank()) {
            val mapped = TranslateLanguage.fromLanguageTag(identified)
            if (mapped != null) return mapped
        }

        // Si contiene ideogramas CJK y no tenía Kana ni Hangul, asumimos japonés/chino según contexto anime
        if (text.any { it.code in 0x4E00..0x9FFF }) {
            return TranslateLanguage.JAPANESE
        }

        return TranslateLanguage.ENGLISH
    }

    @Synchronized
    private fun getOrCreateTranslator(sourceLang: String, targetLang: String): Translator {
        val key = "${sourceLang}_$targetLang"
        val current = activeTranslator
        if (current != null && activeTranslatorKey == key) {
            return current
        }
        runCatching { current?.close() }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLang)
            .setTargetLanguage(targetLang)
            .build()
        val created = Translation.getClient(options)
        activeTranslatorKey = key
        activeTranslator = created
        return created
    }

    @Synchronized
    fun close() {
        runCatching { activeTranslator?.close() }
        activeTranslator = null
        activeTranslatorKey = null
        runCatching { languageIdentifier?.close() }
        languageIdentifier = null
    }
}
