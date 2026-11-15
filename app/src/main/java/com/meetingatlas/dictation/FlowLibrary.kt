package com.meetingatlas.dictation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

internal fun historyDay(timestamp: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

internal fun historyHeading(day: LocalDate, today: LocalDate, locale: Locale): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "EEE, MMM d" else "MMM d, yyyy", locale))
}

@Composable internal fun FlowHistory(
    entries: List<DictationEntry>, options: DictationSettings, status: DictationStatus, compact: Boolean,
    copy: (String) -> Unit, export: (String) -> Unit, report: () -> Unit, openAll: () -> Unit, vm: DictationViewModel,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var count by rememberSaveable { mutableIntStateOf(24) }
    var pending by remember { mutableStateOf<DictationEntry?>(null) }
    val filtered = remember(query, entries) {
        entries.filter { query.isBlank() || it.text?.contains(query.trim(), ignoreCase = true) == true }
    }
    if (!compact) FlowField(query, { query = it; count = 24 }, "Search transcripts",
        leadingIcon = { Icon(Icons.Outlined.Search, null) })
    if (filtered.isEmpty()) FlowEmpty(Icons.Outlined.Schedule,
        if (query.isNotBlank()) "No matching dictations" else "No dictations yet",
        when {
            query.isNotBlank() -> "Try a different search."
            options.historyDays == 0 -> "History is off. Turn it on in Privacy to save future dictations."
            else -> "Your saved dictations will appear here."
        })
    else {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val groups = filtered.take(if (compact) 5 else count).groupBy { historyDay(it.createdAt, zone) }
        groups.forEach { (day, rows) ->
            FlowSection(historyHeading(day, today, Locale.getDefault()))
            for (entry in rows) key(entry.id) {
                FlowHistoryItem(entry, status, options.keepAudio, copy, export, report, retry = { vm.coordinator.retry(entry.id) }, delete = { pending = entry })
            }
        }
        if (compact && entries.size > 5) FlowOutline("All history", onClick = openAll)
        if (!compact && filtered.size > count) FlowOutline("Show more") { count += 24 }
    }
    if (pending != null) FlowDialog(onDismissRequest = { pending = null }, title = { Text("Delete dictation?") },
        text = { Text("The saved text and any retained audio will be removed from this device.") },
        confirmButton = { FlowTextButton(onClick = { pending?.let(vm::delete); pending = null }) { Text("Delete") } },
        dismissButton = { FlowTextButton(onClick = { pending = null }) { Text("Cancel") } })
}

@Composable private fun FlowHistoryItem(
    entry: DictationEntry, status: DictationStatus, keepAudio: Boolean, copy: (String) -> Unit, export: (String) -> Unit,
    report: () -> Unit, retry: () -> Unit, delete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    FlowCard {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(entry.createdAt)), Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                IconButton(onClick = { expanded = true }, Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.MoreVert, "Dictation actions", Modifier.size(20.dp))
                }
                DropdownMenu(expanded, { expanded = false }) {
                    entry.text?.let { text ->
                        DropdownMenuItem(text = { Text("Copy") }, onClick = { expanded = false; copy(text) })
                        DropdownMenuItem(text = { Text("Export") }, onClick = { expanded = false; export(text) })
                    }
                    if (entry.audioKept && keepAudio && entry.text == null) DropdownMenuItem(
                        text = { Text("Retry") }, onClick = { expanded = false; retry() }, enabled = !status.busy,
                    )
                    DropdownMenuItem(text = { Text("Report an issue") }, onClick = { expanded = false; report() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { expanded = false; delete() }, enabled = !status.busy)
                }
            }
        }
        entry.text?.let { transcript ->
            Text(transcript, Modifier.fillMaxWidth().clickable { copy(transcript) }.padding(bottom = 3.dp),
                style = MaterialTheme.typography.bodyLarge, maxLines = 7, overflow = TextOverflow.Ellipsis)
        } ?: FlowMuted(entry.error ?: "Dictation couldn’t finish.")
    }
}

@Composable internal fun FlowDictionary(options: DictationSettings, vm: DictationViewModel, open: Boolean, onOpen: (Boolean) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var value by rememberSaveable { mutableStateOf("") }
    var delete by remember { mutableStateOf<String?>(null) }
    if (!open) LaunchedEffect(open) { editing = null; value = "" }
    FlowTitle("Dictionary")
    FlowMuted("Teach Atlas the names, terms and spellings you use.")
    FlowField(query, { query = it }, "Search dictionary", leadingIcon = { Icon(Icons.Outlined.Search, null) })
    val words = options.vocabulary.filter { it.contains(query.trim(), ignoreCase = true) }
    if (words.isEmpty()) FlowEmpty(Icons.AutoMirrored.Outlined.MenuBook, if (query.isBlank()) "Make it sound like you" else "No matching words",
        if (query.isBlank()) "Add a name or a term you want Atlas to recognize." else "Try a different search.",
        if (query.isBlank()) ({ FlowOutline("Add word") { onOpen(true) } }) else null)
    else FlowRows {
        words.forEachIndexed { i, term ->
            if (i > 0) FlowLine()
            var menu by remember(term) { mutableStateOf(false) }
            FlowRow(term, onClick = { editing = term; value = term; onOpen(true) }) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Actions for $term") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; editing = term; value = term; onOpen(true) })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; delete = term })
                    }
                }
            }
        }
    }
    if (open) {
        val duplicate = options.vocabulary.any { it != editing && it.equals(value.trim(), ignoreCase = true) }
        FlowDialog(onDismissRequest = { onOpen(false) }, title = { Text(if (editing == null) "Add word" else "Edit word") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowField(value, { value = it.take(60) }, "Word or phrase")
                if (duplicate) Text("This word is already in the dictionary.", color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { FlowButton(if (editing == null) "Add" else "Save", enabled = value.isNotBlank() && !duplicate &&
                (editing != null || options.vocabulary.size < 50)) {
                val word = value.trim()
                val previous = editing
                vm.update { it.copy(vocabulary = (it.vocabulary.filterNot { t -> t == previous } + word).distinct()) }
                onOpen(false)
            } },
            dismissButton = { FlowTextButton(onClick = { onOpen(false) }) { Text("Cancel") } })
    }
    if (delete != null) DeletePrompt("Delete word?", { delete = null }) {
        val entry = delete
        vm.update { it.copy(vocabulary = it.vocabulary.filterNot { term -> term == entry }) }
        delete = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun FlowSnippets(options: DictationSettings, vm: DictationViewModel, open: Boolean, onOpen: (Boolean) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var trigger by rememberSaveable { mutableStateOf("") }
    var template by rememberSaveable { mutableStateOf("") }
    var oldTrigger by rememberSaveable { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf<Snippet?>(null) }
    if (!open) LaunchedEffect(open) { oldTrigger = null; trigger = ""; template = "" }
    fun edit(snippet: Snippet) { oldTrigger = snippet.trigger; trigger = snippet.trigger; template = snippet.text; onOpen(true) }
    FlowTitle("Snippets")
    FlowMuted("Say a phrase to insert text you use often.")
    FlowField(query, { query = it }, "Search snippets", leadingIcon = { Icon(Icons.Outlined.Search, null) })
    FlowRows { FlowRow("Expand matching phrases", "Uses exact whole-utterance matches.") {
        Switch(options.snippetsEnabled, { enabled -> vm.update { it.copy(snippetsEnabled = enabled) } },
            modifier = Modifier.semantics { contentDescription = "Expand matching phrases" })
    } }
    val items = options.snippets.filter { it.trigger.contains(query.trim(), true) || it.text.contains(query.trim(), true) }
    if (items.isEmpty()) FlowEmpty(Icons.Outlined.AutoAwesome, if (query.isBlank()) "Say it in fewer words" else "No matching snippets",
        if (query.isBlank()) "Create a snippet for a sign-off, a reply or any reusable text." else "Try a different search.",
        if (query.isBlank()) ({ FlowOutline("Add snippet") { onOpen(true) } }) else null)
    else FlowRows {
        items.forEachIndexed { i, snippet ->
            if (i > 0) FlowLine()
            var menu by remember(snippet.trigger) { mutableStateOf(false) }
            FlowRow(snippet.trigger, snippet.text.lineSequence().firstOrNull().orEmpty().take(95), onClick = { edit(snippet) }) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Actions for ${snippet.trigger}") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; edit(snippet) })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; delete = snippet })
                    }
                }
            }
        }
    }
    if (open) {
        val duplicate = options.snippets.any { it.trigger != oldTrigger &&
            DictationPolicy.triggerKey(it.trigger) == DictationPolicy.triggerKey(trigger) }
        ModalBottomSheet(onDismissRequest = { onOpen(false) }, containerColor = MaterialTheme.colorScheme.surface,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FlowTitle(if (oldTrigger == null) "Add snippet" else "Edit snippet")
                FlowField(trigger, { trigger = it.take(80) }, "Trigger phrase")
                FlowField(template, { template = it.take(2000) }, "Expansion text", minLines = 3)
                if (duplicate) Text("This trigger is already used.", color = MaterialTheme.colorScheme.error)
                FlowButton("Save snippet", Modifier.fillMaxWidth(),
                    enabled = DictationPolicy.triggerKey(trigger).isNotBlank() && template.isNotBlank() &&
                        !duplicate && (oldTrigger != null || options.snippets.size < 30)) {
                    val next = Snippet(trigger.trim(), template)
                    val previous = oldTrigger
                    vm.update { it.copy(snippets = it.snippets.filterNot { entry -> entry.trigger == previous } + next) }
                    onOpen(false)
                }
                FlowOutline("Cancel", Modifier.fillMaxWidth()) { onOpen(false) }
            }
        }
    }
    if (delete != null) DeletePrompt("Delete snippet?", { delete = null }) {
        val entry = delete
        vm.update { it.copy(snippets = it.snippets.filterNot { item -> item == entry }) }
        delete = null
    }
}

@Composable private fun DeletePrompt(title: String, cancel: () -> Unit, delete: () -> Unit) {
    FlowDialog(onDismissRequest = cancel, title = { Text(title) },
        text = { Text("This entry will be removed from this device.") },
        confirmButton = { FlowTextButton(onClick = delete) { Text("Delete") } },
        dismissButton = { FlowTextButton(onClick = cancel) { Text("Cancel") } })
}
