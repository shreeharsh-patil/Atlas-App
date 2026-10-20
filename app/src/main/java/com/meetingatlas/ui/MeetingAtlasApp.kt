package com.meetingatlas.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meetingatlas.BuildConfig
import com.meetingatlas.data.BookmarkEntity
import com.meetingatlas.data.MeetingAnalysisEntity
import com.meetingatlas.data.MeetingEntity
import com.meetingatlas.data.RecordingStatus
import com.meetingatlas.notes.MeetingTemplate
import com.meetingatlas.recording.AudioQuality
import com.meetingatlas.recording.RecordingProcessState
import com.meetingatlas.recording.RecordingState
import com.meetingatlas.recording.formatRecordingTime
import com.meetingatlas.ui.theme.AtlasTheme
import com.meetingatlas.ui.theme.LocalAtlasTextStyles
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

private enum class Destination { HOME, LIBRARY, DETAIL, SETTINGS, RECORDING }
private enum class DetailTab { SUMMARY, MAP, TRANSCRIPT }

/** Shared separator for meta lines so the spacing is identical everywhere. */
internal const val META_SEPARATOR = "  ·  "

@Composable
fun MeetingAtlasApp(viewModel: HomeViewModel = hiltViewModel()) {
    val meetings by viewModel.meetings.collectAsStateWithLifecycle()
    val recordingState by RecordingProcessState.state.collectAsStateWithLifecycle()
    val meetingsReady by viewModel.meetingsReady.collectAsStateWithLifecycle()
    val localError by viewModel.localError.collectAsStateWithLifecycle()

    // The navigation state objects are held explicitly so the callbacks below can be
    // remembered once instead of being rebuilt on every recomposition.
    val destinationState = rememberSaveable { mutableStateOf(Destination.HOME) }
    val settingsReturnState = rememberSaveable { mutableStateOf(Destination.HOME) }
    val detailReturnState = rememberSaveable { mutableStateOf(Destination.HOME) }
    val selectedMeetingIdState = rememberSaveable { mutableStateOf<String?>(null) }
    val recordingMeetingIdState = rememberSaveable { mutableStateOf<String?>(null) }
    var destination by destinationState
    var settingsReturn by settingsReturnState
    var detailReturn by detailReturnState
    var selectedMeetingId by selectedMeetingIdState
    var recordingMeetingId by recordingMeetingIdState

    // A freshly created meeting is not in the Room list until the invalidation tracker
    // emits it. Keeping the entity here stops the recording screen showing "New meeting"
    // and a disabled start button for a few frames.
    var pendingMeeting by remember { mutableStateOf<MeetingEntity?>(null) }

    val activeId = when (val current = recordingState) {
        is RecordingState.Preparing -> current.meetingId
        is RecordingState.Recording -> current.meetingId
        is RecordingState.Paused -> current.meetingId
        is RecordingState.Finalizing -> current.meetingId
        else -> null
    }
    val activeSession = activeId?.let { id -> meetings.firstOrNull { it.id == id } }
    val selectedMeeting = selectedMeetingId?.let { id -> meetings.firstOrNull { it.id == id } }
    val recordingMeeting = recordingMeetingId?.let { id -> meetings.firstOrNull { it.id == id } } ?: pendingMeeting

    // Once Room has the row, the local copy is no longer needed.
    LaunchedEffect(meetings, pendingMeeting) {
        val pending = pendingMeeting ?: return@LaunchedEffect
        if (meetings.any { it.id == pending.id }) pendingMeeting = null
    }

    // `leaveRecording` reads the meeting list and the live session id, both of which change
    // while the user records, so they are read through `rememberUpdatedState`.
    val meetingsState = rememberUpdatedState(meetings)
    val activeIdState = rememberUpdatedState(activeId)

    val openMeeting: (MeetingEntity) -> Unit = remember {
        { meeting: MeetingEntity ->
            if (meeting.recordingStatus in setOf(RecordingStatus.PREPARING, RecordingStatus.RECORDING, RecordingStatus.PAUSED)) {
                recordingMeetingIdState.value = meeting.id
                destinationState.value = Destination.RECORDING
            } else {
                // Remember where the meeting was opened from so Back returns there instead of always going home.
                detailReturnState.value = if (destinationState.value == Destination.LIBRARY) Destination.LIBRARY else Destination.HOME
                selectedMeetingIdState.value = meeting.id
                destinationState.value = Destination.DETAIL
            }
        }
    }
    val leaveRecording: () -> Destination = remember {
        {
            val id = recordingMeetingIdState.value
            val meeting = id?.let { target -> meetingsState.value.firstOrNull { it.id == target } }
            if (activeIdState.value == null && meeting != null && meeting.recordingStatus in setOf(RecordingStatus.SAVED, RecordingStatus.INTERRUPTED, RecordingStatus.FAILED)) {
                selectedMeetingIdState.value = id
                detailReturnState.value = if (destinationState.value == Destination.LIBRARY) Destination.LIBRARY else Destination.HOME
                Destination.DETAIL
            } else {
                Destination.HOME
            }
        }
    }

    BackHandler(destination != Destination.HOME) {
        destination = when (destination) {
            Destination.SETTINGS -> settingsReturn
            Destination.DETAIL -> detailReturn
            Destination.RECORDING -> leaveRecording()
            else -> Destination.HOME
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Edge-to-edge windows are not resized for the keyboard, so the IME inset is consumed
        // by the content area only - the bottom navigation bar stays anchored to the screen.
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
            if (activeSession != null && destination != Destination.RECORDING) {
                ActiveRecordingBar(activeSession.title, recordingState) {
                    recordingMeetingId = activeSession.id
                    destination = Destination.RECORDING
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 920.dp).fillMaxSize()) {
                    when (destination) {
                        Destination.HOME -> AtlasScaffold(
                            selected = Destination.HOME,
                            onHome = { destination = Destination.HOME },
                            onLibrary = { destination = Destination.LIBRARY },
                        ) {
                            HomeScreen(
                                meetings = meetings,
                                activeSession = activeSession,
                                onLibrary = { destination = Destination.LIBRARY },
                                onSettings = { settingsReturn = Destination.HOME; destination = Destination.SETTINGS },
                                onMeeting = openMeeting,
                                onNewRecording = {
                                    if (activeSession != null) {
                                        recordingMeetingId = activeSession.id
                                        destination = Destination.RECORDING
                                    } else {
                                        viewModel.createMeeting { meeting ->
                                            pendingMeeting = meeting
                                            recordingMeetingId = meeting.id
                                            destination = Destination.RECORDING
                                        }
                                    }
                                },
                            )
                        }
                        Destination.LIBRARY -> AtlasScaffold(
                            selected = Destination.LIBRARY,
                            onHome = { destination = Destination.HOME },
                            onLibrary = { destination = Destination.LIBRARY },
                        ) {
                            LibraryScreen(meetings, onBack = { destination = Destination.HOME }, onMeeting = openMeeting)
                        }
                        Destination.DETAIL -> {
                            if (selectedMeeting == null) {
                                // The meeting was deleted, or Room has not emitted it yet.
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                                LaunchedEffect(meetingsReady, selectedMeetingId) { if (meetingsReady) destination = detailReturn }
                            } else {
                                MeetingDetailScreen(
                                    meeting = selectedMeeting,
                                    viewModel = viewModel,
                                    onBack = { destination = detailReturn; viewModel.clearProcessing() },
                                    onSettings = { settingsReturn = Destination.DETAIL; destination = Destination.SETTINGS },
                                )
                            }
                        }
                        Destination.SETTINGS -> SettingsScreen(viewModel, onBack = { destination = settingsReturn })
                        Destination.RECORDING -> RecordingScreenV2(
                            meeting = recordingMeeting,
                            viewModel = viewModel,
                            onBack = { destination = leaveRecording() },
                            onPermissionDenied = {
                                recordingMeetingId?.let { viewModel.discardDraft(it) }
                                pendingMeeting = null
                                destination = Destination.HOME
                            },
                        )
                    }
                }
            }
        }
    }

    if (localError != null) {
        AlertDialog(
            onDismissRequest = viewModel::clearLocalError,
            title = { Text("Could not complete the change") },
            text = { Text(localError.orEmpty()) },
            confirmButton = { TextButton(onClick = viewModel::clearLocalError) { Text("OK") } },
        )
    }
}

@Composable
private fun AtlasScaffold(selected: Destination, onHome: () -> Unit, onLibrary: () -> Unit, content: @Composable () -> Unit) {
    // The bottom bar is hidden while the keyboard is up so it never floats over the IME.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!imeVisible) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    NavigationBarItem(
                        selected = selected == Destination.HOME,
                        onClick = onHome,
                        icon = { Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = { Text("Meetings") },
                    )
                    NavigationBarItem(
                        selected = selected == Destination.LIBRARY,
                        onClick = onLibrary,
                        icon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = { Text("Library") },
                    )
                }
            }
        },
    ) { inset -> Box(Modifier.padding(inset)) { content() } }
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
    // "Recent" must actually be recent: the DAO sorts pinned rows first.
    val recentMeetings = remember(meetings) { meetings.sortedByDescending { it.createdAt }.take(5) }
    val savedCount = remember(meetings) { meetings.count { it.recordingStatus == RecordingStatus.SAVED } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "brand-header") {
            AtlasHero(
                eyebrow = "Meeting Atlas",
                headline = if (savedCount == 0) "Your meetings" else "$savedCount saved ${if (savedCount == 1) "meeting" else "meetings"}",
                body = "Record a conversation. Keep the decisions, the evidence and the next steps.",
                logo = {
                    Image(
                        painter = painterResource(com.meetingatlas.R.drawable.meeting_atlas_mark),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(38.dp),
                    )
                },
                trailing = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = AtlasTheme.colors.heroOn)
                    }
                },
            )
        }
        item(key = "record-action") {
            Button(
                onClick = onNewRecording,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                shape = RoundedCornerShape(20.dp),
            ) {
                Icon(Icons.Default.Mic, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (activeSession == null) "Start a recording" else "Return to recording",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        item(key = "recent-heading") {
            Row(Modifier.padding(top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Recent", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    if (recentMeetings.isNotEmpty()) {
                        Text(
                            "${meetings.size} ${if (meetings.size == 1) "meeting" else "meetings"} in total",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                TextButton(onClick = onLibrary, enabled = meetings.isNotEmpty()) { Text("All meetings") }
            }
        }
        if (recentMeetings.isEmpty()) {
            item(key = "empty-library") {
                AtlasEmptyState(
                    icon = Icons.Default.Mic,
                    title = "No recordings yet",
                    body = "Start a recording here. Saved audio stays on this device until you choose to create notes.",
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        } else {
            items(recentMeetings, key = { "recent-${it.id}" }) { meeting ->
                MeetingRow(meeting, onClick = { onMeeting(meeting) })
            }
        }
    }
}

@Composable
private fun LibraryScreen(meetings: List<MeetingEntity>, onBack: () -> Unit, onMeeting: (MeetingEntity) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val locale = remember { Locale.getDefault() }
    val filtered = remember(meetings, query, locale) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            meetings
        } else {
            val lowered = needle.lowercase(locale)
            // Lower-casing both sides avoids the Turkish dotless-i mismatch of contains(ignoreCase).
            meetings.filter { it.title.lowercase(locale).contains(lowered) }
        }
    }
    val monthFormat = remember(locale) { SimpleDateFormat("MMMM yyyy", locale) }
    val grouped = remember(filtered, monthFormat) {
        filtered.groupBy { monthFormat.format(Date(it.createdAt)) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column(Modifier.padding(start = 4.dp)) {
                Text("Your library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                Text(
                    if (query.isBlank()) "${meetings.size} ${if (meetings.size == 1) "meeting" else "meetings"}"
                    else "${filtered.size} of ${meetings.size} shown",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (meetings.isNotEmpty()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(80) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search meeting titles") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear search") }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                shape = RoundedCornerShape(16.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
        if (meetings.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.Center) {
                AtlasEmptyState(
                    icon = Icons.AutoMirrored.Filled.LibraryBooks,
                    title = "Your library is empty",
                    body = "Saved recordings appear here, newest first. Start one from the Meetings tab.",
                )
            }
        } else if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.Center) {
                Text("No meetings match “${query.trim()}”.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.secondary)
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                grouped.forEach { (month, monthMeetings) ->
                    item(key = "month-$month") {
                        Text(
                            month,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp, start = 2.dp).semantics { heading() },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    items(monthMeetings, key = { it.id }) { meeting ->
                        MeetingRow(meeting, onClick = { onMeeting(meeting) })
                    }
                }
            }
        }
    }
}

@Composable
private fun MeetingRow(meeting: MeetingEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val createdLabel = remember(meeting.createdAt) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(meeting.createdAt))
    }
    // A draft that was never started is not actually "preparing"; label and colour it honestly.
    val isDraft = meeting.recordingStatus == RecordingStatus.PREPARING && meeting.startedAt == null
    val isActive = !isDraft && meeting.recordingStatus in setOf(RecordingStatus.PREPARING, RecordingStatus.RECORDING, RecordingStatus.PAUSED)
    // A "Saved" chip on every row is noise; only surface a status that needs attention.
    val statusChip = meeting.recordingStatus.takeIf { it != RecordingStatus.SAVED }
    val needsAttention = meeting.recordingStatus == RecordingStatus.FAILED || meeting.recordingStatus == RecordingStatus.INTERRUPTED
    val statusText = if (isDraft) "Draft" else statusLabel(meeting.recordingStatus)

    AtlasClickableCard(
        onClick = onClick,
        modifier = modifier,
        onClickLabel = "Open meeting",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .background(
                        if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(13.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    tint = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    meeting.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    meetingMetaLabel(createdLabel, meeting.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            statusChip?.let {
                Spacer(Modifier.width(8.dp))
                AtlasPill(
                    text = statusText,
                    container = when {
                        isActive -> MaterialTheme.colorScheme.primaryContainer
                        needsAttention -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    content = when {
                        isActive -> MaterialTheme.colorScheme.onPrimaryContainer
                        needsAttention -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.secondary
                    },
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
    // "Previous notes" is a transient mode, not a destination: it must not survive process death.
    var previousNotes by remember(meeting.id) { mutableStateOf(false) }
    BackHandler(previousNotes) { previousNotes = false }
    val currentNotes = notes
    if (currentNotes != null && !previousNotes) {
        NotesScreen(
            meeting = meeting,
            document = currentNotes,
            viewModel = viewModel,
            onBack = onBack,
            onSettings = onSettings,
            onPreviousNotes = if (analysis != null) ({ previousNotes = true }) else null,
        )
        return
    }
    val currentAnalysis = analysis
    val segments by remember(meeting.id) { viewModel.observeSegments(meeting.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val bookmarks by remember(meeting.id) { viewModel.observeBookmarks(meeting.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val options by viewModel.recordingOptions.collectAsStateWithLifecycle()
    var seekRequest by remember(meeting.id) { mutableStateOf<PlaybackSeek?>(null) }
    // Keyed by meeting: an un-dismissed dialog must never write to another meeting's bookmark.
    var editingBookmark by remember(meeting.id) { mutableStateOf<BookmarkEntity?>(null) }
    var bookmarkLabel by rememberSaveable(meeting.id) { mutableStateOf("") }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val capturing by com.meetingatlas.recording.AudioWorkCoordinator.recording.collectAsStateWithLifecycle()
    if (diagnostics && BuildConfig.DEBUG) {
        RecordingDiagnostics(meeting.id, viewModel) { diagnostics = false }
        return
    }
    val processing by viewModel.processing.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable(meeting.id) { mutableStateOf(DetailTab.SUMMARY) }
    var showUploadDialog by rememberSaveable(meeting.id) { mutableStateOf(false) }
    var template by rememberSaveable(meeting.id) { mutableStateOf(MeetingTemplate.GENERAL) }
    var waitForCharging by rememberSaveable(meeting.id) { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable(meeting.id) { mutableStateOf(false) }
    var titleDraft by rememberSaveable(meeting.id) { mutableStateOf(meeting.title) }
    val thisProcessing = processing.meetingId == meeting.id
    val hasKey by viewModel.hasOpenRouterKey.collectAsStateWithLifecycle()
    // Bookmarks and transcript rows only offer a seek action when the player can actually play something.
    val playerReady = remember(segments) { playableSegments(segments).isNotEmpty() }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                AtlasEyebrow("Meeting")
                Spacer(Modifier.height(2.dp))
                Text(
                    meeting.title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { titleDraft = meeting.title; showRenameDialog = true }) {
                Icon(Icons.Default.Edit, contentDescription = "Rename meeting", tint = MaterialTheme.colorScheme.secondary)
            }
        }
        Text(
            remember(meeting.createdAt, meeting.durationMs) {
                "${DateFormat.getDateInstance(DateFormat.FULL).format(Date(meeting.createdAt))}$META_SEPARATOR${formatMeetingDuration(meeting.durationMs)}"
            },
            modifier = Modifier.padding(start = 16.dp, top = 2.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(16.dp))
        if (previousNotes) {
            FilledTonalNotice("You are viewing the previous saved notes.") { previousNotes = false }
            Spacer(Modifier.height(12.dp))
        }

        meeting.lastError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
        }
        MeetingPlayer(meeting, segments, viewModel, seekRequest) { seekRequest = null }
        if (playerReady) Spacer(Modifier.height(12.dp))
        if (bookmarks.isNotEmpty()) {
            AtlasCard {
                Text("Bookmarks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(10.dp))
                bookmarks.forEach { bookmark ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (playerReady) {
                            TextButton(onClick = { seekRequest = PlaybackSeek(bookmark.elapsedMs) }) { Text(formatRecordingTime(bookmark.elapsedMs)) }
                        } else {
                            Text(formatRecordingTime(bookmark.elapsedMs), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 12.dp))
                        }
                        Text(
                            bookmark.label.ifBlank { "Bookmarked moment" },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        IconButton(onClick = { editingBookmark = bookmark; bookmarkLabel = bookmark.label; viewModel.clearLocalError() }) {
                            Icon(Icons.Default.Edit, "Edit bookmark label")
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (currentAnalysis == null) {
            ProcessingSetupCard(
                hasAudio = meeting.audioPath != null && meeting.recordingStatus in setOf(RecordingStatus.SAVED, RecordingStatus.INTERRUPTED),
                hasKey = hasKey,
                running = thisProcessing && processing.running,
                progress = if (thisProcessing) processing.message else null,
                error = if (thisProcessing) processing.error else null,
                onGenerate = {
                    if (!hasKey) onSettings() else showUploadDialog = true
                },
            )
        } else {
            if (currentNotes == null) {
                OutlinedButton(onClick = { showUploadDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Create detailed notes with sources") }
                Spacer(Modifier.height(12.dp))
            }
            AudioLifecycleNote(currentAnalysis, meeting.audioPath != null)
            if (thisProcessing && processing.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(processing.error.orEmpty(), modifier = Modifier.padding(horizontal = 3.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                    .padding(4.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DetailTab.entries.forEach { tab ->
                    val label = when (tab) {
                        DetailTab.SUMMARY -> "Summary"
                        DetailTab.MAP -> "Mind map"
                        DetailTab.TRANSCRIPT -> "Transcript"
                    }
                    val selected = selectedTab == tab
                    Box(
                        Modifier
                            .weight(1f)
                            .background(
                                if (selected) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent,
                                RoundedCornerShape(11.dp),
                            )
                            .selectable(selected = selected, role = Role.Tab, onClick = { selectedTab = tab })
                            .heightIn(min = 48.dp)
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.secondary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            when (selectedTab) {
                DetailTab.SUMMARY -> SummaryContent(currentAnalysis)
                DetailTab.MAP -> MindMapContent(currentAnalysis.mindMapJson)
                DetailTab.TRANSCRIPT -> if (currentAnalysis.transcript.isNotBlank()) {
                    TranscriptContent(currentAnalysis.transcript)
                } else {
                    AtlasCard(title = "Transcript") {
                        MutedText("No transcript text was saved for this meeting. Create notes from the source audio to add one.")
                    }
                }
            }
            if (currentAnalysis.audioDeletedAt == null && meeting.audioPath != null) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { viewModel.removeAudioAfterAnalysis(meeting.id) },
                    enabled = !processing.running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (processing.running && thisProcessing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Remove source audio")
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        if (BuildConfig.DEBUG) TextButton(onClick = { diagnostics = true }) { Text("Recording diagnostics") }
        TextButton(onClick = { confirmDelete = true }, enabled = capturing == null && !processing.running) {
            Text("Delete meeting", color = MaterialTheme.colorScheme.error)
        }
    }

    if (editingBookmark != null) {
        AlertDialog(
            onDismissRequest = { editingBookmark = null },
            title = { Text("Bookmark label") },
            text = { OutlinedTextField(value = bookmarkLabel, onValueChange = { bookmarkLabel = it.take(160) }, label = { Text("Optional label") }) },
            confirmButton = {
                TextButton(onClick = {
                    editingBookmark?.let { viewModel.labelBookmark(it.id, bookmarkLabel) }
                    editingBookmark = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    editingBookmark?.let { viewModel.deleteBookmark(it.id) }
                    editingBookmark = null
                }) { Text("Delete bookmark") }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this meeting?") },
            text = { Text("This removes its local recordings, notes, transcript and bookmarks. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.deleteMeeting(meeting.id, onBack) }) {
                    Text("Delete meeting", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    if (showUploadDialog) {
        AlertDialog(
            onDismissRequest = { showUploadDialog = false },
            title = { Text("Create source-linked notes?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "${if (currentAnalysis?.transcript?.isNotBlank() == true) "Your saved transcript" else "Saved audio for transcription and transcript windows"} will be sent to OpenRouter. Transcription may cost money; the text model is free by default. ${if (options?.deleteAfterNotes != false) "Audio is deleted only after complete notes are saved." else "Source audio will be kept."}",
                    )
                    MeetingTemplatePicker(template) { template = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Wait until charging", modifier = Modifier.weight(1f))
                        Switch(checked = waitForCharging, onCheckedChange = { waitForCharging = it })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showUploadDialog = false; viewModel.queueNotes(meeting.id, template, waitForCharging) }) {
                    Text(if (waitForCharging) "Queue notes" else "Process now")
                }
            },
            dismissButton = { TextButton(onClick = { showUploadDialog = false }) { Text("Cancel") } },
        )
    }
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Name this meeting") },
            text = {
                OutlinedTextField(
                    value = titleDraft,
                    onValueChange = { titleDraft = it.take(120) },
                    singleLine = true,
                    label = { Text("Meeting title") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.rename(meeting.id, titleDraft); showRenameDialog = false }, enabled = titleDraft.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FilledTonalNotice(text: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            TextButton(onClick = onDismiss) { Text("Back to current notes") }
        }
    }
}

@Composable
private fun ProcessingSetupCard(hasAudio: Boolean, hasKey: Boolean, running: Boolean, progress: String?, error: String?, onGenerate: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, AtlasTheme.colors.cardBorder),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Meeting notes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                AtlasGeneratedBadge()
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    running -> progress ?: "Working through the recording…"
                    !hasAudio -> "No saved audio is available for this meeting."
                    !hasKey -> "Add an OpenRouter key in Settings to create a summary and mind map."
                    else -> "Create a transcript, a concise summary, decisions, next steps, and a mind map."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
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
            Button(
                onClick = onGenerate,
                enabled = hasAudio && !running,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                } else {
                    Text(if (hasKey) "Create summary & mind map" else "Set up OpenRouter")
                }
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
    Surface(color = if (removed) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (removed) Icons.Default.Check else Icons.Default.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (removed) "Source audio removed. Notes are stored on this device." else "Notes saved. Source audio remains on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SummaryContent(analysis: MeetingAnalysisEntity) {
    // Parsing JSON on every recomposition is wasteful; memoise it on the raw payload.
    val keyPoints = remember(analysis.keyPointsJson) { readStringArray(analysis.keyPointsJson) }
    val decisions = remember(analysis.decisionsJson) { readStringArray(analysis.decisionsJson) }
    val actions = remember(analysis.actionItemsJson) { readActionItems(analysis.actionItemsJson) }
    val summary = analysis.summary.trim()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AtlasCard(title = "Summary") {
            if (summary.isEmpty()) {
                MutedText("No summary text was saved for this meeting.")
            } else {
                SelectionContainer { Text(summary, style = MaterialTheme.typography.bodyLarge) }
            }
        }
        if (keyPoints.isNotEmpty()) {
            AtlasCard(title = "Key points") { keyPoints.forEach { BulletText(it) } }
        }
        AtlasCard(title = "Decisions") {
            if (decisions.isEmpty()) {
                MutedText("No explicit decisions were identified.")
            } else {
                decisions.forEach { BulletText(it) }
            }
        }
        AtlasCard(title = "Next steps") {
            if (actions.isEmpty()) {
                MutedText("No assigned actions were identified.")
            } else {
                actions.forEachIndexed { index, action ->
                    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)
                    Text(action.optString("task"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    val owner = action.optString("owner").takeIf(String::isNotBlank)
                    val deadline = action.optString("deadline").takeIf(String::isNotBlank)
                    val metadata = listOfNotNull(owner?.let { "Owner: $it" }, deadline?.let { "Due: $it" }).joinToString(META_SEPARATOR)
                    if (metadata.isNotBlank()) {
                        Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun MindMapContent(raw: String) {
    val map = remember(raw) { runCatching { JSONObject(raw) }.getOrNull() }
    val branches = map?.optJSONArray("branches") ?: JSONArray()
    var expanded by remember(raw) { mutableStateOf((0 until minOf(branches.length(), 4)).toSet()) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(18.dp)) {
            Text(
                map?.optString("label").orEmpty().ifBlank { "Meeting" },
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (branches.length() == 0) MutedText("No map branches were returned.")
        if (branches.length() > 1) {
            // The label must describe what the tap does, not just whether some branch is open.
            TextButton(onClick = { expanded = if (expanded.size >= branches.length()) emptySet() else (0 until branches.length()).toSet() }) {
                Text(if (expanded.size >= branches.length()) "Collapse all branches" else "Expand all branches")
            }
        }
        for (index in 0 until branches.length()) {
            val branch = branches.optJSONObject(index) ?: continue
            val isExpanded = index in expanded
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, AtlasTheme.colors.cardBorder)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                expanded = if (isExpanded) expanded - index else expanded + index
                            }
                            .semantics { stateDescription = if (isExpanded) "Expanded" else "Collapsed" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                        Spacer(Modifier.width(11.dp))
                        Text(branch.optString("label"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Icon(
                            Icons.Default.ExpandMore,
                            // The row already announces its expanded state; a second label would duplicate it.
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.rotate(if (isExpanded) 180f else 0f),
                        )
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
        if (branches.length() > 0 && expanded.size < branches.length()) {
            Text("Select a branch to expand it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun TranscriptContent(transcript: String) {
    AtlasCard(title = "Transcript") {
        SelectionContainer { Text(transcript, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** A titled content card. The content is a [ColumnScope] so children can use `align`. */
@Composable
private fun ContentCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    AtlasCard {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun BulletText(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 8.dp).size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
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
    val savedSettings = remember { viewModel.openRouterSettings() }
    // The key field must never enter saved instance state, where the plaintext token would be written to the activity bundle.
    var apiKey by remember { mutableStateOf("") }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var model by rememberSaveable { mutableStateOf(savedSettings.model) }
    var transcriptionModel by rememberSaveable { mutableStateOf(savedSettings.transcriptionModel) }
    val hasSavedKey by viewModel.hasOpenRouterKey.collectAsStateWithLifecycle()
    val message by viewModel.settingsMessage.collectAsStateWithLifecycle()
    val messageIsError by viewModel.settingsMessageIsError.collectAsStateWithLifecycle()
    val modelsValid = model.isNotBlank() && transcriptionModel.isNotBlank()
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        }
        Spacer(Modifier.height(14.dp))
        AtlasCard(title = "Recording") {
            Text("Quality applies to your next recording. Storage estimates exclude container overhead.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(6.dp))
            // A radio group must be announced as a group, otherwise a screen reader says "not selected" with no context.
            Column(Modifier.selectableGroup()) {
                AudioQuality.entries.forEach { quality ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = options?.quality == quality,
                                role = Role.RadioButton,
                                onClick = { viewModel.setQuality(quality) },
                            )
                            .heightIn(min = 56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = options?.quality == quality, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(quality.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text("~${quality.megabytesPerHour} MB/h", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Prefer Bluetooth microphone", style = MaterialTheme.typography.bodyMedium)
                    Text("Android 12+ when available. Otherwise follows system routing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
                Switch(
                    modifier = Modifier.semantics { contentDescription = "Prefer Bluetooth microphone" },
                    checked = options?.bluetooth == true,
                    enabled = options != null,
                    onCheckedChange = { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        } else {
                            viewModel.setBluetooth(enabled)
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        AtlasCard(title = "Audio retention") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Delete audio after notes are saved", style = MaterialTheme.typography.bodyMedium)
                    Text("Keep notes and transcript. Turn off to keep recordings for playback.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
                Switch(
                    modifier = Modifier.semantics { contentDescription = "Delete audio after notes are saved" },
                    checked = options?.deleteAfterNotes == true,
                    enabled = options != null,
                    onCheckedChange = viewModel::setDeleteAfterNotes,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        AtlasCard(title = "OpenRouter") {
            Text(
                if (hasSavedKey) "A key is stored securely on this device." else "Add your own OpenRouter key. It is encrypted with Android Keystore and never included in the app build.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = apiKey,
                // Trimming on every keystroke would stop the user typing a space; the store trims on save.
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (hasSavedKey) "Replace API key" else "OpenRouter API key") },
                placeholder = { Text("sk-or-…") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showKey) "Hide API key" else "Show API key",
                        )
                    }
                },
            )
            Spacer(Modifier.height(9.dp))
            Button(
                onClick = { viewModel.saveApiKey(apiKey) { apiKey = ""; showKey = false } },
                enabled = apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) { Text("Save key on this device") }
            if (hasSavedKey) {
                TextButton(onClick = { viewModel.deleteApiKey() }, modifier = Modifier.align(Alignment.End)) {
                    Text("Remove saved key", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        AtlasCard(title = "Models") {
            Text(
                "Summary and mind map default to OpenRouter's free-model router. Transcription defaults to Whisper, which may be billed by audio duration. Check current model pricing before processing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = model, onValueChange = { model = it.trim() }, modifier = Modifier.fillMaxWidth(), label = { Text("Summary & mind map · free default") }, singleLine = true, shape = RoundedCornerShape(14.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = transcriptionModel, onValueChange = { transcriptionModel = it.trim() }, modifier = Modifier.fillMaxWidth(), label = { Text("Transcription model · may cost") }, singleLine = true, shape = RoundedCornerShape(14.dp))
            if (!modelsValid) {
                Spacer(Modifier.height(6.dp))
                Text("Both model names are required before these choices can be saved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { viewModel.saveModels(model, transcriptionModel) }, enabled = modelsValid, modifier = Modifier.fillMaxWidth()) { Text("Save model choices") }
        }
        message?.let { text ->
            Spacer(Modifier.height(12.dp))
            Surface(
                color = if (messageIsError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (messageIsError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Audio and transcript are sent to OpenRouter only when you create notes. The free model default applies to summary and mind map; transcription may cost money. Your audio retention choice takes effect after notes are saved.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Spacer(Modifier.height(22.dp))
        Text(
            "Meeting Atlas ${BuildConfig.VERSION_NAME}" + if (BuildConfig.DEBUG) " · debug build" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
        )
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

private fun statusLabel(status: RecordingStatus): String = when (status) {
    RecordingStatus.PREPARING -> "Preparing"
    RecordingStatus.RECORDING -> "Recording"
    RecordingStatus.PAUSED -> "Paused"
    RecordingStatus.SAVED -> "Saved"
    RecordingStatus.INTERRUPTED -> "Interrupted"
    RecordingStatus.FAILED -> "Failed"
}
