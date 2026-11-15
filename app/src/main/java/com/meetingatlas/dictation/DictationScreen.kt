package com.meetingatlas.dictation

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import com.meetingatlas.BuildConfig
import com.meetingatlas.R
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.meetingatlas.ai.OpenRouterSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.math.ceil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@HiltViewModel
internal class DictationViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    val coordinator: DictationCoordinator,
    private val preferences: DictationSettingsRepository,
    private val history: DictationHistoryRepository,
    private val keys: OpenRouterSettingsStore,
) : ViewModel() {
    val entries = history.entries.catch {
        _notice.value = "Could not read local dictation history."
        emit(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _notice = MutableStateFlow("")
    val notice = _notice.asStateFlow()
    private val _keyReady = MutableStateFlow(false)
    val keyReady = _keyReady.asStateFlow()

    init { viewModelScope.launch { coordinator.settings.map { it.provider }.distinctUntilChanged().collect { refreshKey() } } }

    fun refreshKey() { viewModelScope.launch {
        val provider = coordinator.settings.value.provider
        val ready = withContext(Dispatchers.IO) { runCatching { keys.key(provider == SpeechProvider.OPEN_AI) != null }.getOrDefault(false) }
        if (provider == coordinator.settings.value.provider) _keyReady.value = ready
    } }
    val setupRequired = preferences.data.map<DictationSettings, Boolean?> { !it.onboardingDone && !it.consent }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun finishSetup() = launch { preferences.finishOnboarding() }
    fun consent(value: Boolean) = launch { preferences.consent(value) }
    fun update(change: (DictationSettings) -> DictationSettings) = launch { preferences.update(change) }
    fun notice(text: String) { _notice.value = text }
    fun saveKey(provider: SpeechProvider, key: String) = launch {
        val token = key.trim()
        require(token.length in 8..500 && token.all { it in '!'..'~' }) { "Enter a valid API key." }
        withContext(Dispatchers.IO) { keys.saveKey(provider == SpeechProvider.OPEN_AI, token) }
        refreshKey()
        notice("API key saved on this device.")
    }
    fun removeKey(provider: SpeechProvider) = launch {
        withContext(Dispatchers.IO) { keys.deleteKey(provider == SpeechProvider.OPEN_AI) }
        refreshKey()
        notice("API key removed.")
    }
    fun delete(entry: DictationEntry) = launch { history.delete(entry.id) }
    fun export(uri: Uri, text: String) = launch {
        withContext(Dispatchers.IO) {
            val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the selected file.")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        }
        notice("Text exported.")
    }
    private fun launch(work: suspend () -> Unit) { viewModelScope.launch {
        try { work() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: IllegalArgumentException) { notice(failure.message ?: "Check the entered values.") }
        catch (_: Exception) { notice("Atlas could not save the change. Try again.") }
    } }
}

private enum class FlowPage { Home, Dictionary, Snippets, History, Settings, Provider, Help, Account }

@Composable internal fun DictationScreen(
    onMeetings: () -> Unit, onLibrary: () -> Unit, recoverySignal: Int, vm: DictationViewModel = hiltViewModel(),
) = FlowTheme {
    val context = LocalContext.current
    val options by vm.coordinator.settings.collectAsStateWithLifecycle()
    val status by vm.coordinator.status.collectAsStateWithLifecycle()
    val result by vm.coordinator.result.collectAsStateWithLifecycle()
    val samples by vm.coordinator.latency.collectAsStateWithLifecycle()
    val connected by vm.coordinator.connected.collectAsStateWithLifecycle()
    val lastIssue by vm.coordinator.lastIssue.collectAsStateWithLifecycle()
    val records by vm.entries.collectAsStateWithLifecycle()
    val hasKey by vm.keyReady.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val firstRun by vm.setupRequired.collectAsStateWithLifecycle()
    val entries = remember(records, options.historyDays) {
        if (options.historyDays == 0) emptyList() else records.filter {
            it.createdAt >= System.currentTimeMillis() - options.historyDays * 86_400_000L
        }
    }
    var page by rememberSaveable { mutableStateOf(FlowPage.Home) }
    var setup by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var step by rememberSaveable { mutableStateOf(FlowStep.Welcome) }
    var showDisclosure by rememberSaveable { mutableStateOf(false) }
    var edit by rememberSaveable(page) { mutableStateOf(false) }
    var feedback by rememberSaveable { mutableStateOf<String?>(null) }

    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    LaunchedEffect(firstRun) { if (setup == null && firstRun != null) setup = firstRun }
    LaunchedEffect(recoverySignal) { if (recoverySignal > 0) { setup = false; page = FlowPage.Home } }
    var mic by remember { mutableStateOf(context.permitted(Manifest.permission.RECORD_AUDIO)) }
    var overlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var alerts by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 || context.permitted(Manifest.permission.POST_NOTIFICATIONS)) }
    var bluetooth by remember { mutableStateOf(Build.VERSION.SDK_INT < 31 || context.permitted(Manifest.permission.BLUETOOTH_CONNECT)) }
    fun refresh() {
        mic = context.permitted(Manifest.permission.RECORD_AUDIO)
        overlay = Settings.canDrawOverlays(context)
        alerts = Build.VERSION.SDK_INT < 33 || context.permitted(Manifest.permission.POST_NOTIFICATIONS)
        bluetooth = Build.VERSION.SDK_INT < 31 || context.permitted(Manifest.permission.BLUETOOTH_CONNECT)
        vm.refreshKey()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.coordinator.testHidden() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh()
        if (!it) vm.notice("Permission wasn’t granted. You can try again or change it in Android app settings.")
    }
    val packageUri = "package:${context.packageName}".toUri()
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
    val permissions = FlowPermissions(mic, overlay, connected, alerts, bluetooth)
    fun launch(intent: Intent) {
        try { context.startActivity(intent) }
        catch (_: RuntimeException) { vm.notice("Couldn’t open that screen. Try again from Android settings.") }
    }
    fun repair(item: FlowPermission) = when (item) {
        FlowPermission.Microphone -> if (mic) launch(appDetails) else permission.launch(Manifest.permission.RECORD_AUDIO)
        FlowPermission.Overlay -> launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri))
        FlowPermission.Accessibility -> if (options.consent) launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) else { showDisclosure = true }
        FlowPermission.Notifications -> if (Build.VERSION.SDK_INT >= 33 && !alerts)
            permission.launch(Manifest.permission.POST_NOTIFICATIONS) else launch(appDetails)
        FlowPermission.Bluetooth -> if (Build.VERSION.SDK_INT >= 31 && !bluetooth)
            permission.launch(Manifest.permission.BLUETOOTH_CONNECT) else launch(appDetails)
        FlowPermission.AppDetails -> launch(appDetails)
    }
    var exportText by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportText?.let { vm.export(uri, it) }
        exportText = null
    }
    fun export(text: String) { exportText = text; exporter.launch("atlas-dictation.txt") }
    fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Atlas dictation", text).apply {
            if (Build.VERSION.SDK_INT >= 33) description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        })
        vm.notice("Copied to clipboard.")
    }
    fun finishSetup() { vm.finishSetup(); setup = false; page = FlowPage.Home }
    fun changePage(next: FlowPage) { edit = false; page = next }
    fun closeDrawer(action: () -> Unit) { scope.launch { drawer.close(); action() } }
    fun back() { if (drawer.isOpen) scope.launch { drawer.close() } else changePage(FlowPage.Home) }
    val topPage = page in listOf(FlowPage.Home, FlowPage.Dictionary, FlowPage.Snippets)
    val colors = MaterialTheme.colorScheme
    val pending = setup
    BackHandler((!topPage || drawer.isOpen || page != FlowPage.Home) && pending == false) { back() }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        when {
            pending == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.meeting_atlas_mark), "Atlas", Modifier.size(64.dp))
            }
            pending && page != FlowPage.Provider -> Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                if (notice.isNotBlank()) FlowNotice(notice) { vm.notice("") }
                FlowOnboarding(step, back = { step = FlowStep.entries[(step.ordinal - 1).coerceAtLeast(0)] },
                    next = { if (step == FlowStep.Done) finishSetup() else step = FlowStep.entries[step.ordinal + 1] },
                    leave = ::finishSetup, options, permissions, hasKey, vm, ::repair,
                    review = { showDisclosure = true }, provider = { changePage(FlowPage.Provider) })
            }
            else -> ModalNavigationDrawer(
                drawerState = drawer, gesturesEnabled = topPage && pending == false && !edit, scrimColor = Color.Black.copy(alpha = .32f),
                drawerContent = {
                    ModalDrawerSheet(Modifier.widthIn(max = 308.dp).fillMaxWidth(.84f),
                        drawerContainerColor = colors.surface, drawerContentColor = colors.onSurface, windowInsets = WindowInsets(0, 0, 0, 0)) {
                        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(12.dp)) {
                            Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Image(painterResource(R.drawable.meeting_atlas_mark), null, Modifier.size(36.dp))
                                Spacer(Modifier.width(10.dp))
                                Column { Text("Atlas", style = MaterialTheme.typography.titleMedium); FlowMuted("On this device") }
                            }
                            FlowLine()
                            FlowRow("Report an issue", icon = Icons.Outlined.Flag, onClick = { closeDrawer { feedback = "Report an issue" } })
                            FlowRow("Share feedback", icon = Icons.Outlined.ChatBubbleOutline, onClick = { closeDrawer { feedback = "Share feedback" } })
                            FlowRow("Settings", icon = Icons.Outlined.Settings, onClick = { closeDrawer { changePage(FlowPage.Settings) } })
                            FlowRow("Account", icon = Icons.Outlined.AccountCircle, onClick = { closeDrawer { changePage(FlowPage.Account) } })
                            FlowRow("Help", icon = Icons.AutoMirrored.Outlined.HelpOutline, onClick = { closeDrawer { changePage(FlowPage.Help) } })
                            Spacer(Modifier.height(8.dp))
                            FlowLine()
                            FlowRow("Meetings", icon = Icons.Outlined.MicNone, onClick = { closeDrawer(onMeetings) })
                            FlowRow("Meeting library", icon = Icons.Outlined.Bookmarks, onClick = { closeDrawer(onLibrary) })
                        }
                    }
                },
            ) {
                Column(Modifier.fillMaxSize().imePadding()) {
                    if (notice.isNotBlank()) FlowNotice(notice) { vm.notice("") }
                    if (page == FlowPage.Settings || page == FlowPage.Provider) {
                        Box(Modifier.weight(1f).navigationBarsPadding()) {
                            FlowSettings(options, permissions, hasKey, lastIssue, vm, ::repair, { showDisclosure = true }, ::back, page == FlowPage.Provider)
                        }
                    } else {
                        FlowToolbar(
                            label = if (topPage) "Atlas" else page.name,
                            menu = if (topPage) ({ scope.launch { drawer.open() }; Unit }) else null,
                            back = if (!topPage) ::back else null,
                            action = when (page) {
                                FlowPage.Home -> ({ IconButton(onClick = { changePage(FlowPage.Settings) }) { Icon(Icons.Outlined.Settings, "Settings", Modifier.size(22.dp)) } })
                                FlowPage.Dictionary, FlowPage.Snippets -> ({
                                    IconButton(onClick = { edit = true }) {
                                        Icon(Icons.Outlined.Add, if (page == FlowPage.Dictionary) "Add a word" else "Add a snippet")
                                    }
                                })
                                else -> null
                            },
                        )
                        key(page) {
                            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                when (page) {
                                    FlowPage.Home -> {
                                        FlowTitle("Home")
                                        val providerReady = hasKey && options.model.isNotBlank()
                                        if (!permissions.ready || !options.consent || !providerReady) {
                                            val fix = when {
                                                !mic -> "Enable microphone"
                                                !overlay -> "Enable the bubble"
                                                !options.consent -> "Review disclosure"
                                                !connected -> "Enable accessibility"
                                                else -> "Configure provider"
                                            }
                                            FlowCard(color = colors.tertiaryContainer) {
                                                Text("Finish setting up Atlas", style = MaterialTheme.typography.titleMedium)
                                                FlowMuted(when (fix) {
                                                    "Enable microphone" -> "Allow microphone access to dictate."
                                                    "Enable the bubble" -> "Let Atlas appear above your keyboard."
                                                    "Enable accessibility", "Review disclosure" -> "Give Atlas permission to insert text in supported editors."
                                                    else -> "Add a speech model and API key to start dictating."
                                                })
                                                FlowButton(fix) {
                                                    when (fix) {
                                                        "Enable microphone" -> repair(FlowPermission.Microphone)
                                                        "Enable the bubble" -> repair(FlowPermission.Overlay)
                                                        "Review disclosure" -> showDisclosure = true
                                                        "Enable accessibility" -> repair(FlowPermission.Accessibility)
                                                        else -> changePage(FlowPage.Provider)
                                                    }
                                                }
                                                FlowTextButton(onClick = { step = FlowStep.Welcome; setup = true }) { Text("View setup guide") }
                                            }
                                        }
                                        if (Build.VERSION.SDK_INT < 33) FlowMuted("Android 8–12 support dictation with manual copy.")
                                        FlowStats(entries, samples)
                                        if (result != null) FlowCard(color = colors.primaryContainer) {
                                            Text("Latest transcript", style = MaterialTheme.typography.titleMedium)
                                            SelectionContainer { Text(result.orEmpty()) }
                                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                                FlowTextButton(onClick = { result?.let(::copy) }) { Text("Copy") }
                                                FlowTextButton(onClick = { result?.let(::export) }) { Text("Export") }
                                                FlowTextButton(onClick = { vm.coordinator.discardText() }) { Text("Dismiss") }
                                            }
                                        }
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text("Recent dictations", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                                            FlowTextButton(onClick = { changePage(FlowPage.History) }) { Text("View all") }
                                        }
                                        FlowHistory(entries, options, status, compact = true, ::copy, ::export,
                                            report = { feedback = "Report an issue" }, openAll = { changePage(FlowPage.History) }, vm)
                                        FlowSection("Microphone test")
                                        FlowPractice(vm, mic && options.consent && providerReady)
                                    }
                                    FlowPage.Dictionary -> FlowDictionary(options, vm, edit) { edit = it }
                                    FlowPage.Snippets -> FlowSnippets(options, vm, edit) { edit = it }
                                    FlowPage.History -> {
                                        FlowTitle("Dictation history")
                                        FlowHistory(entries, options, status, compact = false, ::copy, ::export,
                                            report = { feedback = "Report an issue" }, openAll = {}, vm)
                                    }
                                    FlowPage.Help -> {
                                        FlowTitle("Help")
                                        FlowRows {
                                            FlowRow("Set up dictation", "Language, microphone and system access.", onClick = { step = FlowStep.Welcome; setup = true })
                                            FlowLine()
                                            FlowRow("Try the microphone", "Test the configured speech provider below.")
                                            FlowLine()
                                            FlowRow("Repair Android permissions", onClick = { repair(FlowPermission.AppDetails) })
                                        }
                                        FlowPractice(vm, mic && options.consent && hasKey && options.model.isNotBlank())
                                    }
                                    FlowPage.Account -> {
                                        FlowTitle("Account")
                                        FlowMuted("Atlas uses a local profile. No sign-in or subscription is required.")
                                        FlowRows {
                                            FlowRow("Speech provider", options.provider.label, icon = Icons.Outlined.AccountCircle, onClick = { changePage(FlowPage.Provider) })
                                            FlowLine()
                                            FlowRow("Data disclosure", "How dictation uses your device and provider.", onClick = { showDisclosure = true })
                                        }
                                    }
                                    else -> Unit
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        if (topPage && WindowInsets.ime.getBottom(LocalDensity.current) == 0) FlowBottomBar(page, ::changePage)
                        else Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                    }
                }
            }
        }
    }
    if (showDisclosure) DisclosureDialog(options.consent, close = { showDisclosure = false }, confirm = {
        vm.consent(true); showDisclosure = false
    }, withdraw = { vm.consent(false); showDisclosure = false })
    feedback?.let { title ->
        FeedbackDialog(title, close = { feedback = null }) { body ->
            feedback = null
            launch(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Atlas: $title").putExtra(Intent.EXTRA_TEXT, body), "Share report"))
        }
    }
}

@Composable private fun FlowBottomBar(page: FlowPage, navigate: (FlowPage) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            FlowLine()
            Row(Modifier.fillMaxWidth().navigationBarsPadding().heightIn(min = 60.dp).selectableGroup(),
                verticalAlignment = Alignment.CenterVertically) {
                for ((item, icon) in listOf(FlowPage.Home to Icons.Outlined.Home, FlowPage.Dictionary to Icons.AutoMirrored.Outlined.MenuBook,
                    FlowPage.Snippets to Icons.Outlined.AutoAwesome)) {
                    val selected = item == page
                    val tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(Modifier.weight(1f).heightIn(min = 60.dp).selectable(selected, role = Role.Tab) { navigate(item) }.padding(vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Box(Modifier.size(50.dp, 28.dp).background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            CircleShape), contentAlignment = Alignment.Center) {
                            Icon(icon, null, Modifier.size(21.dp), tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else tint)
                        }
                        Text(item.name, style = if (selected) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall, color = tint)
                    }
                }
            }
        }
    }
}
@Composable private fun FlowNotice(text: String, dismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Dismiss message", Modifier.size(18.dp)) }
        }
    }
}
@Composable private fun FlowStats(entries: List<DictationEntry>, samples: List<LatencySample>) {
    val week = remember(entries) { entries.filter { it.createdAt >= System.currentTimeMillis() - 7 * 86_400_000L } }
    val words = remember(week) { week.sumOf { it.text?.trim()?.split(Regex("\\s+"))?.count(String::isNotBlank) ?: 0 } }
    FlowCard {
        FlowSection("Your stats · Last 7 days")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                Text(words.toString(), style = MaterialTheme.typography.headlineMedium)
                FlowMuted("Words dictated")
            }
            Column(Modifier.weight(1f)) {
                Text(week.count { !it.text.isNullOrBlank() }.toString(), style = MaterialTheme.typography.headlineMedium)
                FlowMuted("Dictations saved")
            }
        }
        if (samples.isNotEmpty()) {
            FlowLine()
            FlowMuted("Confirmed insertion · p50 ${percentile(samples.map { it.total }, .5)} ms · p95 ${percentile(samples.map { it.total }, .95)} ms")
            FlowMuted("Median: audio ${percentile(samples.map { it.audioFinalize }, .5)} ms · recognition ${percentile(samples.map { it.speech }, .5)} ms · cleanup ${percentile(samples.map { it.cleanup }, .5)} ms · insert ${percentile(samples.map { it.insert }, .5)} ms")
        }
    }
}
@Composable private fun DisclosureDialog(consented: Boolean, close: () -> Unit, confirm: () -> Unit, withdraw: () -> Unit) {
    FlowDialog(onDismissRequest = close, title = { Text("How Atlas uses dictation access") },
        text = {
            Column(Modifier.heightIn(max = 390.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Android accessibility can expose screen content. Atlas reads the focused field and its cursor to decide where it can show the bubble and safely insert text. It doesn’t send draft text, window contents or app names.")
                Text("When you submit, Atlas sends audio to your selected speech provider. If you configure cleanup, Atlas also sends the transcript, vocabulary hints and a broad writing style. Provider billing and privacy terms apply.")
                Text("Atlas doesn’t listen until you act. History and retained recordings start off. You can revoke Android permissions or withdraw this consent at any time.")
            }
        },
        confirmButton = { FlowButton("Accept and continue", onClick = confirm) },
        dismissButton = { FlowTextButton(onClick = if (consented) withdraw else close) { Text(if (consented) "Withdraw consent" else "Not now") } })
}
@Composable private fun FeedbackDialog(title: String, close: () -> Unit, share: (String) -> Unit) {
    var body by remember { mutableStateOf("") }
    FlowDialog(onDismissRequest = close, title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowField(body, { body = it.take(3000) }, "What happened?", minLines = 3)
            FlowMuted("Atlas includes your note and Android version only. Choose where to share it in the next step.")
        } },
        confirmButton = { FlowButton("Choose where to share", enabled = body.isNotBlank()) {
            share(body.trim() + "\n\nAtlas ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE}")
        } },
        dismissButton = { FlowTextButton(onClick = close) { Text("Cancel") } })
}
private fun Context.permitted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
private fun percentile(values: List<Long>, pct: Double) = values.sorted()[(ceil(values.size * pct).toInt() - 1).coerceAtLeast(0)]
