package com.meetingatlas.dictation

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.meetingatlas.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

@AndroidEntryPoint
class AtlasAccessibilityService : AccessibilityService() {
    @Inject internal lateinit var coordinator: DictationCoordinator
    @Inject internal lateinit var preferences: DictationSettingsRepository
    private val handler = Handler(Looper.getMainLooper())
    private val update = Runnable { refresh() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var meter: Job? = null
    private var overlay: FloatingBubbleController? = null
    private val insertion = TextInsertionManager()
    private var focus: AccessibilityNodeInfo? = null
    private var generation = 0L
    private var inputGeneration = 0L
    private var selection: Pair<Int, Int>? = null
    private var composing = false
    private var anchor: Anchor? = null
    private var watching = false
    private var permission = false
    private var disabledByWindow = false
    private val ops by lazy { getSystemService(AppOpsManager::class.java) }
    private val onOp = AppOpsManager.OnOpChangedListener { _, pkg ->
        if (pkg == packageName) handler.post { if (watching) { disabledByWindow = false; refresh() } }
    }
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                screenOff = true
                dropFocus()
            } else {
                screenOff = false
                refresh()
            }
        }
    }
    private var screenOff = false
    private data class Anchor(val ticket: Long, val input: Long, val snapshot: EditSnapshot)

    private val target = object : DictationTarget {
        override suspend fun deliver(target: Long, session: Long, text: String): InsertionResult {
            if (Build.VERSION.SDK_INT < 33 || !coordinator.owns(session) || !canShow()) return InsertionResult.MANUAL
            val saved = anchor ?: return InsertionResult.MANUAL
            if (saved.ticket != target || saved.input != inputGeneration || generation != target || composing) return InsertionResult.MANUAL
            val node = currentField() ?: return InsertionResult.MANUAL
            if (node != focus || selection != (saved.snapshot.start to saved.snapshot.end)) return InsertionResult.MANUAL
            val expected = try { insertion.commit(inputMethod, node, saved.snapshot, text) }
                catch (_: RuntimeException) { null } ?: return InsertionResult.MANUAL
            // The API reports no success bit. Verify the actual editor text; never attempt a
            // second commit if the editor hides its contents or hasn't acknowledged it yet.
            repeat(5) {
                delay(55)
                if (generation != target || !coordinator.owns(session)) return InsertionResult.UNCONFIRMED
                val observed = currentField()?.takeIf { it == node }?.let(insertion::snapshot)
                if (observed == expected) return InsertionResult.CONFIRMED
            }
            return InsertionResult.UNCONFIRMED
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        coordinator.attach(target)
        if (!watching) {
            watching = true
            ops.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, onOp)
            ops.startWatchingMode(AppOpsManager.OPSTR_RECORD_AUDIO, packageName, onOp)
            ContextCompat.registerReceiver(this, screen, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        disabledByWindow = false
        observer?.cancel()
        observer = scope.launch {
            combine(coordinator.settings, coordinator.status) { settings, status -> settings to status }.collect { refresh() }
        }
        meter?.cancel()
        meter = scope.launch {
            coordinator.amplitude.collect { overlay?.level(it) }
        }
        refresh()
    }

    @RequiresApi(33)
    override fun onCreateInputMethod(): InputMethod = object : InputMethod(this) {
        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
            super.onStartInput(attribute, restarting)
            inputGeneration++
            anchor = null
            selection = ordered(attribute.initialSelStart, attribute.initialSelEnd)
            composing = false
            schedule()
        }
        override fun onFinishInput() {
            super.onFinishInput()
            inputGeneration++
            anchor = null
            selection = null
            composing = false
            schedule()
        }
        override fun onUpdateSelection(
            oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
            candidatesStart: Int, candidatesEnd: Int,
        ) {
            val next = ordered(newSelStart, newSelEnd)
            val hasComposition = candidatesStart >= 0 || candidatesEnd >= 0
            if (selection != next || hasComposition) anchor = null
            selection = next
            composing = hasComposition
            schedule()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!watching || !coordinator.settings.value.consent) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // A screen/window transition is an identity boundary even if Android later
                // returns the exact same node. Ignore Atlas's own overlay updates.
                if (event.packageName?.toString() != packageName || !isOurOverlay(event.windowId)) {
                    val active = focus
                    if (active != null && (event.windowId == active.windowId || !isInputMethod(event.windowId))) {
                        dropFocus()
                    }
                }
                refresh()
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val active = focus
                if (active != null && event.windowId == active.windowId && event.source != active) dropFocus()
                refresh()
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> refresh()
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val saved = anchor
                if (saved != null && event.windowId == focus?.windowId && event.source == focus &&
                    coordinator.status.value.phase != DictationPhase.INSERTING) {
                    // Do not store the editor's draft. An edit or selection move invalidates the capture anchor.
                    anchor = null
                }
                schedule()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> schedule()
            else -> Unit
        }
    }

    private fun isInputMethod(window: Int): Boolean =
        windows.any { it.id == window && it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    private fun isOurOverlay(window: Int): Boolean =
        windows.any { it.id == window && (it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
            it.type == AccessibilityWindowInfo.TYPE_SYSTEM) && !it.isFocused }

    private fun schedule() {
        if (!updateScheduled) {
            handler.postDelayed(update, 32)
            updateScheduled = true
        }
    }

    private var updateScheduled = false
    private fun canShow() = watching && !screenOff && coordinator.settings.value.consent && Settings.canDrawOverlays(this) &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun refresh() {
        updateScheduled = false
        handler.removeCallbacks(update)
        if (!canShow()) {
            if (permission) dropFocus()
            permission = false
            return
        }
        permission = true
        if (disabledByWindow) return
        val node = currentField()
        if (node != focus) {
            val old = generation++
            anchor = null
            focus = node
            coordinator.targetLost(old)
        }
        if (node == null) {
            overlay?.hide()
            return
        }
        val keyboard = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { window -> Rect().also(window::getBoundsInScreen) }
        val field = Rect().also(node::getBoundsInScreen)
        val options = coordinator.settings.value
        val ok = try {
            val bubble = overlay ?: FloatingBubbleController(this,
                tap = ::tap,
                hold = ::startDictation,
                release = coordinator::done,
                cancelHold = coordinator::cancel,
                cancel = { coordinator.cancel() },
                reposition = { x, y -> scope.launch { preferences.position(x, y) } },
            ).also { overlay = it }
            bubble.show(options, coordinator.status.value, field, keyboard)
        } catch (_: RuntimeException) { false }
        if (!ok) {
            disabledByWindow = true
            dropFocus()
            coordinator.showError("Android did not allow Atlas to display the bubble. Reopen Atlas or toggle the overlay permission.")
        }
    }

    private fun currentField(): AccessibilityNodeInfo? {
        val list = windows
        val focusedWindow = list.firstOrNull { it.isFocused }
        if (focusedWindow != null && focusedWindow.type !in setOf(AccessibilityWindowInfo.TYPE_APPLICATION,
                AccessibilityWindowInfo.TYPE_INPUT_METHOD)) return null
        val appWindow = focusedWindow?.takeIf { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?: list.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive } ?: return null
        if (Build.VERSION.SDK_INT >= 30 && appWindow.displayId != android.view.Display.DEFAULT_DISPLAY) return null
        val node = appWindow.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        val pkg = node.packageName?.toString().orEmpty()
        if (node.windowId != appWindow.id || !node.isFocused || !node.isVisibleToUser || !node.isEnabled ||
            !node.isEditable || node.isPassword ||
            !DictationPolicy.allowsPackage(pkg, packageName, coordinator.settings.value.excluded)) return null
        if (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) return null
        if (!DictationPolicy.allowsMetadata(node.hintText, node.contentDescription, node.viewIdResourceName,
                if (Build.VERSION.SDK_INT >= 28) node.paneTitle else null) ||
            (node.inputType != 0 && !DictationPolicy.allowsTextType(node.inputType))) return null
        if (Build.VERSION.SDK_INT >= 33) {
            val editor = inputMethod?.currentInputEditorInfo?.takeIf { it.packageName == pkg } ?: return null
            if (!DictationPolicy.allowsTextType(editor.inputType) || !DictationPolicy.allowsIme(editor.imeOptions) ||
                !DictationPolicy.allowsMetadata(editor.hintText, editor.fieldName)) return null
        } else if (!DictationPolicy.allowsTextType(node.inputType)) return null
        return node
    }

    private fun dropFocus() {
        val old = generation++
        anchor = null
        focus = null
        overlay?.hide()
        coordinator.targetLost(old)
    }

    private fun startDictation(): Long? {
        if (!canShow() || overlay?.visible != true) return null
        val node = currentField()?.takeIf { it == focus } ?: return null
        val style = DictationPolicy.style(node.packageName.toString(), node.viewIdResourceName,
            if (Build.VERSION.SDK_INT >= 33) inputMethod?.currentInputEditorInfo?.imeOptions ?: 0 else 0)
        val snapshot = insertion.snapshot(node)
        val ticket = generation
        anchor = snapshot?.let { Anchor(ticket, inputGeneration, it) }
        return coordinator.start(ticket, style)
    }

    private fun tap() {
        when (coordinator.status.value.phase) {
            DictationPhase.STARTING, DictationPhase.LISTENING -> coordinator.done()
            DictationPhase.PROCESSING, DictationPhase.INSERTING, DictationPhase.RECOVERING -> Unit
            DictationPhase.ERROR -> {
                if (coordinator.status.value.canRetry) coordinator.retry()
                else openAtlas()
            }
            DictationPhase.SUCCESS -> {
                if (!coordinator.status.value.message.startsWith("Inserted") && coordinator.result.value != null) openAtlas()
                else startDictation()
            }
            else -> startDictation()
        }
    }

    private fun openAtlas() {
        try { startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.SHOW_DICTATION, true)) }
        catch (_: RuntimeException) { coordinator.showError("Open Atlas to recover your dictation.") }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        dropFocus()
        disabledByWindow = false
        schedule()
    }

    override fun onInterrupt() { dropFocus() }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        scope.cancel()
        super.onDestroy()
    }

    private fun disconnect() {
        dropFocus()
        handler.removeCallbacksAndMessages(null)
        updateScheduled = false
        observer?.cancel()
        meter?.cancel()
        overlay = null
        if (watching) {
            watching = false
            ops.stopWatchingMode(onOp)
            unregisterReceiver(screen)
        }
        coordinator.detach(target)
    }

    private fun ordered(start: Int, end: Int): Pair<Int, Int>? =
        if (start < 0 || end < 0) null else minOf(start, end) to maxOf(start, end)
}
