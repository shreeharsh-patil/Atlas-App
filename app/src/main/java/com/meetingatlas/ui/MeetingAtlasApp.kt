package com.meetingatlas.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.meetingatlas.data.MeetingAnalysisEntity
import com.meetingatlas.data.MeetingEntity
import com.meetingatlas.data.RecordingStatus
import com.meetingatlas.recording.MeetingRecordingService
import com.meetingatlas.recording.RecordingProcessState
import com.meetingatlas.recording.RecordingState
import com.meetingatlas.recording.formatRecordingTime
import com.meetingatlas.recording.AudioQuality
import com.meetingatlas.data.BookmarkEntity
import com.meetingatlas.BuildConfig
import androidx.compose.material3.Switch
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private enum class Destination { HOME, LIBRARY, DETAIL, SETTINGS, RECORDING }
private enum class DetailTab { SUMMARY, MAP, TRANSCRIPT }

@Composable
fun MeetingAtlasApp(viewModel: HomeViewModel = hiltViewModel()) {
    val meetings by viewModel.meetings.collectAsStateWithLifecycle()
    val recordingState by RecordingProcessState.state.collectAsStateWithLifecycle()
    val meetingsReady by viewModel.meetingsReady.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    var settingsReturn by rememberSaveable { mutableStateOf(Destination.HOME) }
    var selectedMeetingId by rememberSaveable { mutableStateOf<String?>(null) }
    var recordingMeetingId by rememberSaveable { mutableStateOf<String?>(null) }
    val localError by viewModel.localError.collectAsStateWithLifecycle()
    val activeId = when (val current = recordingState) {
        is RecordingState.Preparing -> current.meetingId
        is RecordingState.Recording -> current.meetingId
        is RecordingState.Paused -> current.meetingId
        is RecordingState.Finalizing -> current.meetingId
        else -> null
    }
    val activeSession = activeId?.let { id -> meetings.firstOrNull { it.id == id } }
    val selectedMeeting = selectedMeetingId?.let { id -> meetings.firstOrNull { it.id == id } }
    fun openMeeting(meeting: MeetingEntity) {
        if (meeting.recordingStatus in setOf(RecordingStatus.PREPARING, RecordingStatus.RECORDING, RecordingStatus.PAUSED)) {
            recordingMeetingId = meeting.id; destination = Destination.RECORDING
        } else { selectedMeetingId = meeting.id; destination = Destination.DETAIL }
    }

    BackHandler(destination != Destination.HOME) { destination = if (destination == Destination.SETTINGS) settingsReturn else Destination.HOME }
    Column(Modifier.fillMaxSize()) {
    if (activeSession != null && destination != Destination.RECORDING) ActiveRecordingBar(activeSession.title, recordingState) { recordingMeetingId = activeSession.id; destination = Destination.RECORDING }
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
    Box(Modifier.widthIn(max = 920.dp).fillMaxSize()) {
    when (destination) {
        Destination.HOME -> AtlasScaffold(selected = Destination.HOME, onHome = { destination = Destination.HOME }, onLibrary = { destination = Destination.LIBRARY }) {
            HomeScreen(
                meetings = meetings,
                activeSession = activeSession,
                onLibrary = { destination = Destination.LIBRARY },
                onSettings = { settingsReturn = Destination.HOME; destination = Destination.SETTINGS },
                onMeeting = ::openMeeting,
                onNewRecording = {
                    if (activeSession != null) {
                        recordingMeetingId = activeSession.id
                        destination = Destination.RECORDING
                    } else viewModel.createMeeting { meeting -> recordingMeetingId = meeting.id; destination = Destination.RECORDING }
                },
            )
        }
        Destination.LIBRARY -> AtlasScaffold(selected = Destination.LIBRARY, onHome = { destination = Destination.HOME }, onLibrary = { destination = Destination.LIBRARY }) {
            LibraryScreen(meetings, onBack = { destination = Destination.HOME }, onMeeting = ::openMeeting)
        }
        Destination.DETAIL -> {
            if (selectedMeeting == null) {
                if (!meetingsReady) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                LaunchedEffect(meetingsReady, selectedMeetingId) { if (meetingsReady) destination = Destination.LIBRARY }
            } else MeetingDetailScreen(
                meeting = selectedMeeting,
                viewModel = viewModel,
                onBack = { destination = Destination.LIBRARY; viewModel.clearProcessing() },
                onSettings = { settingsReturn = Destination.DETAIL; destination = Destination.SETTINGS },
            )
        }
        Destination.SETTINGS -> SettingsScreen(viewModel, onBack = { destination = settingsReturn })
        Destination.RECORDING -> RecordingScreenV2(
            meeting = meetings.firstOrNull { it.id == recordingMeetingId },
            viewModel = viewModel,
            onBack = { selectedMeetingId = recordingMeetingId; destination = if (activeId == null && meetings.firstOrNull { it.id == recordingMeetingId }?.recordingStatus in setOf(RecordingStatus.SAVED, RecordingStatus.INTERRUPTED, RecordingStatus.FAILED)) Destination.DETAIL else Destination.HOME },
            onPermissionDenied = { recordingMeetingId?.let { viewModel.discardDraft(it) }; destination = Destination.HOME },
        )
    }
    }
    }
    }
    if (localError != null) AlertDialog(onDismissRequest = viewModel::clearLocalError, title = { Text("Could not complete the change") }, text = { Text(localError.orEmpty()) }, confirmButton = { TextButton(onClick = viewModel::clearLocalError) { Text("OK") } })
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
                BottomItem("Meetings", selected == Destination.HOME, onHome) { Icon(Icons.Default.Mic, null, Modifier.size(19.dp)) }
                BottomItem("Library", selected == Destination.LIBRARY, onLibrary) { Icon(Icons.AutoMirrored.Filled.LibraryBooks, null, Modifier.size(19.dp)) }
            }
        },
    ) { inset -> Box(Modifier.padding(inset)) { content() } }
}

@Composable
private fun BottomItem(label: String, selected: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(Modifier.heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
    val recentMeetings = remember(meetings) { meetings.take(5) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, top = 22.dp, end = 22.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item(key = "brand-header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(com.meetingatlas.R.drawable.meeting_atlas_mark),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("MEETING ATLAS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, letterSpacing = 1.4.sp)
                    Text("Recordings and meeting notes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.secondary) }
            }
        }
        item(key = "home-intro") {
            Column(Modifier.padding(top = 30.dp)) {
                Text("Meetings", style = MaterialTheme.typography.headlineLarge, lineHeight = 39.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp)
                Spacer(Modifier.height(8.dp))
                Text("Record a conversation. Keep the decisions and next steps.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.secondary)
            }
        }
        item(key = "record-action") {
            Button(
                onClick = onNewRecording,
                modifier = Modifier.fillMaxWidth().padding(top = 22.dp).heightIn(min = 58.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Default.Mic, contentDescription = null)
                Spacer(Modifier.width(9.dp))
                Text(if (activeSession == null) "Start a recording" else "Return to recording", fontWeight = FontWeight.SemiBold)
            }
        }
        item(key = "recent-heading") {
            Row(Modifier.padding(top = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recent", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onLibrary) { Text("All meetings") }
            }
        }
        if (recentMeetings.isEmpty()) {
            item(key = "empty-library") {
                Surface(
                    modifier = Modifier.padding(top = 2.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("No recordings yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Text("Start a recording here. Saved audio stays on this device until you choose to create notes.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        } else {
            items(recentMeetings, key = { "recent-${it.id}" }) { meeting ->
                MeetingRow(meeting, onClick = { onMeeting(meeting) }, modifier = Modifier.padding(bottom = 9.dp))
            }
        }
        item(key = "home-bottom-space") { Spacer(Modifier.height(6.dp)) }
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
private fun MeetingRow(meeting: MeetingEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val createdLabel = remember(meeting.createdAt) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(meeting.createdAt))
    }
    val isActive = meeting.recordingStatus in setOf(RecordingStatus.PREPARING, RecordingStatus.RECORDING, RecordingStatus.PAUSED)
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(horizontal = 15.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), Alignment.Center) {
                Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(meeting.title, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text("$createdLabel  ·  ${durationLabel(meeting.durationMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            Surface(
                color = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape,
            ) {
                Text(
                    statusLabel(meeting.recordingStatus),
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.secondary,
                )
            }
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
    val analysis by remember(meeting.id) { viewModel.observeAnalysis(meeting.id) }.collectAsStateWithLifecycle(initialValue = null)
    val notes by remember(meeting.id) { viewModel.observeNotes(meeting.id) }.collectAsStateWithLifecycle(initialValue = null)
    var previousNotes by rememberSaveable(meeting.id) { mutableStateOf(false) }
    BackHandler(previousNotes) { previousNotes = false }
    if (notes != null && !previousNotes) {
        NotesScreen(meeting, notes!!, viewModel, onBack, onSettings, onPreviousNotes = if (analysis != null) ({ previousNotes = true }) else null)
        return
    }
    val segments by remember(meeting.id) { viewModel.observeSegments(meeting.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val bookmarks by remember(meeting.id) { viewModel.observeBookmarks(meeting.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val options by viewModel.recordingOptions.collectAsStateWithLifecycle()
    var seekRequest by remember(meeting.id) { mutableStateOf<Long?>(null) }
    var editingBookmark by remember { mutableStateOf<BookmarkEntity?>(null) }
    var bookmarkLabel by rememberSaveable { mutableStateOf("") }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val capturing by com.meetingatlas.recording.AudioWorkCoordinator.recording.collectAsStateWithLifecycle()
    if (diagnostics && BuildConfig.DEBUG) { RecordingDiagnostics(meeting.id, viewModel) { diagnostics = false }; return }
    val processing by viewModel.processing.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable(meeting.id) { mutableStateOf(DetailTab.SUMMARY) }
    var showUploadDialog by rememberSaveable(meeting.id) { mutableStateOf(false) }
    var template by rememberSaveable(meeting.id) { mutableStateOf(com.meetingatlas.notes.MeetingTemplate.GENERAL) }
    var waitForCharging by rememberSaveable(meeting.id) { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable(meeting.id) { mutableStateOf(false) }
    var titleDraft by rememberSaveable(meeting.id) { mutableStateOf(meeting.title) }
    val thisProcessing = processing.meetingId == meeting.id

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("MEETING", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.secondary)
                Text(meeting.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = { titleDraft = meeting.title; showRenameDialog = true }) { Icon(Icons.Default.Edit, contentDescription = "Rename meeting", tint = MaterialTheme.colorScheme.secondary) }
        }
        Text("${DateFormat.getDateInstance(DateFormat.FULL).format(Date(meeting.createdAt))}  ·  ${durationLabel(meeting.durationMs)}", modifier = Modifier.padding(start = 16.dp, top = 1.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(18.dp))
        if (previousNotes) TextButton(onClick = { previousNotes = false }) { Text("Return to current source-linked notes") }

        meeting.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(12.dp)) }
        MeetingPlayer(meeting, segments, viewModel, seekRequest)
        if (segments.isNotEmpty()) Spacer(Modifier.height(12.dp))
        if (bookmarks.isNotEmpty()) {
            ContentCard("Bookmarks") {
                bookmarks.forEach { bookmark ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (segments.any { it.durationMs > 0 }) TextButton(onClick = { seekRequest = bookmark.elapsedMs }) { Text(formatRecordingTime(bookmark.elapsedMs)) }
                        else Text(formatRecordingTime(bookmark.elapsedMs), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 12.dp))
                        Text(bookmark.label.ifBlank { "Bookmarked moment" }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { editingBookmark = bookmark; bookmarkLabel = bookmark.label }) { Icon(Icons.Default.Edit, "Edit bookmark label") }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (analysis == null) {
            ProcessingSetupCard(
                hasAudio = meeting.audioPath != null && meeting.recordingStatus in setOf(RecordingStatus.SAVED, RecordingStatus.INTERRUPTED),
                hasKey = viewModel.openRouterSettings().hasApiKey,
                running = thisProcessing && processing.running,
                progress = if (thisProcessing) processing.message else null,
                error = if (thisProcessing) processing.error else null,
                onGenerate = {
                    if (!viewModel.openRouterSettings().hasApiKey) onSettings() else showUploadDialog = true
                },
            )
        } else {
            if (notes == null) {
                OutlinedButton(onClick = { showUploadDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Create detailed notes with sources") }
                Spacer(Modifier.height(12.dp))
            }
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
                DetailTab.TRANSCRIPT -> if (analysis!!.transcript.isNotBlank()) TranscriptContent(analysis!!.transcript) else TextButton(onClick = { previousNotes = false }) { Text("Open transcript in current notes") }
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
        if (BuildConfig.DEBUG) TextButton(onClick = { diagnostics = true }) { Text("Recording diagnostics") }
        TextButton(onClick = { confirmDelete = true }, enabled = capturing == null && !processing.running) { Text("Delete meeting", color = MaterialTheme.colorScheme.error) }
    }

    if (editingBookmark != null) AlertDialog(onDismissRequest = { editingBookmark = null }, title = { Text("Bookmark label") }, text = { OutlinedTextField(value = bookmarkLabel, onValueChange = { bookmarkLabel = it.take(160) }, label = { Text("Optional label") }) }, confirmButton = { TextButton(onClick = { editingBookmark?.let { viewModel.labelBookmark(it.id, bookmarkLabel) }; editingBookmark = null }) { Text("Save") } }, dismissButton = { TextButton(onClick = { editingBookmark?.let { viewModel.deleteBookmark(it.id) }; editingBookmark = null }) { Text("Delete bookmark") } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete this meeting?") }, text = { Text("This removes its local recordings, notes, transcript and bookmarks. This cannot be undone.") }, confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.deleteMeeting(meeting.id, onBack) }) { Text("Delete meeting", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })

    if (showUploadDialog) AlertDialog(
        onDismissRequest = { showUploadDialog = false },
        title = { Text("Create source-linked notes?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${if (analysis?.transcript?.isNotBlank() == true) "Your saved transcript" else "Saved audio for transcription and transcript windows"} will be sent to OpenRouter. Transcription may cost money; the text model is free by default. ${if (options.deleteAfterNotes) "Audio is deleted only after complete notes are saved." else "Source audio will be kept."}")
            MeetingTemplatePicker(template) { template = it }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Wait until charging", modifier = Modifier.weight(1f)); Switch(checked = waitForCharging, onCheckedChange = { waitForCharging = it }) }
        } },
        confirmButton = {
            TextButton(onClick = { showUploadDialog = false; viewModel.queueNotes(meeting.id, template, waitForCharging) }) { Text(if (waitForCharging) "Queue notes" else "Process now") }
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
                Text("Processing starts after you confirm and follows your audio-retention setting.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val options by viewModel.recordingOptions.collectAsStateWithLifecycle()
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.setBluetooth(true) else viewModel.reportLocalError("Nearby devices permission is needed to select a Bluetooth microphone.")
    }
    val savedSettings = viewModel.openRouterSettings()
    var apiKey by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf(savedSettings.model) }
    var transcriptionModel by rememberSaveable { mutableStateOf(savedSettings.transcriptionModel) }
    val hasSavedKey by viewModel.hasOpenRouterKey.collectAsStateWithLifecycle()
    val message by viewModel.settingsMessage.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(14.dp))
        ContentCard("Recording") {
            Text("Quality applies to your next recording. Storage estimates exclude container overhead.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            AudioQuality.entries.forEach { quality ->
                Row(Modifier.fillMaxWidth().selectable(selected = quality == options.quality, role = Role.RadioButton, onClick = { viewModel.setQuality(quality) }).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = quality == options.quality, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(quality.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("~${quality.megabytesPerHour} MB/h", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Prefer Bluetooth microphone", style = MaterialTheme.typography.bodyMedium); Text("Android 12+ when available. Otherwise follows system routing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                Switch(modifier = Modifier.semantics { contentDescription = "Prefer Bluetooth microphone" }, checked = options.bluetooth, onCheckedChange = { enabled ->
                    if (enabled && Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) else viewModel.setBluetooth(enabled)
                })
            }
        }
        Spacer(Modifier.height(12.dp))
        ContentCard("Audio retention") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Delete audio after notes are saved", style = MaterialTheme.typography.bodyMedium); Text("Keep notes and transcript. Turn off to keep recordings for playback.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                Switch(modifier = Modifier.semantics { contentDescription = "Delete audio after notes are saved" }, checked = options.deleteAfterNotes, onCheckedChange = viewModel::setDeleteAfterNotes)
            }
        }
        Spacer(Modifier.height(12.dp))
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
        Text("Audio and transcript are sent to OpenRouter only when you create notes. The free model default applies to summary and mind map; transcription may cost money. Your audio retention choice takes effect after notes are saved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(horizontal = 2.dp))
        Spacer(Modifier.height(22.dp))
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
