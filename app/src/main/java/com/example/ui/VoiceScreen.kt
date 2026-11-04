package com.example.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.AppSettings
import com.example.domain.model.CharacterVoiceMode
import com.example.security.ApiKeyValidationResult
import com.example.security.UserApiKeyState
import com.example.tts.GeminiNaturalVoiceOption
import com.example.tts.offline.OfflineDownloadState
import com.example.tts.offline.OfflineVoiceCatalog

private enum class VoiceEngineChoice { NEURAL, SYSTEM, GEMINI }

@Composable
fun VoiceScreen(
    vm: MainViewModel,
    platform: PlatformActions,
    modifier: Modifier = Modifier
) {
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val hasApiKey by vm.hasApiKeyState.collectAsStateWithLifecycle()

    val current = when {
        settings.naturalVoiceEnabled && hasApiKey -> VoiceEngineChoice.GEMINI
        settings.offlineVoiceEnabled -> VoiceEngineChoice.NEURAL
        else -> VoiceEngineChoice.SYSTEM
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader(
            title = "Voz",
            subtitle = "Elige quién lee los subtítulos y cómo suena"
        )

        VoiceModeCard(
            title = "Natural sin conexión",
            description = "Voz neuronal Piper en tu móvil. Entonación humana, sin Internet ni cuentas.",
            badge = "Recomendada",
            selected = current == VoiceEngineChoice.NEURAL,
            onClick = { vm.setPrimaryVoiceType(MainViewModel.VoiceModeOption.NEURAL_OFFLINE) }
        )
        VoiceModeCard(
            title = "Voz del sistema",
            description = "La voz de Android / Google. Instantánea y ligera, pero más robótica.",
            badge = null,
            selected = current == VoiceEngineChoice.SYSTEM,
            onClick = { vm.setPrimaryVoiceType(MainViewModel.VoiceModeOption.SYSTEM_TTS) }
        )
        VoiceModeCard(
            title = "Gemini (IA en la nube)",
            description = "Voces expresivas de Google. Necesita Internet y tu clave de API.",
            badge = "Avanzado",
            selected = current == VoiceEngineChoice.GEMINI,
            onClick = { vm.setPrimaryVoiceType(MainViewModel.VoiceModeOption.GEMINI_AI) }
        )

        when (current) {
            VoiceEngineChoice.NEURAL -> NeuralVoiceSection(vm = vm, settings = settings)
            VoiceEngineChoice.SYSTEM -> SystemVoiceSection(vm = vm, settings = settings, platform = platform)
            VoiceEngineChoice.GEMINI -> GeminiSection(vm = vm, settings = settings)
        }

        CharacterVoicesCard(vm = vm, settings = settings)

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun VoiceModeCard(
    title: String,
    description: String,
    badge: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = if (selected) scheme.primaryContainer.copy(alpha = 0.55f) else scheme.surfaceContainer,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) scheme.primary else scheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface
                    )
                    if (badge != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.secondary
                        )
                    }
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Voz neuronal offline
// ---------------------------------------------------------------------------------------------

@Composable
private fun NeuralVoiceSection(vm: MainViewModel, settings: AppSettings) {
    val downloadStates by vm.offlineDownloadStates.collectAsStateWithLifecycle()
    val readerState by vm.readerState.collectAsStateWithLifecycle()
    var refreshTick by remember { mutableStateOf(0) }

    SubVoxCard {
        Text(
            text = "Voces disponibles",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Se descargan una sola vez y se guardan en tu móvil. Prueba varias: cada una tiene su carácter.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OfflineVoiceCatalog.CANDIDATE_VOICES.forEach { spec ->
            val specState: OfflineDownloadState = downloadStates[spec.id] ?: OfflineDownloadState.Idle
            val installed = remember(spec.id, downloadStates, refreshTick) {
                vm.offlineModelManager.isModelInstalled(spec.id)
            }
            val isSelected = settings.offlineVoiceSelectedId == spec.id
            val isDownloadingThis = !installed && specState is OfflineDownloadState.Downloading

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { vm.onOfflineVoiceSelectedIdChange(spec.id) },
                shape = MaterialTheme.shapes.medium,
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { vm.onOfflineVoiceSelectedIdChange(spec.id) }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = spec.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = spec.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (installed) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Instalada",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                vm.deleteOfflineModel(spec.id)
                                refreshTick++
                            }) {
                                Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Quitar")
                            }
                        } else if (!isDownloadingThis) {
                            Text(
                                text = "${spec.archiveSizeBytes / 1_000_000L} MB",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            SubVoxTonalButton(onClick = {
                                vm.onOfflineVoiceSelectedIdChange(spec.id)
                                vm.startOfflineModelDownload(spec.id)
                            }) { Text("Descargar") }
                        }
                    }

                    if (!installed && specState is OfflineDownloadState.Error) {
                        Text(
                            text = specState.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    if (isDownloadingThis) {
                        val progress = specState as OfflineDownloadState.Downloading
                        LinearProgressIndicator(
                            progress = { (progress.progressPercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = progress.statusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { vm.cancelOfflineModelDownload(spec.id) }) { Text("Cancelar") }
                        }
                    }
                }
            }
        }

        if (readerState.offlineVoiceFallbackNotice.isNotBlank()) {
            Text(
                text = readerState.offlineVoiceFallbackNotice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }

    SubVoxCard {
        Text(
            text = "Ajustes de la voz natural",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        SliderRow(
            title = "Velocidad",
            value = settings.offlineVoiceSpeed,
            valueRange = 0.7f..1.4f,
            onValueCommitted = vm::onOfflineVoiceSpeedChange,
            valueLabel = { "%.2f×".format(it) }
        )
        SwitchRow(
            title = "Acelerar si los subtítulos van rápidos",
            subtitle = "Si la voz se queda atrás, acelera hasta un 30 % y vuelve a la normal cuando va holgada",
            checked = settings.catchUpSpeedEnabled,
            onCheckedChange = vm::setCatchUpSpeedEnabled
        )
        Text(
            text = "Potencia de cálculo",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        ChoiceChips(
            options = listOf(2, 3, 4),
            selected = settings.offlineVoiceThreads,
            label = { "$it hilos" },
            onSelect = vm::onOfflineVoiceThreadsChange
        )
        Text(
            text = "Más hilos = voz más rápida, pero más batería. Con 2 basta en la mayoría de móviles.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SwitchRow(
            title = "Descargar solo con Wi-Fi",
            checked = settings.offlineOnlyOnWifi,
            onCheckedChange = vm::onOfflineOnlyOnWifiChange
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Memoria de frases: ${readerState.offlineCacheUsedBytes / 1_000_000L} MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = vm::clearOfflineCache) { Text("Vaciar") }
        }

        SubVoxButton(onClick = { vm.testOfflineVoice() }) {
            Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Probar esta voz")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Voz del sistema
// ---------------------------------------------------------------------------------------------

@Composable
private fun SystemVoiceSection(vm: MainViewModel, settings: AppSettings, platform: PlatformActions) {
    val voices by vm.availableVoices.collectAsStateWithLifecycle()
    val engines by vm.availableEngines.collectAsStateWithLifecycle()

    SubVoxCard {
        Text(
            text = "Voz del sistema",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (voices.isEmpty()) {
            Text(
                text = "No se ha encontrado ninguna voz en español. Instala los datos de voz de tu motor.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SubVoxTonalButton(onClick = platform.onInstallTtsData) { Text("Instalar voz en español") }
        } else {
            VoiceChoiceRow(
                title = "Automática (la mejor disponible)",
                selected = settings.selectedVoiceName.isBlank(),
                onClick = { vm.setVoiceName("") }
            )
            voices.take(10).forEach { voice ->
                VoiceChoiceRow(
                    title = voice.displayName,
                    selected = settings.selectedVoiceName == voice.name,
                    onClick = { vm.setVoiceName(voice.name) }
                )
            }
        }

        Text(
            text = "Acento",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        ChoiceChips(
            options = listOf("es-ES", "es-MX", "es-US", "es-419"),
            selected = settings.preferredLanguageTag,
            label = {
                when (it) {
                    "es-ES" -> "España"
                    "es-MX" -> "México"
                    "es-US" -> "EE. UU."
                    else -> "Latinoamérica"
                }
            },
            onSelect = vm::setLanguageTag
        )

        SliderRow(
            title = "Velocidad",
            value = settings.speechRate,
            valueRange = 0.6f..1.8f,
            onValueCommitted = vm::setSpeechRate,
            valueLabel = { "%.2f×".format(it) }
        )
        SliderRow(
            title = "Tono",
            value = settings.pitch,
            valueRange = 0.7f..1.4f,
            onValueCommitted = vm::setPitch,
            valueLabel = { "%.2f".format(it) }
        )
        SwitchRow(
            title = "Permitir voces que usan Internet",
            subtitle = "Suelen sonar mejor, pero dependen de la conexión",
            checked = settings.allowNetworkVoices,
            onCheckedChange = vm::setAllowNetworkVoices
        )

        if (engines.size > 1) {
            Text(
                text = "Motor de voz",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            ChoiceChips(
                options = engines,
                selected = engines.firstOrNull { it.packageName == settings.ttsEnginePackage } ?: engines.first(),
                label = { it.label },
                onSelect = { vm.setTtsEnginePackage(it.packageName) }
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubVoxButton(onClick = vm::testSpanishVoiceSample) {
                Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Probar")
            }
            SubVoxOutlinedButton(onClick = platform.onOpenSystemTtsSettings) { Text("Ajustes del sistema") }
        }
    }
}

@Composable
private fun VoiceChoiceRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Gemini (nube)
// ---------------------------------------------------------------------------------------------

@Composable
private fun GeminiSection(vm: MainViewModel, settings: AppSettings) {
    val keyState by vm.userApiKeyState.collectAsStateWithLifecycle()
    val validation by vm.apiKeyValidationState.collectAsStateWithLifecycle()
    val readerState by vm.readerState.collectAsStateWithLifecycle()
    var draftKey by rememberSaveable { mutableStateOf("") }

    SubVoxCard {
        Text(
            text = "Gemini · voz expresiva",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Usa tu propia clave de la API de Google Gemini (se guarda cifrada en este móvil). Si falla o no hay Internet, SubVox cambia sola a la voz neuronal offline o a la del sistema.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val savedInfo = when (val k = keyState) {
            is UserApiKeyState.Valid -> "Clave guardada: ${k.maskedKey}"
            UserApiKeyState.NoKey -> "Aún no hay clave guardada."
            UserApiKeyState.Unreadable -> "No se pudo leer la clave guardada. Vuelve a introducirla."
        }
        Text(
            text = savedInfo,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )

        OutlinedTextField(
            value = draftKey,
            onValueChange = { draftKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Clave de API") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubVoxButton(
                onClick = {
                    vm.validateUserApiKey(draftKey)
                    draftKey = ""
                },
                enabled = draftKey.isNotBlank()
            ) { Text("Guardar y probar") }
            SubVoxOutlinedButton(onClick = vm::clearUserApiKey) { Text("Borrar") }
        }

        val validationText = when (val v = validation) {
            ApiKeyValidationResult.Idle -> null
            ApiKeyValidationResult.Testing -> "Probando la clave…"
            is ApiKeyValidationResult.Valid -> v.message
            is ApiKeyValidationResult.InvalidKey -> v.message
            is ApiKeyValidationResult.QuotaExceeded -> v.message
            is ApiKeyValidationResult.NoConnection -> v.message
            is ApiKeyValidationResult.OtherError -> v.message
        }
        if (validationText != null) {
            Text(
                text = validationText,
                style = MaterialTheme.typography.bodySmall,
                color = if (validation is ApiKeyValidationResult.Valid) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    SubVoxCard {
        Text(
            text = "Voz de Gemini",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        ChoiceChips(
            options = GeminiNaturalVoiceOption.entries.toList(),
            selected = GeminiNaturalVoiceOption.fromIdSafe(settings.naturalVoiceName),
            label = { "${it.voiceId} · ${it.gender}" },
            onSelect = { vm.setNaturalVoiceName(it.voiceId) }
        )
        SliderRow(
            title = "Velocidad",
            value = settings.naturalVoiceSpeed,
            valueRange = 0.7f..1.4f,
            onValueCommitted = vm::setNaturalVoiceSpeed,
            valueLabel = { "%.2f×".format(it) }
        )
        SwitchRow(
            title = "Usar solo con Wi-Fi",
            checked = settings.naturalOnlyOnWifi,
            onCheckedChange = vm::setNaturalOnlyOnWifi
        )
        if (readerState.naturalVoiceFallbackNotice.isNotBlank()) {
            Text(
                text = readerState.naturalVoiceFallbackNotice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        SubVoxButton(onClick = vm::testNaturalVoiceSample) {
            Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Probar esta voz")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Voces por personaje
// ---------------------------------------------------------------------------------------------

@Composable
private fun CharacterVoicesCard(vm: MainViewModel, settings: AppSettings) {
    SubVoxCard {
        Text(
            text = "Voces por personaje",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Cambia el tono según quién habla, para distinguir personajes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ChoiceChips(
            options = CharacterVoiceMode.entries.toList(),
            selected = settings.characterVoicesMode,
            label = {
                when (it) {
                    CharacterVoiceMode.OFF -> "Una sola voz"
                    CharacterVoiceMode.COLOR_BASED -> "Por color del texto"
                    CharacterVoiceMode.ALTERNATE_AB -> "Alternar A / B"
                }
            },
            onSelect = vm::setCharacterVoicesMode
        )
        if (settings.characterVoicesMode != CharacterVoiceMode.OFF) {
            SliderRow(
                title = "Tono voz B",
                value = settings.characterVoicePitchB,
                valueRange = 0.7f..1.5f,
                onValueCommitted = {
                    vm.setCharacterVoicePitches(
                        settings.characterVoicePitchA,
                        it,
                        settings.characterVoicePitchC
                    )
                },
                valueLabel = { "%.2f".format(it) }
            )
            Text(
                text = "El cambio de tono lo aplica la voz del sistema. La voz natural mantiene su timbre.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
