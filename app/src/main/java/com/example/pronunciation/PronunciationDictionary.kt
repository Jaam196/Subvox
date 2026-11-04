package com.example.pronunciation

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

private val Context.pronunciationDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "animesub_voz_pronunciation_dict"
)

data class PronunciationRule(
    val id: String = UUID.randomUUID().toString(),
    val original: String,
    val replacement: String,
    val ignoreCase: Boolean = true,
    val matchWholeWord: Boolean = false,
    val enabled: Boolean = true
)

/**
 * Motor y repositorio de Diccionario de Pronunciación para nombres de anime,
 * honoríficos japoneses (-san, -kun, -chan, -sama, -sensei, -senpai) y siglas.
 * Incluye reglas iniciales por defecto y soporte completo de creación, edición,
 * borrado, importación y exportación en JSON.
 */
object PronunciationProcessor {

    val DEFAULT_ANIME_HONORIFIC_RULES: List<PronunciationRule> = listOf(
        PronunciationRule(
            id = "default_sama",
            original = "-sama",
            replacement = " sama",
            ignoreCase = true,
            matchWholeWord = false,
            enabled = true
        ),
        PronunciationRule(
            id = "default_chan",
            original = "-chan",
            replacement = " chan",
            ignoreCase = true,
            matchWholeWord = false,
            enabled = true
        ),
        PronunciationRule(
            id = "default_kun",
            original = "-kun",
            replacement = " kun",
            ignoreCase = true,
            matchWholeWord = false,
            enabled = true
        ),
        PronunciationRule(
            id = "default_san",
            original = "-san",
            replacement = " san",
            ignoreCase = true,
            matchWholeWord = false,
            enabled = true
        ),
        PronunciationRule(
            id = "default_sensei",
            original = "-sensei",
            replacement = " sensei",
            ignoreCase = true,
            matchWholeWord = false,
            enabled = true
        ),
        PronunciationRule(
            id = "default_senpai",
            original = "-senpai",
            replacement = " senpai",
            ignoreCase = true,
            matchWholeWord = false,
            enabled = true
        ),
        PronunciationRule(
            id = "default_dono",
            original = "-dono",
            replacement = " dono",
            ignoreCase = true,
            matchWholeWord = false,
            enabled = true
        ),
        PronunciationRule(
            id = "default_neechan",
            original = "onee-chan",
            replacement = "oné chan",
            ignoreCase = true,
            matchWholeWord = true,
            enabled = true
        ),
        PronunciationRule(
            id = "default_niichan",
            original = "onii-chan",
            replacement = "oní chan",
            ignoreCase = true,
            matchWholeWord = true,
            enabled = true
        ),
        PronunciationRule(
            id = "default_ova",
            original = "OVA",
            replacement = "ova",
            ignoreCase = false,
            matchWholeWord = true,
            enabled = true
        )
    )

    /**
     * Aplica las reglas activas en orden (primero coincidencias más largas para evitar colisiones
     * parciales como "-san" dentro de "-sensei").
     */
    fun applyRules(text: String, rules: List<PronunciationRule>): String {
        if (text.isBlank() || rules.isEmpty()) return text

        var output = text
        val activeRules = rules
            .filter { it.enabled && it.original.isNotBlank() }
            .sortedByDescending { it.original.length }

        for (rule in activeRules) {
            output = runCatching {
                val escapedTarget = Regex.escape(rule.original)
                val pattern = if (rule.matchWholeWord) {
                    """(?<![\p{L}\p{N}_])$escapedTarget(?![\p{L}\p{N}_])"""
                } else {
                    escapedTarget
                }
                val options = if (rule.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
                val regex = Regex(pattern, options)
                regex.replace(output, Regex.escapeReplacement(rule.replacement))
            }.getOrDefault(output)
        }

        return output.replace(Regex("""\s+"""), " ").trim()
    }

    fun serializeRulesToJson(rules: List<PronunciationRule>): String {
        val array = JSONArray()
        for (rule in rules) {
            val obj = JSONObject().apply {
                put("id", rule.id)
                put("original", rule.original)
                put("replacement", rule.replacement)
                put("ignoreCase", rule.ignoreCase)
                put("matchWholeWord", rule.matchWholeWord)
                put("enabled", rule.enabled)
            }
            array.put(obj)
        }
        return array.toString(2)
    }

    fun parseRulesFromJson(jsonStr: String): List<PronunciationRule> {
        if (jsonStr.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<PronunciationRule>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val orig = obj.optString("original", "").trim()
                if (orig.isEmpty()) continue
                list.add(
                    PronunciationRule(
                        id = obj.optString("id", UUID.randomUUID().toString()).ifBlank { UUID.randomUUID().toString() },
                        original = orig,
                        replacement = obj.optString("replacement", ""),
                        ignoreCase = obj.optBoolean("ignoreCase", true),
                        matchWholeWord = obj.optBoolean("matchWholeWord", false),
                        enabled = obj.optBoolean("enabled", true)
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }
}

class PronunciationRepository(private val context: Context) {

    private object Keys {
        val RULES_JSON = stringPreferencesKey("pronunciation_rules_json")
        val SEEDED_DEFAULTS = booleanPreferencesKey("seeded_default_honorifics")
    }

    val rulesFlow: Flow<List<PronunciationRule>> = context.pronunciationDataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            val seeded = prefs[Keys.SEEDED_DEFAULTS] ?: false
            val rawJson = prefs[Keys.RULES_JSON]
            if (!seeded && rawJson.isNullOrBlank()) {
                PronunciationProcessor.DEFAULT_ANIME_HONORIFIC_RULES
            } else {
                PronunciationProcessor.parseRulesFromJson(rawJson.orEmpty())
            }
        }

    suspend fun upsertRule(rule: PronunciationRule) {
        context.pronunciationDataStore.edit { prefs ->
            val current = getCurrentListFromPrefs(prefs).toMutableList()
            val index = current.indexOfFirst { it.id == rule.id }
            if (index >= 0) {
                current[index] = rule
            } else {
                current.add(0, rule)
            }
            prefs[Keys.SEEDED_DEFAULTS] = true
            prefs[Keys.RULES_JSON] = PronunciationProcessor.serializeRulesToJson(current)
        }
    }

    suspend fun deleteRule(ruleId: String) {
        context.pronunciationDataStore.edit { prefs ->
            val current = getCurrentListFromPrefs(prefs).filterNot { it.id == ruleId }
            prefs[Keys.SEEDED_DEFAULTS] = true
            prefs[Keys.RULES_JSON] = PronunciationProcessor.serializeRulesToJson(current)
        }
    }

    suspend fun restoreDefaultHonorifics() {
        context.pronunciationDataStore.edit { prefs ->
            prefs[Keys.SEEDED_DEFAULTS] = true
            prefs[Keys.RULES_JSON] = PronunciationProcessor.serializeRulesToJson(
                PronunciationProcessor.DEFAULT_ANIME_HONORIFIC_RULES
            )
        }
    }

    suspend fun exportRulesJson(): String {
        val current = rulesFlow.first()
        return PronunciationProcessor.serializeRulesToJson(current)
    }

    suspend fun importRulesJson(json: String, mergeWithExisting: Boolean = true): Int {
        val parsed = PronunciationProcessor.parseRulesFromJson(json)
        if (parsed.isEmpty()) return 0

        context.pronunciationDataStore.edit { prefs ->
            val finalRules = if (mergeWithExisting) {
                val existing = getCurrentListFromPrefs(prefs).toMutableList()
                for (newRule in parsed) {
                    val idx = existing.indexOfFirst {
                        it.original.equals(newRule.original, ignoreCase = true)
                    }
                    if (idx >= 0) {
                        existing[idx] = newRule.copy(id = existing[idx].id)
                    } else {
                        existing.add(newRule)
                    }
                }
                existing
            } else {
                parsed
            }
            prefs[Keys.SEEDED_DEFAULTS] = true
            prefs[Keys.RULES_JSON] = PronunciationProcessor.serializeRulesToJson(finalRules)
        }
        return parsed.size
    }

    private fun getCurrentListFromPrefs(prefs: Preferences): List<PronunciationRule> {
        val seeded = prefs[Keys.SEEDED_DEFAULTS] ?: false
        val rawJson = prefs[Keys.RULES_JSON]
        return if (!seeded && rawJson.isNullOrBlank()) {
            PronunciationProcessor.DEFAULT_ANIME_HONORIFIC_RULES
        } else {
            PronunciationProcessor.parseRulesFromJson(rawJson.orEmpty())
        }
    }
}
