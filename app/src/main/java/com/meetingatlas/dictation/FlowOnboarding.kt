package com.meetingatlas.dictation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meetingatlas.R

internal enum class FlowPermission(val label: String) {
    Microphone("Microphone"), Overlay("Display over other apps"), Accessibility("Accessibility"),
    Notifications("Recording notifications"), Bluetooth("Nearby devices"), AppDetails("App settings")
}
internal data class FlowPermissions(val mic: Boolean, val overlay: Boolean, val accessibility: Boolean, val notifications: Boolean, val bluetooth: Boolean) {
    val ready get() = mic && overlay && accessibility
    val summary get() = if (ready) "Ready" else "Needs attention"
    fun granted(permission: FlowPermission): Boolean = when (permission) {
        FlowPermission.Microphone -> mic; FlowPermission.Overlay -> overlay
        FlowPermission.Accessibility -> accessibility; FlowPermission.Notifications -> notifications
        FlowPermission.Bluetooth -> bluetooth; FlowPermission.AppDetails -> false
    }
}

@Composable internal fun FlowToolbar(label: String, menu: (() -> Unit)? = null, back: (() -> Unit)? = null, action: @Composable (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                menu != null -> IconButton(onClick = menu) { Icon(Icons.Outlined.Menu, "Open menu", Modifier.size(22.dp)) }
                back != null -> IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", Modifier.size(22.dp)) }
            }
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 3.dp))
            action?.invoke()
        }
    }
}

internal enum class FlowStep(val heading: String) {
    Welcome("Speak naturally."), Intro("From thought to text."), Language("Select your language"),
    Mic("Allow microphone access"), Overlay("Show the Atlas bubble"), Accessibility("Type in any app"),
    Privacy("Make it yours"), Practice("Try it yourself"), Done("You’re ready to go"),
}

@Composable internal fun FlowOnboarding(
    step: FlowStep, back: () -> Unit, next: () -> Unit, leave: () -> Unit,
    options: DictationSettings, permissions: FlowPermissions, hasKey: Boolean, vm: DictationViewModel,
    repair: (FlowPermission) -> Unit, review: () -> Unit, provider: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var erase by remember { mutableStateOf(false) }
    BackHandler { if (step == FlowStep.Welcome) leave() else back() }
    Column(Modifier.fillMaxSize().background(if (step == FlowStep.Welcome) colors.tertiaryContainer else colors.surface)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step != FlowStep.Welcome) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Previous step") }
            else Image(painterResource(R.drawable.meeting_atlas_mark), contentDescription = null, Modifier.padding(horizontal = 12.dp).size(24.dp))
            Text("Atlas", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            FlowTextButton(onClick = leave) { Text("Set up later") }
        }
        val position by animateFloatAsState(listOf(.025f, .07f, .12f, .28f, .42f, .56f, .72f, .86f, 1f)[step.ordinal],
            animationSpec = tween(145), label = "Setup progress")
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(4) { i ->
                Box(Modifier.weight(1f).height(2.dp).background(colors.outlineVariant, CircleShape)) {
                    Box(Modifier.fillMaxWidth((position * 4 - i).coerceIn(0f, 1f)).fillMaxHeight().background(colors.secondary, CircleShape))
                }
            }
        }
        Crossfade(step, modifier = Modifier.fillMaxWidth().weight(1f), animationSpec = tween(145), label = "Atlas setup") { page ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 30.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (page) {
                    FlowStep.Welcome -> {
                        Spacer(Modifier.height(28.dp))
                        Image(painterResource(R.drawable.meeting_atlas_mark), "Atlas logo",
                            Modifier.size(82.dp).align(Alignment.CenterHorizontally))
                        Spacer(Modifier.height(24.dp))
                        FlowTitle(page.heading, large = true)
                        Text("Write effortlessly.", style = MaterialTheme.typography.displaySmall)
                        FlowMuted("Turn what you say into text, wherever you already type.")
                    }
                    FlowStep.Intro -> {
                        FlowTitle(page.heading, large = true)
                        FlowMuted("Keep your usual keyboard. Atlas adds a small button to supported text fields.")
                        Box(Modifier.fillMaxWidth().heightIn(min = 130.dp).background(colors.tertiaryContainer, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(38.dp).background(Color(FlowLavenderArgb), CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Close, null, tint = Color(FlowInkArgb))
                                }
                                Box(Modifier.size(67.dp, 38.dp).background(Color(FlowLavenderArgb), CircleShape), contentAlignment = Alignment.Center) {
                                    FlowWave(Modifier.size(40.dp, 23.dp))
                                }
                                Box(Modifier.size(38.dp).background(Color(FlowCreamArgb), CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Check, null, tint = Color(FlowInkArgb))
                                }
                            }
                        }
                        FlowRows {
                            FlowRow("Tap to start", "Tap the checkmark when you’re done talking.")
                            FlowLine()
                            FlowRow("Hold to dictate", "Talk while holding the bubble, then release.")
                        }
                    }
                    FlowStep.Language -> {
                        FlowTitle(page.heading)
                        FlowMuted("You can change this later in Settings.")
                        LanguageOptions(options.language) { selected -> vm.update { it.copy(language = selected) } }
                    }
                    FlowStep.Mic -> PermissionPage(page.heading, Icons.Outlined.MicNone,
                        "Atlas needs the microphone while you speak. It only starts recording when you tap or hold.",
                        permissions.mic, "Allow microphone") { repair(FlowPermission.Microphone) }
                    FlowStep.Overlay -> PermissionPage(page.heading, Icons.Outlined.PictureInPictureAlt,
                        "Allow Atlas to show the floating bubble above your keyboard. Enable Meeting Atlas in Android settings, then return here.",
                        permissions.overlay, "Open overlay settings") { repair(FlowPermission.Overlay) }
                    FlowStep.Accessibility -> {
                        PermissionPage(page.heading, Icons.Outlined.AccessibilityNew,
                            "Accessibility lets Atlas find a compatible field and insert text at the original cursor.",
                            permissions.accessibility && options.consent,
                            if (options.consent) "Open accessibility settings" else "Review disclosure") {
                            if (options.consent) repair(FlowPermission.Accessibility) else review()
                        }
                        if (options.consent) FlowMuted("Turn on the Atlas voice dictation service, rather than the accessibility shortcut.")
                    }
                    FlowStep.Privacy -> {
                        IntroIcon(Icons.Outlined.Lock)
                        FlowTitle(page.heading)
                        FlowMuted("Choose what Atlas keeps on this device. Text history and retained audio are off by default.")
                        PrivacyControls(options, vm::update, disableHistory = { erase = true })
                        FlowOutline("Review dictation disclosure", onClick = review)
                    }
                    FlowStep.Practice -> {
                        FlowTitle(page.heading)
                        FlowMuted("Say a quick sentence to try the microphone and your speech provider.")
                        if (options.model.isBlank() || !hasKey) {
                            FlowCard(color = colors.tertiaryContainer) {
                                Text("Connect a speech provider", style = MaterialTheme.typography.titleMedium)
                                FlowMuted("Choose a model and add a key to run a real dictation.")
                                FlowOutline("Configure provider", onClick = provider)
                            }
                        } else FlowPractice(vm, permissions.mic && options.consent)
                    }
                    FlowStep.Done -> {
                        IntroIcon(Icons.Outlined.Check)
                        val ready = permissions.ready && options.consent && hasKey && options.model.isNotBlank()
                        FlowTitle(if (ready) page.heading else "Setup saved", large = true)
                        if (ready) {
                            FlowMuted("Open a text field in another app and tap the Atlas bubble to start.")
                        } else FlowMuted("You can finish permissions or provider setup any time from Home.")
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
            FlowButton(when (step) {
                FlowStep.Welcome -> "Get started"
                FlowStep.Done -> "Open Atlas"
                else -> "Next"
            }, Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("setup-next"), enabled = when (step) {
                FlowStep.Mic -> permissions.mic
                FlowStep.Overlay -> permissions.overlay
                FlowStep.Accessibility -> options.consent && permissions.accessibility
                else -> true
            }, onClick = next)
        }
    }
    if (erase) FlowDialog(onDismissRequest = { erase = false }, title = { Text("Turn off history?") },
        text = { Text("Existing dictation text and audio will be deleted.") },
        confirmButton = { FlowTextButton(onClick = { vm.update { it.copy(historyDays = 0, keepAudio = false) }; erase = false }) { Text("Delete history") } },
        dismissButton = { FlowTextButton(onClick = { erase = false }) { Text("Cancel") } })
}

@Composable private fun IntroIcon(icon: ImageVector) {
    Box(Modifier.size(58.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(29.dp))
    }
}
@Composable private fun PermissionPage(title: String, icon: ImageVector, message: String, granted: Boolean, grant: String, action: () -> Unit) {
    IntroIcon(icon)
    FlowTitle(title, large = true)
    FlowMuted(message)
    if (granted) FlowRows { FlowRow("Ready", icon = Icons.Outlined.Check) } else FlowOutline(grant, onClick = action)
}

@Composable internal fun FlowPractice(vm: DictationViewModel, enabled: Boolean) {
    val status by vm.coordinator.status.collectAsStateWithLifecycle()
    val result by vm.coordinator.result.collectAsStateWithLifecycle()
    val listening = status.phase == DictationPhase.STARTING || status.phase == DictationPhase.LISTENING
    FlowCard {
        if (listening) {
            val level by vm.coordinator.amplitude.collectAsStateWithLifecycle()
            FlowWave(Modifier.size(54.dp, 25.dp), color = MaterialTheme.colorScheme.onSurface, level = level)
        }
        if (status.message.isNotBlank()) FlowMuted(status.message)
        result?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        FlowButton(if (listening) "Done" else "Record a test", enabled = listening || enabled && !status.busy) {
            if (listening) vm.coordinator.done() else vm.coordinator.start()
        }
        if (status.busy && status.phase != DictationPhase.INSERTING) FlowOutline("Cancel") { vm.coordinator.cancel() }
        if (status.canRetry) FlowOutline("Retry audio") { vm.coordinator.retry() }
    }
}
