package com.example.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.AppSettings
import com.example.domain.model.NewSubtitleBehavior
import com.example.domain.model.OcrScriptMode
import com.example.domain.model.RoiMode
import com.example.domain.model.TranslationReadMode

private enum class ScanPreset(val label: String) {
    REALTIME("Tiempo real"),
    BALANCED("Equilibrado"),
    SAVER("Ahorro de batería")
}

private fun detectPreset(settings: AppSettings): ScanPreset = when {
    settings.batterySaverMode -> ScanPreset.SAVER
    settings.ocrIntervalMs <= 110L -> ScanPreset.REALTIME
    else -> ScanPreset.BALANCED
}

@Composable
fun ReadingScreen(
    vm: MainViewModel,
    platform: PlatformActions,
    modifier: Modifier = Modifier
) {
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val state by vm.readerState.collectAsStateWithLifecycle()
    val translationModels by vm.translationModelsState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader(
            title = "Lectura",
            subtitle = "Cómo SubVox encuentra y lee los subtítulos"
        )

        // --- Velocidad de escaneo ---
        SubVoxCard {
            Text(
                text = "Rendimiento",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            ChoiceChips(
                options = ScanPreset.entries.toList(),
                selected = detectPreset(settings),
                label = { it.label },
                onSelect = { preset ->
                    when (preset) {
                        ScanPreset.REALTIME -> {
                            vm.setBatterySaverMode(false)
                            vm.setOcrIntervalMs(100L)
                        }
                        ScanPreset.BALANCED -> {
                            vm.setBatterySaverMode(false)
                            vm.setOcrIntervalMs(140L)
                        }
                        ScanPreset.SAVER -> {
                            vm.setBatterySaverMode(true)
                            vm.setOcrIntervalMs(200L)
                        }
                    }
                }
            )
            Text(
                text = "SubVox vigila la pantalla varias veces por segundo con un análisis ligero y solo ejecuta el OCR cuando el subtítulo cambia. «Tiempo real» reacciona antes; «Ahorro» gasta menos batería.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SliderRow(
                title = "Frecuencia de vigilancia",
                subtitle = "Menos milisegundos = reacciona antes",
                value = settings.ocrIntervalMs.toFloat(),
                valueRange = 80f..500f,
                onValueCommitted = { vm.setOcrIntervalMs(it.toLong()) },
                valueLabel = { "${it.toInt()} ms" }
            )
        }

        // --- Zona de subtítulos ---
        SubVoxCard {
            Text(
                text = "Zona de subtítulos",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            ChoiceChips(
                options = RoiMode.entries.toList(),
                selected = settings.roiMode,
                label = { if (it == RoiMode.AUTO) "Automática" else "Manual" },
                onSelect = vm::setRoiMode
            )
            Text(
                text = if (settings.roiMode == RoiMode.AUTO) {
                    "SubVox busca el texto en la mitad inferior de la pantalla y se ajusta solo, aunque cambie de posición."
                } else {
                    "SubVox solo mira dentro del rectángulo que elijas: ideal si hay carteles o texto de la interfaz que molestan."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (settings.roiMode == RoiMode.MANUAL) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubVoxTonalButton(
                        onClick = {
                            if (platform.hasOverlayPermission) {
                                vm.toggleInteractiveRoiEditor()
                            } else {
                                platform.onRequestOverlayPermission()
                            }
                        },
                        enabled = state.serviceStatus != com.example.domain.model.ServiceStatus.INACTIVE ||
                            !platform.hasOverlayPermission
                    ) {
                        Icon(Icons.Rounded.CropFree, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Ajustar en pantalla")
                    }
                    SubVoxOutlinedButton(onClick = vm::resetManualRoiToDefault) { Text("Restablecer") }
                }
                if (state.serviceStatus == com.example.domain.model.ServiceStatus.INACTIVE) {
                    Text(
                        text = "Empieza a escuchar primero: el rectángulo se dibuja sobre tu reproductor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            SwitchRow(
                title = "Mostrar la zona detectada",
                subtitle = "Dibuja un marco sobre el vídeo (útil para comprobar)",
                checked = settings.showRoiBoxOnOverlay,
                onCheckedChange = vm::setShowRoiBoxOnOverlay
            )
        }

        // --- Idioma / OCR ---
        SubVoxCard {
            Text(
                text = "Idioma de los subtítulos",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            ChoiceChips(
                options = OcrScriptMode.entries.toList(),
                selected = settings.ocrScriptMode,
                label = {
                    when (it) {
                        OcrScriptMode.LATIN -> "Español / Latino"
                        OcrScriptMode.JAPANESE -> "Japonés"
                        OcrScriptMode.KOREAN -> "Coreano"
                        OcrScriptMode.CHINESE -> "Chino"
                        OcrScriptMode.AUTO -> "Automático"
                    }
                },
                onSelect = vm::setOcrScriptMode
            )
            Text(
                text = settings.ocrScriptMode.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SwitchRow(
                title = "Mejorar el contraste",
                subtitle = "Ayuda con subtítulos finos o de poco contraste",
                checked = settings.highContrastPreprocessing,
                onCheckedChange = vm::setHighContrastPreprocessing
            )
            SwitchRow(
                title = "Blanco y negro estricto",
                subtitle = "Solo si el OCR falla con fondos muy claros (puede empeorar otros casos)",
                checked = settings.binarizeOcrImage,
                onCheckedChange = vm::setBinarizeOcrImage
            )
        }

        // --- Comportamiento de lectura ---
        SubVoxCard {
            Text(
                text = "Cómo se lee",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            ChoiceChips(
                options = NewSubtitleBehavior.entries.toList(),
                selected = settings.newSubtitleBehavior,
                label = {
                    if (it == NewSubtitleBehavior.INTERRUPT_AND_READ_NEW) "Cortar y leer la nueva" else "Terminar la frase actual"
                },
                onSelect = vm::setNewSubtitleBehavior
            )
            SliderRow(
                title = "Ajuste de sincronía",
                subtitle = "Positivo: la voz espera un poco. Negativo: reacciona más rápido.",
                value = settings.syncOffsetMs.toFloat(),
                valueRange = -400f..1500f,
                onValueCommitted = { vm.setSyncOffsetMs(it.toInt()) },
                valueLabel = { "${if (it >= 0) "+" else ""}${it.toInt()} ms" }
            )
            SwitchRow(
                title = "Modo estudio",
                subtitle = "En Inicio, toca cualquier palabra de la frase para ver su significado",
                checked = settings.studyModeEnabled,
                onCheckedChange = vm::setStudyModeEnabled
            )
            SwitchRow(
                title = "Resaltar la palabra que suena",
                checked = settings.karaokeHighlightEnabled,
                onCheckedChange = vm::setKaraokeHighlightEnabled
            )
            SwitchRow(
                title = "Pausar si el vídeo se detiene",
                subtitle = "Deja de leer si la pantalla lleva varios segundos congelada",
                checked = settings.autoPauseOnStaticPlayer,
                onCheckedChange = vm::setAutoPauseOnStaticPlayer
            )
            SwitchRow(
                title = "Silenciar opening / ending",
                subtitle = "Ignora la letra de las canciones mientras esté activo",
                checked = settings.muteOpeningEndingActive,
                onCheckedChange = vm::setMuteOpeningEndingActive
            )
        }

        // --- Filtros ---
        SubVoxCard {
            Text(
                text = "Filtros",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            SwitchRow(
                title = "Filtrar texto no deseado",
                checked = settings.textFilterEnabled,
                onCheckedChange = vm::setTextFilterEnabled
            )
            SwitchRow(
                title = "Ignorar carteles de escena",
                subtitle = "Descarta el texto de letreros en la parte alta de la zona",
                checked = settings.ignoreUpperScreenSigns,
                onCheckedChange = vm::setIgnoreUpperScreenSigns,
                enabled = settings.textFilterEnabled
            )
            SwitchRow(
                title = "Ignorar créditos y fansub",
                checked = settings.ignoreCreditsAndStaff,
                onCheckedChange = vm::setIgnoreCreditsAndStaff,
                enabled = settings.textFilterEnabled
            )
            SwitchRow(
                title = "Saltar openings y endings",
                subtitle = "No lee las letras de canciones (♪) ni los rótulos de staff",
                checked = settings.skipSongLyrics,
                onCheckedChange = vm::setSkipSongLyrics,
                enabled = settings.textFilterEnabled
            )
            SliderRow(
                title = "Longitud mínima",
                value = settings.minSubtitleCharsFilter.toFloat(),
                valueRange = 1f..8f,
                steps = 6,
                onValueCommitted = { vm.setMinSubtitleCharsFilter(it.toInt()) },
                valueLabel = { "${it.toInt()} letras" }
            )
            var patterns by remember(settings.customFilterPatternsRaw) {
                mutableStateOf(settings.customFilterPatternsRaw)
            }
            OutlinedTextField(
                value = patterns,
                onValueChange = { patterns = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Palabras a ignorar (una por línea)") },
                minLines = 2
            )
            SubVoxOutlinedButton(
                onClick = { vm.setCustomFilterPatternsRaw(patterns) },
                enabled = patterns != settings.customFilterPatternsRaw
            ) { Text("Guardar filtros") }
        }

        // --- Traducción ---
        SubVoxCard {
            Text(
                text = "Traducción en vivo",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            SwitchRow(
                title = "Traducir antes de leer",
                subtitle = "Para ver anime con subtítulos en japonés, inglés u otro idioma",
                checked = settings.liveTranslationEnabled,
                onCheckedChange = vm::setLiveTranslationEnabled
            )
            if (settings.liveTranslationEnabled) {
                Text(
                    text = "Traducir a",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                ChoiceChips(
                    options = translationModels,
                    selected = translationModels.firstOrNull { it.languageCode == settings.translationTargetLanguageCode }
                        ?: translationModels.first(),
                    label = { it.displayName.substringBefore(" (") },
                    onSelect = { vm.setTranslationTargetLanguage(it.languageCode) }
                )
                val target = translationModels.firstOrNull { it.languageCode == settings.translationTargetLanguageCode }
                if (target != null && !target.isDownloaded) {
                    SubVoxTonalButton(
                        onClick = { vm.downloadTranslationModel(target.languageCode) },
                        enabled = !target.isDownloading
                    ) { Text(if (target.isDownloading) "Descargando…" else "Descargar idioma (${target.displayName.substringBefore(" (")})") }
                }
                Text(
                    text = "Qué leer",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                ChoiceChips(
                    options = TranslationReadMode.entries.toList(),
                    selected = settings.translationReadMode,
                    label = {
                        when (it) {
                            TranslationReadMode.TRANSLATED_ONLY -> "Solo la traducción"
                            TranslationReadMode.ORIGINAL_ONLY -> "Solo el original"
                            TranslationReadMode.BOTH_ORIGINAL_AND_TRANSLATED -> "Original y traducción"
                        }
                    },
                    onSelect = vm::setTranslationReadMode
                )
                SwitchRow(
                    title = "Descargar idiomas solo con Wi-Fi",
                    checked = settings.translationWifiOnlyDownload,
                    onCheckedChange = vm::setTranslationWifiOnlyDownload
                )
            }
        }

        // --- Panel flotante y atajos ---
        SubVoxCard {
            Text(
                text = "Panel flotante y atajos",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            SwitchRow(
                title = "Mostrar el panel flotante",
                subtitle = "Pausa, repetir y volumen sobre tu reproductor",
                checked = settings.showOverlayControls,
                onCheckedChange = vm::setShowOverlayControls
            )
            SwitchRow(
                title = "Botones de volumen como atajo",
                subtitle = "Con SubVox abierto: subir = repetir, bajar = pausar / reanudar",
                checked = settings.volumeButtonsShortcutEnabled,
                onCheckedChange = vm::setVolumeButtonsShortcutEnabled
            )
            SwitchRow(
                title = "Copiar cada frase al portapapeles",
                checked = settings.autoCopyToClipboard,
                onCheckedChange = vm::setAutoCopyToClipboard
            )
        }

        Spacer(Modifier.height(12.dp))
    }
}
