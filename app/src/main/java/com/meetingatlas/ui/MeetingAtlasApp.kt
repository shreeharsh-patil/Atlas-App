package com.meetingatlas.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.meetingatlas.data.MeetingAnalysisEntity
import com.meetingatlas.data.MeetingEntity
import com.meetingatlas.data.RecordingStatus
import com.meetingatlas.recording.MeetingRecordingService
import com.meetingatlas.recording.RecordingProcessState
import com.meetingatlas.recording.RecordingState
import com.meetingatlas.recording.formatRecordingTime
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private enum class Destination { HOME, LIBRARY, DETAIL, SETTINGS, RECORDING }
private enum class DetailTab { SUMMARY, MAP, TRANSCRIPT }

@Composable
fun MeetingAtlasApp(viewModel: HomeViewModel = hiltViewModel()) {
    val meetings by viewModel.meetings.collectAsState()
    val recordingState by RecordingProcessState.state.collectAsState()
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    var settingsReturn by rememberSaveable { mutableStateOf(Destination.HOME) }
    var selectedMeetingId by rememberSaveable { mutableStateOf<String?>(null) }
    var activeMeeting by remember { mutableStateOf<MeetingEntity?>(null) }
    val activeId = when (val current = recordingState) {
        is RecordingState.Preparing -> current.meetingId
        is RecordingState.Recording -> current.meetingId
        is RecordingState.Paused -> current.meetingId
        is RecordingState.Finalizing -> current.meetingId
        else -> null
    }
    val activeSession = activeId?.let { id -> meetings.firstOrNull { it.id == id } }
    val selectedMeeting = selectedMeetingId?.let { id -> meetings.firstOrNull { it.id == id } }

    when (destination) {
        Destination.HOME -> AtlasScaffold(selected = Destination.HOME, onHome = { destination = Destination.HOME }, onLibrary = { destination = Destination.LIBRARY }) {
            HomeScreen(
                meetings = meetings,
                activeSession = activeSession,
                onLibrary = { destination = Destination.LIBRARY },
                onSettings = { settingsReturn = Destination.HOME; destination = Destination.SETTINGS },
                onMeeting = { selectedMeetingId = it.id; destination = Destination.DETAIL },
                onNewRecording = {
                    if (activeSession != null) {
                        activeMeeting = activeSession
                        destination = Destination.RECORDING
                    } else viewModel.createMeeting { meeting -> activeMeeting = meeting; destination = Destination.RECORDING }
                },
            )
        }
        Destination.LIBRARY -> AtlasScaffold(selected = Destination.LIBRARY, onHome = { destination = Destination.HOME }, onLibrary = { destination = Destination.LIBRARY }) {
            LibraryScreen(meetings, onBack = { destination = Destination.HOME }, onMeeting = { selectedMeetingId = it.id; destination = Destination.DETAIL })
        }
        Destination.DETAIL -> {
            if (selectedMeeting == null) {
                destination = Destination.LIBRARY
            } else MeetingDetailScreen(
                meeting = selectedMeeting,
                viewModel = viewModel,
                onBack = { destination = Destination.LIBRARY; viewModel.clearProcessing() },
                onSettings = { settingsReturn = Destination.DETAIL; destination = Destination.SETTINGS },
            )
        }
        Destination.SETTINGS -> SettingsScreen(viewModel, onBack = { destination = settingsReturn })
        Destination.RECORDING -> RecordingScreen(
            meeting = activeMeeting?.let { local -> meetings.firstOrNull { it.id == local.id } ?: local },
            onBack = { destination = Destination.HOME },
            onPermissionDenied = { activeMeeting?.let { viewModel.discardDraft(it.id) }; destination = Destination.HOME },
        )
    }
}

@Composable
private fun AtlasScaffold(selected: Destination, onHome: () -> Unit, onLibrary: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 28.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                BottomItem("Home", selected == Destination.HOME, onHome) { Icon(Icons.Default.Mic, null, Modifier.size(19.dp)) }
                BottomItem("Library", selected == Destination.LIBRARY, onLibrary) { Icon(Icons.AutoMirrored.Filled.LibraryBooks, null, Modifier.size(19.dp)) }
            }
        },
    ) { inset -> Box(Modifier.padding(inset)) { content() } }
}

@Composable
private fun BottomItem(label: String, selected: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CompositionLocalProviderCompat(content = icon, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun CompositionLocalProviderCompat(content: @Composable () -> Unit, tint: Color) {
    androidx.compose.material3.LocalContentColor.let { local -> androidx.compose.runtime.CompositionLocalProvider(local provides tint, content = content) }
}

@Composable
private fun HomeScreen(
    meetings: List<MeetingEntity>,
    activeSession: MeetingEntity?,
    onLibrary: () -> Unit,
    onSettings: () -> Unit,
    onMeeting: (MeetingEntity) -> Unit,
    onNewRecording: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)), Alignment.Center) {
                Text("M", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("MEETING ATLAS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, letterSpacing = 1.4.sp)
                Text("A clear record of the room.", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.secondary) }
        }
        Spacer(Modifier.height(30.dp))
        Text("Meetings,\nheld in focus.", style = MaterialTheme.typography.headlineLarge, lineHeight = 39.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp)
        Spacer(Modifier.height(10.dp))
        Text("Record a conversation. Keep the decisions and next steps.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = onNewRecording,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Default.Mic, contentDescription = null)
            Spacer(Modifier.width(9.dp))
            Text(if (activeSession == null) "Start a recording" else "Return to recording", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(32.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Recent", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onLibrary) { Text("All meetings") }
        }
        Spacer(Modifier.height(8.dp))
        if (meetings.isEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("Your first meeting starts here", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text("Recordings stay on this device until you choose to process them.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                meetings.take(5).forEach { MeetingRow(it, onClick = { onMeeting(it) }) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LibraryScreen(meetings: List<MeetingEntity>, onBack: () -> Unit, onMeeting: (MeetingEntity) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column(Modifier.padding(start = 4.dp)) {
                Text("Your library", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("${meetings.size} ${if (meetings.size == 1) "meeting" else "meetings"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (meetings.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No saved meetings yet.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.secondary)
            }
        } else LazyColumn(contentPadding = PaddingValues(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(meetings, key = { it.id }) { MeetingRow(it, onClick = { onMeeting(it) }) }
        }
    }
}

@Composable
private fun MeetingRow(meeting: MeetingEntity, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(horizontal = 15.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), Alignment.Center) {
                Text(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(meeting.createdAt)).substringBefore("/").trim(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(meeting.title, maxLines = 1, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text("${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(meeting.createdAt))}  ·  ${durationLabel(meeting.durationMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            Text(statusLabel(meeting.recordingStatus), style = MaterialTheme.typography.labelSmall, color = if (meeting.recordingStatus == RecordingStatus.RECORDING) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun MeetingDetailScreen(
    meeting: MeetingEntity,
    viewModel: HomeViewModel,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    val analysis by viewModel.observeAnalysis(meeting.id).collectAsState(initial = null)
    val processing by viewModel.processing.collectAsState()
    var selectedTab by rememberSaveable(meeting.id) { mutableStateOf(DetailTab.SUMMARY) }
    var showUploadDialog by rememberSaveable(meeting.id) { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable(meeting.id) { mutableStateOf(false) }
    var titleDraft by rememberSaveable(meeting.id) { mutableStateOf(meeting.title) }
    val thisProcessing = processing.meetingId == meeting.id

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("MEETING", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.secondary)
                Text(meeting.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            IconButton(onClick = { titleDraft = meeting.title; showRenameDialog = true }) { Icon(Icons.Default.Edit, contentDescription = "Rename meeting", tint = MaterialTheme.colorScheme.secondary) }
        }
        Text("${DateFormat.getDateInstance(DateFormat.FULL).format(Date(meeting.createdAt))}  ·  ${durationLabel(meeting.durationMs)}", modifier = Modifier.padding(start = 16.dp, top = 1.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(18.dp))

        if (analysis == null) {
            ProcessingSetupCard(
                hasAudio = meeting.audioPath != null || meeting.recordingStatus == RecordingStatus.SAVED || meeting.recordingStatus == RecordingStatus.INTERRUPTED,
                hasKey = viewModel.openRouterSettings().hasApiKey,
                running = thisProcessing && processing.running,
                progress = if (thisProcessing) processing.message else null,
                error = if (thisProcessing) processing.error else null,
                onGenerate = {
                    if (!viewModel.openRouterSettings().hasApiKey) onSettings() else showUploadDialog = true
                },
            )
        } else {
            AudioLifecycleNote(analysis!!, meeting.audioPath != null)
            if (thisProcessing && processing.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(processing.error!!, modifier = Modifier.padding(horizontal = 3.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DetailTab.entries.forEach { tab ->
                    val label = when (tab) { DetailTab.SUMMARY -> "Summary"; DetailTab.MAP -> "Mind map"; DetailTab.TRANSCRIPT -> "Transcript" }
                    val selected = selectedTab == tab
                    Box(Modifier.weight(1f).background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent, RoundedCornerShape(9.dp)).clickable { selectedTab = tab }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(label, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            when (selectedTab) {
                DetailTab.SUMMARY -> SummaryContent(analysis!!)
                DetailTab.MAP -> MindMapContent(analysis!!.mindMapJson)
                DetailTab.TRANSCRIPT -> TranscriptContent(analysis!!.transcript)
            }
            if (analysis!!.audioDeletedAt == null && meeting.audioPath != null) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { viewModel.removeAudioAfterAnalysis(meeting.id) }, enabled = !processing.running, modifier = Modifier.fillMaxWidth()) {
                    if (processing.running && thisProcessing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(Modifier.width(8.dp)); Text("Remove source audio")
                }
            }
        }
        Spacer(Modifier.height(22.dp))
    }

    if (showUploadDialog) AlertDialog(
        onDismissRequest = { showUploadDialog = false },
        title = { Text("Send this recording to OpenRouter?") },
        text = { Text("The audio will leave this device for transcription. The default Whisper transcription model may incur a per-minute charge. The summary and mind map use OpenRouter's free-model router by default. Your local recording is removed only after the notes are saved; the notes and transcript stay on this device.") },
        confirmButton = {
            TextButton(onClick = { showUploadDialog = false; viewModel.generateNotes(meeting.id) }) { Text("Send and create notes") }
        },
        dismissButton = { TextButton(onClick = { showUploadDialog = false }) { Text("Cancel") } },
    )
    if (showRenameDialog) AlertDialog(
        onDismissRequest = { showRenameDialog = false },
        title = { Text("Name this meeting") },
        text = { OutlinedTextField(value = titleDraft, onValueChange = { titleDraft = it }, singleLine = true, label = { Text("Meeting title") }) },
        confirmButton = { TextButton(onClick = { viewModel.rename(meeting.id, titleDraft); showRenameDialog = false }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ProcessingSetupCard(hasAudio: Boolean, hasKey: Boolean, running: Boolean, progress: String?, error: String?, onGenerate: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("Meeting notes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    running -> progress ?: "Working through the recording…"
                    !hasAudio -> "No saved audio is available for this meeting."
                    !hasKey -> "Add an OpenRouter key in Settings to create a summary and mind map."
                    else -> "Create a transcript, a concise summary, decisions, next steps, and a mind map."
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary,
            )
            if (running) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onGenerate, enabled = hasAudio && !running, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(13.dp)) {
                if (running) CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                else Text(if (hasKey) "Create summary & mind map" else "Set up OpenRouter")
            }
            if (hasAudio && hasKey && !running) {
                Spacer(Modifier.height(9.dp))
                Text("Audio is sent only after you confirm. The original is removed after notes are saved.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
private fun AudioLifecycleNote(analysis: MeetingAnalysisEntity, audioStillPresent: Boolean) {
    val removed = analysis.audioDeletedAt != null || !audioStillPresent
    Surface(color = if (removed) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (removed) Icons.Default.Check else Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(9.dp))
            Text(if (removed) "Source audio removed. Notes are stored on this device." else "Notes saved. Source audio remains on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun SummaryContent(analysis: MeetingAnalysisEntity) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ContentCard("Summary") { Text(analysis.summary, style = MaterialTheme.typography.bodyLarge, lineHeight = 25.sp) }
        val keyPoints = readStringArray(analysis.keyPointsJson)
        if (keyPoints.isNotEmpty()) ContentCard("Key points") { keyPoints.forEach { BulletText(it) } }
        val decisions = readStringArray(analysis.decisionsJson)
        ContentCard("Decisions") {
            if (decisions.isEmpty()) MutedText("No explicit decisions were identified.") else decisions.forEach { BulletText(it) }
        }
        val actions = readActionItems(analysis.actionItemsJson)
        ContentCard("Next steps") {
            if (actions.isEmpty()) MutedText("No assigned actions were identified.") else actions.forEachIndexed { index, action ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)
                Text(action.optString("task"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                val owner = action.optString("owner").takeIf(String::isNotBlank)
                val deadline = action.optString("deadline").takeIf(String::isNotBlank)
                val metadata = listOfNotNull(owner?.let { "Owner: $it" }, deadline?.let { "Due: $it" }).joinToString("  ·  ")
                if (metadata.isNotBlank()) Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
private fun MindMapContent(raw: String) {
    val map = remember(raw) { runCatching { JSONObject(raw) }.getOrNull() }
    val branches = map?.optJSONArray("branches") ?: JSONArray()
    val expanded = remember(raw) { mutableStateListOf<Int>().apply { for (index in 0 until minOf(branches.length(), 4)) add(index) } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(16.dp)) {
            Text(map?.optString("label").orEmpty().ifBlank { "Meeting" }, modifier = Modifier.fillMaxWidth().padding(17.dp), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (branches.length() == 0) MutedText("No map branches were returned.")
        for (index in 0 until branches.length()) {
            val branch = branches.optJSONObject(index) ?: continue
            val isExpanded = index in expanded
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                Column(Modifier.fillMaxWidth().clickable {
                    if (isExpanded) expanded.remove(index) else expanded.add(index)
                }.padding(horizontal = 15.dp, vertical = 13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                        Spacer(Modifier.width(11.dp))
                        Text(branch.optString("label"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Icon(Icons.Default.ExpandMore, contentDescription = if (isExpanded) "Collapse branch" else "Expand branch", tint = MaterialTheme.colorScheme.secondary)
                    }
                    if (isExpanded) {
                        val topics = branch.optJSONArray("topics") ?: JSONArray()
                        if (topics.length() > 0) {
                            Spacer(Modifier.height(10.dp))
                            Column(Modifier.padding(start = 19.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (topicIndex in 0 until topics.length()) {
                                    Row(verticalAlignment = Alignment.Top) {
                                        Text("—", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
                                        Text(topics.optString(topicIndex), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Text("Select a branch to expand it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun TranscriptContent(transcript: String) {
    ContentCard("Transcript") { Text(transcript, style = MaterialTheme.typography.bodyMedium, lineHeight = 23.sp) }
}

@Composable
private fun ContentCard(title: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun BulletText(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 7.dp).size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp)
    }
}

@Composable
private fun MutedText(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)

@Composable
private fun SettingsScreen(viewModel: HomeViewModel, onBack: () -> Unit) {
    val savedSettings = viewModel.openRouterSettings()
    var apiKey by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf(savedSettings.model) }
    var transcriptionModel by rememberSaveable { mutableStateOf(savedSettings.transcriptionModel) }
    val hasSavedKey by viewModel.hasOpenRouterKey.collectAsState()
    val message by viewModel.settingsMessage.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(14.dp))
        ContentCard("OpenRouter") {
            Text(if (hasSavedKey) "A key is stored securely on this device." else "Add your own OpenRouter key. It is encrypted with Android Keystore and never included in the app build.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (hasSavedKey) "Replace API key" else "OpenRouter API key") },
                placeholder = { Text("sk-or-…") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Spacer(Modifier.height(9.dp))
            Button(onClick = { viewModel.saveApiKey(apiKey); apiKey = "" }, enabled = apiKey.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Save key on this device") }
            if (hasSavedKey) TextButton(onClick = { viewModel.deleteApiKey() }, modifier = Modifier.align(Alignment.End)) { Text("Remove saved key", color = MaterialTheme.colorScheme.error) }
        }
        Spacer(Modifier.height(12.dp))
        ContentCard("Models") {
            Text("Summary and mind map default to OpenRouter's free-model router. Transcription defaults to Whisper, which may be billed by audio duration. Check current model pricing before processing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = model, onValueChange = { model = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Summary & mind map · free default") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = transcriptionModel, onValueChange = { transcriptionModel = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Transcription model · may cost") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { viewModel.saveModels(model, transcriptionModel) }, modifier = Modifier.fillMaxWidth()) { Text("Save model choices") }
        }
        if (message != null) {
            Spacer(Modifier.height(10.dp))
            Text(message!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(14.dp))
        Text("Audio and transcript are sent to OpenRouter. The default free model applies only to the summary and mind map; transcription may cost money. Notes stay on this device, and the source recording is deleted after notes are saved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(horizontal = 2.dp))
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun RecordingScreen(meeting: MeetingEntity?, onBack: () -> Unit, onPermissionDenied: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by RecordingProcessState.state.collectAsState()
    var elapsed by remember { mutableLongStateOf(0L) }
    var permissionGranted by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }
    fun startService() {
        val id = meeting?.id ?: return
        ContextCompat.startForegroundService(context, Intent(context, MeetingRecordingService::class.java).setAction(MeetingRecordingService.ACTION_START).putExtra(MeetingRecordingService.EXTRA_MEETING_ID, id))
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startService() }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (granted && pendingStart) {
            pendingStart = false
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else startService()
        } else if (!granted) onPermissionDenied()
    }
    val isCurrent = meeting != null && when (val current = state) {
        is RecordingState.Recording -> current.meetingId == meeting.id
        is RecordingState.Paused -> current.meetingId == meeting.id
        is RecordingState.Preparing -> current.meetingId == meeting.id
        is RecordingState.Finalizing -> current.meetingId == meeting.id
        else -> false
    }
    LaunchedEffect(state, meeting?.id, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                elapsed = when (val current = state) {
                    is RecordingState.Recording -> current.elapsedBeforeStart + android.os.SystemClock.elapsedRealtime() - current.startedAtElapsed
                    is RecordingState.Paused -> current.elapsedMs
                    else -> meeting?.durationMs ?: 0L
                }
                delay(1_000)
            }
        }
    }
    val recording = state is RecordingState.Recording && isCurrent
    val paused = state is RecordingState.Paused && isCurrent
    val finished = meeting?.recordingStatus in setOf(RecordingStatus.SAVED, RecordingStatus.INTERRUPTED, RecordingStatus.FAILED)
    val failed = (state as? RecordingState.Failed)?.takeIf { it.meetingId == meeting?.id }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f)) {
                Text("RECORDING", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.1.sp, color = MaterialTheme.colorScheme.secondary)
                Text(meeting?.title ?: "New meeting", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Text(if (recording) "Live" else if (paused) "Paused" else if (isCurrent) "Starting" else "Ready", color = if (recording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.weight(1f))
        Surface(Modifier.align(Alignment.CenterHorizontally), color = MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape) {
            Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(20.dp).size(30.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(formatRecordingTime(elapsed), modifier = Modifier.align(Alignment.CenterHorizontally), fontSize = 48.sp, fontWeight = FontWeight.Light, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            when { recording -> "Audio is being saved on this device"; paused -> "Recording is paused"; finished -> statusLabel(meeting!!.recordingStatus); else -> "Ready when you are" },
            modifier = Modifier.align(Alignment.CenterHorizontally), color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.weight(1f))
        Text("Let everyone know before recording.", modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 14.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        if (failed != null) Text(failed.message, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (recording || paused || isCurrent) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { context.startService(Intent(context, MeetingRecordingService::class.java).setAction(if (recording) MeetingRecordingService.ACTION_PAUSE else MeetingRecordingService.ACTION_RESUME)) }, modifier = Modifier.weight(1f).height(54.dp), enabled = recording || paused, shape = RoundedCornerShape(14.dp)) {
                    Icon(if (recording) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(if (recording) "Pause" else "Resume")
                }
                Button(onClick = { context.startService(Intent(context, MeetingRecordingService::class.java).setAction(MeetingRecordingService.ACTION_STOP)) }, modifier = Modifier.weight(1f).height(54.dp), enabled = recording || paused, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Default.Stop, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Stop & save")
                }
            }
        } else if (finished) {
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp)) { Text("View this meeting") }
        } else {
            Button(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    permissionGranted = true
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else startService()
                } else {
                    pendingStart = true
                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Mic, contentDescription = null); Spacer(Modifier.width(8.dp)); Text(if (permissionGranted) "Start recording" else "Allow microphone & start")
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

private fun readStringArray(raw: String): List<String> = runCatching {
    val array = JSONArray(raw)
    (0 until array.length()).map { array.optString(it) }.filter(String::isNotBlank)
}.getOrDefault(emptyList())

private fun readActionItems(raw: String): List<JSONObject> = runCatching {
    val array = JSONArray(raw)
    (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}.getOrDefault(emptyList())

private fun durationLabel(milliseconds: Long): String {
    if (milliseconds <= 0) return "Duration unavailable"
    val seconds = milliseconds / 1_000
    return if (seconds >= 3_600) "%dh %02dm".format(seconds / 3_600, seconds / 60 % 60) else "%dm".format(seconds / 60)
}

private fun statusLabel(status: RecordingStatus): String = when (status) {
    RecordingStatus.PREPARING -> "Preparing"
    RecordingStatus.RECORDING -> "Recording"
    RecordingStatus.PAUSED -> "Paused"
    RecordingStatus.SAVED -> "Saved"
    RecordingStatus.INTERRUPTED -> "Interrupted"
    RecordingStatus.FAILED -> "Failed"
}
