package com.meetingatlas.dictation

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private enum class SettingsPage(val title: String) {
    General("Settings"), Language("Languages"), Bubble("Bubble size"), Opacity("Bubble opacity"),
    Audio("Microphone"), Privacy("Privacy"), Provider("Speech provider"), Status("Service status"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun FlowSettings(
    options: DictationSettings, permissions: FlowPermissions, hasKey: Boolean, lastIssue: String?,
    vm: DictationViewModel, repair: (FlowPermission) -> Unit, review: () -> Unit, back: () -> Unit,
    startAtProvider: Boolean,
) {
    var page by rememberSaveable(startAtProvider) { mutableStateOf(if (startAtProvider) SettingsPage.Provider else SettingsPage.General) }
    var erase by remember { mutableStateOf(false) }
    fun goBack() { if (page == SettingsPage.General || (startAtProvider && page == SettingsPage.Provider)) back() else page = SettingsPage.General }
    androidx.activity.compose.BackHandler { goBack() }
    Column(Modifier.fillMaxSize()) {
        FlowToolbar(page.title, back = ::goBack)
        key(page) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FlowTitle(page.title)
                when (page) {
                    SettingsPage.General -> {
                        FlowSection("General")
                        FlowRows {
                            FlowRow("Languages", options.language.label, onClick = { page = SettingsPage.Language })
                            FlowLine()
                            FlowRow("Bubble size", "${options.size} dp", onClick = { page = SettingsPage.Bubble })
                            FlowLine()
                            FlowRow("Bubble opacity", "${(options.opacity * 100).roundToInt()}%", onClick = { page = SettingsPage.Opacity })
                            FlowLine()
                            FlowRow("Microphone", options.microphone.label, onClick = { page = SettingsPage.Audio })
                        }
                        FlowSection("Dictation")
                        FlowRows {
                            FlowRow("Speech provider", options.provider.label, onClick = { page = SettingsPage.Provider })
                            FlowLine()
                            FlowRow("Privacy", onClick = { page = SettingsPage.Privacy })
                            FlowLine()
                            FlowRow("Service status", permissions.summary, onClick = { page = SettingsPage.Status })
                        }
                    }
                    SettingsPage.Language -> {
                        FlowMuted("Select the language Atlas should expect from your speech.")
                        LanguageOptions(options.language) { selected -> vm.update { it.copy(language = selected) } }
                    }
                    SettingsPage.Bubble, SettingsPage.Opacity -> {
                        val sizing = page == SettingsPage.Bubble
                        var size by remember(options.size) { mutableFloatStateOf(options.size.toFloat()) }
                        var opacity by remember(options.opacity) { mutableFloatStateOf(options.opacity) }
                        Box(Modifier.fillMaxWidth().height(128.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.large),
                            contentAlignment = Alignment.Center) {
                            Box(Modifier.size(size.dp).background(Color(FlowLavenderArgb).copy(alpha = opacity), CircleShape),
                                contentAlignment = Alignment.Center) { FlowWave(Modifier.size((size / 2.55f).dp, (size / 2.8f).dp)) }
                        }
                        Text(if (sizing) "${size.roundToInt()} dp" else "${(opacity * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium)
                        if (sizing) Slider(size, { size = it }, valueRange = 48f..72f, steps = 2,
                            onValueChangeFinished = { val next = size.roundToInt(); vm.update { it.copy(size = next) } })
                        else Slider(opacity, { opacity = it }, valueRange = .6f..1f, steps = 3,
                            onValueChangeFinished = { val next = opacity; vm.update { it.copy(opacity = next) } })
                        if (sizing) FlowRows {
                            FlowRow("Shrink the bubble when idle", "Keeps a compact bubble after five seconds.") {
                                Switch(options.idleShrink, { value -> vm.update { it.copy(idleShrink = value) } },
                                    modifier = Modifier.semantics { contentDescription = "Shrink the bubble when idle" })
                            }
                        }
                        FlowOutline("Reset") { vm.update { if (sizing) it.copy(size = 56) else it.copy(opacity = 1f) } }
                        FlowMuted("Drag the bubble to reposition it. Atlas keeps it clear of the system keyboard.")
                    }
                    SettingsPage.Audio -> {
                        FlowMuted("Choose which microphone Atlas uses when you dictate.")
                        for (input in Microphone.entries) FlowOption(input.label, selected = input == options.microphone) {
                            vm.update { it.copy(microphone = input) }
                        }
                        if (Build.VERSION.SDK_INT >= 31 && options.microphone == Microphone.BLUETOOTH && !permissions.bluetooth) {
                            FlowButton("Allow Nearby devices") { repair(FlowPermission.Bluetooth) }
                        }
                    }
                    SettingsPage.Privacy -> {
                        FlowMuted("Dictation audio is sent only when you submit to the configured speech provider. History is stored on this device.")
                        PrivacyControls(options, update = vm::update, disableHistory = { erase = true })
                        var excluded by remember(options.excluded) { mutableStateOf(options.excluded.sorted().joinToString("\n")) }
                        FlowSection("Excluded apps")
                        FlowField(excluded, { excluded = it.take(8000) }, "One package name per line", minLines = 2)
                        FlowOutline("Save exclusions") {
                            val values = excluded.lineSequence().map(String::trim).filter { it.matches(Regex("[a-zA-Z0-9_.]+")) }.toSet()
                            vm.update { it.copy(excluded = values) }
                        }
                        FlowOutline("Review dictation disclosure", onClick = review)
                    }
                    SettingsPage.Provider -> SpeechControls(options, hasKey, vm)
                    SettingsPage.Status -> {
                        FlowMuted("Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER}")
                        FlowRows {
                            for (item in listOf(FlowPermission.Microphone, FlowPermission.Overlay, FlowPermission.Accessibility, FlowPermission.Notifications)) {
                                FlowRow(item.label, if (permissions.granted(item)) "Ready" else "Needs attention", onClick = { repair(item) })
                                FlowLine()
                            }
                            FlowRow("Android app settings", onClick = { repair(FlowPermission.AppDetails) })
                        }
                        lastIssue?.let { FlowCard { Text("Last issue", style = MaterialTheme.typography.titleSmall); FlowMuted(it) } }
                        if (Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco", "samsung")) {
                            FlowMuted("If Atlas keeps disconnecting, review the device’s battery and background app settings.")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    if (erase) FlowDialog(onDismissRequest = { erase = false }, title = { Text("Turn off history?") },
        text = { Text("Atlas will delete saved dictation text and audio. Meeting recordings and notes are kept.") },
        confirmButton = { FlowTextButton(onClick = { vm.update { it.copy(historyDays = 0, keepAudio = false) }; erase = false }) { Text("Delete history") } },
        dismissButton = { FlowTextButton(onClick = { erase = false }) { Text("Cancel") } })
}

@Composable internal fun LanguageOptions(selected: SpeechLanguage, onSelect: (SpeechLanguage) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    FlowField(search, { search = it }, "Search languages", leadingIcon = { Icon(Icons.Outlined.Search, null) })
    val matches = SpeechLanguage.entries.filter { it.label.contains(search.trim(), ignoreCase = true) }
    for (language in matches) FlowOption(language.label, selected == language) { onSelect(language) }
    if (matches.isEmpty()) FlowMuted("No matching languages.")
}

@Composable internal fun PrivacyControls(options: DictationSettings, update: ((DictationSettings) -> DictationSettings) -> Unit, disableHistory: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    FlowRows {
        Box {
            FlowRow("Keep text history", if (options.historyDays == 0) "Off" else "${options.historyDays} days", onClick = { menu = true })
            DropdownMenu(menu, { menu = false }) {
                for (days in listOf(0, 7, 30, 90)) DropdownMenuItem(
                    text = { Text(if (days == 0) "Off" else "$days days") },
                    onClick = {
                        menu = false
                        if (days == 0 && options.historyDays > 0) disableHistory()
                        else update { it.copy(historyDays = days, keepAudio = it.keepAudio && days > 0) }
                    },
                )
            }
        }
        FlowLine()
        FlowRow("Keep audio for retries", "Up to 20 recordings on this device.") {
            Switch(options.keepAudio, { yes -> update { it.copy(keepAudio = yes) } },
                modifier = Modifier.semantics { contentDescription = "Keep audio for retries" }, enabled = options.historyDays > 0)
        }
    }
}

@Composable private fun SpeechControls(options: DictationSettings, keyReady: Boolean, vm: DictationViewModel) {
    var speechModel by remember(options.provider, options.model) { mutableStateOf(options.model) }
    var cleanupModel by remember(options.provider, options.cleanupModel) { mutableStateOf(options.cleanupModel) }
    var token by remember(options.provider) { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    FlowMuted("Atlas uses the model you choose. Your provider’s rate and privacy terms apply.")
    for (provider in SpeechProvider.entries) FlowOption(provider.label, options.provider == provider) {
        if (provider != options.provider) vm.update { it.copy(provider = provider, cleanupModel = "") }
    }
    FlowField(speechModel, { speechModel = it.take(150) }, "Speech model ID")
    FlowMuted(if (options.provider == SpeechProvider.OPEN_ROUTER) "For example, openai/whisper-large-v3" else "For example, gpt-transcribe")
    FlowField(cleanupModel, { cleanupModel = it.take(150) }, "Text model ID (optional)")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        Cleanup.entries.forEachIndexed { i, mode ->
            SegmentedButton(selected = options.cleanup == mode, onClick = { vm.update { it.copy(cleanup = mode) } },
                shape = SegmentedButtonDefaults.itemShape(i, Cleanup.entries.size)) { Text(mode.name) }
        }
    }
    FlowMuted("Without a cleanup model, Atlas uses the raw transcript.")
    FlowButton("Save models") {
        val provider = options.provider
        val speech = speechModel
        val cleanup = cleanupModel
        vm.update {
            when {
                it.provider != provider -> it
                provider == SpeechProvider.OPEN_ROUTER -> it.copy(routerModel = speech, cleanupModel = cleanup)
                else -> it.copy(openAiModel = speech, cleanupModel = cleanup)
            }
        }
    }
    FlowField(token, { token = it.take(500) }, "${options.provider.label} API key", transformation = PasswordVisualTransformation())
    if (keyReady) FlowMuted("A key is saved on this device.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowButton("Save key", enabled = token.isNotBlank()) { vm.saveKey(options.provider, token); token = "" }
        if (keyReady) FlowOutline("Remove") { confirm = true }
    }
    if (options.provider == SpeechProvider.OPEN_ROUTER) FlowMuted("Meeting Atlas shares this OpenRouter key and keeps its own model settings.")
    if (confirm) FlowDialog(onDismissRequest = { confirm = false }, title = { Text("Remove saved key?") },
        text = { Text(if (options.provider == SpeechProvider.OPEN_ROUTER) "This also removes the key used by Meeting Atlas."
            else "You’ll need to enter a new key to use OpenAI.") },
        confirmButton = { FlowTextButton(onClick = { vm.removeKey(options.provider); confirm = false }) { Text("Remove key") } },
        dismissButton = { FlowTextButton(onClick = { confirm = false }) { Text("Cancel") } })
}
