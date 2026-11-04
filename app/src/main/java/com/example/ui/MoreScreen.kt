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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pronunciation.PronunciationRule
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class MoreTab(val label: String) {
    THEMES("Temas"),
    HISTORY("Historial"),
    PRONUNCIATION("Pronunciación"),
    CORRECTIONS("Correcciones"),
    APPS("Apps"),
    ABOUT("Acerca de")
}

@Composable
fun MoreScreen(
    vm: MainViewModel,
    platform: PlatformActions,
    modifier: Modifier = Modifier
) {
    var tab by rememberSaveable { mutableStateOf(MoreTab.THEMES.name) }
    val currentTab = MoreTab.valueOf(tab)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader(
            title = "Más",
            subtitle = "Historial, pronunciación, apps y detalles"
        )

        ChoiceChips(
            options = MoreTab.entries.toList(),
            selected = currentTab,
            label = { it.label },
            onSelect = { tab = it.name }
        )

        when (currentTab) {
            MoreTab.THEMES -> ThemePickerSection(vm)
            MoreTab.HISTORY -> HistoryTab(vm)
            MoreTab.PRONUNCIATION -> PronunciationTab(vm)
            MoreTab.CORRECTIONS -> CorrectionsTab(vm)
            MoreTab.APPS -> AppsTab(vm, platform)
            MoreTab.ABOUT -> AboutTab()
        }

        Spacer(Modifier.height(12.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Historial
// ---------------------------------------------------------------------------------------------

@Composable
private fun HistoryTab(vm: MainViewModel) {
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val records by vm.historyRecordsState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val timeFormat = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()) }
    var editing by remember { mutableStateOf<com.example.history.SubtitleHistoryRecord?>(null) }
    var editText by remember { mutableStateOf("") }
    var learnedMessage by remember { mutableStateOf<String?>(null) }

    editing?.let { rec ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Corregir lo que leyó el OCR") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Edita solo lo que esté mal. La app aprenderá el cambio y lo aplicará sola en las próximas frases.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val source = rec.originalText.ifBlank { rec.spokenText }
                    vm.learnOcrCorrection(source, editText) { n ->
                        learnedMessage = if (n > 0) "Aprendido: $n corrección(es) nueva(s)."
                        else "No encontré un cambio claro que aprender (¿reescribiste toda la frase?)."
                    }
                    editing = null
                }) { Text("Aprender") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancelar") } }
        )
    }

    learnedMessage?.let { msg ->
        SubVoxCard {
            Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(4_000)
            learnedMessage = null
        }
    }

    SubVoxCard {
        SwitchRow(
            title = "Guardar historial de frases",
            subtitle = "Se guarda solo en este móvil. Útil para repasar y estudiar.",
            checked = settings.saveHistoryToDatabase,
            onCheckedChange = vm::setSaveHistoryToDatabase
        )
        if (records.isNotEmpty()) {
            SubVoxOutlinedButton(onClick = vm::clearAllHistoryRecords) { Text("Borrar todo el historial") }
        }
    }

    if (records.isEmpty()) {
        SubVoxCard {
            Text(
                text = "Todavía no hay frases guardadas.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        records.take(80).forEach { record ->
            SubVoxCard {
                Text(
                    text = record.spokenText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (record.translatedText.isNotBlank() && record.originalText.isNotBlank()) {
                    Text(
                        text = "Original: ${record.originalText}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = timeFormat.format(Date(record.timestampMs)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        editing = record
                        editText = record.originalText.ifBlank { record.spokenText }
                    }) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Corregir", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { vm.speakTextSample(record.spokenText) }) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Leer", modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = { clipboard.setText(AnnotatedString(record.spokenText)) }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copiar", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { vm.deleteHistoryRecord(record.id) }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Borrar", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Correcciones del OCR
// ---------------------------------------------------------------------------------------------

@Composable
private fun CorrectionsTab(vm: MainViewModel) {
    val rules by vm.ocrCorrectionsState.collectAsStateWithLifecycle()
    var wrong by rememberSaveable { mutableStateOf("") }
    var right by rememberSaveable { mutableStateOf("") }

    SubVoxCard {
        Text(
            text = "Si el OCR lee mal una palabra (por ejemplo «Ia» en vez de «la»), corrígela desde el Historial con el lápiz y la app lo aprenderá. También puedes añadir reglas a mano aquí.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = wrong,
            onValueChange = { wrong = it },
            label = { Text("Lo que lee mal") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = right,
            onValueChange = { right = it },
            label = { Text("Lo que debería poner") },
            modifier = Modifier.fillMaxWidth()
        )
        SubVoxButton(
            onClick = {
                vm.addOcrCorrection(wrong, right)
                wrong = ""
                right = ""
            },
            enabled = wrong.isNotBlank() && right.isNotBlank() && wrong.trim() != right.trim()
        ) { Text("Añadir corrección") }
    }

    if (rules.isEmpty()) {
        SubVoxCard {
            Text(
                text = "Aún no has enseñado ninguna corrección.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        rules.forEach { rule ->
            SubVoxCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = rule.wrong,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "→ ${rule.right}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { vm.deleteOcrCorrection(rule.id) }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Borrar", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        SubVoxOutlinedButton(onClick = vm::clearOcrCorrections) { Text("Borrar todas") }
    }
}

// ---------------------------------------------------------------------------------------------
// Diccionario de pronunciación
// ---------------------------------------------------------------------------------------------

@Composable
private fun PronunciationTab(vm: MainViewModel) {
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val rules by vm.pronunciationRulesState.collectAsStateWithLifecycle()
    var original by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }

    SubVoxCard {
        SwitchRow(
            title = "Usar diccionario de pronunciación",
            subtitle = "Corrige nombres y honoríficos (-san, -kun, -sama…) para que suenen bien",
            checked = settings.pronunciationDictionaryEnabled,
            onCheckedChange = vm::setPronunciationDictionaryEnabled
        )
    }

    SubVoxCard {
        Text(
            text = "Añadir regla",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        OutlinedTextField(
            value = original,
            onValueChange = { original = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Texto que aparece (p. ej. Naruto)") },
            singleLine = true
        )
        OutlinedTextField(
            value = replacement,
            onValueChange = { replacement = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Cómo debe sonar (p. ej. Naruto)") },
            singleLine = true
        )
        SubVoxTonalButton(
            onClick = {
                vm.upsertPronunciationRule(
                    PronunciationRule(original = original.trim(), replacement = replacement.trim())
                )
                original = ""
                replacement = ""
            },
            enabled = original.isNotBlank() && replacement.isNotBlank()
        ) { Text("Añadir") }
    }

    SubVoxCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Reglas (${rules.size})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            SubVoxOutlinedButton(onClick = vm::restoreDefaultPronunciationRules) { Text("Restaurar") }
        }
        rules.forEach { rule ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${rule.original}  →  ${rule.replacement}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (rule.enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { vm.upsertPronunciationRule(rule.copy(enabled = it)) }
                )
                IconButton(onClick = { vm.deletePronunciationRule(rule.id) }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Borrar regla", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Perfiles por app
// ---------------------------------------------------------------------------------------------

@Composable
private fun AppsTab(vm: MainViewModel, platform: PlatformActions) {
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val profiles by vm.appProfilesState.collectAsStateWithLifecycle()
    val state by vm.readerState.collectAsStateWithLifecycle()

    SubVoxCard {
        SwitchRow(
            title = "Perfiles por aplicación",
            subtitle = "Usa ajustes distintos en cada navegador o reproductor (zona, idioma, voz…)",
            checked = settings.appProfilesEnabled,
            onCheckedChange = vm::setAppProfilesEnabled
        )
        if (settings.appProfilesEnabled) {
            SwitchRow(
                title = "Detectar la app automáticamente",
                subtitle = "Necesita el permiso «Acceso a datos de uso»",
                checked = settings.autoDetectForegroundAppProfile,
                onCheckedChange = vm::setAutoDetectForegroundAppProfile
            )
            if (settings.autoDetectForegroundAppProfile && !platform.hasUsageStatsPermission) {
                SubVoxTonalButton(onClick = platform.onRequestUsageStats) { Text("Conceder acceso de uso") }
            }
            Text(
                text = "Perfil activo ahora: ${state.activeProfileName}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    SubVoxCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Perfiles (${profiles.size})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            SubVoxOutlinedButton(onClick = vm::restoreDefaultAppProfiles) { Text("Restaurar") }
        }
        profiles.forEach { profile ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.appDisplayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = profile.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = profile.enabled,
                    onCheckedChange = { vm.upsertAppProfile(profile.copy(enabled = it)) }
                )
                IconButton(onClick = { vm.deleteAppProfile(profile.packageName) }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Borrar perfil", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Acerca de
// ---------------------------------------------------------------------------------------------

@Composable
private fun AboutTab() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var lastCrash by remember { mutableStateOf(com.example.diagnostics.CrashReporter.readLast(context)) }
    val crashText = lastCrash
    if (crashText != null) {
        SubVoxCard {
            Text(
                text = "Último cierre inesperado",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                text = "Copia este texto y envíaselo a quien te esté ayudando: dice exactamente qué falló.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = crashText,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 18
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubVoxTonalButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(crashText))
                }) { Text("Copiar") }
                SubVoxOutlinedButton(onClick = {
                    com.example.diagnostics.CrashReporter.clear(context)
                    lastCrash = null
                }) { Text("Borrar") }
            }
        }
    }
    SubVoxCard {
        Text(
            text = "SubVox",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Lector de subtítulos en tiempo real. Vigila la pantalla, reconoce el texto con ML Kit y lo lee en voz alta, todo en tu móvil.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    SubVoxCard {
        Text(
            text = "Privacidad",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Las capturas de pantalla se procesan en memoria y se descartan al instante. Con la voz natural sin conexión y la voz del sistema no se envía nada a Internet. Solo Gemini (opcional) envía el texto de cada frase a Google.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    SubVoxCard {
        Text(
            text = "Créditos",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "• Reconocimiento de texto: Google ML Kit\n" +
                "• Motor de voz neuronal: Sherpa-ONNX (k2-fsa)\n" +
                "• Voces: Piper (rhasspy). «Claude» (es_MX, Apache-2.0), «Daniela» (es_AR, CC BY-SA 4.0 · OpenSLR 61), «Sharvard», «Ald» y «Davefx».\n" +
                "• Traducción en vivo: ML Kit Translate",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
