package com.meetingatlas.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meetingatlas.data.*
import com.meetingatlas.notes.*
import com.meetingatlas.recording.formatRecordingTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MeetingTemplatePicker(value: MeetingTemplate, onChange: (MeetingTemplate) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(value.label, Modifier.weight(1f)); Icon(Icons.Default.ExpandMore, null) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            MeetingTemplate.entries.forEach { template -> DropdownMenuItem(text = { Text(template.label) }, onClick = { onChange(template); expanded = false }) }
        }
    }
}

@Composable
private fun NotePanel(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotesScreen(meeting: MeetingEntity, document: NotesDocument, viewModel: HomeViewModel, onBack: () -> Unit, onSettings: () -> Unit, onPreviousNotes: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val jobs by remember(meeting.id) { viewModel.observeNotesJobs(meeting.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val segments by remember(meeting.id) { viewModel.observeSegments(meeting.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val bookmarks by remember(meeting.id) { viewModel.observeBookmarks(meeting.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var tab by rememberSaveable(meeting.id) { mutableIntStateOf(0) }
    var view by rememberSaveable(meeting.id) { mutableStateOf(SummaryView.STANDARD) }
    var sourceLimit by rememberSaveable(meeting.id) { mutableIntStateOf(100) }
    var transcriptQuery by rememberSaveable(meeting.id) { mutableStateOf("") }
    // One extra row tells us whether more matches exist, so "Load more" is never offered at the end.
    val transcriptRows by remember(meeting.id, sourceLimit, transcriptQuery) { viewModel.searchTranscript(meeting.id, transcriptQuery, sourceLimit + 1) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val transcript = remember(transcriptRows, sourceLimit) { transcriptRows.take(sourceLimit) }
    val hasMoreTranscript = transcriptRows.size > sourceLimit
    var seek by remember { mutableStateOf<PlaybackSeek?>(null) }
    var selectedEntry by remember { mutableStateOf<NoteEntryEntity?>(null) }
    var selectedTopic by remember { mutableStateOf<NoteTopicEntity?>(null) }
    var chapter by remember { mutableStateOf<MeetingChapterEntity?>(null) }
    var refreshTopic by remember { mutableStateOf<NoteTopicEntity?>(null) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var title by rememberSaveable(meeting.id) { mutableStateOf(meeting.title) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmAudioDelete by remember { mutableStateOf(false) }
    var confirmRetranscribe by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // A stored template name can be unknown after a downgrade or manual edit; never crash the notes screen for it.
    val template = meetingTemplateFrom(document.state.template)
    val activeJobs = remember(jobs) { jobs.filter { it.status in setOf("QUEUED", "RUNNING", "WAITING", "TRANSCRIBING", "EXTRACTING") } }
    // Jobs are ordered newest first: only the latest attempt may advertise a failure or cleanup warning.
    val latestJob = jobs.firstOrNull()
    val latestFailure = latestJob?.takeIf { it.status in setOf("FAILED", "CANCELLED") }
    val cleanupWarning = if (document.state.audioDeletedAt == null) {
        jobs.firstOrNull { it.status == "SUCCEEDED" }?.takeIf { it.error != null }?.error
    } else null
    val canRefresh = activeJobs.isEmpty() && document.state.status in setOf("FINALIZED", "PARTIAL_AUDIO")
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val markdown = viewModel.exportNotes(meeting.title, document, view)
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { it.write(markdown) }
                }
                snackbar.showSnackbar("Notes exported")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { viewModel.reportLocalError("The export could not be completed. Try another destination.") }
        }
    }
    val visible = remember(document.entries, view) { SummaryProjection.select(document.entries, view) }
    val visibleByTopic = remember(visible) { visible.groupBy { it.topicId } }
    val entriesInMapByTopic = remember(document.entries) { document.entries.filter { it.inMap }.groupBy { it.topicId } }
    val orderedTopics = remember(document.topics) { document.topics.sortedBy { it.position } }
    val orderedChapters = remember(document.chapters) { document.chapters.sortedBy { it.startMs } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = { Text(meeting.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }, actions = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Meeting actions") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename meeting") }, onClick = { menu = false; title = meeting.title; rename = true })
                    DropdownMenuItem(text = { Text("Export ${view.name.lowercase()} notes") }, onClick = { menu = false; export.launch("${meeting.title.take(60).replace(Regex("[^A-Za-z0-9 ._-]"), "_")}-notes.md") })
                    DropdownMenuItem(text = { Text("OpenRouter settings") }, onClick = { menu = false; onSettings() })
                    if (onPreviousNotes != null) DropdownMenuItem(text = { Text("Previous saved notes") }, onClick = { menu = false; onPreviousNotes() })
                    DropdownMenuItem(text = { Text("Delete meeting") }, enabled = activeJobs.isEmpty(), onClick = { menu = false; confirmDelete = true })
                }
            }
        })
    }) { inset ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(inset), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "status") {
                val label = when (document.state.status) { "FINALIZED" -> "Final notes · source linked"; "NO_SPEECH" -> "No speech detected · source audio kept"; "PARTIAL_AUDIO" -> "Available audio processed · recording interrupted"; "TRANSCRIBING" -> "Transcribing saved audio"; "QUEUED" -> "Queued for processing"; else -> "Draft notes · processing incomplete" }
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("${template.label} · Revision ${document.state.revision}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            if (activeJobs.isNotEmpty()) item(key = "processing") {
                NotePanel {
                    Text(activeJobs.first().status.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall)
                    Text("${document.state.transcribedChunks} of ${document.state.expectedChunks} audio chunks saved. Processing waits for network, battery and recording availability.", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = { activeJobs.forEach { viewModel.cancelNotes(it.id) } }) { Text("Pause processing") }
                }
            }
            latestFailure?.let { failed -> item(key = "retry") {
                NotePanel {
                    Text(failed.error ?: "Processing did not finish. Saved content is safe.", style = MaterialTheme.typography.bodySmall)
                    Text("Resume uses your current model settings for unfinished work.", style = MaterialTheme.typography.bodySmall)
                    Row { TextButton(onClick = { viewModel.queueNotes(meeting.id, template, topicId = failed.topicId) }) { Text("Resume saved progress") }; TextButton(onClick = onSettings) { Text("Settings") } }
                }
            } }
            cleanupWarning?.let { warning -> item(key = "cleanup-warning") { Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }
            if (document.state.status == "NO_SPEECH" && activeJobs.isEmpty() && meeting.audioPath != null) item(key = "retry-speech") {
                OutlinedButton(onClick = { confirmRetranscribe = true }) { Text("Try transcription again") }
            }
            if (meeting.audioPath != null) item(key = "player") { MeetingPlayer(meeting, segments, viewModel, seek) { seek = null } }
            if (bookmarks.isNotEmpty()) item(key = "bookmarks") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    bookmarks.forEach { mark -> AssistChip(onClick = { seek = PlaybackSeek(mark.elapsedMs); scope.launch { listState.scrollToItem(0) } }, enabled = meeting.audioPath != null, label = { Text("${formatRecordingTime(mark.elapsedMs)} ${mark.label}".trim()) }) }
                }
            }
            item(key = "tabs") {
                // Scrollable so the row survives narrow screens and large font scales.
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Overview", "Transcript", "Mind map").forEachIndexed { index, label -> FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(label) }) }
                }
            }
            if (tab == 0) {
                item(key = "views") {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SummaryView.entries.forEach { mode -> FilterChip(selected = view == mode, onClick = { view = mode }, label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
                    }
                }
                if (orderedChapters.isNotEmpty() && view == SummaryView.DETAILED) item(key = "chapters") {
                    NotePanel("Chapters") { orderedChapters.forEach { c -> TextButton(onClick = { chapter = c }) { Text("${formatRecordingTime(c.startMs)} · ${c.title}") } } }
                }
                if (visible.isEmpty()) item(key = "empty-notes") { Text(if (view == SummaryView.STUDY) "No educational definitions, formulas, examples or explanations have been saved from this meeting." else "No notes saved in this view yet. Completed transcript segments are available in Transcript.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary) }
                orderedTopics.forEach { topic ->
                    val notes = visibleByTopic[topic.id].orEmpty()
                    if (notes.isNotEmpty()) {
                        item(key = "heading-${topic.id}") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(topic.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                IconButton(onClick = { selectedTopic = topic }) { Icon(Icons.Default.AccountTree, "Open topic in mind map") }
                                if (canRefresh) IconButton(onClick = { refreshTopic = topic }) { Icon(Icons.Default.Refresh, "Regenerate this topic") }
                            }
                        }
                        items(notes, key = { "entry-${it.id}" }) { entry -> NoteEntryCard(entry, viewModel) { selectedEntry = entry } }
                    }
                }
            } else if (tab == 1) {
                item(key = "query") { OutlinedTextField(value = transcriptQuery, onValueChange = { transcriptQuery = it.take(160); sourceLimit = 100 }, label = { Text("Search transcript") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                items(transcript, key = { it.id }) { source ->
                    NotePanel {
                        source.startMs?.let { time ->
                            TextButton(onClick = { seek = PlaybackSeek(time); scope.launch { listState.scrollToItem(0) } }, enabled = meeting.audioPath != null) { Text("${formatRecordingTime(time)}${if (source.precision == "CHUNK") " · approximate interval" else ""}") }
                        }
                        Text(source.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (hasMoreTranscript) item(key = "more-sources") { OutlinedButton(onClick = { sourceLimit += 100 }, modifier = Modifier.fillMaxWidth()) { Text("Load more transcript") } }
                if (transcript.isEmpty()) item(key = "no-sources") {
                    Text(
                        if (transcriptQuery.isBlank()) "No finalized transcript segments are saved yet." else "No transcript segments match “$transcriptQuery”.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            } else {
                item(key = "map-root") { Text(meeting.title, style = MaterialTheme.typography.headlineSmall); Text("Saved topics and evidence from your notes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                if (orderedTopics.isEmpty()) item(key = "map-empty") {
                    Text("No topics are saved yet. Topics and their evidence appear here once notes finish processing.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                }
                items(orderedTopics, key = { it.id }) { topic ->
                    NotePanel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.collapseTopic(topic.id, !topic.collapsed) }) { Icon(if (topic.collapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore, if (topic.collapsed) "Expand topic" else "Collapse topic") }
                            Text(topic.title, modifier = Modifier.weight(1f).clickable { selectedTopic = topic }, style = MaterialTheme.typography.titleMedium)
                            IconButton(onClick = { selectedTopic = topic }) { Icon(Icons.Default.MoreHoriz, "Topic details") }
                        }
                        if (!topic.collapsed) entriesInMapByTopic[topic.id].orEmpty().forEach { entry ->
                            Row(Modifier.fillMaxWidth().clickable { selectedEntry = entry }.padding(start = 22.dp, top = 10.dp, bottom = 10.dp)) { Text("•  ", color = MaterialTheme.colorScheme.primary); Text(entry.text, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
            if (meeting.audioPath != null && activeJobs.isEmpty() && document.state.status in setOf("FINALIZED", "PARTIAL_AUDIO")) item(key = "remove-audio") { TextButton(onClick = { confirmAudioDelete = true }) { Text("Remove source audio") } }
            item(key = "footer") { Spacer(Modifier.height(20.dp)) }
        }
    }
    selectedEntry?.let { entry ->
        val current = document.entries.firstOrNull { it.id == entry.id } ?: entry
        NoteEntrySheet(current, meeting.audioPath != null, viewModel, onDismiss = { selectedEntry = null }, onSeek = { time -> selectedEntry = null; seek = PlaybackSeek(time); scope.launch { listState.scrollToItem(0) } }, onMap = { selectedEntry = null; tab = 2; selectedTopic = document.topics.firstOrNull { it.id == entry.topicId } })
    }
    selectedTopic?.let { topic ->
        TopicSheet(topic, document.entries.filter { it.topicId == topic.id }, viewModel, canRefresh, onDismiss = { selectedTopic = null }, onEntry = { selectedTopic = null; selectedEntry = it }, onRefresh = { selectedTopic = null; refreshTopic = topic })
    }
    chapter?.let { c -> ChapterSheet(c, viewModel, meeting.audioPath != null, onDismiss = { chapter = null }, onSeek = { chapter = null; seek = PlaybackSeek(c.startMs); scope.launch { listState.scrollToItem(0) } }, onEntry = { chapter = null; selectedEntry = it }) }
    refreshTopic?.let { topic -> AlertDialog(onDismissRequest = { refreshTopic = null }, title = { Text("Refresh ${topic.title}?") }, text = { Text("Only this topic's saved transcript evidence is sent to OpenRouter. Existing manual edits are preserved; proposed replacements appear in revision history.") }, confirmButton = { TextButton(onClick = { refreshTopic = null; viewModel.queueNotes(meeting.id, template, topicId = topic.id) }) { Text("Refresh topic") } }, dismissButton = { TextButton(onClick = { refreshTopic = null }) { Text("Cancel") } }) }
    if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text("Meeting title") }, text = { OutlinedTextField(value = title, onValueChange = { title = it.take(120) }, label = { Text("Title") }, singleLine = true) }, confirmButton = { TextButton(onClick = { viewModel.rename(meeting.id, title); rename = false }, enabled = title.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete meeting?") }, text = { Text("All its audio, notes, transcript, chapters and history will be removed from this device.") }, confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.deleteMeeting(meeting.id, onBack) }) { Text("Delete meeting", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
    if (confirmAudioDelete) AlertDialog(onDismissRequest = { confirmAudioDelete = false }, title = { Text("Remove source recording?") }, text = { Text("Notes, evidence text and history stay on this device. Playback will no longer be available.") }, confirmButton = { TextButton(onClick = { confirmAudioDelete = false; viewModel.removeAudioAfterAnalysis(meeting.id) }) { Text("Remove audio") } }, dismissButton = { TextButton(onClick = { confirmAudioDelete = false }) { Text("Cancel") } })
    if (confirmRetranscribe) AlertDialog(onDismissRequest = { confirmRetranscribe = false }, title = { Text("Transcribe again?") }, text = { Text("No speech was saved on the previous attempt. This uploads the source audio again using your current OpenRouter transcription model, which may charge for another attempt.") }, confirmButton = { TextButton(onClick = { confirmRetranscribe = false; viewModel.queueNotes(meeting.id, template) }) { Text("Transcribe again") } }, dismissButton = { TextButton(onClick = { confirmRetranscribe = false }) { Text("Cancel") } })
}

@Composable
private fun NoteEntryCard(entry: NoteEntryEntity, viewModel: HomeViewModel, onOpen: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen), color = if (entry.highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.section.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                if (entry.section == "ACTION") Checkbox(checked = entry.completed, onCheckedChange = { viewModel.completeAction(entry.id, it) }, modifier = Modifier.semantics { contentDescription = "Complete action: ${entry.text}" })
            }
            Text(entry.text, style = MaterialTheme.typography.bodyLarge)
            val meta = listOfNotNull(entry.owner?.let { "Owner: $it" }, entry.deadline?.let { "Due: $it" }).joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall)
            Text(when { entry.supersededBy != null -> "Revised later · open history"; entry.userEdited -> "User edited · sources and history"; entry.verification == "UNCERTAIN" -> "Uncertain · review transcript evidence"; else -> "Source linked · open evidence" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEntrySheet(entry: NoteEntryEntity, hasAudio: Boolean, viewModel: HomeViewModel, onDismiss: () -> Unit, onSeek: (Long) -> Unit, onMap: () -> Unit) {
    val context = LocalContext.current
    var evidence by remember(entry.id) { mutableStateOf<List<SourceEvidence>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val history by remember(entry.id) { viewModel.observeNoteHistory(entry.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var editing by remember { mutableStateOf(false) }
    var editingRevision by remember(entry.id) { mutableIntStateOf(entry.revision) }
    var draft by remember(entry.id) { mutableStateOf(entry.text) }
    var showHistory by remember { mutableStateOf(false) }
    var copied by remember(entry.id) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2_000); copied = false } }
    LaunchedEffect(entry.id, entry.revision) { try { evidence = viewModel.noteEvidence(entry.id) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { error = "Evidence could not be opened. Reopen this note to retry." } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(entry.section.lowercase().replace('_', ' '), style = MaterialTheme.typography.titleLarge)
            if (editing) {
                OutlinedTextField(value = draft, onValueChange = { draft = it.take(10000) }, modifier = Modifier.fillMaxWidth(), minLines = 4, label = { Text("Edit note") })
                Button(onClick = { viewModel.editNote(entry.copy(revision = editingRevision), draft, onDismiss) }, enabled = draft.isNotBlank()) { Text("Save new revision") }
            } else Text(entry.text, style = MaterialTheme.typography.bodyLarge)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = {
                    runCatching { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Meeting note", entry.text)) }
                        .onSuccess { copied = true }
                        .onFailure { error = "The note could not be copied to the clipboard." }
                }, modifier = Modifier.semantics { if (copied) liveRegion = LiveRegionMode.Polite }) { Text(if (copied) "Copied" else "Copy") }
                TextButton(onClick = { if (!editing) { editingRevision = entry.revision; draft = entry.text }; editing = !editing }) { Text(if (editing) "Cancel edit" else "Edit") }
                TextButton(onClick = { viewModel.highlightNote(entry.id) }) { Text(if (entry.highlighted) "Unhighlight" else "Highlight") }
                TextButton(onClick = { viewModel.addNoteToMap(entry.id); onMap() }) { Text("Open in mind map") }
                TextButton(onClick = { showHistory = !showHistory }) { Text("History (${history.size})") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (showHistory) history.forEach { revision ->
                NotePanel("Revision ${revision.number} · ${revision.origin.lowercase().replace('_', ' ')}") {
                    Text(revision.text, style = MaterialTheme.typography.bodyMedium)
                    if (revision.text != entry.text) TextButton(onClick = { draft = revision.text; editingRevision = entry.revision; editing = true; showHistory = false }) { Text("Use as a new edit") }
                }
            }
            Text("Transcript evidence", style = MaterialTheme.typography.titleMedium)
            Text("These links locate the supporting quotes. Review the source to judge the note's accuracy.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            evidence.forEach { source ->
                NotePanel {
                    Text("“${source.quote}”", style = MaterialTheme.typography.bodyLarge)
                    if (source.sourceRevision != source.currentRevision) Text("The transcript has changed since this citation was saved.", color = MaterialTheme.colorScheme.error)
                    source.startMs?.let { time -> TextButton(onClick = { onSeek(time) }, enabled = hasAudio) { Text("${formatRecordingTime(time)}${if (source.precision == "CHUNK") " · approximate chunk interval" else " · open recording"}") } } ?: Text("Original transcript · timestamp unavailable", style = MaterialTheme.typography.bodySmall)
                    Text(source.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopicSheet(topic: NoteTopicEntity, entries: List<NoteEntryEntity>, viewModel: HomeViewModel, canRefresh: Boolean, onDismiss: () -> Unit, onEntry: (NoteEntryEntity) -> Unit, onRefresh: () -> Unit) {
    var title by remember(topic.id) { mutableStateOf(topic.title) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 650.dp), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedTextField(title, onValueChange = { title = it.take(160) }, label = { Text("Topic title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row {
                    TextButton(onClick = { viewModel.renameTopic(topic.id, title); onDismiss() }, enabled = title.isNotBlank() && title.trim() != topic.title) { Text("Save title") }
                    TextButton(onClick = onRefresh, enabled = canRefresh) { Text("Refresh from meeting") }
                }
            }
            items(entries, key = { it.id }) { entry -> NoteEntryCard(entry, viewModel) { onEntry(entry) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSheet(chapter: MeetingChapterEntity, viewModel: HomeViewModel, hasAudio: Boolean, onDismiss: () -> Unit, onSeek: () -> Unit, onEntry: (NoteEntryEntity) -> Unit) {
    var notes by remember(chapter.id) { mutableStateOf<List<NoteEntryEntity>>(emptyList()) }
    var sources by remember(chapter.id) { mutableStateOf<List<TranscriptSegmentEntity>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(chapter.id) { try { notes = viewModel.chapterNotes(chapter.id); sources = viewModel.chapterTranscript(chapter.id) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { error = "Chapter content could not be opened. Try again." } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 650.dp), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(chapter.title, style = MaterialTheme.typography.titleLarge); TextButton(onClick = onSeek, enabled = hasAudio) { Text("${formatRecordingTime(chapter.startMs)} – ${formatRecordingTime(chapter.endMs)} · recording") }; error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
            items(notes, key = { it.id }) { entry -> NoteEntryCard(entry, viewModel) { onEntry(entry) } }
            item { Text("Chapter transcript", style = MaterialTheme.typography.titleMedium) }
            items(sources, key = { it.id }) { Text(it.text, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
