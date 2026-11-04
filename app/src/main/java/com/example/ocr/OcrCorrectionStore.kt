package com.example.ocr

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private val Context.ocrCorrectionsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "subvox_ocr_corrections"
)

/** Guarda en el móvil las correcciones de OCR que el usuario enseña a la app. */
class OcrCorrectionStore(private val context: Context) {

    private object Keys {
        val RULES_JSON = stringPreferencesKey("rules_json")
    }

    val rulesFlow: Flow<List<OcrCorrection>> = context.ocrCorrectionsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> parse(prefs[Keys.RULES_JSON].orEmpty()) }

    private fun parse(json: String): List<OcrCorrection> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val wrong = o.optString("wrong")
                if (wrong.isEmpty()) return@mapNotNull null
                OcrCorrection(
                    id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                    wrong = wrong,
                    right = o.optString("right")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun serialize(rules: List<OcrCorrection>): String {
        val array = JSONArray()
        rules.forEach {
            array.put(JSONObject().put("id", it.id).put("wrong", it.wrong).put("right", it.right))
        }
        return array.toString()
    }

    /** Añade o actualiza (una sola corrección por texto erróneo). */
    suspend fun add(wrong: String, right: String): Boolean {
        val w = wrong.trim()
        val r = right.trim()
        if (w.isEmpty() || w == r) return false
        context.ocrCorrectionsDataStore.edit { prefs ->
            val current = parse(prefs[Keys.RULES_JSON].orEmpty()).toMutableList()
            val idx = current.indexOfFirst { it.wrong == w }
            if (idx >= 0) current[idx] = current[idx].copy(right = r) else current.add(0, OcrCorrection(wrong = w, right = r))
            prefs[Keys.RULES_JSON] = serialize(current.take(500))
        }
        return true
    }

    /** Aprende de un texto corregido a mano. Devuelve los pares aprendidos. */
    suspend fun learn(original: String, corrected: String): List<Pair<String, String>> {
        val pairs = OcrCorrector.learn(original, corrected)
        pairs.forEach { add(it.first, it.second) }
        return pairs
    }

    suspend fun delete(id: String) {
        context.ocrCorrectionsDataStore.edit { prefs ->
            prefs[Keys.RULES_JSON] = serialize(parse(prefs[Keys.RULES_JSON].orEmpty()).filterNot { it.id == id })
        }
    }

    suspend fun clear() {
        context.ocrCorrectionsDataStore.edit { it.remove(Keys.RULES_JSON) }
    }
}
