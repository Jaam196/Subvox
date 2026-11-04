package com.example.profiles

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.AppSettings
import com.example.domain.model.NormalizedRect
import com.example.domain.model.OcrScriptMode
import com.example.domain.model.RoiMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private val Context.appProfilesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "animesub_voz_app_profiles"
)

/**
 * Perfil personalizado por aplicación reproductora o navegador (Crunchyroll, Netflix, YouTube,
 * Chrome, VLC, Firefox, etc.): permite sobreescribir ROI, voz, velocidad, tono, volumen,
 * refuerzo dB, modo de script OCR y activación del diccionario.
 */
data class AppProfile(
    val packageName: String,
    val appDisplayName: String,
    val enabled: Boolean = true,
    val roiMode: RoiMode = RoiMode.AUTO,
    val customRoi: NormalizedRect = NormalizedRect.DEFAULT_SUBTITLE_ROI,
    val preferredLanguageTag: String = "es-ES",
    val selectedVoiceName: String = "",
    val speechRate: Float = 1.08f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f,
    val volumeBoostDb: Int = 0,
    val ocrScriptMode: OcrScriptMode = OcrScriptMode.LATIN,
    val usePronunciationDictionary: Boolean = true
) {
    /**
     * Aplica las sobrecargas del perfil sobre un [AppSettings] global sin perder el resto de ajustes.
     */
    fun applyOverlayToSettings(base: AppSettings): AppSettings {
        if (!enabled) return base
        return base.copy(
            roiMode = roiMode,
            manualRoi = customRoi,
            preferredLanguageTag = preferredLanguageTag.ifBlank { base.preferredLanguageTag },
            selectedVoiceName = selectedVoiceName.ifBlank { base.selectedVoiceName },
            speechRate = speechRate.coerceIn(0.5f, 2.2f),
            pitch = pitch.coerceIn(0.5f, 1.8f),
            volume = volume.coerceIn(0.0f, 1.0f),
            volumeBoostDb = volumeBoostDb.coerceIn(0, 15),
            ocrScriptMode = ocrScriptMode,
            pronunciationDictionaryEnabled = usePronunciationDictionary
        )
    }
}

object ForegroundAppDetector {

    /**
     * Comprueba si el usuario ha concedido el permiso especial `PACKAGE_USAGE_STATS`
     * en Ajustes -> Acceso especial -> Acceso a datos de uso.
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Devuelve el `packageName` de la última aplicación puesta en primer plano (excluyendo
     * esta misma app `com.example` / `AnimeSub Voz` y el SystemUI).
     */
    fun detectForegroundPackage(context: Context, previousKnownPackage: String = ""): String {
        if (!hasUsageStatsPermission(context)) return previousKnownPackage

        return runCatching {
            val usageStatsManager =
                context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                    ?: return previousKnownPackage

            val now = System.currentTimeMillis()
            // Consultamos los últimos 30 segundos de eventos de cambio a primer plano
            val events = usageStatsManager.queryEvents(now - 30_000L, now)
            val event = UsageEvents.Event()
            var latestPkg = ""
            var latestTime = 0L

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val isForegroundEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                } else {
                    @Suppress("DEPRECATION")
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                }
                if (isForegroundEvent && event.timeStamp >= latestTime) {
                    val pkg = event.packageName.orEmpty()
                    if (isRelevantExternalApp(pkg, context.packageName)) {
                        latestTime = event.timeStamp
                        latestPkg = pkg
                    }
                }
            }

            if (latestPkg.isNotBlank()) {
                latestPkg
            } else if (previousKnownPackage.isNotBlank()) {
                previousKnownPackage
            } else {
                // Fallback a queryUsageStats en ventana de 2 minutos
                val stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    now - 120_000L,
                    now
                )
                stats
                    ?.filter { isRelevantExternalApp(it.packageName, context.packageName) }
                    ?.maxByOrNull { it.lastTimeUsed }
                    ?.packageName
                    .orEmpty()
            }
        }.getOrDefault(previousKnownPackage)
    }

    private fun isRelevantExternalApp(pkg: String, ownPkg: String): Boolean {
        if (pkg.isBlank()) return false
        if (pkg == ownPkg) return false
        if (pkg == "com.android.systemui") return false
        return true
    }
}

class AppProfileRepository(private val context: Context) {

    companion object {
        val DEFAULT_PRESET_PROFILES: List<AppProfile> = listOf(
            AppProfile(
                packageName = "com.kpstv.yts",
                appDisplayName = "Crunchyroll (com.Partem.crunchyroll)",
                enabled = true
            ).copy(
                packageName = "com.ellation.crunchyroll",
                appDisplayName = "Crunchyroll",
                roiMode = RoiMode.MANUAL,
                customRoi = NormalizedRect(0.06f, 0.75f, 0.94f, 0.94f),
                speechRate = 1.12f,
                volumeBoostDb = 2
            ),
            AppProfile(
                packageName = "com.android.chrome",
                appDisplayName = "Google Chrome (Anime Web)",
                enabled = true,
                roiMode = RoiMode.AUTO,
                customRoi = NormalizedRect.DEFAULT_SUBTITLE_ROI,
                speechRate = 1.08f
            ),
            AppProfile(
                packageName = "com.google.android.youtube",
                appDisplayName = "YouTube",
                enabled = true,
                roiMode = RoiMode.MANUAL,
                customRoi = NormalizedRect(0.08f, 0.72f, 0.92f, 0.91f),
                speechRate = 1.10f
            ),
            AppProfile(
                packageName = "com.netflix.mediaclient",
                appDisplayName = "Netflix",
                enabled = true,
                roiMode = RoiMode.MANUAL,
                customRoi = NormalizedRect(0.08f, 0.76f, 0.92f, 0.94f),
                speechRate = 1.06f
            ),
            AppProfile(
                packageName = "org.videolan.vlc",
                appDisplayName = "VLC Media Player",
                enabled = true,
                roiMode = RoiMode.MANUAL,
                customRoi = NormalizedRect(0.06f, 0.76f, 0.94f, 0.95f),
                speechRate = 1.08f
            )
        )
    }

    private object Keys {
        val PROFILES_JSON = stringPreferencesKey("app_profiles_json")
        val SEEDED_PRESETS = booleanPreferencesKey("seeded_preset_profiles")
    }

    val profilesFlow: Flow<List<AppProfile>> = context.appProfilesDataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            val seeded = prefs[Keys.SEEDED_PRESETS] ?: false
            val rawJson = prefs[Keys.PROFILES_JSON]
            if (!seeded && rawJson.isNullOrBlank()) {
                DEFAULT_PRESET_PROFILES
            } else {
                parseProfilesJson(rawJson.orEmpty())
            }
        }

    suspend fun upsertProfile(profile: AppProfile) {
        context.appProfilesDataStore.edit { prefs ->
            val current = getCurrentList(prefs).toMutableList()
            val idx = current.indexOfFirst { it.packageName == profile.packageName }
            if (idx >= 0) {
                current[idx] = profile
            } else {
                current.add(0, profile)
            }
            prefs[Keys.SEEDED_PRESETS] = true
            prefs[Keys.PROFILES_JSON] = serializeProfilesJson(current)
        }
    }

    suspend fun deleteProfile(packageName: String) {
        context.appProfilesDataStore.edit { prefs ->
            val current = getCurrentList(prefs).filterNot { it.packageName == packageName }
            prefs[Keys.SEEDED_PRESETS] = true
            prefs[Keys.PROFILES_JSON] = serializeProfilesJson(current)
        }
    }

    suspend fun restorePresetProfiles() {
        context.appProfilesDataStore.edit { prefs ->
            prefs[Keys.SEEDED_PRESETS] = true
            prefs[Keys.PROFILES_JSON] = serializeProfilesJson(DEFAULT_PRESET_PROFILES)
        }
    }

    private fun getCurrentList(prefs: Preferences): List<AppProfile> {
        val seeded = prefs[Keys.SEEDED_PRESETS] ?: false
        val rawJson = prefs[Keys.PROFILES_JSON]
        return if (!seeded && rawJson.isNullOrBlank()) {
            DEFAULT_PRESET_PROFILES
        } else {
            parseProfilesJson(rawJson.orEmpty())
        }
    }

    fun serializeProfilesJson(profiles: List<AppProfile>): String {
        val array = JSONArray()
        for (p in profiles) {
            val obj = JSONObject().apply {
                put("packageName", p.packageName)
                put("appDisplayName", p.appDisplayName)
                put("enabled", p.enabled)
                put("roiMode", p.roiMode.name)
                put("roiLeft", p.customRoi.left.toDouble())
                put("roiTop", p.customRoi.top.toDouble())
                put("roiRight", p.customRoi.right.toDouble())
                put("roiBottom", p.customRoi.bottom.toDouble())
                put("preferredLanguageTag", p.preferredLanguageTag)
                put("selectedVoiceName", p.selectedVoiceName)
                put("speechRate", p.speechRate.toDouble())
                put("pitch", p.pitch.toDouble())
                put("volume", p.volume.toDouble())
                put("volumeBoostDb", p.volumeBoostDb)
                put("ocrScriptMode", p.ocrScriptMode.name)
                put("usePronunciationDictionary", p.usePronunciationDictionary)
            }
            array.put(obj)
        }
        return array.toString(2)
    }

    fun parseProfilesJson(json: String): List<AppProfile> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            val list = mutableListOf<AppProfile>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val pkg = obj.optString("packageName", "").trim()
                if (pkg.isEmpty()) continue
                val defRoi = NormalizedRect.DEFAULT_SUBTITLE_ROI
                val roi = NormalizedRect(
                    left = obj.optDouble("roiLeft", defRoi.left.toDouble()).toFloat(),
                    top = obj.optDouble("roiTop", defRoi.top.toDouble()).toFloat(),
                    right = obj.optDouble("roiRight", defRoi.right.toDouble()).toFloat(),
                    bottom = obj.optDouble("roiBottom", defRoi.bottom.toDouble()).toFloat()
                ).clamp()
                val roiModeStr = obj.optString("roiMode", RoiMode.AUTO.name)
                list.add(
                    AppProfile(
                        packageName = pkg,
                        appDisplayName = obj.optString("appDisplayName", pkg),
                        enabled = obj.optBoolean("enabled", true),
                        roiMode = runCatching { RoiMode.valueOf(roiModeStr) }.getOrDefault(RoiMode.AUTO),
                        customRoi = roi,
                        preferredLanguageTag = obj.optString("preferredLanguageTag", "es-ES"),
                        selectedVoiceName = obj.optString("selectedVoiceName", ""),
                        speechRate = obj.optDouble("speechRate", 1.08).toFloat().coerceIn(0.5f, 2.2f),
                        pitch = obj.optDouble("pitch", 1.0).toFloat().coerceIn(0.5f, 1.8f),
                        volume = obj.optDouble("volume", 1.0).toFloat().coerceIn(0.0f, 1.0f),
                        volumeBoostDb = obj.optInt("volumeBoostDb", 0).coerceIn(0, 15),
                        ocrScriptMode = OcrScriptMode.fromNameSafe(obj.optString("ocrScriptMode")),
                        usePronunciationDictionary = obj.optBoolean("usePronunciationDictionary", true)
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }
}
