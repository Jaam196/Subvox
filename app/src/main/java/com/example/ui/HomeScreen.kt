package com.example.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.example.ui.theme.CardDecor
import com.example.ui.theme.LocalAppTheme
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.CaptureWarning
import com.example.domain.model.ReaderState
import com.example.domain.model.ServiceStatus
import com.example.study.StudyGlossaryManager
import com.example.study.StudyWordEntry
import com.example.tts.offline.OfflineDownloadState
import com.example.tts.offline.OfflineVoiceCatalog

/** Acciones que dependen de la Activity (permisos e Intents del sistema). */
data class PlatformActions(
    val hasOverlayPermission: Boolean,
    val hasNotificationPermission: Boolean,
    val hasUsageStatsPermission: Boolean,
    val onStartReader: () -> Unit,
    val onRequestOverlayPermission: () -> Unit,
    val onRequestNotificationPermission: () -> Unit,
    val onRequestUsageStats: () -> Unit,
    val onInstallTtsData: () -> Unit,
    val onOpenSystemTtsSettings: () -> Unit
)

@Composable
fun HomeScreen(
    vm: MainViewModel,
    platform: PlatformActions,
    modifier: Modifier = Modifier
) {
    val state by vm.readerState.collectAsStateWithLifecycle()
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val downloadStates by vm.offlineDownloadStates.collectAsStateWithLifecycle()
    val downloadState: OfflineDownloadState =
        downloadStates[settings.offlineVoiceSelectedId] ?: OfflineDownloadState.Idle
    val modelInstalled = remember(settings.offlineVoiceSelectedId, downloadStates) {
        vm.offlineModelManager.isModelInstalled(settings.offlineVoiceSelectedId)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader(
            title = "SubVox",
            subtitle = "Lee en voz alta los subtítulos de lo que estás viendo"
        )

        HeroCard(
            state = state,
            onStart = platform.onStartReader,
            onPause = vm::pauseReader,
            onResume = vm::resumeReader,
            onStop = vm::stopReader
        )

        WarningBanner(
            warning = state.captureWarning,
            onInstallVoice = platform.onInstallTtsData
        )

        if (settings.offlineVoiceEnabled && !modelInstalled && !settings.offlinePromptDismissed) {
            VoiceDownloadPrompt(
                modelName = OfflineVoiceCatalog.getModelById(settings.offlineVoiceSelectedId).displayName,
                sizeMb = (OfflineVoiceCatalog.getModelById(settings.offlineVoiceSelectedId).archiveSizeBytes / 1_000_000L).toInt(),
                downloadState = downloadState,
                onDownload = { vm.startOfflineModelDownload() },
                onCancel = { vm.cancelOfflineModelDownload() },
                onDismiss = vm::dismissOfflinePrompt
            )
        }

        NowReadingCard(
            state = state,
            karaokeEnabled = settings.karaokeHighlightEnabled,
            studyMode = settings.studyModeEnabled,
            onRepeat = vm::repeatLastSubtitle,
            onPrevious = vm::repeatPreviousSubtitle,
            onSpeakWord = vm::speakTextSample
        )

        QuickMixCard(vm = vm, modelInstalled = modelInstalled)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                label = "Frases leídas",
                value = state.subtitlesReadCount.toString(),
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = "Último OCR",
                value = if (state.lastLatencyMs > 0L) "${state.lastLatencyMs} ms" else "—",
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = "Palabras",
                value = state.totalWordsSpokenInSession.toString(),
                modifier = Modifier.weight(1f)
            )
        }

        if (state.serviceStatus == ServiceStatus.INACTIVE) {
            SetupChecklist(platform = platform)
        }

        Spacer(Modifier.height(12.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Tarjeta principal con el botón grande
// ---------------------------------------------------------------------------------------------

@Composable
private fun HeroCard(
    state: ReaderState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val status = state.serviceStatus
    val accent = when (status) {
        ServiceStatus.ACTIVE_SCANNING -> scheme.secondary
        ServiceStatus.PAUSED -> scheme.tertiary
        ServiceStatus.INACTIVE -> scheme.primary
    }
    val pulsing = status == ServiceStatus.ACTIVE_SCANNING && !state.isAutoPausedByStaticVideo

    val title = when {
        status == ServiceStatus.INACTIVE -> "Toca para empezar"
        status == ServiceStatus.PAUSED -> "En pausa"
        state.isAutoPausedByStaticVideo -> "Vídeo detenido"
        else -> "Escuchando tu episodio"
    }
    val subtitle = when {
        status == ServiceStatus.INACTIVE ->
            "Abre después tu navegador o reproductor y pon el episodio."
        status == ServiceStatus.PAUSED -> "Toca para reanudar la lectura."
        state.isAutoPausedByStaticVideo -> "Se reanuda sola cuando el vídeo vuelva a moverse."
        state.isOcrProcessing -> "Leyendo la pantalla…"
        else -> "Detecta cada subtítulo nuevo y lo lee al instante."
    }

    SubVoxCard(
        containerColor = scheme.surfaceContainer,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusPill(
                text = when (status) {
                    ServiceStatus.ACTIVE_SCANNING -> "ESCUCHANDO"
                    ServiceStatus.PAUSED -> "EN PAUSA"
                    ServiceStatus.INACTIVE -> "DETENIDO"
                },
                color = accent
            )

            Box(
                modifier = Modifier.size(176.dp),
                contentAlignment = Alignment.Center
            ) {
                if (pulsing) {
                    PulseRings(color = accent, modifier = Modifier.fillMaxSize())
                }
                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .clip(LocalAppTheme.current.heroShape)
                        .background(
                            Brush.linearGradient(
                                listOf(accent, accent.copy(alpha = 0.65f))
                            )
                        )
                        .clickable {
                            when (status) {
                                ServiceStatus.INACTIVE -> onStart()
                                ServiceStatus.ACTIVE_SCANNING -> onPause()
                                ServiceStatus.PAUSED -> onResume()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (status == ServiceStatus.ACTIVE_SCANNING) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = title,
                        tint = scheme.background,
                        modifier = Modifier.size(54.dp)
                    )
                }
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
                textAlign = TextAlign.Center
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (status != ServiceStatus.INACTIVE) {
                SubVoxTonalButton(onClick = onStop) {
                    Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Detener")
                }
            }

            Text(
                text = state.activeSpeechTierName,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PulseRings(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2600, easing = LinearEasing)),
        label = "phase"
    )
    val squares = LocalAppTheme.current.cardDecor == CardDecor.BRACKETS
    Canvas(modifier = modifier) {
        val maxRadius = size.minDimension / 2f
        for (i in 0 until 3) {
            val p = (phase + i / 3f) % 1f
            val radius = maxRadius * (0.62f + 0.38f * p)
            if (squares) {
                // Temas "HUD": cuadrados que giran y se expanden
                val side = radius * 1.5f
                rotate(degrees = 45f + 90f * p, pivot = center) {
                    drawRect(
                        color = color.copy(alpha = (1f - p) * 0.50f),
                        topLeft = Offset(center.x - side / 2f, center.y - side / 2f),
                        size = Size(side, side),
                        style = Stroke(width = 2.5.dp.toPx())
                    )
                }
            } else {
                drawCircle(
                    color = color.copy(alpha = (1f - p) * 0.40f),
                    radius = radius,
                    style = Stroke(width = 3.dp.toPx())
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Avisos
// ---------------------------------------------------------------------------------------------

@Composable
private fun WarningBanner(warning: CaptureWarning, onInstallVoice: () -> Unit) {
    if (warning == CaptureWarning.NONE) return

    val message = when (warning) {
        CaptureWarning.BLACK_SCREEN_OR_DRM ->
            "La pantalla se ve negra: el reproductor bloquea la captura (DRM / contenido protegido). Prueba con otro navegador o reproductor."
        CaptureWarning.PROJECTION_REVOKED ->
            "El sistema detuvo la captura de pantalla. Vuelve a pulsar «Empezar»."
        CaptureWarning.TTS_SPANISH_VOICE_MISSING ->
            "No hay ninguna voz en español instalada en el sistema."
        CaptureWarning.OCR_ENGINE_DOWNLOADING ->
            "Descargando el modelo de reconocimiento de texto. Necesita Internet solo esta vez."
        CaptureWarning.NONE -> ""
    }

    SubVoxCard(
        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (warning == CaptureWarning.TTS_SPANISH_VOICE_MISSING) {
                    TextButton(onClick = onInstallVoice) { Text("Instalar voz") }
                }
            }
        }
    }
}

@Composable
private fun VoiceDownloadPrompt(
    modelName: String,
    sizeMb: Int,
    downloadState: OfflineDownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    SubVoxCard(
        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    ) {
        Text(
            text = "Activa la voz natural",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Descarga «$modelName» (≈ $sizeMb MB, una sola vez) y SubVox sonará mucho más humano que la voz del sistema. Después funciona sin Internet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        when (downloadState) {
            is OfflineDownloadState.Downloading -> {
                LinearProgressIndicator(
                    progress = { (downloadState.progressPercent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = downloadState.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SubVoxOutlinedButton(onClick = onCancel) { Text("Cancelar") }
            }
            is OfflineDownloadState.Error -> {
                Text(
                    text = downloadState.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubVoxButton(onClick = onDownload) { Text("Reintentar") }
                    TextButton(onClick = onDismiss) { Text("Ahora no") }
                }
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubVoxButton(onClick = onDownload) { Text("Descargar") }
                    TextButton(onClick = onDismiss) { Text("Ahora no") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Lo que se está leyendo
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NowReadingCard(
    state: ReaderState,
    karaokeEnabled: Boolean,
    studyMode: Boolean,
    onRepeat: () -> Unit,
    onPrevious: () -> Unit,
    onSpeakWord: (String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val text = state.lastDetectedSubtitle
    var selectedWord by remember { mutableStateOf<StudyWordEntry?>(null) }

    SubVoxCard {
        Text(
            text = "AHORA SUENA",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.primary
        )

        if (text.isBlank()) {
            Text(
                text = "Aquí aparecerá cada frase que SubVox lea.",
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant
            )
        } else {
            Text(
                text = buildKaraokeText(
                    text = text,
                    spokenText = state.karaokeSpokenText,
                    start = state.karaokeWordStartChar,
                    end = state.karaokeWordEndChar,
                    enabled = karaokeEnabled,
                    highlight = scheme.secondary
                ),
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface
            )
            if (state.lastTranslatedSubtitle.isNotBlank() && state.lastOriginalSubtitle.isNotBlank()) {
                Text(
                    text = "Original: ${state.lastOriginalSubtitle}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }

        if (studyMode && text.isNotBlank()) {
            val originalForStudy = state.lastOriginalSubtitle.ifBlank { text }
            val tokens = remember(originalForStudy) {
                StudyGlossaryManager.tokenizeSubtitleForStudy(originalForStudy)
            }
            if (tokens.isNotEmpty()) {
                Text(
                    text = "Toca una palabra para ver su significado",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tokens.take(24).forEach { word ->
                        AssistChip(
                            onClick = { selectedWord = StudyGlossaryManager.lookupWordOffline(word) },
                            label = { Text(word) }
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubVoxTonalButton(onClick = onPrevious, enabled = state.recentSubtitles.isNotEmpty()) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Anterior")
            }
            SubVoxTonalButton(onClick = onRepeat, enabled = text.isNotBlank()) {
                Icon(Icons.Rounded.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Repetir")
            }
        }
    }

    val entry = selectedWord
    if (entry != null) {
        AlertDialog(
            onDismissRequest = { selectedWord = null },
            confirmButton = { TextButton(onClick = { selectedWord = null }) { Text("Cerrar") } },
            dismissButton = { TextButton(onClick = { onSpeakWord(entry.term) }) { Text("Escuchar") } },
            title = { Text(entry.term) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(entry.reading, style = MaterialTheme.typography.labelLarge)
                    Text(entry.meaningEs, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        entry.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }
}

private fun buildKaraokeText(
    text: String,
    spokenText: String,
    start: Int,
    end: Int,
    enabled: Boolean,
    highlight: Color
): AnnotatedString {
    val valid = enabled && spokenText == text && start >= 0 && end > start && end <= text.length
    if (!valid) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text.substring(0, start))
        withStyle(SpanStyle(color = highlight, fontWeight = FontWeight.Bold)) {
            append(text.substring(start, end))
        }
        append(text.substring(end))
    }
}

// ---------------------------------------------------------------------------------------------
// Mezcla rápida de audio
// ---------------------------------------------------------------------------------------------

@Composable
private fun QuickMixCard(vm: MainViewModel, modelInstalled: Boolean) {
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val usingNeural = settings.offlineVoiceEnabled && modelInstalled && !settings.naturalVoiceEnabled

    SubVoxCard {
        Text(
            text = "Audio",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        SwitchRow(
            title = "Leer en voz alta",
            subtitle = "Apágalo para solo ver el texto detectado",
            checked = settings.ttsEnabled,
            onCheckedChange = vm::setTtsEnabled
        )

        SwitchRow(
            title = "Bajar el volumen del vídeo mientras habla",
            subtitle = "La voz suena por encima del episodio",
            checked = settings.duckOtherAudio,
            onCheckedChange = vm::setDuckOtherAudio
        )

        SliderRow(
            title = "Volumen de la voz",
            value = settings.volume,
            valueRange = 0.2f..1f,
            onValueCommitted = vm::setVolume,
            valueLabel = { "${(it * 100).toInt()} %" }
        )

        SliderRow(
            title = "Potenciar la voz",
            subtitle = "Ganancia extra por encima del volumen máximo",
            value = settings.volumeBoostDb.toFloat(),
            valueRange = 0f..15f,
            steps = 14,
            onValueCommitted = { vm.setVolumeBoostDb(it.toInt()) },
            valueLabel = { if (it.toInt() == 0) "Apagado" else "+${it.toInt()} dB" }
        )

        if (usingNeural) {
            SliderRow(
                title = "Velocidad de la voz",
                value = settings.offlineVoiceSpeed,
                valueRange = 0.7f..1.4f,
                onValueCommitted = vm::onOfflineVoiceSpeedChange,
                valueLabel = { "%.2f×".format(it) }
            )
        } else {
            SliderRow(
                title = "Velocidad de la voz",
                value = settings.speechRate,
                valueRange = 0.6f..1.8f,
                onValueCommitted = vm::setSpeechRate,
                valueLabel = { "%.2f×".format(it) }
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Lista de requisitos
// ---------------------------------------------------------------------------------------------

@Composable
private fun SetupChecklist(platform: PlatformActions) {
    SubVoxCard {
        Text(
            text = "Antes de empezar",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        PermissionRow(
            title = "Notificaciones",
            description = "Para mostrar los controles mientras SubVox escucha.",
            granted = platform.hasNotificationPermission,
            onGrant = platform.onRequestNotificationPermission
        )
        PermissionRow(
            title = "Mostrar sobre otras apps",
            description = "Para el panel flotante con pausa, repetir y volumen.",
            granted = platform.hasOverlayPermission,
            onGrant = platform.onRequestOverlayPermission
        )
        Text(
            text = "Al empezar, Android te pedirá permiso para grabar la pantalla. SubVox solo lee los subtítulos en tu móvil; nada se envía a Internet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
            contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!granted) {
            SubVoxTonalButton(onClick = onGrant) { Text("Conceder") }
        }
    }
}
